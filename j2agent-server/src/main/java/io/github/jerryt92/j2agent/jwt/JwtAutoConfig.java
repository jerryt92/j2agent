package io.github.jerryt92.j2agent.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JwtAutoConfig {

    @Bean
    @ConfigurationProperties(prefix = "j2agent.security.jwt")
    public JwtProperties jwtProperties() {
        return new JwtProperties();
    }
}
