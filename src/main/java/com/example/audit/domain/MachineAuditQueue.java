package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 机审审核队列表。
 * uk_dynamic 保证一个动态只有一条队列记录（幂等，重复发布不会插两条）。
 */
@Data
public class MachineAuditQueue {

    private Long id;
    private Long dynamicId;
    private Integer priority;
    private Integer queueStatus;    // 见 QueueStatus
    private Integer retryCount;
    private LocalDateTime nextRetryTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
