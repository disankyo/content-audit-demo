package com.example.audit.mq;

import java.io.Serializable;

/**
 * 机审完成事件。
 *
 * <p>priority 由机审侧算好（疑似插队）一起带过来，消费方不用再判断一遍业务规则。
 */
public record MachineAuditedEvent(Long dynamicId, int machineResultCode, int priority) implements Serializable {
}
