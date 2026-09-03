-- ============================================================
--  UGC 动态审核系统 · 建表脚本
--  MySQL 8.0 / utf8mb4
-- ============================================================

-- ---------- 1. 动态基础表 ----------
CREATE TABLE IF NOT EXISTS dynamic_base (
  id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id     BIGINT UNSIGNED NOT NULL                COMMENT '动态业务ID（雪花）',
  user_id        BIGINT UNSIGNED NOT NULL,
  type           TINYINT      NOT NULL                   COMMENT '1文字 2图片 3动图 4视频',
  title          VARCHAR(200) NOT NULL DEFAULT '',
  content        TEXT                                    COMMENT '文字正文',
  biz_status     TINYINT      NOT NULL DEFAULT 1         COMMENT '0草稿 1待审核 2已通过 3已驳回 4已下架',
  machine_status TINYINT      NOT NULL DEFAULT 0         COMMENT '机审 0未审 1通过 2驳回 3疑似',
  manual_status  TINYINT      NOT NULL DEFAULT 0         COMMENT '人审 0未审 1通过 2驳回',
  risk_score     INT          NOT NULL DEFAULT 0         COMMENT '风险分 0-100',
  risk_type      VARCHAR(64)  NOT NULL DEFAULT ''        COMMENT '色情/暴恐/广告/违禁/政治',
  create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dynamic_id (dynamic_id),
  KEY idx_user (user_id),
  KEY idx_biz_status_create (biz_status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态基础表';

-- ---------- 2. 动态图片表（含动图抽帧结果） ----------
CREATE TABLE IF NOT EXISTS dynamic_image (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id   BIGINT UNSIGNED NOT NULL,
  image_url    VARCHAR(512) NOT NULL,
  width        INT NOT NULL DEFAULT 0,
  height       INT NOT NULL DEFAULT 0,
  file_size    INT NOT NULL DEFAULT 0,
  sort_no      INT NOT NULL DEFAULT 0                 COMMENT '多图排序 / 动图帧序',
  image_status TINYINT NOT NULL DEFAULT 0             COMMENT '0未审 1通过 2驳回',
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dynamic (dynamic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态图片表（含动图抽帧结果）';

-- ---------- 3. 动态视频表 ----------
CREATE TABLE IF NOT EXISTS dynamic_video (
  id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id   BIGINT UNSIGNED NOT NULL,
  video_url    VARCHAR(512) NOT NULL,
  cover_url    VARCHAR(512) NOT NULL DEFAULT '',
  duration     INT NOT NULL DEFAULT 0                 COMMENT '时长（秒）',
  file_size    BIGINT NOT NULL DEFAULT 0,
  video_status TINYINT NOT NULL DEFAULT 0             COMMENT '0未审 1通过 2驳回',
  frame_status TINYINT NOT NULL DEFAULT 0             COMMENT '抽帧状态 0未抽 1已抽',
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dynamic (dynamic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='动态视频表';

-- ---------- 4. 机审审核队列表 ----------
CREATE TABLE IF NOT EXISTS machine_audit_queue (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id      BIGINT UNSIGNED NOT NULL,
  priority        TINYINT NOT NULL DEFAULT 5            COMMENT '1-9，越小越优先',
  queue_status    TINYINT NOT NULL DEFAULT 0            COMMENT '0待处理 1处理中 2已完成 3失败',
  retry_count     INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dynamic (dynamic_id),                          -- 幂等：一个动态只有一条机审队列
  KEY idx_pick (queue_status, priority, next_retry_time, id)   -- 捞取走这个索引
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机审审核队列表';

-- ---------- 5. 机审审核结果表（状态表：一个动态一条结论） ----------
CREATE TABLE IF NOT EXISTS machine_audit_result (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id    BIGINT UNSIGNED NOT NULL,
  audit_result  TINYINT NOT NULL                        COMMENT '1通过 2驳回 3疑似',
  risk_score    INT NOT NULL DEFAULT 0,
  risk_type     VARCHAR(64) NOT NULL DEFAULT '',
  hit_rule      VARCHAR(255) NOT NULL DEFAULT ''        COMMENT '命中的规则/关键词',
  model_version VARCHAR(32) NOT NULL DEFAULT ''         COMMENT '模型版本，便于回溯',
  cost_ms       INT NOT NULL DEFAULT 0,
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dynamic (dynamic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机审审核结果表';

-- ---------- 6. 机审审核日志表（事件表：一次机审多阶段留痕） ----------
CREATE TABLE IF NOT EXISTS machine_audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id    BIGINT UNSIGNED NOT NULL,
  stage         VARCHAR(32) NOT NULL                    COMMENT 'rule/ai/image/frame/video',
  request_body  TEXT,
  response_body TEXT,
  cost_ms       INT NOT NULL DEFAULT 0,
  success       TINYINT NOT NULL DEFAULT 1,
  error_msg     VARCHAR(512) NOT NULL DEFAULT '',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dynamic (dynamic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='机审审核日志表';

-- ---------- 7. 人审审核队列表 ----------
CREATE TABLE IF NOT EXISTS manual_audit_queue (
  id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id       BIGINT UNSIGNED NOT NULL,
  priority         TINYINT NOT NULL DEFAULT 5,
  queue_status     TINYINT NOT NULL DEFAULT 0           COMMENT '0待处理 1审核中 2已完成',
  machine_result   TINYINT DEFAULT NULL                 COMMENT '机审结论 1通过 3疑似',
  assignee_id      BIGINT UNSIGNED DEFAULT NULL         COMMENT '审核员ID',
  lock_expire_time DATETIME DEFAULT NULL                COMMENT '锁过期时间，防审核员挂起',
  create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dynamic (dynamic_id),
  KEY idx_pick (queue_status, priority, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人审审核队列表';

-- ---------- 8. 人审审核结果表 ----------
CREATE TABLE IF NOT EXISTS manual_audit_result (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id    BIGINT UNSIGNED NOT NULL,
  auditor_id    BIGINT UNSIGNED NOT NULL,
  audit_result  TINYINT NOT NULL                        COMMENT '1通过 2驳回',
  reject_reason VARCHAR(255) NOT NULL DEFAULT '',
  risk_type     VARCHAR(64) NOT NULL DEFAULT '',
  cost_ms       INT NOT NULL DEFAULT 0                 COMMENT '从领取到提交，用于核算效率',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dynamic (dynamic_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人审审核结果表';

-- ---------- 9. 人审审核日志表 ----------
CREATE TABLE IF NOT EXISTS manual_audit_log (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  dynamic_id    BIGINT UNSIGNED NOT NULL,
  auditor_id    BIGINT UNSIGNED NOT NULL,
  action        VARCHAR(32) NOT NULL                    COMMENT 'claim/pass/reject/return/timeout',
  before_status TINYINT DEFAULT NULL,
  after_status  TINYINT DEFAULT NULL,
  remark        VARCHAR(512) NOT NULL DEFAULT '',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_dynamic (dynamic_id),
  KEY idx_auditor (auditor_id, create_time)             -- 支持审核员绩效统计
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='人审审核日志表';
