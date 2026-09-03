package com.example.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * UGC 动态审核系统（机审 + 人审）· 练手项目
 *
 * <p>启动前准备：
 * <ol>
 *   <li>本地起 MySQL 8：<code>docker compose up -d</code>（建库 audit_demo 后表会自动创建）</li>
 *   <li>确认 application.yml 里的数据库连接信息</li>
 *   <li>（可选）配置 OPENAI_API_KEY 启用大模型兜底层；不配也能跑，AI 阶段自动降级为转人工</li>
 * </ol>
 */
@EnableScheduling
@SpringBootApplication
public class AuditApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuditApplication.class, args);
    }
}
