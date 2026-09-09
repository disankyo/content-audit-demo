package com.example.audit.mapper;

import com.example.audit.domain.MachineAuditLog;
import com.example.audit.domain.MachineAuditQueue;
import com.example.audit.domain.MachineAuditResult;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MachineAuditMapper {

    // ==================== 队列表 ====================

    /**
     * 幂等入队：uk_dynamic 保证一个动态只有一条队列记录。
     * 用 INSERT IGNORE，重复发布不会产生第二条，也不会报错。
     */
    @Insert("""
            INSERT IGNORE INTO machine_audit_queue
              (dynamic_id, priority, queue_status, retry_count, next_retry_time)
            VALUES
              (#{dynamicId}, #{priority}, 0, 0, NOW())
            """)
    int insertIgnore(@Param("dynamicId") Long dynamicId, @Param("priority") Integer priority);

    /** 捞取待处理任务：优先级升序、先进先出，走 idx_pick 索引 */
    @Select("""
            SELECT * FROM machine_audit_queue
            WHERE queue_status = 0 AND next_retry_time <= NOW()
            ORDER BY priority ASC, id ASC
            LIMIT #{limit}
            """)
    List<MachineAuditQueue> pickBatch(@Param("limit") Integer limit);

    /**
     * 抢占任务（乐观锁）。
     * 关键：把「检查状态」和「修改状态」合成一个原子操作，靠 affected rows 判断是否抢到。
     * 和防超卖的 CAS 是同一个思路。
     */
    @Update("UPDATE machine_audit_queue SET queue_status = 1 WHERE id = #{id} AND queue_status = 0")
    int claim(@Param("id") Long id);

    /**
     * 机审成功出结论后删除队列记录。
     * 队列是临时调度数据，结论已落 machine_audit_result、过程已落 machine_audit_log，
     * 成功的任务没必要在队列里留「已完成」态——留着只会让表无限膨胀。
     * 只有失败的任务会留在队列里重试 / 等人工介入。
     */
    @Delete("DELETE FROM machine_audit_queue WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Select("SELECT * FROM machine_audit_queue WHERE dynamic_id = #{dynamicId}")
    MachineAuditQueue selectByDynamicId(@Param("dynamicId") Long dynamicId);

    /** 失败重试：次数 +1，退避时间按次数递增（MySQL 中引用到的是更新前的旧值） */
    @Update("""
            UPDATE machine_audit_queue
            SET queue_status = 0,
                retry_count = retry_count + 1,
                next_retry_time = DATE_ADD(NOW(), INTERVAL (retry_count * 30) SECOND)
            WHERE id = #{id}
            """)
    int retryLater(@Param("id") Long id);

    /** 超过最大重试次数：标记失败并留在队列里，等人工介入（失败任务没有结论，不能删） */
    @Update("UPDATE machine_audit_queue SET queue_status = 3 WHERE id = #{id}")
    int markFailed(@Param("id") Long id);

    // ==================== 结果表（状态表） ====================

    /** 结论覆盖写：重复机审时以最后一次为准 */
    @Insert("""
            INSERT INTO machine_audit_result
              (dynamic_id, audit_result, risk_score, risk_type, hit_rule, model_version, cost_ms)
            VALUES
              (#{dynamicId}, #{auditResult}, #{riskScore}, #{riskType}, #{hitRule}, #{modelVersion}, #{costMs})
            ON DUPLICATE KEY UPDATE
              audit_result  = VALUES(audit_result),
              risk_score    = VALUES(risk_score),
              risk_type     = VALUES(risk_type),
              hit_rule      = VALUES(hit_rule),
              model_version = VALUES(model_version),
              cost_ms       = VALUES(cost_ms)
            """)
    int upsertResult(MachineAuditResult result);

    @Select("SELECT * FROM machine_audit_result WHERE dynamic_id = #{dynamicId}")
    MachineAuditResult selectResult(@Param("dynamicId") Long dynamicId);

    // ==================== 日志表（事件表） ====================

    @Insert("""
            <script>
            INSERT INTO machine_audit_log
              (dynamic_id, stage, request_body, response_body, cost_ms, success, error_msg)
            VALUES
            <foreach collection='list' item='l' separator=','>
              (#{l.dynamicId}, #{l.stage}, #{l.requestBody}, #{l.responseBody},
               #{l.costMs}, #{l.success}, #{l.errorMsg})
            </foreach>
            </script>
            """)
    int batchInsertLog(@Param("list") List<MachineAuditLog> logs);

    @Select("SELECT * FROM machine_audit_log WHERE dynamic_id = #{dynamicId} ORDER BY id")
    List<MachineAuditLog> selectLogs(@Param("dynamicId") Long dynamicId);
}
