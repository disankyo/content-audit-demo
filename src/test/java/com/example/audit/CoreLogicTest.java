package com.example.audit;

import com.example.audit.common.AuditConst.Stage;
import com.example.audit.service.IdGenerator;
import com.example.audit.service.RuleEngine;
import com.example.audit.service.StageResult;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心逻辑单元测试（不依赖数据库，可直接运行）。
 */
class CoreLogicTest {

    // ==================== 敏感词规则引擎 ====================

    @Test
    void ruleEngine_shouldHitSensitiveWord() {
        RuleEngine engine = new RuleEngine("加微信,违禁词A");

        StageResult hit = engine.scan("便宜出，需要的加微信详聊");
        assertTrue(hit.violation(), "包含敏感词应判定违规");
        assertEquals(100, hit.score());
        assertEquals("违禁", hit.riskType());
        assertTrue(hit.detail().contains("加微信"));
    }

    @Test
    void ruleEngine_shouldPassNormalContent() {
        RuleEngine engine = new RuleEngine("加微信");

        StageResult pass = engine.scan("今天天气不错，分享一套穿搭");
        assertFalse(pass.violation(), "正常内容不应命中");
        assertEquals(0, pass.score());
        assertEquals(Stage.RULE, pass.stage());
    }

    @Test
    void ruleEngine_shouldHandleBlankContent() {
        RuleEngine engine = new RuleEngine("加微信");

        assertFalse(engine.scan(null).violation(), "空内容不应命中");
        assertFalse(engine.scan("").violation());
        assertFalse(engine.scan("   ").violation());
    }

    @Test
    void ruleEngine_shouldSupportHotReload() {
        RuleEngine engine = new RuleEngine("加微信");
        assertFalse(engine.scan("内部优惠券").violation());

        engine.addWord("内部优惠券");
        assertTrue(engine.scan("内部优惠券").violation(), "热更新后应能命中新词");
    }

    // ==================== 雪花 ID ====================

    @Test
    void idGenerator_shouldGenerateUniqueIds() {
        int n = 20000;
        Set<Long> ids = new HashSet<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(IdGenerator.nextId());
        }
        assertEquals(n, ids.size(), "生成的 ID 不应重复");
    }

    @Test
    void idGenerator_shouldBeIncreasing() {
        long prev = IdGenerator.nextId();
        for (int i = 0; i < 1000; i++) {
            long cur = IdGenerator.nextId();
            assertTrue(cur > prev, "ID 应保持递增趋势");
            prev = cur;
        }
    }

    @Test
    void idGenerator_shouldBePositive() {
        for (int i = 0; i < 100; i++) {
            assertTrue(IdGenerator.nextId() > 0, "ID 应为正数（符号位固定为 0）");
        }
    }
}
