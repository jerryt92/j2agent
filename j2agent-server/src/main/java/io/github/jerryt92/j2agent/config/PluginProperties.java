package io.github.jerryt92.j2agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    public PluginProperties() {
    }

    /**
     * 解析插件根目录（与 {@link #path} 一致，供调用方统一入口）。
     */
    public String resolvePath() {
        return StringUtils.hasText(path) ? path : null;
    }
}
