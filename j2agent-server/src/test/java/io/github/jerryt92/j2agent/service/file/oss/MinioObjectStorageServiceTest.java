package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.provider.MinioObjectStorageService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioObjectStorageServiceTest {
    private static final Instant LAST_MODIFIED = Instant.EPOCH;

    private S3Client s3Client;
    private S3Presigner presigner;
    private MinioObjectStorageService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
        service = new MinioObjectStorageService(s3Client, presigner, "bucket");
    }

    @Test
    void shouldRemoveEmptyParentDirectoryMarkersAfterDeletingNestedObject() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().build());
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenAnswer(invocation -> {
            HeadObjectRequest request = invocation.getArgument(0);
            if (Set.of("manual/images/", "manual/").contains(request.key())) {
                return HeadObjectResponse.builder().build();
            }
            throw NoSuchKeyException.builder().message("not found").build();
        });
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.removeObject("bucket", "manual/images/logo.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(3)).deleteObject(captor.capture());
        assertEquals(
                List.of("manual/images/logo.png", "manual/images/", "manual/"),
                captor.getAllValues().stream().map(DeleteObjectRequest::key).toList()
        );
    }

    @Test
    void shouldSkipDirectoryMarkersWhenListingObjects() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder()
                                .key("manual/images/")
                                .eTag("etag")
                                .size(0L)
                                .lastModified(LAST_MODIFIED)
                                .build(),
                        S3Object.builder()
                                .key("manual/images/logo.png")
                                .eTag("etag")
                                .size(10L)
                                .lastModified(LAST_MODIFIED)
                                .build()
                )
                .isTruncated(false)
                .build());

        ObjectStoragePage page = service.listObjects("bucket", "", null, 10);

        assertEquals(1, page.objects().size());
        assertEquals("manual/images/logo.png", page.objects().getFirst().objectName());
    }

    @Test
    void shouldStopCleaningWhenSiblingObjectsRemain() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("manual/images/").build(),
                        S3Object.builder()
                                .key("manual/images/thumb.png")
                                .eTag("etag")
                                .size(10L)
                                .lastModified(LAST_MODIFIED)
                                .build()
                )
                .build());
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        service.removeObject("bucket", "manual/images/logo.png");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client, times(1)).deleteObject(captor.capture());
        assertEquals("manual/images/logo.png", captor.getValue().key());
    }
}
