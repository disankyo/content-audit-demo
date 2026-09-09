package com.example.audit.mq;

import com.example.audit.mq.AuditEventHandler;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * 测试替身（仅 test 编译期存在）：模拟 broker 把消息投递给消费端。
 *
 * <p>生产环境走 RocketMQ（{@code mq/rocketmq}），但集成测试不依赖真实 broker——
 * 这里直接调用与真实 listener 完全相同的 {@link AuditEventHandler} 逻辑，
 * 因此「发布/机审 → 入队」的业务动作被完整覆盖；
 * 传输层（JSON 序列化、ACK、重试、死信）由 RocketMQ 自身保证，不在此重复测。
 *
 * <p>注意：这只是测试替身，不是第二种 MQ 实现。生产路径 100% 是 RocketMQ。
 */
@Component
@Primary
public class TestAuditEventPublisher implements AuditEventPublisher {

    private final AuditEventHandler handler;

    public TestAuditEventPublisher(AuditEventHandler handler) {
        this.handler = handler;
    }

    @Override
    public void publishDynamicPublished(Long dynamicId) {
        handler.onDynamicPublished(dynamicId);
    }

    @Override
    public void publishMachineAudited(Long dynamicId, int machineResultCode, int priority) {
        handler.onMachineAudited(dynamicId, machineResultCode, priority);
    }
}
