package com.example.audit.service;

import com.example.audit.domain.DynamicBase;
import com.example.audit.common.AuditConst.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 大模型语义兜底层。
 *
 * <p>定位：<b>只处理前几层都拿不准的长尾内容</b>。
 * 规则能拦的、图片服务能判的，都不该走到这一层——成本、延迟、确定性三方面都不划算。
 *
 * <p><b>降级方向是本项目最值得讲的设计</b>：
 * 模型超时或不可用时，降级结果必须是「疑似 → 转人工」，<b>绝不能是「通过」</b>。
 * 这是内容安全系统的铁律——宁可多花人工成本，也不能漏放违规内容。
 *
 * <p>生产建议：这里应该用 Resilience4j 的 @CircuitBreaker + @TimeLimiter 做熔断限流，
 * 本项目用 CompletableFuture.orTimeout 手写，依赖更少、更容易跑通。
 */
@Slf4j
@Component
public class AiAuditService {

    private static final String SYSTEM_PROMPT = """
            你是内容安全审核助手。判断以下内容是否违规，只输出 JSON，不要输出任何其他文字：
            {"violation": boolean, "type": "色情|暴恐|广告|违禁|政治|无", "score": 0-100, "reason": string}
            规则：
            1. score 越高越可疑，0 表示完全正常，100 表示确定违规
            2. 无法判断时 violation=false, score=50, type="无"
            3. 涉及营销引流、站外交易、导流私域的内容，type="广告"，score>=70
            """;

    private final ChatClient chatClient;
    private final long timeoutMs;
    private final boolean enabled;
    private final boolean realModelAvailable;
    /** 专用线程池：阻塞 I/O 不走 ForkJoinPool 公共池，避免互相饥饿 */
    private final Executor ioExecutor = Executors.newCachedThreadPool();

    /**
     * 用 ObjectProvider 延迟获取 ChatClient.Builder：
     * 没配 api-key 时 Spring AI 不会创建该 bean，此时取不到，AI 层自动降级为「转人工」，
     * 保证项目开箱即跑、空 key 也能启动。
     */
    /** 未配置真实密钥时使用的占位符，见 application.yml 注释 */
    private static final String PLACEHOLDER_KEY = "disabled";

    public AiAuditService(ObjectProvider<ChatClient.Builder> builderProvider,
                          @Value("${audit.ai.enabled:true}") boolean enabled,
                          @Value("${audit.ai.timeout-ms:3000}") long timeoutMs,
                          @Value("${spring.ai.openai.api-key:disabled}") String apiKey) {
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        this.chatClient = builder != null ? builder.defaultSystem(SYSTEM_PROMPT).build() : null;
        this.enabled = enabled;
        this.timeoutMs = timeoutMs;
        // 占位符（disabled）不算真实密钥：不发起任何网络请求，纯本地降级
        boolean realKey = apiKey != null && !apiKey.isBlank() && !PLACEHOLDER_KEY.equals(apiKey.trim());
        this.realModelAvailable = realKey && this.chatClient != null;
        log.info("AI 审核层初始化完成，enabled={}, 真实模型可用={}", enabled, realModelAvailable);
    }

    public StageResult audit(DynamicBase dynamic) {
        long start = System.currentTimeMillis();

        if (!enabled) {
            return StageResult.degraded(Stage.AI, "AI 层已关闭，转人工", 0);
        }

        if (chatClient == null || !realModelAvailable) {
            // 没配 api-key：直接走降级分支，保证项目开箱即跑
            return StageResult.degraded(Stage.AI, "未配置模型密钥，AI 层跳过，转人工", 0);
        }

        try {
            String content = buildContent(dynamic);

            CompletableFuture<AiVerdict> future = CompletableFuture
                    .supplyAsync(() -> chatClient.prompt()
                            .user(content)
                            .call()
                            // Structured Output：框架自动注入格式约束并反序列化
                            .entity(AiVerdict.class), ioExecutor);

            AiVerdict verdict = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            long cost = System.currentTimeMillis() - start;

            if (verdict == null) {
                return StageResult.degraded(Stage.AI, "模型返回空，转人工", cost);
            }
            return StageResult.ok(Stage.AI,
                    Boolean.TRUE.equals(verdict.violation()),
                    verdict.score() == null ? 50 : verdict.score(),
                    normalizeType(verdict.type()),
                    verdict.reason(),
                    cost);

        } catch (TimeoutException e) {
            long cost = System.currentTimeMillis() - start;
            log.warn("AI 审核超时, dynamicId={}, costMs={}", dynamic.getDynamicId(), cost);
            return StageResult.degraded(Stage.AI, "AI 审核超时，转人工", cost);

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            log.error("AI 审核异常, dynamicId={}", dynamic.getDynamicId(), e);
            return StageResult.degraded(Stage.AI, "AI 审核异常，转人工", cost);
        }
    }

    private String buildContent(DynamicBase d) {
        StringBuilder sb = new StringBuilder();
        sb.append("【标题】").append(nvl(d.getTitle())).append('\n');
        sb.append("【正文】").append(nvl(d.getContent()));
        return sb.toString();
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank() || "无".equals(type)) {
            return "";
        }
        return type;
    }

    /** Spring AI 结构化输出的目标类型 */
    public record AiVerdict(Boolean violation, String type, Integer score, String reason) {
    }
}
