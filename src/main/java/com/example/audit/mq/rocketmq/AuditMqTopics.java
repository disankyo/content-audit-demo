package com.example.audit.mq.rocketmq;

/** RocketMQ 的 topic / group 命名集中放，避免生产端和消费端写岔。 */
public final class AuditMqTopics {

    private AuditMqTopics() {}

    public static final String DYNAMIC_PUBLISHED = "AUDIT_DYNAMIC_PUBLISHED";
    public static final String MACHINE_AUDITED = "AUDIT_MACHINE_AUDITED";

    public static final String PRODUCER_GROUP = "audit_producer_group";
    public static final String CONSUMER_GROUP = "audit_consumer_group";
}
