package io.github.jerryt92.j2agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

/**
 * API 错误文案固定使用简体中文（不跟随浏览器 Accept-Language，避免中文界面仍返回英文报错）。
 */
@Configuration
public class LocaleConfig implements WebMvcConfigurer {

    @Bean
    public LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE);
    }
}
