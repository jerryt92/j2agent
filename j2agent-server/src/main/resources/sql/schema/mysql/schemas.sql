USE j2agent;

DROP TABLE IF EXISTS api_key_info;
CREATE TABLE api_key_info
(
    api_key       varchar(64) PRIMARY KEY,
    api_key_owner varchar(64),
    create_time   bigint,
    expire_time   bigint COMMENT 'NULL - 永久有效，否则为到期时间戳'
) COMMENT ='已授权API密钥信息';

DROP TABLE IF EXISTS chat_context_record;
CREATE TABLE chat_context_record
(
    context_id         varchar(64)  NOT NULL,
    agent_id           varchar(64)  NOT NULL DEFAULT '' COMMENT '智能体ID，空串为历史默认行',
    title              varchar(64)  NOT NULL,
    user_id            varchar(64),
    memory_version     int          NOT NULL DEFAULT 1 COMMENT '记忆管理版本',
    last_message_index int          NULL COMMENT '最后一条消息索引',
    update_time        bigint       NOT NULL,
    PRIMARY KEY (context_id, agent_id)
) COMMENT ='聊天会话上下文';

CREATE INDEX idx_chat_context_user_update_time ON chat_context_record (user_id, agent_id, update_time);

DROP TABLE IF EXISTS chat_context_item;
CREATE TABLE chat_context_item
(
    message_id        char(36)     NOT NULL COMMENT '消息主键',
    context_id        varchar(64)  NOT NULL,
    agent_id          varchar(64)  NOT NULL DEFAULT '' COMMENT '智能体ID，与 chat_context_record 对齐',
    message_index     int          NOT NULL,
    chat_role         int          NOT NULL COMMENT '0-system, 1-user, 2-assistant',
    content           text         NOT NULL,
    feedback          int COMMENT '1-good, 2-bad',
    rag_infos         text COMMENT 'RAG 信息',
    add_time          bigint       NOT NULL,
    token_count       int          NULL COMMENT 'token数量',
    meta_json         text         NULL COMMENT '扩展元数据',
    PRIMARY KEY (message_id)
) COMMENT ='聊天消息';

CREATE INDEX idx_chat_context_item_ctx_agent ON chat_context_item (context_id, agent_id);
CREATE INDEX idx_chat_context_item_ctx_agent_msg_idx ON chat_context_item (context_id, agent_id, message_index);
CREATE INDEX idx_chat_context_item_add_time ON chat_context_item (context_id, agent_id, add_time);

DROP TABLE IF EXISTS user;
CREATE TABLE user
(
    id            char(32)    NOT NULL,
    username      varchar(64) NOT NULL,
    password_hash char(64)    NOT NULL COMMENT '密码哈希值',
    create_time   bigint,
    role          int COMMENT '1-管理员, 2-普通用户',
    email         varchar(128) DEFAULT NULL COMMENT '邮箱',
    PRIMARY KEY (id),
    UNIQUE KEY uq_user_email (email)
);

DROP TABLE IF EXISTS ai_properties;
CREATE TABLE ai_properties
(
    property_name  varchar(128) NOT NULL,
    property_value text         DEFAULT NULL,
    description    varchar(512) DEFAULT NULL,
    PRIMARY KEY (property_name)
);

DROP TABLE IF EXISTS api_provider_config;
CREATE TABLE api_provider_config
(
    id            bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    api_type      varchar(64)  NOT NULL COMMENT 'API类型（llm/embedding/rerank等）',
    config_name   varchar(128) NOT NULL COMMENT '配置名称',
    provider_type varchar(64)  NOT NULL COMMENT '提供商类型（open-ai/anthropic/ollama等）',
    config_json   text         NOT NULL COMMENT '配置JSON，存放不同提供商差异化字段',
    enabled       tinyint(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    is_current    tinyint(1)   NOT NULL DEFAULT 0 COMMENT '是否当前生效配置',
    description   varchar(512) DEFAULT NULL COMMENT '描述',
    create_time   bigint       DEFAULT NULL COMMENT '创建时间',
    update_time   bigint       DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id)
);

CREATE INDEX idx_api_provider_type ON api_provider_config (api_type, provider_type);
CREATE INDEX idx_api_provider_current ON api_provider_config (api_type, is_current);

DROP TABLE IF EXISTS knowledge_source_file_hash;
CREATE TABLE knowledge_source_file_hash
(
    id              varchar(32)  NOT NULL COMMENT '主键（UUIDv7）',
    file_path       varchar(2048) NOT NULL COMMENT '源文件相对root-path路径',
    file_path_hash  char(64)     NOT NULL COMMENT '源文件相对路径sha256',
    file_sha256     char(64)     NOT NULL COMMENT '源文件sha256',
    info_json_hash  char(64)     NOT NULL COMMENT '匹配info.json文件sha256',
    collection_name varchar(128) NOT NULL COMMENT '目标collection',
    partition_names varchar(2048) DEFAULT NULL COMMENT 'Milvus分区名JSON数组，与info.json一致',
    knowledge_count int          NOT NULL DEFAULT 0 COMMENT '该文件产生的知识条数',
    file_size_bytes bigint       NOT NULL DEFAULT 0 COMMENT '源文件大小（字节）',
    last_scan_time  bigint       NOT NULL COMMENT '最近扫描时间',
    sync_status     varchar(32)  NOT NULL COMMENT '同步状态（ACTIVE/DELETED）',
    created_at      bigint       NOT NULL COMMENT '创建时间',
    updated_at      bigint       NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uq_file_path_hash (file_path_hash),
    KEY idx_sync_status (sync_status)
) COMMENT ='知识库源文件哈希树';