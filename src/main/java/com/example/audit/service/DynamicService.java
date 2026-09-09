package com.example.audit.service;

import com.example.audit.common.AuditConst;
import com.example.audit.domain.DynamicBase;
import com.example.audit.domain.DynamicImage;
import com.example.audit.domain.DynamicVideo;
import com.example.audit.domain.MachineAuditLog;
import com.example.audit.domain.MachineAuditResult;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.DynamicImageMapper;
import com.example.audit.mapper.DynamicVideoMapper;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mq.AuditEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态发布服务。
 * 发布 → 落库 → <b>发消息</b>（由消费端入机审队列）。
 *
 * <p>发布服务不再自己写机审队列表：入队属于「调度」职责，交给消费端去做。
 * 这样发布接口的耗时和失败率都不受调度侧影响，后续要加「发布后送风控」「发布后发通知」，
 * 只要多订阅一个消费者，发布服务不用改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicService {

    private final DynamicBaseMapper dynamicBaseMapper;
    private final DynamicImageMapper dynamicImageMapper;
    private final DynamicVideoMapper dynamicVideoMapper;
    private final MachineAuditMapper machineAuditMapper;
    private final AuditEventPublisher eventPublisher;
    private final FrameExtractor frameExtractor;

    @Transactional
    public Long publish(PublishCmd cmd) {
        long dynamicId = IdGenerator.nextId();
        AuditConst.DynamicType type = AuditConst.DynamicType.of(cmd.type());

        // 抽帧放在事务外：避免长时间持有 DB 连接
        List<String> preExtractedFrames = null;
        if (type == AuditConst.DynamicType.GIF && !cmd.imageUrls().isEmpty()) {
            preExtractedFrames = frameExtractor.extract(cmd.imageUrls().get(0), true);
        }

        writePublishData(dynamicId, cmd, type, preExtractedFrames);

        // 通知机审侧入队。事务提交后才会真正投递（见 AfterCommit），
        // 否则消费端可能查不到刚写入的动态
        eventPublisher.publishDynamicPublished(dynamicId);

        log.info("动态发布成功, dynamicId={}, type={}", dynamicId, type.getDesc());
        return dynamicId;
    }

    @Transactional
    public void writePublishData(long dynamicId, PublishCmd cmd,
                                 AuditConst.DynamicType type, List<String> gifFrames) {
        // 1. 动态主表
        DynamicBase base = new DynamicBase();
        base.setDynamicId(dynamicId);
        base.setUserId(cmd.userId());
        base.setType(cmd.type());
        base.setTitle(cmd.title());
        base.setContent(cmd.content());
        base.setBizStatus(AuditConst.BizStatus.AUDITING.getCode());
        dynamicBaseMapper.insert(base);

        // 2. 图片 / 动图：动图使用预抽帧结果
        if (type == AuditConst.DynamicType.IMAGE || type == AuditConst.DynamicType.GIF) {
            List<DynamicImage> images = new ArrayList<>();
            if (gifFrames != null) {
                for (int i = 0; i < gifFrames.size(); i++) {
                    images.add(buildImage(dynamicId, gifFrames.get(i), i));
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

        // 4. 入机审队列不再在这里做：改由「动态已发布」事件的消费端处理（见 AuditEventHandler）
    }

    // ==================== 查询方法 ====================

    public DynamicBase getDetail(Long dynamicId) {
        return dynamicBaseMapper.selectByDynamicId(dynamicId);
    }

    public MachineAuditResult getMachineResult(Long dynamicId) {
        return machineAuditMapper.selectResult(dynamicId);
    }

    public List<MachineAuditLog> getMachineLogs(Long dynamicId) {
        return machineAuditMapper.selectLogs(dynamicId);
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
