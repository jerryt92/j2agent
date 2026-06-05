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
     * 插件根目录（{@code .../plugins}）。Agent 在 {@code agents/} 下，平台 Skill 在 {@code skills/} 下。
     */
    private String path;
}
