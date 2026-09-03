package com.example.audit.service;

import com.example.audit.common.AuditConst.Stage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词规则引擎（DFA / Trie 树实现）。
 *
 * <p>为什么第一道防线是规则而不是大模型：
 * <ul>
 *   <li>成本：UGC 平台日增量百万级，全量调模型账单扛不住</li>
 *   <li>延迟：规则是毫秒级，模型是百毫秒到秒级</li>
 *   <li>确定性：明确违禁词规则 100% 命中且可解释，模型有概率误判</li>
 * </ul>
 *
 * <p>生产环境注意：词库应支持热更新（配置中心 / 数据库定时拉取），
 * 而不是硬编码在配置文件里。这里为了跑通流程从配置读取。
 */
@Slf4j
@Component
public class RuleEngine {

    private final TrieNode root = new TrieNode();

    public RuleEngine(@Value("${audit.sensitive-words:}") String wordsStr) {
        int n = 0;
        if (wordsStr != null && !wordsStr.isBlank()) {
            for (String w : wordsStr.split("[,，]")) {   // 兼容中英文逗号
                if (w != null && !w.isBlank()) {
                    insert(w.trim());
                    n++;
                }
            }
        }
        log.info("敏感词库加载完成，共 {} 条", n);
    }

    /** 支持热更新：新增敏感词 */
    public synchronized void addWord(String word) {
        insert(word);
    }

    public StageResult scan(String content) {
        long start = System.currentTimeMillis();
        if (content == null || content.isBlank()) {
            return StageResult.ok(Stage.RULE, false, 0, "", "", 0);
        }
        String hit = match(content);
        long cost = System.currentTimeMillis() - start;
        if (hit != null) {
            return StageResult.ok(Stage.RULE, true, 100, "违禁", "命中敏感词：" + hit, cost);
        }
        return StageResult.ok(Stage.RULE, false, 0, "", "", cost);
    }

    // ==================== DFA / Trie ====================

    private void insert(String word) {
        TrieNode node = root;
        for (char c : word.toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        node.end = true;
    }

    /** 返回第一个命中的敏感词，未命中返回 null */
    private String match(String text) {
        for (int i = 0; i < text.length(); i++) {
            TrieNode node = root;
            for (int j = i; j < text.length(); j++) {
                node = node.children.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.end) {
                    return text.substring(i, j + 1);
                }
            }
        }
        return null;
    }

    private static class TrieNode {
        private final java.util.Map<Character, TrieNode> children = new java.util.HashMap<>();
        private boolean end;
    }
}
