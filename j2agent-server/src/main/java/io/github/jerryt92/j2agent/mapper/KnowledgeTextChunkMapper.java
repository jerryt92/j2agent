package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识库逻辑文本块持久化 Mapper。
 */
@Mapper
public interface KnowledgeTextChunkMapper {

    @Insert("""
            INSERT INTO knowledge_text_chunk
            (id, heading_path, text_chunk, source_file, collection_name, file_sha256, created_at, updated_at)
            VALUES
            (#{id}, #{headingPath}, #{textChunk}, #{sourceFile}, #{collectionName}, #{fileSha256}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
              heading_path = VALUES(heading_path),
              text_chunk = VALUES(text_chunk),
              source_file = VALUES(source_file),
              collection_name = VALUES(collection_name),
              file_sha256 = VALUES(file_sha256),
              updated_at = VALUES(updated_at)
            """)
    int upsert(KnowledgeTextChunkPo po);

    @Select("""
            <script>
            SELECT id, heading_path AS headingPath, text_chunk AS textChunk, source_file AS sourceFile,
                   collection_name AS collectionName, file_sha256 AS fileSha256, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_text_chunk
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
            </foreach>
            </script>
            """)
    List<KnowledgeTextChunkPo> selectByIds(@Param("ids") List<String> ids);

    @Select("""
            <script>
            SELECT id, heading_path AS headingPath, text_chunk AS textChunk, source_file AS sourceFile,
                   collection_name AS collectionName, file_sha256 AS fileSha256, created_at AS createdAt, updated_at AS updatedAt
            FROM knowledge_text_chunk
            WHERE collection_name = #{collectionName}
            <if test="search != null and search != ''">
              AND (heading_path LIKE CONCAT('%', #{search}, '%')
                OR text_chunk LIKE CONCAT('%', #{search}, '%')
                OR source_file LIKE CONCAT('%', #{search}, '%'))
            </if>
            ORDER BY updated_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<KnowledgeTextChunkPo> selectByCollection(@Param("collectionName") String collectionName,
                                                  @Param("search") String search,
                                                  @Param("offset") int offset,
                                                  @Param("limit") int limit);

    @Delete("DELETE FROM knowledge_text_chunk WHERE source_file = #{sourceFile}")
    int deleteBySourceFile(@Param("sourceFile") String sourceFile);

    @Delete("DELETE FROM knowledge_text_chunk")
    int deleteAll();
}
