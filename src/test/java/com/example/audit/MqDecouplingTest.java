package com.example.audit;

import com.example.audit.common.AuditConst;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mapper.ManualAuditMapper;
import com.example.audit.mq.AuditEventHandler;
import com.example.audit.mq.AuditEventPublisher;
import com.example.audit.service.DynamicService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * 验证「业务写库」与「入队调度」已通过事件解耦。
 *
 * <p>把 AuditEventPublisher 换成 mock，消息就停在半路（模拟「消息还在 broker 里、消费者还没处理」），
 * 此时队列表里不应该有数据；手动触发一次消费动作后，队列里才有数据。
 * 这就证明了入队不再由业务服务直接做。
 */
@SpringBootTest
@ActiveProfiles("test")
class MqDecouplingTest {

    @Autowired DynamicService dynamicService;
    @Autowired AuditEventHandler handler;
    @Autowired MachineAuditMapper machineAuditMapper;
    @Autowired ManualAuditMapper manualAuditMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 拦截发送：消息发出去但没人消费 */
    @MockitoBean AuditEventPublisher publisher;

    @BeforeEach
    void cleanTables() {
        for (String t : new String[]{
                "manual_audit_log", "manual_audit_result", "manual_audit_queue",
                "machine_audit_log", "machine_audit_result", "machine_audit_queue",
                "dynamic_video", "dynamic_image", "dynamic_base"}) {
            jdbcTemplate.execute("DELETE FROM " + t);
        }
    }

    @Test
    void publish_should_emit_event_instead_of_enqueue_directly() {
        Long id = dynamicService.publish(new DynamicService.PublishCmd(
                2001L, AuditConst.DynamicType.TEXT.getCode(),
                "今日穿搭", "分享一套秋日搭配",
                List.of(), null, null, null, null));

        // 消息已发出，但消费端还没动作 → 机审队列里不应该有数据
        verify(publisher).publishDynamicPublished(id);
        assertNull(machineAuditMapper.selectByDynamicId(id), "发布只发消息，不直接入队");

        // 消费端收到消息后才入队
        handler.onDynamicPublished(id);
        assertNotNull(machineAuditMapper.selectByDynamicId(id), "消费消息后才入机审队列");
    }

    @Test
    void machine_audited_event_should_enqueue_manual_and_be_idempotent() {
        // 机审驳回：消费端不入人审
        handler.onMachineAudited(2002L, AuditConst.MachineResult.REJECT.getCode(), AuditConst.PRIORITY_NORMAL);
        assertNull(manualAuditMapper.selectByDynamicId(2002L), "驳回不应占用人工");

        // 机审疑似：入人审并插队
        handler.onMachineAudited(2003L, AuditConst.MachineResult.SUSPECT.getCode(), AuditConst.PRIORITY_SUSPECT);
        assertNotNull(manualAuditMapper.selectByDynamicId(2003L), "疑似应入人审队列");
        assertEquals(AuditConst.PRIORITY_SUSPECT,
                manualAuditMapper.selectByDynamicId(2003L).getPriority(), "疑似应插队");

        // 重复消费：MQ 至少一次投递，不能插出第二条
        handler.onMachineAudited(2003L, AuditConst.MachineResult.SUSPECT.getCode(), AuditConst.PRIORITY_SUSPECT);
        Long cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM manual_audit_queue WHERE dynamic_id = 2003", Long.class);
        assertEquals(1L, cnt, "重复消费必须幂等");
    }
}
