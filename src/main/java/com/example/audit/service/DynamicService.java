package com.example.audit.service;

import com.example.audit.common.AuditConst;
import com.example.audit.domain.DynamicBase;
import com.example.audit.domain.DynamicImage;
import com.example.audit.domain.DynamicVideo;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.DynamicImageMapper;
import com.example.audit.mapper.DynamicVideoMapper;
import com.example.audit.mapper.MachineAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态发布服务。
 * 发布 → 落库 → 入机审队列（幂等）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicService {

    private final DynamicBaseMapper dynamicBaseMapper;
    private final DynamicImageMapper dynamicImageMapper;
    private final DynamicVideoMapper dynamicVideoMapper;
    private final MachineAuditMapper machineAuditMapper;
    private final FrameExtractor frameExtractor;

    @Transactional
    public Long publish(PublishCmd cmd) {
        long dynamicId = IdGenerator.nextId();

        // 1. 动态主表
        DynamicBase base = new DynamicBase();
        base.setDynamicId(dynamicId);
        base.setUserId(cmd.userId());
        base.setType(cmd.type());
        base.setTitle(cmd.title());
        base.setContent(cmd.content());
        base.setBizStatus(AuditConst.BizStatus.AUDITING.getCode());
        dynamicBaseMapper.insert(base);

        AuditConst.DynamicType type = AuditConst.DynamicType.of(cmd.type());

        // 2. 图片 / 动图：动图先抽帧，抽出的帧与多图共用 dynamic_image
        if (type == AuditConst.DynamicType.IMAGE || type == AuditConst.DynamicType.GIF) {
            List<DynamicImage> images = new ArrayList<>();
            if (type == AuditConst.DynamicType.GIF && !cmd.imageUrls().isEmpty()) {
                List<String> frames = frameExtractor.extract(cmd.imageUrls().get(0), true);
                for (int i = 0; i < frames.size(); i++) {
                    images.add(buildImage(dynamicId, frames.get(i), i));
                }
            } else {
                for (int i = 0; i < cmd.imageUrls().size(); i++) {
                    images.add(buildImage(dynamicId, cmd.imageUrls().get(i), i));
                }
            }
            if (!images.isEmpty()) {
                dynamicImageMapper.batchInsert(images);
            }
        }

        // 3. 视频：只落库，抽帧放到机审阶段异步做（避免发布接口变慢）
        if (type == AuditConst.DynamicType.VIDEO && cmd.videoUrl() != null) {
            DynamicVideo video = new DynamicVideo();
            video.setDynamicId(dynamicId);
            video.setVideoUrl(cmd.videoUrl());
            video.setCoverUrl(cmd.coverUrl() == null ? "" : cmd.coverUrl());
            video.setDuration(cmd.duration() == null ? 0 : cmd.duration());
            video.setFileSize(cmd.fileSize() == null ? 0L : cmd.fileSize());
            dynamicVideoMapper.insert(video);
        }

        // 4. 幂等入机审队列：重复发布不会产生第二条
        machineAuditMapper.insertIgnore(dynamicId, AuditConst.PRIORITY_NORMAL);

        log.info("动态发布成功, dynamicId={}, type={}", dynamicId, type.getDesc());
        return dynamicId;
    }

    private DynamicImage buildImage(long dynamicId, String url, int sortNo) {
        DynamicImage img = new DynamicImage();
        img.setDynamicId(dynamicId);
        img.setImageUrl(url);
        img.setSortNo(sortNo);
        return img;
    }

    // ==================== 请求对象 ====================

    public record PublishCmd(
            Long userId,
            Integer type,           // 1文字 2图片 3动图 4视频
            String title,
            String content,
            List<String> imageUrls,
            String videoUrl,
            String coverUrl,
            Integer duration,
            Long fileSize
    ) {
        public PublishCmd {
            if (imageUrls == null) {
                imageUrls = List.of();
            }
            if (title == null) {
                title = "";
            }
        }
    }
}
