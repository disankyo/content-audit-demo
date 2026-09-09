package com.example.audit;

import com.example.audit.common.AuditConst;
import com.example.audit.domain.DynamicBase;
import com.example.audit.domain.MachineAuditQueue;
import com.example.audit.domain.ManualAuditQueue;
import com.example.audit.mapper.DynamicBaseMapper;
import com.example.audit.mapper.MachineAuditMapper;
import com.example.audit.mapper.ManualAuditMapper;
import com.example.audit.service.DynamicService;
import com.example.audit.service.MachineAuditConsumer;
import com.example.audit.service.ManualAuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试（H2 MySQL 兼容模式，无需外部数据库）。
 * 覆盖：发布 → 机审（规则/图片/AI 降级）→ 人审 → 最终态。
 */
@SpringBootTest
@ActiveProfiles("test")
class AuditFlowIntegrationTest {

    @Autowired DynamicService dynamicService;
    @Autowired MachineAuditConsumer machineAuditConsumer;
    @Autowired ManualAuditService manualAuditService;
    @Autowired MachineAuditMapper machineAuditMapper;
    @Autowired ManualAuditMapper manualAuditMapper;
    @Autowired DynamicBaseMapper dynamicBaseMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    /** 每个用例前清空所有表，避免 H2 内存库在测试间共享数据造成污染 */
    @BeforeEach
    void cleanTables() {
        for (String t : new String[]{
                "manual_audit_log", "manual_audit_result", "manual_audit_queue",
                "machine_audit_log", "machine_audit_result", "machine_audit_queue",
                "dynamic_video", "dynamic_image", "dynamic_base"}) {
            jdbcTemplate.execute("DELETE FROM " + t);
        }
    }

    /** 跑一次机审：取队列 → 调用消费者的 handleOne（与定时任务同一逻辑） */
    private void runMachineAudit(Long dynamicId) {
        List<MachineAuditQueue> batch = machineAuditMapper.pickBatch(1);
        MachineAuditQueue q = batch.stream()
                .filter(x -> x.getDynamicId().equals(dynamicId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("机审队列未生成, dynamicId=" + dynamicId));
        machineAuditConsumer.handleOne(q);
    }

    // ---------- 用例 1：敏感词命中 → 机审直接驳回 ----------
    @Test
    void text_with_sensitive_word_should_reject_by_machine() {
        Long id = dynamicService.publish(new DynamicService.PublishCmd(
                1001L, AuditConst.DynamicType.TEXT.getCode(),
                "好物分享", "便宜出，需要的加微信详聊",
                List.of(), null, null, null, null));

        runMachineAudit(id);

        DynamicBase d = dynamicBaseMapper.selectByDynamicId(id);
        assertEquals(AuditConst.BizStatus.REJECTED.getCode(), d.getBizStatus(), "敏感词应机审驳回");
        assertEquals(AuditConst.MachineResult.REJECT.getCode(), d.getMachineStatus());
        // 机审驳回不进人审队列
        assertNull(manualAuditMapper.selectByDynamicId(id), "驳回不应产生人审任务");
    }

    // ---------- 用例 2：正常文本 → 机审通过 → 人审通过 → 最终通过 ----------
    @Test
    void clean_text_should_pass_machine_then_manual() {
        Long id = dynamicService.publish(new DynamicService.PublishCmd(
                1002L, AuditConst.DynamicType.TEXT.getCode(),
                "今日穿搭", "分享一套秋日通勤穿搭灵感",
                List.of(), null, null, null, null));

        runMachineAudit(id);

        DynamicBase afterMachine = dynamicBaseMapper.selectByDynamicId(id);
        assertTrue(afterMachine.getMachineStatus() == AuditConst.MachineResult.PASS.getCode()
                        || afterMachine.getMachineStatus() == AuditConst.MachineResult.SUSPECT.getCode(),
                "正常文本机审应放行或转人工");
        assertNotNull(manualAuditMapper.selectByDynamicId(id), "应通过或疑似，进入人审队列");

        // 审核员领取并提交通过
        ManualAuditService.ManualTask task = manualAuditService.claim(9001L);
        assertNotNull(task, "应能领取到人审任务");
        boolean ok = manualAuditService.submit(9001L, id, AuditConst.ManualResult.PASS, "内容正常");
        assertTrue(ok);
        // 队列是临时调度数据：审完即删，结论只留在 manual_audit_result / manual_audit_log
        assertNull(manualAuditMapper.selectByDynamicId(id), "人审提交后队列记录应删除");
        assertNotNull(manualAuditMapper.selectResult(id), "人审结论应保留在结果表");
        assertFalse(manualAuditMapper.selectLogs(id).isEmpty(), "人审动作应有留痕");

        DynamicBase finalD = dynamicBaseMapper.selectByDynamicId(id);
        assertEquals(AuditConst.BizStatus.PASSED.getCode(), finalD.getBizStatus(), "人审通过应最终通过");
    }

    // ---------- 用例 3：图片命中违规桶 → 机审驳回 ----------
    @Test
    void image_violation_should_reject_by_machine() {
        String url = findUrlForBucket(0);   // ImageAuditClient：bucket 0 = 违规
        Long id = dynamicService.publish(new DynamicService.PublishCmd(
                1003L, AuditConst.DynamicType.IMAGE.getCode(),
                "随手拍", "",
                List.of(url), null, null, null, null));

        runMachineAudit(id);

        DynamicBase d = dynamicBaseMapper.selectByDynamicId(id);
        assertEquals(AuditConst.BizStatus.REJECTED.getCode(), d.getBizStatus(), "违规图片应机审驳回");
    }

    // ---------- 用例 4：人审队列只有两态，放弃后退回待领取 ----------
    @Test
    void manual_queue_should_only_have_pending_and_claimed() {
        Long id = dynamicService.publish(new DynamicService.PublishCmd(
                1004L, AuditConst.DynamicType.TEXT.getCode(),
                "周末去哪玩", "记录一次近郊徒步",
                List.of(), null, null, null, null));
        runMachineAudit(id);

        ManualAuditQueue before = manualAuditMapper.selectByDynamicId(id);
        assertEquals(AuditConst.ManualQueueStatus.PENDING.getCode(), before.getQueueStatus(), "入队应为待领取");

        assertNotNull(manualAuditService.claim(9002L), "应能领取");
        ManualAuditQueue claimed = manualAuditMapper.selectByDynamicId(id);
        assertEquals(AuditConst.ManualQueueStatus.CLAIMED.getCode(), claimed.getQueueStatus(), "领取后应为已领取");
        assertEquals(9002L, claimed.getAssigneeId());

        assertTrue(manualAuditService.giveBack(9002L, id), "持有者应能放弃");
        ManualAuditQueue back = manualAuditMapper.selectByDynamicId(id);
        assertEquals(AuditConst.ManualQueueStatus.PENDING.getCode(), back.getQueueStatus(), "放弃后应退回待领取");
        assertNull(back.getAssigneeId(), "放弃后应清空持有人");
    }

    // ImageAuditClient.scan 按 Math.abs(url.hashCode()) % 10 分桶；找到命中指定桶的 URL
    private String findUrlForBucket(int bucket) {
        for (int i = 0; i < 100000; i++) {
            String u = "http://cdn.example.com/p" + i + ".jpg";
            if (Math.abs(u.hashCode()) % 10 == bucket) {
                return u;
            }
        }
        throw new IllegalStateException("找不到命中桶 " + bucket + " 的测试 URL");
    }
}
