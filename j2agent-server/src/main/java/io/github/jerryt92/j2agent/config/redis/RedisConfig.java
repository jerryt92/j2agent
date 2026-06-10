package io.github.jerryt92.j2agent.config.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;

import java.util.stream.Collectors;

@Configuration
public class RedisConfig {

    @Value("${spring.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown") // 销毁容器时关闭 Redisson 客户端
    public RedissonClient redissonClient(RedisProperties redisProperties, ObjectMapper objectMapper) {
        Config config = new Config();
        config.setCodec(new JsonJacksonCodec(objectMapper));
        if (null != redisProperties.getCluster() && !CollectionUtils.isEmpty(redisProperties.getCluster().getNodes())) {
            ClusterServersConfig config0 = config.useClusterServers();
            config0.setNodeAddresses(redisProperties.getCluster().getNodes().stream().map(i -> "redis://" + i).collect(
                    Collectors.toList()));
            config0
                    .setMasterConnectionPoolSize(32)
                    .setSlaveConnectionPoolSize(32)
                    .setMasterConnectionMinimumIdleSize(5)
                    .setSlaveConnectionMinimumIdleSize(5);
            if (StringUtils.isNotBlank(redisProperties.getPassword())) {
                config0.setPassword(redisProperties.getPassword());
            }
        } else {
            SingleServerConfig config0 = config.useSingleServer();
            config0.setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                    .setDatabase(database)
                    .setConnectionPoolSize(64)
                    .setConnectionMinimumIdleSize(10);
            if (StringUtils.isNotBlank(redisProperties.getPassword())) {
                config0.setPassword(redisProperties.getPassword());
            }
        }
        // 3. 创建实例
        return Redisson.create(config);
    }
}
