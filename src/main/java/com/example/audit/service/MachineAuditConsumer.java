package com.example.audit.service;

import com.example.audit.common.AuditConst;
import com.example.audit.domain.MachineAuditLog;
import com.example.audit.domain.MachineAuditQueue;
import com.example.audit.domain.MachineAuditResult;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mq.AuditEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 机审队列消费者。
 *
 * <p>流程：捞取 → 抢占 → 机审 → 写结论 → <b>发消息</b> → 出队（删除）
 * <pre>
 *   机审驳回   → 动态直接驳回，流程结束（也发消息，消费端判断后不入人审）
 *   机审通过   → 发消息，消费端入人审队列（priority 5）
 *   机审疑似   → 发消息，消费端入人审队列并插队（priority 2）
 * </pre>
 * 队列是临时调度数据：出结论后即删除；只有处理失败的任务会留下重试，
 * 重试耗尽标记「失败待介入」等人工处理。
 *
 * <p>关于队列表 vs MQ（面试必考，见 README）：
 * 队列表的优势是可靠、可查询、支持优先级与人工干预；
 * 劣势是数据库轮询在高并发下是瓶颈。
 * 生产方案通常是 <b>MQ 做流转 + 表做状态</b>，两者分工而非替代。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MachineAuditConsumer {

    private final MachineAuditMapper machineAuditMapper;
    private final DynamicBaseMapper dynamicBaseMapper;
    private final MachineAuditService machineAuditService;
    private final AuditEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Value("${audit.machine.batch-size:20}")
    private int batchSize;

    @Value("${audit.machine.max-retry:3}")
    private int maxRetry;

    @Value("${audit.machine.stuck-timeout-min:5}")
    private int stuckTimeoutMin;

    @Scheduled(fixedDelayString = "${audit.machine.poll-interval-ms:1000}")
    public void consume() {
        List<MachineAuditQueue> batch;
        try {
            batch = machineAuditMapper.pickBatch(batchSize);
        } catch (Exception e) {
            log.error("捞取机审队列失败", e);
            return;
        }
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (MachineAuditQueue q : batch) {
            try {
                handleOne(q);
            } catch (Exception e) {
                // 单条失败不能影响整批
                log.error("机审处理异常, queueId={}, retryCount={}", q.getId(), q.getRetryCount(), e);
                // 重试次数用完就留在队列里标记失败等人工介入；否则退避后重新回到待处理
                if (q.getRetryCount() != null && q.getRetryCount() + 1 >= maxRetry) {
                    machineAuditMapper.markFailed(q.getId());
                    log.error("机审重试耗尽，转人工介入, queueId={}, dynamicId={}", q.getId(), q.getDynamicId());
                } else {
                    machineAuditMapper.retryLater(q.getId());
                }
            }
        }
    }

    /**
     * 回收卡死在「处理中」(status=1) 的机审任务。
     *
     * <p>claim 把状态置 1 后即开始机审（含同步 AI 调用，最长可达数秒）；若此时消费者进程崩溃 /
     * 被 kill，deleteById 永远不会执行，这一行永久停在 1，而 pickBatch 只捞 status=0，
     * 于是动态卡死在 AUDITING。本任务定期把「超过阈值仍未结束的处理中任务」重置回待处理
     * （重试耗尽则标记失败），让其重新被调度——与人审的 releaseExpired 是同一思路。
     */
    @Scheduled(fixedDelay = 60_000)
    public int recoverStuck() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(stuckTimeoutMin);
        int n = machineAuditMapper.resetStuck(threshold, maxRetry);
        if (n > 0) {
            log.warn("回收卡死机审任务 {} 条（重置为待处理 / 标记失败，等待重新调度）", n);
        }
        return n;
    }

    public void handleOne(MachineAuditQueue queue) {
        // 抢占：用 WHERE queue_status = 0 保证只有一个线程能拿到
        int claimed = machineAuditMapper.claim(queue.getId());
        if (claimed == 0) {
            return;   // 已被其他线程/实例抢走
        }

        Long dynamicId = queue.getDynamicId();

        // 机审执行（可能包含外部 HTTP 调用，不开事务，避免长时间占用 DB 连接）
        MachineOutcome outcome = machineAuditService.audit(dynamicId);

        // 持久化结论（纯 DB 操作，用 TransactionTemplate 显式事务，避免自调用导致 @Transactional 失效）
        transactionTemplate.executeWithoutResult(status ->
            persistResult(queue, dynamicId, outcome)
        );
    }

    /**
     * 纯 DB 写入，由调用方通过 TransactionTemplate 提供事务上下文。
     */
    public void persistResult(MachineAuditQueue queue, Long dynamicId, MachineOutcome outcome) {
        // ---- 写结论（覆盖写，重复机审以最后一次为准） ----
        MachineAuditResult result = new MachineAuditResult();
        result.setDynamicId(dynamicId);
        result.setAuditResult(outcome.result().getCode());
        result.setRiskScore(outcome.riskScore());
        result.setRiskType(outcome.riskType());
        result.setHitRule(outcome.hitRule());
        result.setModelVersion(outcome.modelVersion());
        result.setCostMs(outcome.totalCostMs());
        machineAuditMapper.upsertResult(result);

        // ---- 写日志（每个阶段一条，用于追溯与复盘） ----
        List<MachineAuditLog> logs = outcome.stages().stream().map(s -> {
            MachineAuditLog log = new MachineAuditLog();
            log.setDynamicId(dynamicId);
            log.setStage(s.stage().name());
            log.setRequestBody("");
            log.setResponseBody(s.detail());
            log.setCostMs((int) s.costMs());
            log.setSuccess(s.success() ? 1 : 0);
            log.setErrorMsg(s.errorMsg());
            return log;
        }).toList();
        if (!logs.isEmpty()) {
            machineAuditMapper.batchInsertLog(logs);
        }

        // ---- 回写动态的机审结论 ----
        dynamicBaseMapper.updateMachineResult(dynamicId, outcome.result().getCode(),
                outcome.riskScore(), outcome.riskType());

        // ---- 流转 ----
        if (outcome.isReject()) {
            // 机审判定违规：直接驳回，不占用人工资源。
            // 同样发消息——消息总线上是「机审完成」这个事实，要不要入人审由消费端决定，
            // 以后想加「驳回后通知作者」直接多一个订阅方就行
            dynamicBaseMapper.updateBizStatus(dynamicId, AuditConst.BizStatus.REJECTED.getCode());
            eventPublisher.publishMachineAudited(dynamicId, outcome.result().getCode(),
                    AuditConst.PRIORITY_NORMAL);
            machineAuditMapper.deleteById(queue.getId());
            log.info("机审驳回, dynamicId={}", dynamicId);
            return;
        }

        // 通过或疑似 → 发消息通知人审侧入队；疑似插队（priority 更小）
        int priority = outcome.isSuspect()
                ? AuditConst.PRIORITY_SUSPECT
                : AuditConst.PRIORITY_NORMAL;
        // 这里不直接写人审队列表：入队是调度动作，交给事件的消费端做。
        // 消息在事务提交后才投递（见 AfterCommit），保证消费端能查到刚写入的机审结论
        eventPublisher.publishMachineAudited(dynamicId, outcome.result().getCode(), priority);
        machineAuditMapper.deleteById(queue.getId());
        log.info("机审完成并发出流转消息, dynamicId={}, result={}, priority={}",
                dynamicId, outcome.result(), priority);
    }
}
