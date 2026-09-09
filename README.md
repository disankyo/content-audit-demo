# content-audit-demo

UGC 动态审核系统（机审 + 人审）练手项目。

支持 **文字 / 图片 / 动图 / 视频** 四类动态，采用「机审先行、机审通过才流转人审」的两级审核流程。

> 说明：这是**个人技术实践项目**，用于验证设计思路与保持工程手感，不是生产系统。
> 已知不足见文末。

---

## 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17（可切 21） | 想用虚拟线程改 pom 的 `java.version` 为 21，并在 yml 加 `spring.threads.virtual.enabled=true` |
| Spring Boot | 3.5.5 | |
| Spring AI | 1.0.9 | 未配置 api-key 时自动降级，不影响启动 |
| MyBatis | 3.0.4 | 注解方式，无 XML |
| MySQL | 8.0 | 队列表方案的载体 |

---

## 快速开始

```bash
# 1. 起数据库
docker compose up -d

# 2. 启动应用（首次启动自动建表，见 schema.sql）
mvn spring-boot:run

# 可选：启用大模型兜底层
export OPENAI_API_KEY=sk-xxxx
```

应用监听 `http://localhost:8080`。

---

## 跑一遍完整流程

```bash
# 1. 发布一条文字动态（含敏感词，会被机审直接驳回）
curl -X POST http://localhost:8080/api/dynamic/publish \
  -H 'Content-Type: application/json' \
  -d '{"userId":10001,"type":1,"title":"便宜出","content":"需要的加微信详聊","imageUrls":[]}'

# 2. 发布一条正常动态（会流入人审）
curl -X POST http://localhost:8080/api/dynamic/publish \
  -H 'Content-Type: application/json' \
  -d '{"userId":10002,"type":2,"title":"今日穿搭","content":"分享一套搭配","imageUrls":["https://cdn.example.com/a.jpg"]}'

# 3. 查机审结论
curl http://localhost:8080/api/dynamic/{dynamicId}/machine-result

# 4. 查机审各阶段留痕
curl http://localhost:8080/api/dynamic/{dynamicId}/machine-logs

# 5. 审核员领取任务
curl -X POST 'http://localhost:8080/api/audit/manual/claim?auditorId=1'

# 6. 提交审核结论
curl -X POST http://localhost:8080/api/audit/manual/submit \
  -H 'Content-Type: application/json' \
  -d '{"auditorId":1,"dynamicId":123456789,"pass":true,"reason":""}'

# 7. 积压监控
curl http://localhost:8080/api/audit/manual/stats
```

---

## 审核流转

```
发布动态
  └─→ machine_audit_queue（机审队列）
         │
    机审消费者（每秒轮询，批量 20）
         │
    ┌────┴─────────────────┐
  驳回                  通过 / 疑似
    │                      │
 biz_status=3         manual_audit_queue
 （终结，不占人工）     （疑似 priority=2 插队）
                           │
                      审核员领取（乐观锁防并发）
                           │
                    ┌──────┴──────┐
                  通过          驳回
                    │            │
              biz_status=2   biz_status=3
                    │            │
              ┌─────┴────────────┘
          队列记录删除
     （结论在 result，痕迹在 log）
```

> 人审队列是**临时调度数据**：只有「待领取 / 已领取」两态，审核提交后记录直接删除，
> 不保留完成态——队列只负责调度，结论和痕迹分别由 `manual_audit_result`、`manual_audit_log` 承载。

---

## 目录结构

```
src/main/java/com/example/audit/
├── AuditApplication.java          启动类（@EnableScheduling）
├── common/
│   ├── AuditConst.java            全部状态枚举集中定义
│   └── R.java                     统一返回
├── domain/                        9 张表的实体
├── mapper/                        MyBatis 注解 Mapper
└── service/
    ├── IdGenerator.java           雪花 ID（含时钟回拨处理）
    ├── RuleEngine.java            敏感词 DFA / Trie
    ├── ImageAuditClient.java      图片审核（Mock，生产接第三方）
    ├── FrameExtractor.java        动图/视频抽帧
    ├── AiAuditService.java        Spring AI 兜底层（含降级）
    ├── MachineAuditService.java   多阶段机审 + 聚合
    ├── MachineAuditConsumer.java  机审队列消费者 + 流转
    ├── DynamicService.java        发布
    └── ManualAuditService.java    人审领取/提交/超时回收
```

---

## 核心设计点（面试重点）

### 1. 为什么机审必须先于人审

UGC 平台内容量远大于审核团队人力。机审的作用是**自动拦截明显违规、自动放行明显合规**，只把「机器拿不准的疑似内容」交给人——人工审核量通常能降到总量的 5%–20%。

机审设计为**三态**而非二态：通过 → 进人审复核；驳回 → 直接拒绝；疑似 → 进人审并插队。二态不够用，因为机器经常拿不准，这种情况既不能放行也不能直接拒。

### 2. 机审分层的依据

| 层 | 作用 | 为什么 |
|---|---|---|
| 规则（敏感词） | 拦第一道 | 毫秒级、100% 命中、可解释、几乎零成本 |
| 图片/抽帧审核 | 多模态识别 | 大模型做不了像素级识别，必须用专业服务 |
| AI 语义兜底 | 处理长尾 | 成本高、延迟大，只用于前两层拿不准的内容 |

**为什么不全用 AI**：成本（日增百万级内容，全量调模型账单扛不住）、延迟（毫秒 vs 秒级）、确定性（明确违禁词规则必胜，模型有概率误判）。

### 3. 降级方向是铁律

AI 层超时或不可用时，降级结果**必须是「疑似 → 转人工」，绝不能是「通过」**。宁可多花人工成本，也不能漏放违规内容。

生产上应该用 Resilience4j 的 `@CircuitBreaker` + `@TimeLimiter`，本项目用 `CompletableFuture.orTimeout` 手写，依赖更少、更易跑通。

### 4. 动图与视频怎么审

核心思路是**降维成图片**：动图抽帧、视频抽关键帧，然后复用图片审核能力。抽帧结果作为多条 `dynamic_image` 记录写入（`sort_no` 记帧序），图片与动图共用一套逻辑。

抽帧策略：固定间隔（简单但可能漏中间帧）、按场景切换点（准但成本高）。生产常用「首尾帧 + 固定间隔 + 关键帧」组合，对高风险内容再提高密度做二次审核。ffmpeg 一行命令即可，无需自己解码。

### 5. 并发防重

```sql
UPDATE manual_audit_queue
SET queue_status = 1, assignee_id = ?, lock_expire_time = ?
WHERE id = ? AND queue_status = 0
```

靠 `affected rows` 判断是否抢到。**本质是把「检查状态」和「修改状态」合成一个原子操作**，和防超卖的乐观锁 CAS 是同一个思路。

配套 `lock_expire_time` 做超时回收：审核员领了任务但没提交（关页面/掉线），锁到期后自动放回队列。

### 6. 队列表 vs MQ

| | 队列表 | MQ |
|---|---|---|
| 可靠 | 不丢任务 | 需处理发送确认与持久化 |
| 可查询 | 运营能直接看积压 | 需额外建表 |
| 优先级 | 天然支持 | 需多队列或插件 |
| 人工干预 | 直接改状态 | 麻烦 |
| 吞吐 | 轮询是瓶颈，几千 QPS 吃力 | 高 |

**生产方案：MQ 做流转 + 表做状态**，两者分工而非替代。

### 7. 状态表 vs 事件表

- `machine_audit_result` 加 **唯一键**：一个动态只有一个最终结论，重复机审用 `ON DUPLICATE KEY UPDATE` 覆盖
- `machine_audit_log` **不加唯一键**：一次机审经历多个阶段（规则/图片/抽帧/AI），每阶段留一条用于追溯与复盘
- **队列表是易失的**：只保存「还没干完的活」，干完即删，避免无限膨胀；所以队列状态只需要「待领取 / 已领取」两态，不需要完成态

如果要完整追溯，还应有一张「机审结果历史表」记录每次结论变化——很多团队会漏掉这个。

---

## 已知不足

坦诚列出来，避免被误认为是生产系统：

1. **图片/视频审核为 Mock 实现**，按 URL 哈希给出结论。生产应接腾讯云天御、阿里云绿网等第三方服务
2. **抽帧为模拟**，未真正调用 ffmpeg
3. **未做高并发优化**，MySQL 队列表轮询在数千 QPS 下会成为瓶颈，应改为 MQ 流转
4. **无权限体系**，审核员身份仅靠参数传入，生产需要完整的认证与角色管理
5. **AI 层未做限流与成本统计**，生产需要按调用量计费与预算告警
6. **无申诉与复核机制**，真实审核系统需要申诉通道和二次复核
7. **缺少审核效果指标**（准确率、召回率、误杀率、平均审核时长），这些是内容安全系统的核心运营指标

---

## 后续可加（加分项）

- [ ] 审核结果回流成样本库，用 RAG 提升 AI 层准确率
- [ ] 积压告警与「先发后审 + 事后抽检」降级策略
- [ ] 用户信用分级：低风险用户走快速通道
- [ ] 审核效果统计看板
- [ ] 接入真实第三方审核服务并做结果对比
