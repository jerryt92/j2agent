CREATE TABLE object_file
(
    id                 char(32)      NOT NULL,
    provider           varchar(32)   NOT NULL,
    bucket_name        varchar(255)  NOT NULL,
    object_key         varchar(2048) NOT NULL,
    object_key_hash    char(64)      NOT NULL,
    etag               varchar(255)  DEFAULT NULL,
    size_bytes         bigint        NOT NULL DEFAULT 0,
    content_type       varchar(255)  DEFAULT NULL,
    object_modified_at bigint        NOT NULL DEFAULT 0,
    operation_status   varchar(32)   NOT NULL,
    last_error         varchar(2048) DEFAULT NULL,
    created_at         bigint        NOT NULL,
    updated_at         bigint        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_object_file_bucket_key (bucket_name, object_key_hash),
    KEY idx_object_file_bucket_status (bucket_name, operation_status)
);

CREATE TABLE object_storage_sync_task
(
    id                  char(32)      NOT NULL,
    bucket_name         varchar(255)  NOT NULL,
    provider            varchar(32)   NOT NULL,
    task_status         varchar(32)   NOT NULL,
    scanned_count       bigint        NOT NULL DEFAULT 0,
    in_sync_count       bigint        NOT NULL DEFAULT 0,
    oss_only_count      bigint        NOT NULL DEFAULT 0,
    db_only_count       bigint        NOT NULL DEFAULT 0,
    mismatch_count      bigint        NOT NULL DEFAULT 0,
    in_progress_count   bigint        NOT NULL DEFAULT 0,
    error_message       varchar(2048) DEFAULT NULL,
    created_at          bigint        NOT NULL,
    started_at          bigint        DEFAULT NULL,
    completed_at        bigint        DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_storage_sync_task_bucket_status (bucket_name, task_status),
    KEY idx_storage_sync_task_created (created_at),
    KEY idx_storage_sync_task_cleanup (task_status, completed_at)
);

CREATE TABLE object_storage_sync_diff
(
    id                    char(32)      NOT NULL,
    task_id               char(32)      NOT NULL,
    bucket_name           varchar(255)  NOT NULL,
    object_key            varchar(2048) NOT NULL,
    object_key_hash       char(64)      NOT NULL,
    diff_type             varchar(32)   NOT NULL,
    resolution_status     varchar(32)   NOT NULL DEFAULT 'PENDING',
    oss_etag              varchar(255)  DEFAULT NULL,
    oss_size_bytes        bigint        DEFAULT NULL,
    oss_modified_at       bigint        DEFAULT NULL,
    db_etag               varchar(255)  DEFAULT NULL,
    db_size_bytes         bigint        DEFAULT NULL,
    db_modified_at        bigint        DEFAULT NULL,
    resolution_action     varchar(32)   DEFAULT NULL,
    resolution_error      varchar(2048) DEFAULT NULL,
    created_at            bigint        NOT NULL,
    updated_at            bigint        NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_storage_sync_diff_task_key (task_id, object_key_hash),
    KEY idx_storage_sync_diff_bucket_task (bucket_name, task_id),
    KEY idx_storage_sync_diff_task_type (task_id, diff_type),
    KEY idx_storage_sync_diff_resolution (task_id, resolution_status)
);

CREATE TABLE object_file_reference
(
    id            char(32)     NOT NULL COMMENT 'UUIDv7',
    file_id       char(32)     NOT NULL COMMENT 'object_file.id',
    business_type varchar(64)  NOT NULL COMMENT 'Business type',
    business_id   varchar(512) NOT NULL COMMENT 'Business unique identifier',
    owner_id      varchar(64)  DEFAULT NULL COMMENT 'Reference owner',
    created_at    bigint       NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_object_file_reference (file_id, business_type, business_id),
    KEY idx_object_file_reference_file (file_id),
    KEY idx_object_file_reference_business (business_type, business_id),
    CONSTRAINT fk_object_file_reference_file FOREIGN KEY (file_id) REFERENCES object_file (id)
);
