package io.github.jerryt92.j2agent.config.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Redis key 统一前缀：{@code spring.application.name} + ":" + 业务命名空间。
 */
@Component
public class RedisKeyNamespaces {

    private final String prefix;

    public RedisKeyNamespaces(@Value("${spring.application.name}") String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("spring.application.name must not be blank.");
        }
        this.prefix = applicationName + ":";
    }

    /**
     * 拼接完整 Redis key 前缀段：{@code {appName}:{namespace}}，namespace 可自带尾部冒号或继续拼接子键。
     */
    public String key(String namespace) {
        return prefix + namespace;
    }
}
