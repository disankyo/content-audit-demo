package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 机审审核结果表。
 * 状态表：一个动态只有一条最终结论，重复机审用 upsert 覆盖。
 */
@Data
public class MachineAuditResult {

    private Long id;
    private Long dynamicId;
    private Integer auditResult;    // 见 MachineResult
    private Integer riskScore;
    private String riskType;
    private String hitRule;
    private String modelVersion;
    private Integer costMs;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
