package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncDiffMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncTaskMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncDiffPo;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageSyncServiceTest {
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ObjectFileManagementService fileService = mock(ObjectFileManagementService.class);
    private final ObjectFileMapper fileMapper = mock(ObjectFileMapper.class);
    private final ObjectStorageSyncTaskMapper taskMapper = mock(ObjectStorageSyncTaskMapper.class);
    private final ObjectStorageSyncDiffMapper diffMapper = mock(ObjectStorageSyncDiffMapper.class);
    private final ObjectStorageDifferenceSnapshotService snapshotService =
            mock(ObjectStorageDifferenceSnapshotService.class);
    private final TaskExecutor directExecutor = Runnable::run;
    private final ObjectStorageSyncService service = new ObjectStorageSyncService(
            storage, fileService, fileMapper, taskMapper, diffMapper, snapshotService, directExecutor
    );

    ObjectStorageSyncServiceTest() {
        when(taskMapper.markRunning(any(), anyLong())).thenReturn(1);
        when(snapshotService.complete(any(), any())).thenReturn(true);
    }

    @Test
    void shouldClassifyUploadingRecordAsInProgress() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.listObjects(eq("bucket"), eq(""), any(), eq(500))).thenReturn(
                new ObjectStoragePage(List.of(
                        object("uploading.txt", "etag-1", 10, 100)
                ), null)
        );
        ObjectFilePo uploading = file("uploading.txt", "etag-1", 10, 100);
        uploading.setOperationStatus("UPLOADING");
        uploading.setEtag(null);
        when(fileMapper.selectByBucket("bucket")).thenReturn(List.of(uploading));

        service.runScan("task");

        ArgumentCaptor<ObjectStorageSyncDiffPo> captor =
                ArgumentCaptor.forClass(ObjectStorageSyncDiffPo.class);
        verify(diffMapper).insert(captor.capture());
        assertEquals(ObjectStorageSyncService.IN_PROGRESS, captor.getValue().getDiffType());
    }

    @Test
    void shouldClassifyDeletingDbOnlyAsInProgress() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.listObjects(eq("bucket"), eq(""), any(), eq(500))).thenReturn(
                new ObjectStoragePage(List.of(), null)
        );
        ObjectFilePo deleting = file("deleting.txt", "etag-1", 10, 100);
        deleting.setOperationStatus("DELETING");
        when(fileMapper.selectByBucket("bucket")).thenReturn(List.of(deleting));

        service.runScan("task");

        ArgumentCaptor<ObjectStorageSyncDiffPo> captor =
                ArgumentCaptor.forClass(ObjectStorageSyncDiffPo.class);
        verify(diffMapper).insert(captor.capture());
        assertEquals(ObjectStorageSyncService.IN_PROGRESS, captor.getValue().getDiffType());
    }

    @Test
    void shouldPersistOnlyAbnormalDifferenceTypesAndNormalizeEtag() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.listObjects(eq("bucket"), eq(""), any(), eq(500))).thenReturn(
                new ObjectStoragePage(List.of(
                        object("same.txt", "\"etag-1\"", 10, 100),
                        object("oss-only.txt", "etag-2", 20, 200),
                        object("changed.txt", "new-etag", 30, 300)
                ), null)
        );
        when(fileMapper.selectByBucket("bucket")).thenReturn(List.of(
                file("same.txt", "etag-1", 10, 100),
                file("changed.txt", "old-etag", 30, 300),
                file("db-only.txt", "etag-4", 40, 400)
        ));

        service.runScan("task");

        ArgumentCaptor<ObjectStorageSyncDiffPo> captor =
                ArgumentCaptor.forClass(ObjectStorageSyncDiffPo.class);
        verify(diffMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<String> types = captor.getAllValues().stream().map(ObjectStorageSyncDiffPo::getDiffType).toList();
        assertTrue(types.contains(ObjectStorageSyncService.OSS_ONLY));
        assertTrue(types.contains(ObjectStorageSyncService.DB_ONLY));
        assertTrue(types.contains(ObjectStorageSyncService.METADATA_MISMATCH));
        verify(snapshotService).complete(eq("bucket"), any());
    }

    @Test
    void shouldCancelCheckAndDiscardTemporaryDifferences() {
        ObjectStorageSyncTaskPo cancelling = new ObjectStorageSyncTaskPo();
        cancelling.setId("task");
        cancelling.setTaskStatus("CANCEL_REQUESTED");
        when(taskMapper.selectById("task")).thenReturn(cancelling);

        service.runScan("task");

        verify(snapshotService).cancel(eq("task"), anyLong());
    }

    @Test
    void shouldRejectSecondActiveScan() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(taskMapper.countActive("bucket")).thenReturn(1);

        assertThrows(ResponseStatusException.class, service::startScan);
    }

    @Test
    void shouldUpdateDifferenceStatusAfterSuccessfulResolution() {
        ObjectFilePo dbFile = file("db-only.txt", "etag-1", 10, 1_000);
        ObjectStorageSyncDiffPo diff = new ObjectStorageSyncDiffPo();
        diff.setId("diff");
        diff.setBucketName("bucket");
        diff.setObjectKey(dbFile.getObjectKey());
        diff.setObjectKeyHash(dbFile.getObjectKeyHash());
        diff.setDiffType(ObjectStorageSyncService.DB_ONLY);
        diff.setResolutionStatus("PENDING");
        diff.setDbEtag(dbFile.getEtag());
        diff.setDbSizeBytes(dbFile.getSizeBytes());
        diff.setDbModifiedAt(dbFile.getObjectModifiedAt());
        when(diffMapper.selectById("diff")).thenReturn(diff);
        when(fileMapper.selectByKey("bucket", dbFile.getObjectKeyHash())).thenReturn(dbFile);
        when(storage.objectExists("bucket", dbFile.getObjectKey())).thenReturn(false);
        when(diffMapper.updateResolution(
                eq("diff"), eq("RESOLVED"), eq("DELETE_DB"), isNull(), anyLong()
        )).thenReturn(1);

        assertTrue(service.resolve(List.of("diff"), "DELETE_DB").isEmpty());

        verify(fileMapper).deleteByKey("bucket", dbFile.getObjectKeyHash());
        verify(diffMapper).updateResolution(
                eq("diff"), eq("RESOLVED"), eq("DELETE_DB"), isNull(), anyLong()
        );
    }

    @Test
    void shouldTreatQuotedAndPlainEtagsAsEqual() {
        assertTrue(service.metadataMatches(
                object("a", "\"abc\"", 1, 2),
                file("a", "abc", 1, 2)
        ));
    }

    @Test
    void shouldIgnoreSubSecondLastModifiedDifferences() {
        assertTrue(service.metadataMatches(
                object("a", "abc", 1, 1_999),
                file("a", "abc", 1, 1_000)
        ));
    }

    @Test
    void shouldDetectLastModifiedDifferencesAcrossSeconds() {
        assertTrue(!service.metadataMatches(
                object("a", "abc", 1, 2_000),
                file("a", "abc", 1, 1_000)
        ));
    }

    private ObjectStorageObject object(String key, String etag, long size, long modified) {
        return new ObjectStorageObject(key, etag, size, "text/plain", modified);
    }

    private ObjectFilePo file(String key, String etag, long size, long modified) {
        ObjectFilePo po = new ObjectFilePo();
        po.setId(key);
        po.setBucketName("bucket");
        po.setObjectKey(key);
        po.setObjectKeyHash(ObjectKeyUtils.hash(key));
        po.setEtag(etag);
        po.setSizeBytes(size);
        po.setObjectModifiedAt(modified);
        po.setCreatedAt(1L);
        return po;
    }
}
