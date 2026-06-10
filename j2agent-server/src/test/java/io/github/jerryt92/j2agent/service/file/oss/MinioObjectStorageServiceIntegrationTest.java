package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.storage.ObjectStorageConfig;
import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;
import io.github.jerryt92.j2agent.service.file.oss.provider.MinioObjectStorageService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MinioObjectStorageServiceIntegrationTest {
    private static final String ENDPOINT = System.getenv().getOrDefault(
            "J2AGENT_S3_ENDPOINT",
            "http://127.0.0.1:19000"
    );
    private static final String ACCESS_KEY = System.getenv().getOrDefault("J2AGENT_S3_ACCESS_KEY_ID", "minioadmin");
    private static final String SECRET_KEY = System.getenv().getOrDefault("J2AGENT_S3_SECRET_ACCESS_KEY", "j2apassword@2026#");

    private ObjectStorageService storage;
    private String objectKey;

    @BeforeEach
    void setUp() {
        assumeTrue(isMinioReachable(), "MinIO is not reachable at " + ENDPOINT);
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType(ObjectStorageProperties.StorageType.MINIO);
        properties.setBucket("j2agent-integration-" + UUID.randomUUID().toString().substring(0, 8));
        properties.getS3().setEndpoint(ENDPOINT);
        properties.getS3().setAccessKeyId(ACCESS_KEY);
        properties.getS3().setSecretAccessKey(SECRET_KEY);
        storage = new ObjectStorageConfig().objectStorageService(properties);
        objectKey = "integration/" + UUID.randomUUID() + ".txt";
    }

    @AfterEach
    void tearDown() {
        if (storage == null) {
            return;
        }
        try {
            if (storage.objectExists(objectKey)) {
                storage.removeObject(objectKey);
            }
        } finally {
            storage.close();
        }
    }

    @Test
    void shouldUploadDownloadPresignAndPaginateAgainstLocalMinio() throws Exception {
        byte[] payload = "s3-sdk-integration".getBytes(StandardCharsets.UTF_8);
        storage.putObject(
                objectKey,
                new ByteArrayInputStream(payload),
                payload.length,
                "text/plain"
        );
        assertTrue(storage.objectExists(objectKey));

        try (var input = storage.getObject(objectKey)) {
            assertEquals("s3-sdk-integration", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }

        URL downloadUrl = storage.generatePresignedUrl(objectKey, Duration.ofMinutes(5));
        HttpURLConnection connection = (HttpURLConnection) downloadUrl.openConnection();
        connection.setRequestMethod("GET");
        assertEquals(200, connection.getResponseCode());
        assertEquals(
                "s3-sdk-integration",
                new String(connection.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        );

        PresignedUploadCredential uploadCredential = storage.generatePresignedUploadUrl(
                "integration/upload-" + UUID.randomUUID() + ".txt",
                Duration.ofMinutes(5),
                "text/plain",
                4L
        );
        assertNotNull(uploadCredential.uploadUrl());
        assertEquals("PUT", uploadCredential.method());
        assertEquals("text/plain", uploadCredential.headers().get("Content-Type"));

        String listPrefix = "integration/";
        for (int index = 0; index < 3; index++) {
            String key = listPrefix + "page-" + index + ".txt";
            byte[] body = ("body-" + index).getBytes(StandardCharsets.UTF_8);
            storage.putObject(key, new ByteArrayInputStream(body), body.length, "text/plain");
        }

        ObjectStoragePage firstPage = storage.listObjects(listPrefix, null, 2);
        assertEquals(2, firstPage.objects().size());
        assertNotNull(firstPage.continuationToken());

        ObjectStoragePage secondPage = storage.listObjects(listPrefix, firstPage.continuationToken(), 2);
        assertFalse(secondPage.objects().isEmpty());

        assertInstanceOf(MinioObjectStorageService.class, storage);
    }

    private static boolean isMinioReachable() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(ENDPOINT + "/minio/health/live").openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 200;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void assertInstanceOf(Class<?> expected, Object actual) {
        assertTrue(expected.isInstance(actual), () -> "Expected " + expected.getSimpleName());
    }
}
