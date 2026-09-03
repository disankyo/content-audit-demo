-- ============================================================
--  H2 (MySQL 兼容模式) 建表脚本 —— 仅供集成测试使用
--  相比 schema.sql 去掉了 CHARSET / ENGINE / ON UPDATE CURRENT_TIMESTAMP 等 MySQL 专有语法
-- ============================================================

CREATE TABLE IF NOT EXISTS dynamic_base (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id     BIGINT NOT NULL,
  user_id        BIGINT NOT NULL,
  type           TINYINT NOT NULL,
  title          VARCHAR(200) NOT NULL DEFAULT '',
  content        TEXT,
  biz_status     TINYINT NOT NULL DEFAULT 1,
  machine_status TINYINT NOT NULL DEFAULT 0,
  manual_status  TINYINT NOT NULL DEFAULT 0,
  risk_score     INT NOT NULL DEFAULT 0,
  risk_type      VARCHAR(64) NOT NULL DEFAULT '',
  create_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_dynamic_id ON dynamic_base(dynamic_id);
CREATE INDEX idx_user ON dynamic_base(user_id);
CREATE INDEX idx_biz_status_create ON dynamic_base(biz_status, create_time);

CREATE TABLE IF NOT EXISTS dynamic_image (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id   BIGINT NOT NULL,
  image_url    VARCHAR(512) NOT NULL,
  width        INT NOT NULL DEFAULT 0,
  height       INT NOT NULL DEFAULT 0,
  file_size    INT NOT NULL DEFAULT 0,
  sort_no      INT NOT NULL DEFAULT 0,
  image_status TINYINT NOT NULL DEFAULT 0,
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_dynamic ON dynamic_image(dynamic_id);

CREATE TABLE IF NOT EXISTS dynamic_video (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id   BIGINT NOT NULL,
  video_url    VARCHAR(512) NOT NULL,
  cover_url    VARCHAR(512) NOT NULL DEFAULT '',
  duration     INT NOT NULL DEFAULT 0,
  file_size    BIGINT NOT NULL DEFAULT 0,
  video_status TINYINT NOT NULL DEFAULT 0,
  frame_status TINYINT NOT NULL DEFAULT 0,
  create_time  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_dynamic_video ON dynamic_video(dynamic_id);

CREATE TABLE IF NOT EXISTS machine_audit_queue (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id      BIGINT NOT NULL,
  priority        TINYINT NOT NULL DEFAULT 5,
  queue_status    TINYINT NOT NULL DEFAULT 0,
  retry_count     INT NOT NULL DEFAULT 0,
  next_retry_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_maq_dynamic ON machine_audit_queue(dynamic_id);
CREATE INDEX idx_maq_pick ON machine_audit_queue(queue_status, priority, next_retry_time, id);

CREATE TABLE IF NOT EXISTS machine_audit_result (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id    BIGINT NOT NULL,
  audit_result  TINYINT NOT NULL,
  risk_score    INT NOT NULL DEFAULT 0,
  risk_type     VARCHAR(64) NOT NULL DEFAULT '',
  hit_rule      VARCHAR(255) NOT NULL DEFAULT '',
  model_version VARCHAR(32) NOT NULL DEFAULT '',
  cost_ms       INT NOT NULL DEFAULT 0,
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_mar_dynamic ON machine_audit_result(dynamic_id);

CREATE TABLE IF NOT EXISTS machine_audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id    BIGINT NOT NULL,
  stage         VARCHAR(32) NOT NULL,
  request_body  TEXT,
  response_body TEXT,
  cost_ms       INT NOT NULL DEFAULT 0,
  success       TINYINT NOT NULL DEFAULT 1,
  error_msg     VARCHAR(512) NOT NULL DEFAULT '',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_mal_dynamic ON machine_audit_log(dynamic_id);

CREATE TABLE IF NOT EXISTS manual_audit_queue (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id       BIGINT NOT NULL,
  priority         TINYINT NOT NULL DEFAULT 5,
  queue_status     TINYINT NOT NULL DEFAULT 0,
  machine_result   TINYINT DEFAULT NULL,
  assignee_id      BIGINT DEFAULT NULL,
  lock_expire_time DATETIME DEFAULT NULL,
  create_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_manq_dynamic ON manual_audit_queue(dynamic_id);
CREATE INDEX idx_manq_pick ON manual_audit_queue(queue_status, priority, id);

CREATE TABLE IF NOT EXISTS manual_audit_result (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id    BIGINT NOT NULL,
  auditor_id    BIGINT NOT NULL,
  audit_result  TINYINT NOT NULL,
  reject_reason VARCHAR(255) NOT NULL DEFAULT '',
  risk_type     VARCHAR(64) NOT NULL DEFAULT '',
  cost_ms       INT NOT NULL DEFAULT 0,
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX uk_manr_dynamic ON manual_audit_result(dynamic_id);

CREATE TABLE IF NOT EXISTS manual_audit_log (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  dynamic_id    BIGINT NOT NULL,
  auditor_id    BIGINT NOT NULL,
  action        VARCHAR(32) NOT NULL,
  before_status TINYINT DEFAULT NULL,
  after_status  TINYINT DEFAULT NULL,
  remark        VARCHAR(512) NOT NULL DEFAULT '',
  create_time   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_manl_dynamic ON manual_audit_log(dynamic_id);
CREATE INDEX idx_manl_auditor ON manual_audit_log(auditor_id, create_time);
