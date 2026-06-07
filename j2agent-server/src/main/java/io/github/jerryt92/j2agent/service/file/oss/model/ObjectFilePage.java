package io.github.jerryt92.j2agent.service.file.oss.model;

import java.util.List;

public record ObjectFilePage(List<ObjectFileView> items, long total) {
}
