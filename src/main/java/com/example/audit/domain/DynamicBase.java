package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 动态基础表。
 */
@Data
public class DynamicBase {

    private Long id;
    private Long dynamicId;
    private Long userId;
    private Integer type;           // 1文字 2图片 3动图 4视频
    private String title;
    private String content;
    private Integer bizStatus;      // 见 BizStatus
    private Integer machineStatus;  // 见 MachineResult，0 表示未审
    private Integer manualStatus;   // 见 ManualResult，0 表示未审
    private Integer riskScore;
    private String riskType;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
