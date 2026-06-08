package io.github.jerryt92.j2agent.service.file.oss.provider;

import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3CompatibleObjectStorageServiceTest {
    private S3Client s3Client;
    private S3Presigner presigner;
    private S3CompatibleObjectStorageService service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        when(s3Client.headBucket(any(HeadBucketRequest.class)))
                .thenReturn(HeadBucketResponse.builder().build());
        service = new S3CompatibleObjectStorageService(
                s3Client,
                presigner,
                "bucket",
                "Test S3",
                true
        );
    }

    @Test
    void shouldReturnFalseWhenObjectDoesNotExist() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("not found").build());

        assertFalse(service.objectExists("bucket", "missing.png"));
    }

    @Test
    void shouldGeneratePresignedDownloadUrl() throws Exception {
        URL expectedUrl = new URL("http://127.0.0.1:9000/bucket/logo.png?X-Amz-Signature=abc");
        when(presigner.presignGetObject(any(GetObjectPresignRequest.class)))
                .thenReturn(PresignedGetObjectRequest.builder()
                        .expiration(Instant.now().plusSeconds(300))
                        .isBrowserExecutable(true)
                        .signedHeaders(Map.of("host", List.of("127.0.0.1:9000")))
                        .httpRequest(software.amazon.awssdk.http.SdkHttpRequest.builder()
                                .uri(expectedUrl.toURI())
                                .method(software.amazon.awssdk.http.SdkHttpMethod.GET)
                                .build())
                        .build());

        URL url = service.generatePresignedUrl("bucket", "logo.png", Duration.ofMinutes(5));

        assertEquals(expectedUrl, url);
    }

    @Test
    void shouldGeneratePresignedUploadUrlWithContentType() throws Exception {
        URL expectedUrl = new URL("http://127.0.0.1:9000/bucket/logo.png?X-Amz-Signature=abc");
        when(presigner.presignPutObject(any(PutObjectPresignRequest.class)))
                .thenReturn(PresignedPutObjectRequest.builder()
                        .expiration(Instant.now().plusSeconds(300))
                        .isBrowserExecutable(true)
                        .signedHeaders(Map.of("Content-Type", List.of("image/png")))
                        .httpRequest(software.amazon.awssdk.http.SdkHttpRequest.builder()
                                .uri(expectedUrl.toURI())
                                .method(software.amazon.awssdk.http.SdkHttpMethod.PUT)
                                .build())
                        .build());

        PresignedUploadCredential credential = service.generatePresignedUploadUrl(
                "bucket",
                "logo.png",
                Duration.ofMinutes(5),
                "image/png",
                1024L
        );

        assertEquals("Test S3", credential.provider());
        assertEquals(expectedUrl.toString(), credential.uploadUrl());
        assertEquals("PUT", credential.method());
        assertEquals("image/png", credential.headers().get("Content-Type"));

        ArgumentCaptor<PutObjectPresignRequest> captor = ArgumentCaptor.forClass(PutObjectPresignRequest.class);
        verify(presigner).presignPutObject(captor.capture());
        assertEquals("image/png", captor.getValue().putObjectRequest().contentType());
    }

    @Test
    void shouldUseContinuationTokenWhenListingObjects() {
        when(s3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenAnswer(invocation -> {
            ListObjectsV2Request request = invocation.getArgument(0);
            if (request.continuationToken() == null) {
                return ListObjectsV2Response.builder()
                        .contents(object("file-1.png"))
                        .isTruncated(true)
                        .nextContinuationToken("token-2")
                        .build();
            }
            assertEquals("token-2", request.continuationToken());
            return ListObjectsV2Response.builder()
                    .contents(object("file-2.png"))
                    .isTruncated(false)
                    .build();
        });

        ObjectStoragePage firstPage = service.listObjects("bucket", "", null, 1);
        assertEquals(1, firstPage.objects().size());
        assertEquals("file-1.png", firstPage.objects().getFirst().objectName());
        assertEquals("token-2", firstPage.continuationToken());

        ObjectStoragePage secondPage = service.listObjects("bucket", "", firstPage.continuationToken(), 1);
        assertEquals(1, secondPage.objects().size());
        assertEquals("file-2.png", secondPage.objects().getFirst().objectName());
        assertNull(secondPage.continuationToken());
    }

    @Test
    void shouldReturnTrueWhenObjectExists() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(software.amazon.awssdk.services.s3.model.HeadObjectResponse.builder().build());

        assertTrue(service.objectExists("bucket", "logo.png"));
    }

    private static List<S3Object> object(String key) {
        return List.of(S3Object.builder()
                .key(key)
                .eTag("etag")
                .size(10L)
                .lastModified(Instant.EPOCH)
                .build());
    }
}
