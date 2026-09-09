package com.example.audit.mq.rocketmq;

import com.example.audit.mq.AuditEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 消费「机审完成」→ 入人审队列（疑似内容插队）。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.mq.type", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = AuditMqTopics.MACHINE_AUDITED,
        consumerGroup = AuditMqTopics.CONSUMER_GROUP
)
public class RocketMqMachineAuditedListener implements RocketMQListener<String> {

    private final AuditEventHandler handler;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String body) {
        try {
            RocketMqAuditEventPublisher.MachineAuditedPayload payload =
                    objectMapper.readValue(body, RocketMqAuditEventPublisher.MachineAuditedPayload.class);
            handler.onMachineAudited(payload.dynamicId(), payload.machineResultCode(), payload.priority());
        } catch (Exception e) {
            // 反序列化失败：这条消息重试多少次都还是失败，记日志告警，交给人工/死信处理
            log.error("机审完成事件反序列化失败, body={}", body, e);
            throw new IllegalStateException("无法解析机审完成事件: " + body, e);
        }
    }
}
