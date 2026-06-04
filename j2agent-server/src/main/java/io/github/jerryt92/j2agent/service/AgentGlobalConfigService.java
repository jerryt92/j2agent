package io.github.jerryt92.j2agent.service;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.event.PropertiesUpdatedEvent;
import io.github.jerryt92.j2agent.model.PropertyDto;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 智能体全局配置：从 {@code ai_properties.agent-global-config-json} 读取，供智能报表等工具按 key 取值。
 */
@Service
public class AgentGlobalConfigService {

    private static final JSONObject DEFAULT_CONFIG = JSONObject.parseObject(
            "{\"datasource\":{\"jdbcUrl\":\"\",\"username\":\"\",\"password\":\"\","
                    + "\"driverClassName\":\"com.mysql.cj.jdbc.Driver\"},\"service\":{\"baseUrl\":\"\"}}");

    private final PropertiesService propertiesService;
    private volatile JSONObject cachedConfig = DEFAULT_CONFIG;

    public AgentGlobalConfigService(PropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    @PostConstruct
    public void init() {
        reloadFromDb();
    }

    @EventListener
    public void onPropertiesUpdated(PropertiesUpdatedEvent event) {
        if (event.getPropertyNames() == null) {
            return;
        }
        if (event.getPropertyNames().contains(PropertiesService.AGENT_GLOBAL_CONFIG_JSON)) {
            reloadFromDb();
        }
    }

    /**
     * 从数据库重新加载配置到内存缓存。
     */
    public void reloadFromDb() {
        cachedConfig = parseConfig(propertiesService.getProperty(PropertiesService.AGENT_GLOBAL_CONFIG_JSON));
    }

    /**
     * 返回完整智能体全局配置 JSON。
     */
    public JSONObject getConfig() {
        return cachedConfig.clone();
    }

    /**
     * 更新智能体全局配置并持久化。
     */
    public void updateConfig(JSONObject config) {
        if (config == null) {
            return;
        }
        PropertyDto propertyDto = new PropertyDto();
        propertyDto.setPropertyName(PropertiesService.AGENT_GLOBAL_CONFIG_JSON);
        propertyDto.setPropertyValue(JSONObject.toJSONString(config));
        propertiesService.putProperty(List.of(propertyDto));
        cachedConfig = config.clone();
    }

    /**
     * 按点分路径读取字符串值，例如 {@code datasource.jdbcUrl}。
     */
    public String getString(String key) {
        return getString(key, null);
    }

    /**
     * 按点分路径读取字符串值，不存在时返回默认值。
     */
    public String getString(String key, String defaultValue) {
        if (StringUtils.isBlank(key)) {
            return defaultValue;
        }
        String[] parts = key.split("\\.");
        JSONObject current = cachedConfig;
        for (int i = 0; i < parts.length - 1; i++) {
            if (current == null) {
                return defaultValue;
            }
            current = current.getJSONObject(parts[i]);
        }
        if (current == null) {
            return defaultValue;
        }
        String value = current.getString(parts[parts.length - 1]);
        return value == null ? defaultValue : value;
    }

    /**
     * 是否已配置智能体全局 JDBC 数据源（jdbcUrl 非空）。
     */
    public boolean isDatasourceEnabled() {
        return StringUtils.isNotBlank(getString("datasource.jdbcUrl"));
    }

    private static JSONObject parseConfig(String json) {
        if (StringUtils.isBlank(json)) {
            return DEFAULT_CONFIG.clone();
        }
        JSONObject parsed = JSONObject.parseObject(json);
        return parsed == null ? DEFAULT_CONFIG.clone() : parsed;
    }
}
