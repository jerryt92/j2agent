package io.github.jerryt92.j2agent.config.web;

import io.github.jerryt92.j2agent.interceptor.LoginInterceptor;
import io.github.jerryt92.j2agent.interceptor.OutsideAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class InterceptorConfig implements WebMvcConfigurer {

    private final OutsideAuthInterceptor outsideAuthInterceptor;
    private final LoginInterceptor loginInterceptor;

    public InterceptorConfig(OutsideAuthInterceptor outsideAuthInterceptor, LoginInterceptor loginInterceptor) {
        this.outsideAuthInterceptor = outsideAuthInterceptor;
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(outsideAuthInterceptor)
                .addPathPatterns("/v*/**")
                .excludePathPatterns("/v*/**");
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/v*/**")
                .excludePathPatterns("/v*/auth/**");
    }
}