package io.github.jerryt92.j2agent.interceptor;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * 全局认证拦截器，用于验证API密钥
 */
@Component
public class OutsideAuthInterceptor implements HandlerInterceptor {

    private final OutsideAuth outsideAuth;

    public OutsideAuthInterceptor(OutsideAuth outsideAuth) {
        this.outsideAuth = outsideAuth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String key = request.getHeader("x-api-key");
        if (Objects.nonNull(request.getRequestURI()) && request.getRequestURI().startsWith(CommonConstants.FILE_URL)) {
            return true;
        }
        if (key == null || key.isEmpty() || !outsideAuth.getAuthKeys().contains(key)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            PrintWriter writer = response.getWriter();
            writer.print(
                    JSONObject.of(
                            "timestamp", System.currentTimeMillis(),
                            "status", HttpServletResponse.SC_FORBIDDEN,
                            "error", "Invalid API key",
                            "path", request.getRequestURI()
                    )
            );
            writer.flush();
            return false;
        }
        return true;
    }
}