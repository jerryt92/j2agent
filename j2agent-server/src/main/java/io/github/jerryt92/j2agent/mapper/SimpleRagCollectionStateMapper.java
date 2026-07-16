package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.SimpleRagCollectionStatePo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * SimpleRag collection 同步状态 Mapper。
 */
@Mapper
public interface SimpleRagCollectionStateMapper {
    /**
     * 按 collection 名称查询同步状态。
     */
    @Select("""
            SELECT id, collection_name AS collectionName, owner_agent_id AS ownerAgentId,
                   sync_status AS syncStatus, document_count AS documentCount,
                   error_message AS errorMessage, created_at AS createdAt, updated_at AS updatedAt
            FROM simple_rag_collection_state
            WHERE collection_name = #{collectionName}
            """)
    SimpleRagCollectionStatePo selectByCollectionName(@Param("collectionName") String collectionName);

    /**
     * 查询全部已记录的 collection 名称。
     */
    @Select("SELECT collection_name FROM simple_rag_collection_state")
    List<String> selectAllCollectionNames();

    /**
     * 按 collection_name 冲突时更新状态（upsert）。
     */
    @Insert("""
            INSERT INTO simple_rag_collection_state
            (id, collection_name, owner_agent_id, sync_status, document_count, error_message, created_at, updated_at)
            VALUES
            (#{id}, #{collectionName}, #{ownerAgentId}, #{syncStatus}, #{documentCount}, #{errorMessage}, #{createdAt}, #{updatedAt})
            ON CONFLICT (collection_name) DO UPDATE SET
              owner_agent_id = EXCLUDED.owner_agent_id,
              sync_status = EXCLUDED.sync_status,
              document_count = EXCLUDED.document_count,
              error_message = EXCLUDED.error_message,
              updated_at = EXCLUDED.updated_at
            """)
    int upsert(SimpleRagCollectionStatePo po);

    /**
     * 按 collection 名称删除状态记录。
     */
    @Delete("DELETE FROM simple_rag_collection_state WHERE collection_name = #{collectionName}")
    int deleteByCollectionName(@Param("collectionName") String collectionName);
}
