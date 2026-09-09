package com.example.audit.mq.rocketmq;

import com.example.audit.mq.AfterCommit;
import com.example.audit.mq.AuditEventPublisher;
import com.example.audit.mq.AuditEventHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RocketMQ 实现：真正投递到 broker。
 *
 * <p>几个工程上的取舍：
 * <ul>
 *   <li><b>消息体用 JSON 字符串</b>：不同版本客户端的序列化器实现有差异，
 *       自己显式序列化最稳，也方便在控制台直接看消息内容排查问题</li>
 *   <li><b>事务提交后再发</b>（{@link AfterCommit}）：避免消费端回查时本地事务还没提交</li>
 *   <li><b>发送失败降级成直接入队</b>：broker 挂了不能让任务凭空消失，
 *       宁可退化成同步写队列，也不能丢审核任务</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.mq.type", havingValue = "rocketmq")
public class RocketMqAuditEventPublisher implements AuditEventPublisher {

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final AuditEventHandler fallbackHandler;

    @Override
    public void publishDynamicPublished(Long dynamicId) {
        AfterCommit.run(() -> send(AuditMqTopics.DYNAMIC_PUBLISHED, dynamicId.toString(), () -> {
            log.error("发布事件发送失败，降级为直接入机审队列, dynamicId={}", dynamicId);
            fallbackHandler.onDynamicPublished(dynamicId);
        }));
    }

    @Override
    public void publishMachineAudited(Long dynamicId, int machineResultCode, int priority) {
        AfterCommit.run(() -> {
            String body = toJson(new MachineAuditedPayload(dynamicId, machineResultCode, priority));
            send(AuditMqTopics.MACHINE_AUDITED, body, () -> {
                log.error("机审完成事件发送失败，降级为直接入人审队列, dynamicId={}", dynamicId);
                fallbackHandler.onMachineAudited(dynamicId, machineResultCode, priority);
            });
        });
    }

    private void send(String topic, String body, Runnable fallback) {
        try {
            rocketMQTemplate.convertAndSend(topic, body);
        } catch (Exception e) {
            log.error("发送 MQ 消息失败, topic={}, body={}", topic, body, e);
            fallback.run();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("消息序列化失败", e);
        }
    }

    /** 机审完成事件的消息体 */
    public record MachineAuditedPayload(Long dynamicId, int machineResultCode, int priority) {
    }
}
