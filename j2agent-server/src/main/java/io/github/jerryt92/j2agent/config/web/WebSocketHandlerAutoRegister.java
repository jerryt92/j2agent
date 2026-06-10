package io.github.jerryt92.j2agent.config.web;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 2024-03-07 AutoRegisterWebSocketHandler注解处理器
 *
 * @author tianjingli
 */
@Configuration
@EnableWebSocket
public class WebSocketHandlerAutoRegister implements WebSocketConfigurer {
    private static final Logger log = LogManager.getLogger(WebSocketHandlerAutoRegister.class);
    final List<WebSocketHandler> webSocketHandlers;
    private final HandshakeInterceptor[] handshakeInterceptors;

    public WebSocketHandlerAutoRegister(List<WebSocketHandler> webSocketHandlers, HandshakeInterceptor[] handshakeInterceptors) {
        this.webSocketHandlers = webSocketHandlers;
        this.handshakeInterceptors = handshakeInterceptors;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (null != webSocketHandlers && !webSocketHandlers.isEmpty()) {
            for (WebSocketHandler webSocketHandler : webSocketHandlers) {
                if (webSocketHandler.getClass().isAnnotationPresent(AutoRegisterWebSocketHandler.class)) {
                    AutoRegisterWebSocketHandler annotation =
                            webSocketHandler.getClass().getDeclaredAnnotation(AutoRegisterWebSocketHandler.class);
                    Set<String> interceptorsPackClassNames = Set.of(annotation.interceptorsClassNames());
                    List<HandshakeInterceptor> handshakeInterceptorList = new ArrayList<>();
                    for (HandshakeInterceptor handshakeInterceptor : handshakeInterceptors) {
                        log.info(handshakeInterceptor.getClass().getName());
                        if (interceptorsPackClassNames.contains(handshakeInterceptor.getClass().getName())) {
                            handshakeInterceptorList.add(handshakeInterceptor);
                        }
                    }
                    HandshakeInterceptor[] needHandshakeInterceptors = new HandshakeInterceptor[handshakeInterceptorList.size()];
                    handshakeInterceptorList.toArray(needHandshakeInterceptors);
                    registry.addHandler(webSocketHandler, annotation.path())
                            .addInterceptors(needHandshakeInterceptors)
                            .setAllowedOrigins(annotation.allowedOrigin());
                }
            }
        }
    }
}