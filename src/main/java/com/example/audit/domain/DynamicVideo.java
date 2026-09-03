package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 动态视频表。
 */
@Data
public class DynamicVideo {

    private Long id;
    private Long dynamicId;
    private String videoUrl;
    private String coverUrl;
    private Integer duration;       // 时长（秒）
    private Long fileSize;
    private Integer videoStatus;    // 0未审 1通过 2驳回
    private Integer frameStatus;    // 0未抽帧 1已抽帧
    private LocalDateTime createTime;
}
