package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectFileReferencePo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ObjectFileReferenceMapper {
    @Insert("""
            INSERT INTO object_file_reference
            (id, file_id, business_type, business_id, owner_id, created_at)
            VALUES
            (#{id}, #{fileId}, #{businessType}, #{businessId}, #{ownerId}, #{createdAt})
            ON CONFLICT (file_id, business_type, business_id) DO NOTHING
            """)
    int insertIgnore(ObjectFileReferencePo po);

    @Select("SELECT COUNT(1) FROM object_file_reference WHERE file_id = #{fileId}")
    int countByFileId(String fileId);

    @Select("""
            SELECT DISTINCT file_id
            FROM object_file_reference
            WHERE business_type = #{businessType}
              AND business_id LIKE CONCAT(#{businessIdPrefix}, '%')
            """)
    List<String> selectFileIdsByBusinessPrefix(@Param("businessType") String businessType,
                                               @Param("businessIdPrefix") String businessIdPrefix);

    @Delete("""
            DELETE FROM object_file_reference
            WHERE business_type = #{businessType}
              AND business_id LIKE CONCAT(#{businessIdPrefix}, '%')
            """)
    int deleteByBusinessPrefix(@Param("businessType") String businessType,
                               @Param("businessIdPrefix") String businessIdPrefix);

    @Delete("DELETE FROM object_file_reference WHERE file_id = #{fileId}")
    int deleteByFileId(@Param("fileId") String fileId);
}
