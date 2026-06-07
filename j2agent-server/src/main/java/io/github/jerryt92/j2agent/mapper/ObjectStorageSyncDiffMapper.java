package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncDiffPo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ObjectStorageSyncDiffMapper {
    @Insert("""
            INSERT INTO object_storage_sync_diff
            (id, task_id, bucket_name, object_key, object_key_hash, diff_type, resolution_status,
             oss_etag, oss_size_bytes, oss_modified_at, db_etag, db_size_bytes, db_modified_at,
             resolution_action, resolution_error, created_at, updated_at)
            VALUES
            (#{id}, #{taskId}, #{bucketName}, #{objectKey}, #{objectKeyHash}, #{diffType},
             #{resolutionStatus}, #{ossEtag}, #{ossSizeBytes}, #{ossModifiedAt}, #{dbEtag},
             #{dbSizeBytes}, #{dbModifiedAt}, #{resolutionAction}, #{resolutionError},
             #{createdAt}, #{updatedAt})
            """)
    int insert(ObjectStorageSyncDiffPo po);

    @Select("""
            SELECT id, task_id, bucket_name, object_key, object_key_hash, diff_type, resolution_status,
                   oss_etag, oss_size_bytes, oss_modified_at, db_etag, db_size_bytes, db_modified_at,
                   resolution_action, resolution_error, created_at, updated_at
            FROM object_storage_sync_diff
            WHERE task_id = #{taskId}
            ORDER BY object_key
            """)
    List<ObjectStorageSyncDiffPo> selectByTask(String taskId);

    @Select("""
            SELECT id, task_id, bucket_name, object_key, object_key_hash, diff_type, resolution_status,
                   oss_etag, oss_size_bytes, oss_modified_at, db_etag, db_size_bytes, db_modified_at,
                   resolution_action, resolution_error, created_at, updated_at
            FROM object_storage_sync_diff
            WHERE id = #{id}
            """)
    ObjectStorageSyncDiffPo selectById(String id);

    @Update("""
            UPDATE object_storage_sync_diff
            SET resolution_status = #{status}, resolution_action = #{action},
                resolution_error = #{error}, updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateResolution(
            @Param("id") String id,
            @Param("status") String status,
            @Param("action") String action,
            @Param("error") String error,
            @Param("updatedAt") long updatedAt
    );

    @Delete("DELETE FROM object_storage_sync_diff WHERE task_id = #{taskId}")
    int deleteByTask(String taskId);

    @Delete("""
            DELETE FROM object_storage_sync_diff
            WHERE bucket_name = #{bucket} AND task_id <> #{taskId}
            """)
    int deleteByBucketExceptTask(@Param("bucket") String bucket, @Param("taskId") String taskId);

    @Delete("""
            DELETE d
            FROM object_storage_sync_diff d
            INNER JOIN object_storage_sync_task t ON t.id = d.task_id
            WHERE t.task_status IN ('FAILED', 'CANCELLED')
              AND t.completed_at IS NOT NULL
              AND t.completed_at < #{cutoff}
            """)
    int deleteByCompletedTaskBefore(@Param("cutoff") long cutoff);
}
