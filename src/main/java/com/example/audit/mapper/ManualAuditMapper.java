package com.example.audit.mapper;

import com.example.audit.domain.ManualAuditLog;
import com.example.audit.domain.ManualAuditQueue;
import com.example.audit.domain.ManualAuditResult;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ManualAuditMapper {

    // ==================== 队列表 ====================

    /** 幂等入队：疑似内容用更低数值的 priority 插队 */
    @Insert("""
            INSERT IGNORE INTO manual_audit_queue
              (dynamic_id, priority, queue_status, machine_result)
            VALUES
              (#{dynamicId}, #{priority}, 0, #{machineResult})
            """)
    int insertIgnore(@Param("dynamicId") Long dynamicId,
                     @Param("priority") Integer priority,
                     @Param("machineResult") Integer machineResult);

    /** 捞一条待领取任务：优先级升序、先进先出 */
    @Select("""
            SELECT * FROM manual_audit_queue
            WHERE queue_status = 0
            ORDER BY priority ASC, id ASC
            LIMIT 1
            """)
    ManualAuditQueue pickOne();

    /**
     * 领取任务（乐观锁）。
     * 并发下多个审核员可能 SELECT 到同一条，靠 WHERE queue_status = 0 + affected rows
     * 保证只有一个人能领走。
     */
    @Update("""
            UPDATE manual_audit_queue
            SET queue_status = 1,
                assignee_id = #{auditorId},
                lock_expire_time = #{expireTime}
            WHERE id = #{id} AND queue_status = 0
            """)
    int claim(@Param("id") Long id,
              @Param("auditorId") Long auditorId,
              @Param("expireTime") LocalDateTime expireTime);

    /**
     * 超时回收：审核员领了任务但没提交（关页面/掉线），锁到期后自动放回队列。
     * 由定时任务调用。
     */
    @Update("""
            UPDATE manual_audit_queue
            SET queue_status = 0, assignee_id = NULL, lock_expire_time = NULL
            WHERE queue_status = 1 AND lock_expire_time < NOW()
            """)
    int releaseExpired();

    /**
     * 审核结束（提交/驳回）：直接从队列删除。
     * 队列是临时调度数据，结论已落 manual_audit_result、痕迹已落 manual_audit_log，
     * 不需要在队列里保留「已完成」态——留着只会让表无限膨胀。
     */
    @Delete("DELETE FROM manual_audit_queue WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    /** 主动放弃 / 超时回收：状态退回「待领取」，清空持有人与锁，等待被重新领取 */
    @Update("""
            UPDATE manual_audit_queue
            SET queue_status = 0, assignee_id = NULL, lock_expire_time = NULL
            WHERE id = #{id} AND queue_status = 1
            """)
    int release(@Param("id") Long id);

    @Select("SELECT * FROM manual_audit_queue WHERE dynamic_id = #{dynamicId}")
    ManualAuditQueue selectByDynamicId(@Param("dynamicId") Long dynamicId);

    /** 积压监控：按状态统计队列量（队列只有待领取 / 已领取两态） */
    @Select("SELECT queue_status, COUNT(*) AS cnt FROM manual_audit_queue GROUP BY queue_status")
    List<java.util.Map<String, Object>> countByStatus();

    // ==================== 结果表 ====================

    @Insert("""
            INSERT INTO manual_audit_result
              (dynamic_id, auditor_id, audit_result, reject_reason, risk_type, cost_ms)
            VALUES
              (#{dynamicId}, #{auditorId}, #{auditResult}, #{rejectReason}, #{riskType}, #{costMs})
            ON DUPLICATE KEY UPDATE
              auditor_id    = VALUES(auditor_id),
              audit_result  = VALUES(audit_result),
              reject_reason = VALUES(reject_reason),
              risk_type     = VALUES(risk_type),
              cost_ms       = VALUES(cost_ms)
            """)
    int upsertResult(ManualAuditResult result);

    @Select("SELECT * FROM manual_audit_result WHERE dynamic_id = #{dynamicId}")
    ManualAuditResult selectResult(@Param("dynamicId") Long dynamicId);

    // ==================== 日志表 ====================

    @Insert("""
            INSERT INTO manual_audit_log
              (dynamic_id, auditor_id, action, before_status, after_status, remark)
            VALUES
              (#{dynamicId}, #{auditorId}, #{action}, #{beforeStatus}, #{afterStatus}, #{remark})
            """)
    int insertLog(ManualAuditLog log);

    @Select("SELECT * FROM manual_audit_log WHERE dynamic_id = #{dynamicId} ORDER BY id")
    List<ManualAuditLog> selectLogs(@Param("dynamicId") Long dynamicId);
}
