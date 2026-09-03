package com.example.audit.service;

import com.example.audit.common.AuditConst.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 图片审核客户端。
 *
 * <p>本项目用 Mock 实现（按 URL 哈希给出结论），目的是跑通流程。
 *
 * <p><b>生产环境真实做法</b>：接第三方内容安全服务，例如
 * 腾讯云天御（IMS）、阿里云内容安全（绿网）、百度内容审核平台。
 * 这些服务提供多模态能力（色情 / 暴恐 / 政治人物 / 广告 / 二维码识别），
 * 是大模型替代不了的——大模型做不了精确的图像像素级识别。
 *
 * <p>接入时要处理的工程问题（面试可讲）：
 * <ul>
 *   <li>超时与重试：第三方偶发超时，需要 timeout + 有限重试</li>
 *   <li>降级：服务不可用时的兜底策略（本项目降级为转人工）</li>
 *   <li>成本：按调用次数计费，需要缓存相同图片的审核结果（图片 MD5 去重）</li>
 *   <li>异步回调：部分服务走异步模式，需要回调接口 + 轮询补偿</li>
 * </ul>
 */
@Slf4j
@Component
public class ImageAuditClient {

    public StageResult scan(String imageUrl, Stage stage) {
        long start = System.currentTimeMillis();
        try {
            // ---- Mock 实现：按 URL 哈希模拟结果，让流程能跑出不同分支 ----
            int hash = Math.abs(imageUrl.hashCode());
            int bucket = hash % 10;

            long cost = System.currentTimeMillis() - start;

            if (bucket == 0) {
                return StageResult.ok(stage, true, 95, "色情", "疑似违规图片（mock）", cost);
            }
            if (bucket == 1) {
                return StageResult.ok(stage, true, 90, "广告", "疑似广告二维码（mock）", cost);
            }
            if (bucket == 2) {
                return StageResult.ok(stage, false, 60, "", "疑似边缘内容，需人工确认（mock）", cost);
            }
            return StageResult.ok(stage, false, 5, "", "正常（mock）", cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("图片审核异常, url={}", imageUrl, e);
            // 第三方不可用：降级为疑似，转人工，绝不自动放行
            return StageResult.degraded(stage, "图片审核服务不可用，转人工", cost);
        }
    }

    public StageResult scan(String imageUrl) {
        return scan(imageUrl, Stage.IMAGE);
    }
}
