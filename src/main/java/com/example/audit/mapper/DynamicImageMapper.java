package com.example.audit.mapper;

import com.example.audit.domain.DynamicImage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DynamicImageMapper {

    /**
     * 批量写入图片 / 动图抽帧结果。
     * 动图（GIF）抽帧后每帧一条记录，用 sort_no 记帧序，与多图共用一套逻辑。
     */
    @Insert("""
            <script>
            INSERT INTO dynamic_image (dynamic_id, image_url, sort_no, image_status)
            VALUES
            <foreach collection='list' item='i' separator=','>
              (#{i.dynamicId}, #{i.imageUrl}, #{i.sortNo}, 0)
            </foreach>
            </script>
            """)
    int batchInsert(@Param("list") List<DynamicImage> list);

    @Update("""
            UPDATE dynamic_image
            SET image_status = #{imageStatus}
            WHERE dynamic_id = #{dynamicId}
            """)
    int updateStatusByDynamic(@Param("dynamicId") Long dynamicId, @Param("imageStatus") Integer imageStatus);

    @Select("SELECT * FROM dynamic_image WHERE dynamic_id = #{dynamicId} ORDER BY sort_no")
    List<DynamicImage> selectByDynamicId(Long dynamicId);
}
