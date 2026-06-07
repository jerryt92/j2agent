package io.github.jerryt92.j2agent.service.file.oss.model;

import java.util.List;

/**
 * 对象存储分页结果，continuationToken 为空表示已经到末页。
 */
public record ObjectStoragePage(
        List<ObjectStorageObject> objects,
        String continuationToken
) {
}
