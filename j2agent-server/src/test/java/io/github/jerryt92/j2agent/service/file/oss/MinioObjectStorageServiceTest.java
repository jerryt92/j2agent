package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.provider.MinioObjectStorageService;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MinioObjectStorageServiceTest {
    private static final ZonedDateTime LAST_MODIFIED = ZonedDateTime.ofInstant(Instant.EPOCH, java.time.ZoneOffset.UTC);

    @Test
    void shouldRemoveEmptyParentDirectoryMarkersAfterDeletingNestedObject() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioObjectStorageService service = new MinioObjectStorageService(client, "bucket");

        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of());
        when(client.statObject(any(StatObjectArgs.class))).thenAnswer(invocation -> {
            StatObjectArgs args = invocation.getArgument(0);
            if (Set.of("manual/images/", "manual/").contains(args.object())) {
                return mock(StatObjectResponse.class);
            }
            throw new RuntimeException("unexpected stat: " + args.object());
        });
        doNothing().when(client).removeObject(any(RemoveObjectArgs.class));

        service.removeObject("bucket", "manual/images/logo.png");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client, times(3)).removeObject(captor.capture());
        assertEquals(
                List.of("manual/images/logo.png", "manual/images/", "manual/"),
                captor.getAllValues().stream().map(RemoveObjectArgs::object).toList()
        );
    }

    @Test
    void shouldSkipDirectoryMarkersWhenListingObjects() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioObjectStorageService service = new MinioObjectStorageService(client, "bucket");

        Item marker = mock(Item.class);
        when(marker.isDir()).thenReturn(false);
        when(marker.objectName()).thenReturn("manual/images/");
        when(marker.etag()).thenReturn("etag");
        when(marker.size()).thenReturn(0L);
        when(marker.lastModified()).thenReturn(LAST_MODIFIED);

        Item file = mock(Item.class);
        when(file.isDir()).thenReturn(false);
        when(file.objectName()).thenReturn("manual/images/logo.png");
        when(file.etag()).thenReturn("etag");
        when(file.size()).thenReturn(10L);
        when(file.lastModified()).thenReturn(LAST_MODIFIED);

        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(
                new Result<>(marker),
                new Result<>(file)
        ));

        ObjectStoragePage page = service.listObjects("bucket", "", null, 10);

        assertEquals(1, page.objects().size());
        assertEquals("manual/images/logo.png", page.objects().getFirst().objectName());
    }

    @Test
    void shouldStopCleaningWhenSiblingObjectsRemain() throws Exception {
        MinioClient client = mock(MinioClient.class);
        MinioObjectStorageService service = new MinioObjectStorageService(client, "bucket");

        Item marker = mock(Item.class);
        when(marker.objectName()).thenReturn("manual/images/");

        Item sibling = mock(Item.class);
        when(sibling.isDir()).thenReturn(false);
        when(sibling.objectName()).thenReturn("manual/images/thumb.png");
        when(sibling.etag()).thenReturn("etag");
        when(sibling.size()).thenReturn(10L);
        when(sibling.lastModified()).thenReturn(LAST_MODIFIED);

        when(client.listObjects(any(ListObjectsArgs.class))).thenReturn(List.of(
                new Result<>(marker),
                new Result<>(sibling)
        ));
        doNothing().when(client).removeObject(any(RemoveObjectArgs.class));

        service.removeObject("bucket", "manual/images/logo.png");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client, times(1)).removeObject(captor.capture());
        assertEquals("manual/images/logo.png", captor.getValue().object());
    }
}
