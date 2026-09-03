package com.example.audit.mapper;

import com.example.audit.domain.DynamicBase;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DynamicBaseMapper {

    @Insert("""
            INSERT INTO dynamic_base
              (dynamic_id, user_id, type, title, content, biz_status,
               machine_status, manual_status, risk_score, risk_type)
            VALUES
              (#{dynamicId}, #{userId}, #{type}, #{title}, #{content}, #{bizStatus},
               0, 0, 0, '')
            """)
    int insert(DynamicBase entity);

    @Select("SELECT * FROM dynamic_base WHERE dynamic_id = #{dynamicId}")
    DynamicBase selectByDynamicId(Long dynamicId);

    /** 更新动态业务状态（待审核 / 已通过 / 已驳回 / 已下架） */
    @Update("UPDATE dynamic_base SET biz_status = #{status} WHERE dynamic_id = #{dynamicId}")
    int updateBizStatus(@Param("dynamicId") Long dynamicId, @Param("status") Integer status);

    /** 回写机审结论 */
    @Update("""
            UPDATE dynamic_base
            SET machine_status = #{machineStatus}, risk_score = #{riskScore}, risk_type = #{riskType}
            WHERE dynamic_id = #{dynamicId}
            """)
    int updateMachineResult(@Param("dynamicId") Long dynamicId,
                            @Param("machineStatus") Integer machineStatus,
                            @Param("riskScore") Integer riskScore,
                            @Param("riskType") String riskType);

    /** 回写人审结论 */
    @Update("UPDATE dynamic_base SET manual_status = #{manualStatus} WHERE dynamic_id = #{dynamicId}")
    int updateManualStatus(@Param("dynamicId") Long dynamicId, @Param("manualStatus") Integer manualStatus);

    @Select("""
            SELECT * FROM dynamic_base
            WHERE biz_status = #{status}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<DynamicBase> selectByBizStatus(@Param("status") Integer status, @Param("limit") Integer limit);
}
