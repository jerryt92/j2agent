package io.github.jerryt92.j2agent.interceptor;

import io.github.jerryt92.j2agent.config.security.RequiredRole;
import io.github.jerryt92.j2agent.model.security.SessionBo;
import io.github.jerryt92.j2agent.service.security.LoginService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 全局登录拦截器
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    private final ApiLoginChecker apiLoginChecker;
    private final LoginService loginService;

    public LoginInterceptor(ApiLoginChecker apiLoginChecker, LoginService loginService) {
        this.apiLoginChecker = apiLoginChecker;
        this.loginService = loginService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Cookie[] cookies = request.getCookies();
        int port = request.getServerPort();
        boolean checkedLogin = apiLoginChecker.checkLogin(cookies, port);
        if (checkedLogin) {
            if (!checkRole(handler)) {
                loginService.clearSession();
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return false;
            }
            return true;
        } else {
            loginService.clearSession();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    /**
     * 读取接口角色要求，默认登录用户即可访问。
     */
    private boolean checkRole(Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequiredRole requiredRole = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RequiredRole.class);
        if (requiredRole == null) {
            requiredRole = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiredRole.class);
        }
        int role = requiredRole == null ? RequiredRole.USER : requiredRole.value();
        SessionBo session = loginService.getSession();
        return session != null && session.hasAccess(role);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        loginService.clearSession();
    }
}