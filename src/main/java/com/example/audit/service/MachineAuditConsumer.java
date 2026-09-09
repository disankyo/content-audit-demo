package com.example.audit.service;

import com.example.audit.common.AuditConst;
import com.example.audit.domain.MachineAuditLog;
import com.example.audit.domain.MachineAuditQueue;
import com.example.audit.domain.MachineAuditResult;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mapper.ManualAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 机审队列消费者。
 *
 * <p>流程：捞取 → 抢占 → 机审 → 写结论 → 流转 → 出队（删除）
 * <pre>
 *   机审驳回   → 动态直接驳回，流程结束
 *   机审通过   → 入人审队列（priority 5）
 *   机审疑似   → 入人审队列并插队（priority 2）
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
    private final ManualAuditMapper manualAuditMapper;
    private final DynamicBaseMapper dynamicBaseMapper;
    private final MachineAuditService machineAuditService;

    @Value("${audit.machine.batch-size:20}")
    private int batchSize;

    @Value("${audit.machine.max-retry:3}")
    private int maxRetry;

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

    @Transactional
    public void handleOne(MachineAuditQueue queue) {
        // 抢占：用 WHERE queue_status = 0 保证只有一个线程能拿到
        int claimed = machineAuditMapper.claim(queue.getId());
        if (claimed == 0) {
            return;   // 已被其他线程/实例抢走
        }

        Long dynamicId = queue.getDynamicId();
        MachineOutcome outcome = machineAuditService.audit(dynamicId);

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
            // 机审判定违规：直接驳回，不占用人工资源
            dynamicBaseMapper.updateBizStatus(dynamicId, AuditConst.BizStatus.REJECTED.getCode());
            machineAuditMapper.deleteById(queue.getId());
            log.info("机审驳回, dynamicId={}", dynamicId);
            return;
        }

        // 通过或疑似 → 进人审；疑似插队
        int priority = outcome.isSuspect()
                ? AuditConst.PRIORITY_SUSPECT
                : AuditConst.PRIORITY_NORMAL;
        manualAuditMapper.insertIgnore(dynamicId, priority, outcome.result().getCode());
        machineAuditMapper.deleteById(queue.getId());
        log.info("机审完成并流转人审, dynamicId={}, result={}, priority={}",
                dynamicId, outcome.result(), priority);
    }
}
