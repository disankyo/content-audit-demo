package com.example.audit.controller;

import com.example.audit.common.AuditConst;
import com.example.audit.common.R;
import com.example.audit.domain.ManualAuditLog;
import com.example.audit.mapper.ManualAuditMapper;
import com.example.audit.service.ManualAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit/manual")
@RequiredArgsConstructor
public class ManualAuditController {

    private final ManualAuditService manualAuditService;
    private final ManualAuditMapper manualAuditMapper;

    /** 领取一条待审任务 */
    @PostMapping("/claim")
    public R<ManualAuditService.ManualTask> claim(@RequestParam Long auditorId) {
        ManualAuditService.ManualTask task = manualAuditService.claim(auditorId);
        return task == null ? R.fail("当前没有待审任务") : R.ok(task);
    }

    /** 提交审核结论 */
    @PostMapping("/submit")
    public R<Boolean> submit(@RequestBody SubmitCmd cmd) {
        AuditConst.ManualResult result = cmd.pass()
                ? AuditConst.ManualResult.PASS
                : AuditConst.ManualResult.REJECT;
        boolean ok = manualAuditService.submit(cmd.auditorId(), cmd.dynamicId(), result, cmd.reason());
        return ok ? R.ok(true) : R.fail("提交失败：任务不存在、状态已变更，或不是你的任务");
    }

    /** 主动放弃任务 */
    @PostMapping("/give-back")
    public R<Boolean> giveBack(@RequestParam Long auditorId, @RequestParam Long dynamicId) {
        return R.ok(manualAuditService.giveBack(auditorId, dynamicId));
    }

    /** 审核留痕 */
    @GetMapping("/logs/{dynamicId}")
    public R<List<ManualAuditLog>> logs(@PathVariable Long dynamicId) {
        return R.ok(manualAuditMapper.selectLogs(dynamicId));
    }

    /** 积压监控：按队列状态统计待审量 */
    @GetMapping("/stats")
    public R<Map<String, Object>> stats() {
        return R.ok(manualAuditService.stats());
    }

    public record SubmitCmd(Long auditorId, Long dynamicId, boolean pass, String reason) {
    }
}
