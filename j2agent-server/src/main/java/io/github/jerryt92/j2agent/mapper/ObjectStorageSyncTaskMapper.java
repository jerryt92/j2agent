package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ObjectStorageSyncTaskMapper {
    @Insert("""
            INSERT INTO object_storage_sync_task
            (id, bucket_name, provider, task_status, scanned_count, in_sync_count, oss_only_count,
             db_only_count, mismatch_count, in_progress_count, error_message, created_at, started_at,
             completed_at)
            VALUES
            (#{id}, #{bucketName}, #{provider}, #{taskStatus}, #{scannedCount}, #{inSyncCount},
             #{ossOnlyCount}, #{dbOnlyCount}, #{mismatchCount}, #{inProgressCount}, #{errorMessage},
             #{createdAt}, #{startedAt}, #{completedAt})
            """)
    int insert(ObjectStorageSyncTaskPo po);

    @Select("""
            SELECT id, bucket_name, provider, task_status, scanned_count, in_sync_count,
                   oss_only_count, db_only_count, mismatch_count, in_progress_count, error_message,
                   created_at, started_at, completed_at
            FROM object_storage_sync_task
            WHERE id = #{id}
            """)
    ObjectStorageSyncTaskPo selectById(String id);

    @Select("""
            SELECT COUNT(1)
            FROM object_storage_sync_task
            WHERE bucket_name = #{bucket}
              AND task_status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
            """)
    int countActive(String bucket);

    @Update("""
            UPDATE object_storage_sync_task
            SET task_status = 'RUNNING', started_at = #{startedAt}
            WHERE id = #{id} AND task_status = 'PENDING'
            """)
    int markRunning(@Param("id") String id, @Param("startedAt") long startedAt);

    @Update("""
            UPDATE object_storage_sync_task
            SET scanned_count = #{scannedCount}, in_sync_count = #{inSyncCount},
                oss_only_count = #{ossOnlyCount}, db_only_count = #{dbOnlyCount},
                mismatch_count = #{mismatchCount}, in_progress_count = #{inProgressCount}
            WHERE id = #{id}
            """)
    int updateProgress(ObjectStorageSyncTaskPo po);

    @Update("""
            UPDATE object_storage_sync_task
            SET task_status = 'SUCCESS', scanned_count = #{scannedCount}, in_sync_count = #{inSyncCount},
                oss_only_count = #{ossOnlyCount}, db_only_count = #{dbOnlyCount},
                mismatch_count = #{mismatchCount}, in_progress_count = #{inProgressCount},
                completed_at = #{completedAt}
            WHERE id = #{id} AND task_status = 'RUNNING'
            """)
    int markSuccess(ObjectStorageSyncTaskPo po);

    @Update("""
            UPDATE object_storage_sync_task
            SET task_status = 'FAILED', error_message = #{errorMessage}, completed_at = #{completedAt}
            WHERE id = #{id}
            """)
    int markFailed(
            @Param("id") String id,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") long completedAt
    );

    @Update("""
            UPDATE object_storage_sync_task
            SET task_status = 'CANCEL_REQUESTED'
            WHERE id = #{id} AND task_status IN ('PENDING', 'RUNNING')
            """)
    int requestCancellation(@Param("id") String id);

    @Update("""
            UPDATE object_storage_sync_task
            SET task_status = 'CANCELLED', completed_at = #{completedAt}
            WHERE id = #{id}
              AND task_status IN ('PENDING', 'RUNNING', 'CANCEL_REQUESTED')
            """)
    int markCancelled(@Param("id") String id, @Param("completedAt") long completedAt);

    @Select("""
            SELECT id, bucket_name, provider, task_status, scanned_count, in_sync_count,
                   oss_only_count, db_only_count, mismatch_count, in_progress_count, error_message,
                   created_at, started_at, completed_at
            FROM object_storage_sync_task
            WHERE bucket_name = #{bucket} AND task_status = 'SUCCESS'
            ORDER BY completed_at DESC, created_at DESC
            LIMIT 1
            """)
    ObjectStorageSyncTaskPo selectLatestSuccessful(@Param("bucket") String bucket);

    @Delete("""
            DELETE t
            FROM object_storage_sync_task t
            LEFT JOIN object_storage_sync_diff d ON d.task_id = t.id
            LEFT JOIN object_storage_sync_task newer
              ON newer.bucket_name = t.bucket_name
             AND newer.task_status = 'SUCCESS'
             AND newer.completed_at > t.completed_at
            WHERE t.task_status IN ('SUCCESS', 'FAILED', 'CANCELLED')
              AND t.completed_at IS NOT NULL
              AND t.completed_at < #{cutoff}
              AND d.id IS NULL
              AND (t.task_status <> 'SUCCESS' OR newer.id IS NOT NULL)
            """)
    int deleteCompletedBefore(@Param("cutoff") long cutoff);
}
