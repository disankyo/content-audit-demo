package com.example.audit.service;

import com.example.audit.common.AuditConst.Stage;

/**
 * 单个机审阶段的结果。
 * 一次机审会跑多个阶段（规则 → 图片/抽帧 → AI），每个阶段产出一条，
 * 最终由 MachineAuditService 聚合成一个结论。
 */
public record StageResult(
        Stage stage,
        boolean violation,     // 是否判定违规
        int score,             // 风险分 0-100，越高越可疑
        String riskType,       // 色情/暴恐/广告/违禁/政治/空
        String detail,         // 命中的规则或模型给出的理由
        long costMs,
        boolean success,       // 该阶段是否正常返回
        String errorMsg
) {
    public static StageResult ok(Stage stage, boolean violation, int score, String riskType, String detail, long costMs) {
        return new StageResult(stage, violation, score, riskType, detail, costMs, true, "");
    }

    public static StageResult fail(Stage stage, String errorMsg, long costMs) {
        return new StageResult(stage, false, 0, "", "", costMs, false, errorMsg);
    }

    /**
     * 阶段执行失败时的兜底结论。
     * 铁律：任何阶段失败都不能判定为「通过」，必须降级为疑似 → 转人工。
     */
    public static StageResult degraded(Stage stage, String reason, long costMs) {
        return new StageResult(stage, false, 50, "", reason, costMs, true, "");
    }
}
