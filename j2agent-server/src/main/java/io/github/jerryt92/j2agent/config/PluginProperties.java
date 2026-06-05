package io.github.jerryt92.j2agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 插件（动态 JAR）加载配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "j2agent.plugin")
public class PluginProperties {

    /**
     * 外部 JAR 插件存放路径。
     */
    private String path;
}
