package com.example.audit.mq;

import com.example.audit.common.AuditConst;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mapper.ManualAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 事件消费侧的入队逻辑。
 *
 * <p>本地事件和 RocketMQ 两种传输方式共用这一个实现——
 * 换个 MQ 只换「消息怎么来」，业务动作不变。
 *
 * <p><b>幂等是这里的生命线</b>：MQ 只保证至少一次投递，重复消费必然发生。
 * 两条入队 SQL 都是 INSERT IGNORE + 唯一键（uk_dynamic），
 * 重复投递不会插出第二条，也不会报错。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventHandler {

    private final MachineAuditMapper machineAuditMapper;
    private final ManualAuditMapper manualAuditMapper;

    /** 收到「动态已发布」→ 入机审队列 */
    public void onDynamicPublished(Long dynamicId) {
        int n = machineAuditMapper.insertIgnore(dynamicId, AuditConst.PRIORITY_NORMAL);
        log.info("收到发布事件，入机审队列, dynamicId={}, 入队行数={}（0 表示已存在，重复消费）", dynamicId, n);
    }

    /**
     * 收到「机审完成」→ 入人审队列。
     * 机审驳回的不进人审，直接终结，不占人工资源。
     */
    public void onMachineAudited(Long dynamicId, int machineResultCode, int priority) {
        if (machineResultCode == AuditConst.MachineResult.REJECT.getCode()) {
            log.info("机审驳回，不入人审队列, dynamicId={}", dynamicId);
            return;
        }
        int n = manualAuditMapper.insertIgnore(dynamicId, priority, machineResultCode);
        log.info("收到机审完成事件，入人审队列, dynamicId={}, result={}, priority={}, 入队行数={}",
                dynamicId, machineResultCode, priority, n);
    }
}
