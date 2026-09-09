package com.example.audit.mq.rocketmq;

import com.example.audit.mq.AuditEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 消费「动态已发布」→ 入机审队列。
 *
 * <p>消费逻辑必须幂等：RocketMQ 是至少一次投递，重投、Rebalance、消费者重启都会导致重复消费。
 * 入队 SQL 是 INSERT IGNORE + uk_dynamic，重复消费天然安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.mq.type", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = AuditMqTopics.DYNAMIC_PUBLISHED,
        consumerGroup = AuditMqTopics.CONSUMER_GROUP
)
public class RocketMqDynamicPublishedListener implements RocketMQListener<String> {

    private final AuditEventHandler handler;

    @Override
    public void onMessage(String body) {
        Long dynamicId = Long.valueOf(body.trim());
        log.debug("消费发布事件, dynamicId={}", dynamicId);
        handler.onDynamicPublished(dynamicId);
    }
}
