package com.example.audit.mq;

/**
 * 审核流水线的事件发布口。
 *
 * <p><b>为什么要有这层抽象</b>：业务服务只依赖接口，不依赖具体 MQ。
 * 这样带来两个好处：
 * <ul>
 *   <li>换 MQ（RocketMQ → Kafka）只换实现，业务代码零改动</li>
 *   <li>本地开发和测试可以走「本地事件」实现，不装 broker 也能把整个流程跑通</li>
 * </ul>
 *
 * <p><b>为什么用 MQ 而不是直接写队列表</b>：
 * 直接写队列表意味着「发布」这个接口要顺带承担「调度」职责，
 * 队列写入失败会拖垮发布接口，而且以后要加个「发布后送风控」「发布后发通知」都得改发布服务。
 * 发一条消息出去，谁关心谁订阅，发布服务只管把动态写进去。
 */
public interface AuditEventPublisher {

    /** 动态发布成功：通知机审侧入队 */
    void publishDynamicPublished(Long dynamicId);

    /** 机审出结论：通知人审侧入队（驳回不入队，不占人工） */
    void publishMachineAudited(Long dynamicId, int machineResultCode, int priority);
}
