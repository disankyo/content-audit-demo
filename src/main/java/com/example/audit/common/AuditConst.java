package com.example.audit.common;

import java.util.Arrays;

/**
 * 审核域常量与枚举。
 * 集中放置，避免散落各处导致状态值对不上。
 */
public final class AuditConst {

    private AuditConst() {}

    /** 动态类型 */
    public enum DynamicType {
        TEXT(1, "文字"),
        IMAGE(2, "图片"),
        GIF(3, "动图"),
        VIDEO(4, "视频");

        private final int code;
        private final String desc;

        DynamicType(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() { return code; }
        public String getDesc() { return desc; }

        public static DynamicType of(Integer code) {
            return Arrays.stream(values())
                    .filter(t -> t.code == (code == null ? -1 : code))
                    .findFirst()
                    .orElse(TEXT);
        }
    }

    /** 动态业务状态 */
    public enum BizStatus {
        DRAFT(0, "草稿"),
        AUDITING(1, "待审核"),
        PASSED(2, "已通过"),
        REJECTED(3, "已驳回"),
        OFFLINE(4, "已下架");

        private final int code;
        private final String desc;

        BizStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }

    /** 机审结论（三态：通过 / 驳回 / 疑似） */
    public enum MachineResult {
        PASS(1, "通过"),
        REJECT(2, "驳回"),
        SUSPECT(3, "疑似");

        private final int code;
        private final String desc;

        MachineResult(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }

    /** 人审结论 */
    public enum ManualResult {
        PASS(1, "通过"),
        REJECT(2, "驳回");

        private final int code;
        private final String desc;

        ManualResult(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }

    /** 机审队列表状态 */
    public enum QueueStatus {
        PENDING(0, "待处理"),
        PROCESSING(1, "处理中"),
        DONE(2, "已完成"),
        FAILED(3, "失败");

        private final int code;
        private final String desc;

        QueueStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }

    /**
     * 人审队列表状态：只有两态。
     *
     * <p>人审队列是<b>临时数据</b>——审核提交后记录直接删除，不保留「已完成」状态。
     * 原因：队列只承担「调度」职责（谁待领、谁被谁领了），
     * 结论已经落在 manual_audit_result，流转痕迹落在 manual_audit_log，
     * 队列里再存一份完成态既冗余又会无限膨胀。
     */
    public enum ManualQueueStatus {
        PENDING(0, "待领取"),
        CLAIMED(1, "已领取");

        private final int code;
        private final String desc;

        ManualQueueStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public int getCode() { return code; }
        public String getDesc() { return desc; }
    }

    /** 机审阶段（写入日志表，用于追溯与复盘） */
    public enum Stage {
        RULE,   // 敏感词规则
        IMAGE,  // 图片审核
        FRAME,  // 动图/视频抽帧审核
        VIDEO,  // 视频审核
        AI      // 大模型语义兜底
    }

    /** 人审动作 */
    public enum ManualAction {
        CLAIM, PASS, REJECT, RETURN, TIMEOUT
    }

    /** 人审队列优先级：疑似内容插队 */
    public static final int PRIORITY_SUSPECT = 2;
    public static final int PRIORITY_NORMAL = 5;
}
