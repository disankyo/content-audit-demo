package com.example.audit.service;

import com.example.audit.common.AuditConst.MachineResult;

import java.util.List;

/**
 * 机审最终结论（多阶段聚合后的结果）。
 */
public record MachineOutcome(
        MachineResult result,   // 通过 / 驳回 / 疑似
        int riskScore,
        String riskType,
        String hitRule,         // 命中的规则或理由，多个用分号拼接
        List<StageResult> stages
) {
    public boolean isPass() {
        return result == MachineResult.PASS;
    }

    public boolean isReject() {
        return result == MachineResult.REJECT;
    }

    public boolean isSuspect() {
        return result == MachineResult.SUSPECT;
    }

    public int totalCostMs() {
        return stages.stream().mapToInt(s -> (int) s.costMs()).sum();
    }

    public String modelVersion() {
        // 记录参与机审的阶段，便于事后回溯「这条结论是怎么来的」
        return String.join("+", stages.stream().map(s -> s.stage().name()).toList());
    }
}
