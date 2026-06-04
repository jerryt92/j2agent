package io.github.jerryt92.j2agent.interceptor;

import com.alibaba.fastjson2.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.PrintWriter;
import java.util.Map;
import java.util.Objects;

/**
 * Websocket全局登录拦截器
 */
@Slf4j
@Component
public class OutsideWebsocketAuthInterceptor implements HandshakeInterceptor {

    private final OutsideAuth outsideAuth;

    public OutsideWebsocketAuthInterceptor(OutsideAuth outsideAuth) {
        this.outsideAuth = outsideAuth;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String key = getParam("key", Objects.requireNonNull(request.getURI()).toString());
        if (key == null || key.isEmpty() || !outsideAuth.getAuthKeys().contains(key)) {
            if (response instanceof ServletServerHttpResponse) {
                HttpServletResponse servletResponse = ((ServletServerHttpResponse) response).getServletResponse();
                try (PrintWriter writer = servletResponse.getWriter()) {
                    servletResponse.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    writer.print(
                            JSONObject.of(
                                    "timestamp", System.currentTimeMillis(),
                                    "status", HttpServletResponse.SC_FORBIDDEN,
                                    "error", "Invalid API key",
                                    "path", request.getURI()
                            )
                    );
                    writer.flush();
                } catch (Throwable t) {
                    log.error("", t);
                }
            }
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {

    }

    private static String getParam(String param, String url) {
        if (url != null) {
            // 找到查询参数部分（?后面的部分）
            int queryStart = url.indexOf('?');
            if (queryStart != -1 && queryStart < url.length() - 1) {
                String queryString = url.substring(queryStart + 1);
                String[] params = queryString.split("&");
                for (String p : params) {
                    String[] keyValue = p.split("=", 2); // 限制分割成2部分
                    if (keyValue.length >= 1 && keyValue[0].equals(param)) {
                        return keyValue.length >= 2 ? keyValue[1] : null;
                    }
                }
            }
        }
        return null;
    }
}