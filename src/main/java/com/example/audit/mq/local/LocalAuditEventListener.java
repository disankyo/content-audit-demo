package com.example.audit.mq.local;

import com.example.audit.mq.AuditEventHandler;
import com.example.audit.mq.DynamicPublishedEvent;
import com.example.audit.mq.MachineAuditedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 本地事件的订阅方，逻辑与 RocketMQ 消费端共用 {@link AuditEventHandler}。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "audit.mq.type", havingValue = "local", matchIfMissing = true)
public class LocalAuditEventListener {

    private final AuditEventHandler handler;

    @EventListener
    public void onDynamicPublished(DynamicPublishedEvent event) {
        handler.onDynamicPublished(event.dynamicId());
    }

    @EventListener
    public void onMachineAudited(MachineAuditedEvent event) {
        handler.onMachineAudited(event.dynamicId(), event.machineResultCode(), event.priority());
    }
}
