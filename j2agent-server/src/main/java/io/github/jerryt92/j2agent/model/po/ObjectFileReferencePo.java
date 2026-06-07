package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

@Data
public class ObjectFileReferencePo {
    private String id;
    private String fileId;
    private String businessType;
    private String businessId;
    private String ownerId;
    private Long createdAt;
}
