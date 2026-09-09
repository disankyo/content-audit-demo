package com.example.audit.service;

import com.example.audit.common.AuditConst.DynamicType;
import com.example.audit.common.AuditConst.MachineResult;
import com.example.audit.common.AuditConst.Stage;
import com.example.audit.domain.DynamicBase;
import com.example.audit.domain.DynamicImage;
import com.example.audit.domain.DynamicVideo;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.DynamicImageMapper;
import com.example.audit.mapper.DynamicVideoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 机审服务：多阶段审核 + 结论聚合。
 *
 * <p>分层设计（这是本项目最值得讲的部分）：
 * <pre>
 *   规则（敏感词）  → 快、准、便宜，拦第一道
 *   图片/抽帧审核   → 多模态，大模型做不了，交给专业服务
 *   AI 语义兜底     → 只处理前两层拿不准的长尾，成本最高、最慢
 * </pre>
 *
 * <p>为什么不全用 AI：成本、延迟、确定性三方面都不划算。
 * 明确违禁词用规则是 100% 命中且可解释的，用大模型反而有概率误判。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MachineAuditService {

    private final DynamicBaseMapper dynamicBaseMapper;
    private final DynamicImageMapper dynamicImageMapper;
    private final DynamicVideoMapper dynamicVideoMapper;
    private final RuleEngine ruleEngine;
    private final ImageAuditClient imageAuditClient;
    private final FrameExtractor frameExtractor;
    private final AiAuditService aiAuditService;

    /** 风险分阈值：达到即判定为疑似，转人工复核 */
    private static final int SUSPECT_SCORE = 60;

    public MachineOutcome audit(Long dynamicId) {
        DynamicBase dynamic = dynamicBaseMapper.selectByDynamicId(dynamicId);
        if (dynamic == null) {
            throw new IllegalArgumentException("动态不存在: " + dynamicId);
        }

        List<StageResult> stages = new ArrayList<>();
        DynamicType type = DynamicType.of(dynamic.getType());

        // ---- 阶段一：文字规则（最快、最确定） ----
        if (StringUtils.hasText(dynamic.getContent()) || StringUtils.hasText(dynamic.getTitle())) {
            String text = dynamic.getTitle() + " " + dynamic.getContent();
            stages.add(ruleEngine.scan(text));
        }

        // ---- 阶段二：图片 / 动图抽帧 ----
        if (type == DynamicType.IMAGE || type == DynamicType.GIF) {
            List<DynamicImage> images = dynamicImageMapper.selectByDynamicId(dynamicId);
            for (DynamicImage img : images) {
                Stage s = (type == DynamicType.GIF) ? Stage.FRAME : Stage.IMAGE;
                stages.add(imageAuditClient.scan(img.getImageUrl(), s));
            }
        }

        // ---- 阶段三：视频抽帧后送图片审核 ----
        if (type == DynamicType.VIDEO) {
            stages.add(auditVideo(dynamicId));
        }

        // ---- 阶段四：AI 语义兜底（仅当前面各阶段都没给出明确结论时） ----
        if (needAi(stages)) {
            stages.add(aiAuditService.audit(dynamic));
        }

        MachineOutcome outcome = aggregate(stages);
        log.info("机审完成, dynamicId={}, result={}, score={}, stages={}",
                dynamicId, outcome.result(), outcome.riskScore(), outcome.modelVersion());
        return outcome;
    }

    /**
     * 视频审核：抽关键帧 → 逐帧送图片审核。
     * 抽帧结果落 dynamic_image（frame_status 标记），避免重复抽帧。
     */
    private StageResult auditVideo(Long dynamicId) {
        DynamicVideo video = dynamicVideoMapper.selectByDynamicId(dynamicId);
        if (video == null) {
            return StageResult.fail(Stage.VIDEO, "视频记录不存在", 0);
        }

        // 检查是否已抽帧（重试场景下避免重复抽帧，抽帧是最耗时的操作）
        List<DynamicImage> frameImages;
        boolean alreadyFramed = video.getFrameStatus() != null && video.getFrameStatus() == 1;
        if (alreadyFramed) {
            frameImages = dynamicImageMapper.selectByDynamicId(dynamicId);
            log.debug("视频已抽帧，复用已有帧, dynamicId={}, frames={}", dynamicId, frameImages.size());
        } else {
            List<String> frames = frameExtractor.extract(video.getVideoUrl(), false);
            frameImages = new ArrayList<>();
            for (int i = 0; i < frames.size(); i++) {
                DynamicImage img = new DynamicImage();
                img.setDynamicId(dynamicId);
                img.setImageUrl(frames.get(i));
                img.setSortNo(i);
                frameImages.add(img);
            }
            dynamicImageMapper.batchInsert(frameImages);
            dynamicVideoMapper.markFramed(dynamicId);
        }

        // 逐帧审核：任一帧命中即视为违规
        StageResult worst = null;
        for (DynamicImage f : frameImages) {
            StageResult r = imageAuditClient.scan(f.getImageUrl(), Stage.FRAME);
            if (worst == null || r.score() > worst.score()) {
                worst = r;
            }
            if (Boolean.TRUE.equals(r.violation())) {
                break;   // 已确定违规，无需再审后续帧
            }
        }
        return worst == null ? StageResult.ok(Stage.VIDEO, false, 0, "", "", 0) : worst;
    }

    /**
     * 是否需要调用 AI 兜底层。
     * 只有前面各阶段都「没命中、但也没十足把握」时才调用——控制成本。
     */
    private boolean needAi(List<StageResult> stages) {
        if (stages.isEmpty()) {
            return true;
        }
        boolean anyViolation = stages.stream().anyMatch(StageResult::violation);
        boolean anyDegraded = stages.stream().anyMatch(s -> s.score() >= SUSPECT_SCORE);
        // 已确定违规 → 不需要再花成本；已出现疑似 → 交给人工，也不必调模型
        return !anyViolation && !anyDegraded;
    }

    /**
     * 聚合各阶段结论。
     * 规则：任一阶段判定违规 → 驳回；任一阶段高风险或降级 → 疑似；否则通过。
     */
    private MachineOutcome aggregate(List<StageResult> stages) {
        boolean violation = stages.stream().anyMatch(StageResult::violation);
        int maxScore = stages.stream().mapToInt(StageResult::score).max().orElse(0);

        String riskType = stages.stream()
                .map(StageResult::riskType)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");

        String hitRule = String.join(";", stages.stream()
                .map(StageResult::detail)
                .filter(StringUtils::hasText)
                .toList());

        MachineResult result;
        if (violation) {
            result = MachineResult.REJECT;
        } else if (maxScore >= SUSPECT_SCORE) {
            result = MachineResult.SUSPECT;
        } else {
            result = MachineResult.PASS;
        }

        return new MachineOutcome(result, maxScore, riskType, hitRule, stages);
    }
}
