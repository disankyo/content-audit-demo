package com.example.audit.mq.local;

import com.example.audit.mq.AfterCommit;
import com.example.audit.mq.AuditEventPublisher;
import com.example.audit.mq.DynamicPublishedEvent;
import com.example.audit.mq.MachineAuditedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 本地事件实现（默认）。
 *
 * <p>用 Spring 的 ApplicationEvent 在 JVM 内投递，语义和 MQ 一致（发布 → 订阅 → 入队），
 * 但零外部依赖：不装 RocketMQ 也能把整条链路跑通，集成测试也跑这个实现。
 *
 * <p>局限（面试要主动说）：本地事件是<b>进程内、同步、不持久化</b>的，
 * 服务重启消息就没了，也做不到跨实例广播。它只是开发和测试用的替身，
 * 生产必须换成真正的 MQ。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.mq.type", havingValue = "local", matchIfMissing = true)
public class LocalAuditEventPublisher implements AuditEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publishDynamicPublished(Long dynamicId) {
        AfterCommit.run(() -> applicationEventPublisher.publishEvent(new DynamicPublishedEvent(dynamicId)));
    }

    @Override
    public void publishMachineAudited(Long dynamicId, int machineResultCode, int priority) {
        AfterCommit.run(() -> applicationEventPublisher.publishEvent(
                new MachineAuditedEvent(dynamicId, machineResultCode, priority)));
    }
}
