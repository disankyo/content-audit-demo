package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 人审审核结果表。
 */
@Data
public class ManualAuditResult {

    private Long id;
    private Long dynamicId;
    private Long auditorId;
    private Integer auditResult;    // 见 ManualResult
    private String rejectReason;
    private String riskType;
    private Integer costMs;         // 从领取到提交，用于核算审核效率
    private LocalDateTime createTime;
}
