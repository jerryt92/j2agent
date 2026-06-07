package io.github.jerryt92.j2agent.mapper;

import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ObjectFileMapper {
    @Select("""
            SELECT id, provider, bucket_name, object_key, object_key_hash, etag, size_bytes,
                   content_type, object_modified_at, operation_status, last_error, created_at, updated_at
            FROM object_file
            WHERE bucket_name = #{bucket}
            ORDER BY object_key
            """)
    List<ObjectFilePo> selectByBucket(String bucket);

    @Select("""
            SELECT id, provider, bucket_name, object_key, object_key_hash, etag, size_bytes,
                   content_type, object_modified_at, operation_status, last_error, created_at, updated_at
            FROM object_file
            WHERE bucket_name = #{bucket} AND operation_status = #{status}
            ORDER BY object_key
            """)
    List<ObjectFilePo> selectByBucketAndStatus(
            @Param("bucket") String bucket,
            @Param("status") String status
    );

    @Select("""
            SELECT id, provider, bucket_name, object_key, object_key_hash, etag, size_bytes,
                   content_type, object_modified_at, operation_status, last_error, created_at, updated_at
            FROM object_file
            WHERE bucket_name = #{bucket} AND object_key_hash = #{objectKeyHash}
            LIMIT 1
            """)
    ObjectFilePo selectByKey(@Param("bucket") String bucket, @Param("objectKeyHash") String objectKeyHash);

    @Insert("""
            INSERT INTO object_file
            (id, provider, bucket_name, object_key, object_key_hash, etag, size_bytes, content_type,
             object_modified_at, operation_status, last_error, created_at, updated_at)
            VALUES
            (#{id}, #{provider}, #{bucketName}, #{objectKey}, #{objectKeyHash}, #{etag}, #{sizeBytes},
             #{contentType}, #{objectModifiedAt}, #{operationStatus}, #{lastError}, #{createdAt}, #{updatedAt})
            """)
    int insert(ObjectFilePo po);

    @Insert("""
            INSERT INTO object_file
            (id, provider, bucket_name, object_key, object_key_hash, etag, size_bytes, content_type,
             object_modified_at, operation_status, last_error, created_at, updated_at)
            VALUES
            (#{id}, #{provider}, #{bucketName}, #{objectKey}, #{objectKeyHash}, #{etag}, #{sizeBytes},
             #{contentType}, #{objectModifiedAt}, #{operationStatus}, #{lastError}, #{createdAt}, #{updatedAt})
            ON DUPLICATE KEY UPDATE
              provider = VALUES(provider),
              object_key = VALUES(object_key),
              etag = VALUES(etag),
              size_bytes = VALUES(size_bytes),
              content_type = VALUES(content_type),
              object_modified_at = VALUES(object_modified_at),
              operation_status = VALUES(operation_status),
              last_error = VALUES(last_error),
              updated_at = VALUES(updated_at)
            """)
    int upsert(ObjectFilePo po);

    @Update("""
            UPDATE object_file
            SET operation_status = #{status}, last_error = #{lastError}, updated_at = #{updatedAt}
            WHERE bucket_name = #{bucket} AND object_key_hash = #{objectKeyHash}
            """)
    int updateStatus(
            @Param("bucket") String bucket,
            @Param("objectKeyHash") String objectKeyHash,
            @Param("status") String status,
            @Param("lastError") String lastError,
            @Param("updatedAt") long updatedAt
    );

    @Delete("""
            DELETE FROM object_file
            WHERE bucket_name = #{bucket} AND object_key_hash = #{objectKeyHash}
            """)
    int deleteByKey(@Param("bucket") String bucket, @Param("objectKeyHash") String objectKeyHash);
}
