package com.example.audit.mq;

import java.io.Serializable;

/**
 * 动态发布事件。
 *
 * <p>字段只带 dynamicId 而不带正文：消息体越小越好（省带宽、省存储），
 * 且内容可能后续被编辑，带上快照反而会和库里不一致。消费方按需回查即可。
 */
public record DynamicPublishedEvent(Long dynamicId) implements Serializable {
}
