package com.example.audit.controller;

import com.example.audit.common.R;
import com.example.audit.domain.DynamicBase;
import com.example.audit.domain.MachineAuditLog;
import com.example.audit.domain.MachineAuditResult;
import com.example.audit.service.DynamicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/dynamic")
@RequiredArgsConstructor
public class DynamicController {

    private final DynamicService dynamicService;

    /** 发布动态 → 自动进入机审队列 */
    @PostMapping("/publish")
    public R<Long> publish(@RequestBody DynamicService.PublishCmd cmd) {
        return R.ok(dynamicService.publish(cmd));
    }

    /** 查询动态当前状态 */
    @GetMapping("/{dynamicId}")
    public R<DynamicBase> detail(@PathVariable Long dynamicId) {
        return R.ok(dynamicService.getDetail(dynamicId));
    }

    /** 查询机审结论 */
    @GetMapping("/{dynamicId}/machine-result")
    public R<MachineAuditResult> machineResult(@PathVariable Long dynamicId) {
        return R.ok(dynamicService.getMachineResult(dynamicId));
    }

    /** 查询机审各阶段留痕（调试/复盘用） */
    @GetMapping("/{dynamicId}/machine-logs")
    public R<List<MachineAuditLog>> machineLogs(@PathVariable Long dynamicId) {
        return R.ok(dynamicService.getMachineLogs(dynamicId));
    }
}
