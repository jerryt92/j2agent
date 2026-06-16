package io.github.jerryt92.j2agent.interceptor;

import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.service.security.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Component
public class WebsocketLoginInterceptor implements HandshakeInterceptor {

    private final LoginService loginService;

    public WebsocketLoginInterceptor(LoginService loginService) {
        this.loginService = loginService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        if (loginService.resolveRequest(servletRequest)) {
            UserContextBo userContextBo = loginService.getSession();
            if (userContextBo != null) {
                attributes.put(LoginService.LOGIN_ATTRIBUTE, userContextBo);
            }
            loginService.clearSession();
            return userContextBo != null;
        }
        response.setStatusCode(HttpStatusCode.valueOf(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED));
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
    }
}
