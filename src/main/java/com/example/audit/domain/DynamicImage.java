package com.example.audit.domain;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 动态图片表。
 * 说明：动图（GIF）不单独建表，抽帧后每一帧作为一条记录写入本表，
 * 用 sortNo 记录帧序。这样图片与动图共用一套审核逻辑。
 */
@Data
public class DynamicImage {

    private Long id;
    private Long dynamicId;
    private String imageUrl;
    private Integer width;
    private Integer height;
    private Integer fileSize;
    private Integer sortNo;         // 多图排序 / 动图帧序
    private Integer imageStatus;    // 0未审 1通过 2驳回
    private LocalDateTime createTime;
}
