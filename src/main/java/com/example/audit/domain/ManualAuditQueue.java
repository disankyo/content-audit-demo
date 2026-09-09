package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 人审审核队列表（临时调度数据：审核提交后记录删除）。
 */
@Data
public class ManualAuditQueue {

    private Long id;
    private Long dynamicId;
    private Integer priority;
    private Integer queueStatus;    // 见 ManualQueueStatus：0待领取 1已领取
    private Integer machineResult;  // 机审结论，供审核员参考
    private Long assigneeId;        // 审核员ID
    private LocalDateTime lockExpireTime;   // 锁过期时间，防审核员挂起
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
