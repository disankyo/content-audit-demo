package com.example.audit.service;

import com.example.audit.common.AuditConst;
import com.example.audit.domain.DynamicBase;
import com.example.audit.domain.DynamicImage;
import com.example.audit.domain.MachineAuditResult;
import com.example.audit.domain.ManualAuditLog;
import com.example.audit.domain.ManualAuditQueue;
import com.example.audit.domain.ManualAuditResult;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.DynamicImageMapper;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mapper.ManualAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人审服务：领取 → 审核 → 提交，以及超时回收。
 *
 * <p>并发防重是这里的核心：多个审核员同时点「领取」时，
 * 靠 <code>WHERE queue_status = 0</code> + affected rows 保证只有一个人能领到。
 * 本质是把「检查状态」和「修改状态」合成一个原子操作，
 * 和防超卖的乐观锁 CAS 是同一个思路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualAuditService {

    private final ManualAuditMapper manualAuditMapper;
    private final MachineAuditMapper machineAuditMapper;
    private final DynamicBaseMapper dynamicBaseMapper;
    private final DynamicImageMapper dynamicImageMapper;

    @Value("${audit.manual.lock-minutes:5}")
    private int lockMinutes;

    /** 审核员领取一条待审任务；没有待审任务返回 null */
    @Transactional
    public ManualTask claim(Long auditorId) {
        ManualAuditQueue q = manualAuditMapper.pickOne();
        if (q == null) {
            return null;
        }
        int claimed = manualAuditMapper.claim(q.getId(), auditorId,
                LocalDateTime.now().plusMinutes(lockMinutes));
        if (claimed == 0) {
            // 被其他审核员抢走（高并发下的正常情况）
            return null;
        }
        manualAuditMapper.insertLog(buildLog(q.getDynamicId(), auditorId,
                AuditConst.ManualAction.CLAIM, 0, 1, "领取任务"));
        log.info("审核员 {} 领取任务, dynamicId={}", auditorId, q.getDynamicId());
        return assemble(q);
    }

    /** 提交审核结论 */
    @Transactional
    public boolean submit(Long auditorId, Long dynamicId, AuditConst.ManualResult result, String reason) {
        ManualAuditQueue q = manualAuditMapper.selectByDynamicId(dynamicId);
        if (q == null || q.getQueueStatus() != AuditConst.ManualQueueStatus.CLAIMED.getCode()) {
            log.warn("任务状态异常，无法提交, dynamicId={}, status={}",
                    dynamicId, q == null ? "null（已出队或不存在）" : q.getQueueStatus());
            return false;
        }
        if (q.getAssigneeId() == null || !q.getAssigneeId().equals(auditorId)) {
            log.warn("非任务持有者提交, dynamicId={}, auditorId={}", dynamicId, auditorId);
            return false;
        }

        // 1. 写人审结论
        ManualAuditResult r = new ManualAuditResult();
        r.setDynamicId(dynamicId);
        r.setAuditorId(auditorId);
        r.setAuditResult(result.getCode());
        r.setRejectReason(reason == null ? "" : reason);
        r.setRiskType("");
        r.setCostMs(0);
        manualAuditMapper.upsertResult(r);

        // 2. 回写动态业务状态：这是最终态
        AuditConst.BizStatus bizStatus = (result == AuditConst.ManualResult.PASS)
                ? AuditConst.BizStatus.PASSED
                : AuditConst.BizStatus.REJECTED;
        dynamicBaseMapper.updateBizStatus(dynamicId, bizStatus.getCode());
        dynamicBaseMapper.updateManualStatus(dynamicId, result.getCode());

        // 3. 同步图片状态，保持各表一致
        int imageStatus = (result == AuditConst.ManualResult.PASS) ? 1 : 2;
        dynamicImageMapper.updateStatusByDynamic(dynamicId, imageStatus);

        // 4. 队列删除：队列只做调度，结论在 result、痕迹在 log，审完不必留着
        manualAuditMapper.deleteById(q.getId());

        // 5. 留痕
        manualAuditMapper.insertLog(buildLog(dynamicId, auditorId,
                result == AuditConst.ManualResult.PASS
                        ? AuditConst.ManualAction.PASS
                        : AuditConst.ManualAction.REJECT,
                1, 2, reason));

        log.info("人审提交, dynamicId={}, auditorId={}, result={}", dynamicId, auditorId, result.getDesc());
        return true;
    }

    /**
     * 审核员主动放弃任务：立即退回「待领取」，清空锁，别人可以马上领走。
     * 不依赖定时回收——放弃是明确动作，没必要让任务空等到锁过期。
     */
    @Transactional
    public boolean giveBack(Long auditorId, Long dynamicId) {
        ManualAuditQueue q = manualAuditMapper.selectByDynamicId(dynamicId);
        if (q == null || !auditorId.equals(q.getAssigneeId())) {
            return false;
        }
        manualAuditMapper.release(q.getId());
        manualAuditMapper.insertLog(buildLog(dynamicId, auditorId,
                AuditConst.ManualAction.RETURN, 1, 0, "主动放弃"));
        return true;
    }

    /**
     * 超时回收：审核员领了任务但没提交（关页面 / 掉线 / 请假），
     * 锁到期后自动放回队列，避免任务被永久占用。
     */
    @Scheduled(fixedDelay = 60_000)
    public int releaseExpired() {
        int n = manualAuditMapper.releaseExpired();
        if (n > 0) {
            log.info("回收超时人审任务 {} 条", n);
        }
        return n;
    }

    /** 组装审核工作台展示所需的数据 */
    public ManualTask assemble(ManualAuditQueue q) {
        DynamicBase base = dynamicBaseMapper.selectByDynamicId(q.getDynamicId());
        MachineAuditResult machineResult = machineAuditMapper.selectResult(q.getDynamicId());
        List<DynamicImage> images = dynamicImageMapper.selectByDynamicId(q.getDynamicId());
        return new ManualTask(q, base, machineResult, images);
    }

    /** 积压监控：待领取 / 已领取 各多少条 */
    public Map<String, Object> stats() {
        Map<String, Object> stat = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : manualAuditMapper.countByStatus()) {
            Object cntObj = row.get("cnt");
            long cnt = (cntObj instanceof Number n) ? n.longValue() : 0L;
            stat.put(statusName(row.get("queue_status")), cnt);
            total += cnt;
        }
        stat.put("total", total);
        return stat;
    }

    private String statusName(Object code) {
        int c = (code instanceof Number n) ? n.intValue() : -1;
        for (AuditConst.ManualQueueStatus s : AuditConst.ManualQueueStatus.values()) {
            if (s.getCode() == c) {
                return s.name().toLowerCase();
            }
        }
        return "status_" + code;
    }

    private ManualAuditLog buildLog(Long dynamicId, Long auditorId,
                                    AuditConst.ManualAction action,
                                    int before, int after, String remark) {
        ManualAuditLog log = new ManualAuditLog();
        log.setDynamicId(dynamicId);
        log.setAuditorId(auditorId);
        log.setAction(action.name().toLowerCase());
        log.setBeforeStatus(before);
        log.setAfterStatus(after);
        log.setRemark(remark == null ? "" : remark);
        return log;
    }

    /** 审核工作台任务视图 */
    public record ManualTask(
            ManualAuditQueue queue,
            DynamicBase dynamic,
            MachineAuditResult machineResult,
            List<DynamicImage> images
    ) {
    }
}
