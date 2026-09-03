package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 机审审核日志表。
 * 事件表：一次机审会经历多个阶段（规则/图片/抽帧/AI），每阶段留一条，用于追溯与复盘。
 * 因此这里不能加唯一键。
 */
@Data
public class MachineAuditLog {

    private Long id;
    private Long dynamicId;
    private String stage;           // 见 Stage
    private String requestBody;
    private String responseBody;
    private Integer costMs;
    private Integer success;
    private String errorMsg;
    private LocalDateTime createTime;
}
