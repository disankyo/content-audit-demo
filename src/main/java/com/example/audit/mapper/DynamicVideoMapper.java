package com.example.audit.mapper;

import com.example.audit.domain.DynamicVideo;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DynamicVideoMapper {

    @Insert("""
            INSERT INTO dynamic_video
              (dynamic_id, video_url, cover_url, duration, file_size, video_status, frame_status)
            VALUES
              (#{dynamicId}, #{videoUrl}, #{coverUrl}, #{duration}, #{fileSize}, 0, 0)
            """)
    int insert(DynamicVideo entity);

    @Select("SELECT * FROM dynamic_video WHERE dynamic_id = #{dynamicId}")
    DynamicVideo selectByDynamicId(Long dynamicId);

    @Update("UPDATE dynamic_video SET frame_status = 1 WHERE dynamic_id = #{dynamicId}")
    int markFramed(Long dynamicId);

    @Update("UPDATE dynamic_video SET video_status = #{status} WHERE dynamic_id = #{dynamicId}")
    int updateStatus(@Param("dynamicId") Long dynamicId, @Param("status") Integer status);
}
