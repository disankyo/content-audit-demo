package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 人审审核日志表。
 */
@Data
public class ManualAuditLog {

    private Long id;
    private Long dynamicId;
    private Long auditorId;
    private String action;          // claim/pass/reject/return/timeout
    private Integer beforeStatus;
    private Integer afterStatus;
    private String remark;
    private LocalDateTime createTime;
}
