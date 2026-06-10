package io.github.jerryt92.j2agent.config.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
public class WebsocketConfig {

    @Value("${j2agent.websocket.max_message_buffer_size}")
    private Integer maxMessageBufferSize;

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(maxMessageBufferSize);
        container.setMaxBinaryMessageBufferSize(maxMessageBufferSize);
        return container;
    }
}
