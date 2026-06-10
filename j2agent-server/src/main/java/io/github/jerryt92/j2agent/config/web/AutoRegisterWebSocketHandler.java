package io.github.jerryt92.j2agent.config.web;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自动注册websocket handler
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface AutoRegisterWebSocketHandler {
    String[] path();

    String[] allowedOrigin() default "";

    String[] interceptorsClassNames() default "";
}
