package io.github.jerryt92.j2agent.i18n;

import io.github.jerryt92.j2agent.config.i18n.ErrorI18n;
import org.springframework.stereotype.Service;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/**
 * 使用 resources/j2agent-i18n.properties 解析 API 错误文案（固定简体中文）。
 */
@Service
public class ApiMessageService {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]*");
    private static final String MSG_SUFFIX = ".msg";
    /** 使用默认 bundle 中的中文文案，避免受 JVM 默认语言影响 */
    private static final Locale API_ERROR_LOCALE = Locale.SIMPLIFIED_CHINESE;

    /**
     * 将错误码解析为中文文案；非错误码时返回原文。
     */
    public String resolve(String codeOrText) {
        return resolve(codeOrText, null);
    }

    /**
     * 带占位参数解析错误文案。
     */
    public String resolve(String codeOrText, Object[] args) {
        if (codeOrText == null || !isErrorCode(codeOrText)) {
            return codeOrText;
        }
        String key = codeOrText + MSG_SUFFIX;
        try {
            ResourceBundle bundle = ResourceBundle.getBundle(ErrorI18n.BASENAME, API_ERROR_LOCALE);
            String pattern = bundle.getString(key);
            if (args == null || args.length == 0) {
                return pattern;
            }
            return MessageFormat.format(pattern, args);
        } catch (MissingResourceException ex) {
            return codeOrText;
        }
    }

    private boolean isErrorCode(String text) {
        return ERROR_CODE.matcher(text).matches();
    }
}
