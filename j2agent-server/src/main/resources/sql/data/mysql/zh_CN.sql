INSERT INTO j2agent.user (id, username, password_hash, create_time, role)
VALUES ('AZ5EsB1JcMqXv6z1FcfEUw', 'aiadmin',
        '35339f8c3a2d91f4ea67acdce7078562cf4f6c385a0782c82830a1993cd7fd53',
        null, 1);

INSERT INTO j2agent.api_key_info (api_key, api_key_owner, create_time, expire_time)
VALUES ('publicaidemo', '公开试用', 1756796400000, null);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
VALUES ('RETRIEVE_TOP_K', '5', '检索结果最大数量');

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
VALUES ('RETRIEVE_METRIC_TYPE', 'COSINE', '检索度量指标（COSINE余弦相似度、IP积内积、L2欧式距离等）');

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
VALUES ('RETRIEVE_METRIC_SCORE_COMPARE_EXPR', '> 0.8',
        '检索度量结果评分过滤条件，不同度量指标的取值范围不同，请根据实际情况修改');

-- MCP
INSERT INTO j2agent.ai_properties (property_name, property_value, description)
VALUES ('mcp-config-json', '{
  "mcpServers": {}
}', 'MCP配置JSON');
INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'mcp-auto-reconnect', 'true', 'MCP auto reconnect'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'mcp-auto-reconnect'
);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'mcp-health-check-interval-seconds', '15', 'MCP health check interval seconds'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'mcp-health-check-interval-seconds'
);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'user-email-register-enabled', 'false', '是否允许邮箱自助注册'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'user-email-register-enabled'
);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'user-email-register-smtp-json', '{"host":"","port":587,"username":"","password":"","from":"","ssl":false,"startTls":true}', '邮箱注册 SMTP 配置 JSON'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'user-email-register-smtp-json'
);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'user-email-register-whitelist-enabled', 'false', '是否启用邮箱注册白名单'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'user-email-register-whitelist-enabled'
);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'user-email-register-whitelist-rules', '', '邮箱注册白名单规则，逗号分隔，如 *@xxx.com, aaa@ccc.com'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'user-email-register-whitelist-rules'
);

INSERT INTO j2agent.ai_properties (property_name, property_value, description)
SELECT 'user-email-register-whitelist-denied-message', '', '邮箱不在白名单时的拒绝提示，为空则使用默认文案'
    WHERE NOT EXISTS (
    SELECT 1 FROM j2agent.ai_properties WHERE property_name = 'user-email-register-whitelist-denied-message'
);
