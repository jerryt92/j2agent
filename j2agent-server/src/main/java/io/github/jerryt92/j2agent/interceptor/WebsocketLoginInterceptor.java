package io.github.jerryt92.j2agent.interceptor;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * Websocket全局登录拦截器
 */
@Component
public class WebsocketLoginInterceptor implements HandshakeInterceptor {

    private final ApiLoginChecker apiLoginChecker;

    public WebsocketLoginInterceptor(ApiLoginChecker apiLoginChecker) {
        this.apiLoginChecker = apiLoginChecker;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        Cookie[] cookies = servletRequest.getCookies();
        int port = servletRequest.getServerPort();
        boolean checkedLogin = apiLoginChecker.checkLogin(cookies, port);
        if (checkedLogin) {
            return true;
        } else {
            response.setStatusCode(HttpStatusCode.valueOf(HttpServletResponse.SC_UNAUTHORIZED));
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {

    }
}