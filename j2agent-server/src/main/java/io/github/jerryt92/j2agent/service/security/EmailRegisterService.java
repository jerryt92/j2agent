package io.github.jerryt92.j2agent.service.security;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.constants.ErrorConstants;
import io.github.jerryt92.j2agent.model.RegisterEnabledDto;
import io.github.jerryt92.j2agent.model.security.EmailRegisterSmtpConfig;
import io.github.jerryt92.j2agent.service.PropertiesService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * 邮箱自助注册开关与 SMTP 发信。
 */
@Service
public class EmailRegisterService {

    private static final Logger log = LoggerFactory.getLogger(EmailRegisterService.class);

    /** classpath 下邮箱验证码 HTML 模板（注册 / 找回密码共用布局，文案占位符区分） */
    private static final String VERIFICATION_HTML_TEMPLATE = "static/email/email-verification.html";
    private static final String CODE_PLACEHOLDER = "{{code}}";
    private static final String HEAD_TITLE_PLACEHOLDER = "{{headTitle}}";
    private static final String HEADER_SUBTITLE_PLACEHOLDER = "{{headerSubtitle}}";
    private static final String BODY_MESSAGE_PLACEHOLDER = "{{bodyMessage}}";

    private static final EmailVerificationMailContent REGISTER_MAIL = new EmailVerificationMailContent(
            "注册验证码",
            "账号注册验证",
            "您正在注册 J2Agent AI，请使用以下验证码完成注册：",
            "J2Agent AI 注册验证码",
            "您正在注册 J2Agent AI 账号。\n\n注册验证码：",
            ErrorConstants.REGISTER_SEND_FAILED);
    private static final EmailVerificationMailContent RESET_PASSWORD_MAIL = new EmailVerificationMailContent(
            "重置密码验证码",
            "重置密码验证",
            "您正在重置 J2Agent AI 账号密码，请使用以下验证码完成操作：",
            "J2Agent AI 重置密码验证码",
            "您正在重置 J2Agent AI 账号密码。\n\n重置密码验证码：",
            ErrorConstants.RESET_PASSWORD_SEND_FAILED);

    private final PropertiesService propertiesService;

    /** 缓存的 HTML 模板正文 */
    private volatile String verificationHtmlTemplate;

    public EmailRegisterService(PropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    /**
     * 是否已开启邮箱自助注册。
     */
    public boolean isEnabled() {
        String value = propertiesService.getProperty(PropertiesService.USER_EMAIL_REGISTER_ENABLED);
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
    }

    /**
     * 对外返回注册开关状态（不含 SMTP 详情）。
     */
    public RegisterEnabledDto getEnabledStatus() {
        RegisterEnabledDto dto = new RegisterEnabledDto();
        dto.setEnabled(isEnabled());
        return dto;
    }

    /**
     * 未开启时拒绝注册相关请求。
     */
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ErrorConstants.REGISTER_DISABLED);
        }
    }

    /**
     * 是否已开启邮箱注册白名单。
     */
    public boolean isWhitelistEnabled() {
        String value = propertiesService.getProperty(PropertiesService.USER_EMAIL_REGISTER_WHITELIST_ENABLED);
        return value != null && ("true".equalsIgnoreCase(value.trim()) || "1".equals(value.trim()));
    }

    /**
     * 白名单未通过时拒绝注册相关请求。
     */
    public void requireEmailAllowed(String email) {
        if (!isWhitelistEnabled()) {
            return;
        }
        if (!isEmailAllowed(email)) {
            String customMessage = propertiesService.getProperty(
                    PropertiesService.USER_EMAIL_REGISTER_WHITELIST_DENIED_MESSAGE);
            if (StringUtils.isNotBlank(customMessage)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, customMessage.trim());
            }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ErrorConstants.REGISTER_EMAIL_NOT_ALLOWED);
        }
    }

    /**
     * 判断邮箱是否命中白名单规则。
     */
    public boolean isEmailAllowed(String email) {
        if (StringUtils.isBlank(email)) {
            return false;
        }
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        List<String> rules = parseWhitelistRules(
                propertiesService.getProperty(PropertiesService.USER_EMAIL_REGISTER_WHITELIST_RULES));
        if (rules.isEmpty()) {
            return false;
        }
        for (String rule : rules) {
            if (matchesWhitelistRule(normalized, rule)) {
                return true;
            }
        }
        return false;
    }

    private List<String> parseWhitelistRules(String rulesText) {
        List<String> rules = new ArrayList<>();
        if (StringUtils.isBlank(rulesText)) {
            return rules;
        }
        for (String part : rulesText.split(",")) {
            String rule = part.trim();
            if (!rule.isEmpty()) {
                rules.add(rule.toLowerCase(Locale.ROOT));
            }
        }
        return rules;
    }

    private boolean matchesWhitelistRule(String normalizedEmail, String rule) {
        if (rule.startsWith("*@")) {
            String domain = rule.substring(2);
            if (domain.isEmpty()) {
                return false;
            }
            return normalizedEmail.endsWith("@" + domain);
        }
        return normalizedEmail.equals(rule);
    }

    /**
     * 读取 SMTP 配置。
     */
    public EmailRegisterSmtpConfig loadSmtpConfig() {
        String json = propertiesService.getProperty(PropertiesService.USER_EMAIL_REGISTER_SMTP_JSON);
        if (StringUtils.isBlank(json)) {
            return new EmailRegisterSmtpConfig();
        }
        try {
            return JSON.parseObject(json, EmailRegisterSmtpConfig.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_SMTP_INVALID);
        }
    }

    /**
     * 发送找回密码验证码邮件。
     */
    public void sendResetPasswordMail(String toEmail, String code) {
        sendVerificationMail(toEmail, code, RESET_PASSWORD_MAIL);
    }

    /**
     * 发送注册验证码邮件（HTML 正文，验证码居中框选展示）。
     */
    public void sendVerificationMail(String toEmail, String code) {
        sendVerificationMail(toEmail, code, REGISTER_MAIL);
    }

    private void sendVerificationMail(String toEmail, String code, EmailVerificationMailContent content) {
        EmailRegisterSmtpConfig smtp = loadSmtpConfig();
        validateSmtpConfig(smtp);
        JavaMailSenderImpl sender = buildMailSender(smtp);
        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(smtp.getFrom());
            helper.setTo(toEmail);
            helper.setSubject(content.subject());
            helper.setText(buildPlainText(code, content), buildVerificationHtml(code, content));
            sender.send(message);
        } catch (Exception e) {
            log.error("failed to send verification email to {}", toEmail, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, content.sendFailedError());
        }
    }

    private String buildPlainText(String code, EmailVerificationMailContent content) {
        return content.plainTextPrefix() + code + "\n\n验证码 10 分钟内有效，请勿泄露给他人。";
    }

    /**
     * 构建验证码 HTML 邮件正文（共用模板，替换文案与验证码占位符）。
     */
    private String buildVerificationHtml(String code, EmailVerificationMailContent content) {
        return loadVerificationHtmlTemplate()
                .replace(HEAD_TITLE_PLACEHOLDER, escapeHtml(content.headTitle()))
                .replace(HEADER_SUBTITLE_PLACEHOLDER, escapeHtml(content.headerSubtitle()))
                .replace(BODY_MESSAGE_PLACEHOLDER, escapeHtml(content.bodyMessage()))
                .replace(CODE_PLACEHOLDER, escapeHtml(code));
    }

    /**
     * 加载并缓存注册验证码 HTML 模板。
     */
    private String loadVerificationHtmlTemplate() {
        String cached = verificationHtmlTemplate;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            cached = verificationHtmlTemplate;
            if (cached != null) {
                return cached;
            }
            verificationHtmlTemplate = loadHtmlTemplate(VERIFICATION_HTML_TEMPLATE);
            return verificationHtmlTemplate;
        }
    }

    private String loadHtmlTemplate(String classpath) {
        try {
            ClassPathResource resource = new ClassPathResource(classpath);
            if (!resource.exists()) {
                throw new IllegalStateException("email template not found: " + classpath);
            }
            String template = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            for (String placeholder : List.of(
                    CODE_PLACEHOLDER, HEAD_TITLE_PLACEHOLDER, HEADER_SUBTITLE_PLACEHOLDER, BODY_MESSAGE_PLACEHOLDER)) {
                if (!template.contains(placeholder)) {
                    throw new IllegalStateException("email template missing placeholder: " + placeholder);
                }
            }
            return template;
        } catch (IOException e) {
            throw new IllegalStateException("failed to load email template: " + classpath, e);
        }
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private void validateSmtpConfig(EmailRegisterSmtpConfig smtp) {
        if (StringUtils.isBlank(smtp.getHost()) || smtp.getPort() == null || StringUtils.isBlank(smtp.getFrom())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_SMTP_NOT_CONFIGURED);
        }
        if (StringUtils.isNotBlank(smtp.getUsername()) && StringUtils.isBlank(smtp.getPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ErrorConstants.REGISTER_SMTP_PASSWORD_REQUIRED);
        }
    }

    private JavaMailSenderImpl buildMailSender(EmailRegisterSmtpConfig smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.getHost());
        sender.setPort(smtp.getPort());
        if (StringUtils.isNotBlank(smtp.getUsername())) {
            sender.setUsername(smtp.getUsername());
            sender.setPassword(StringUtils.defaultString(smtp.getPassword()));
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(StringUtils.isNotBlank(smtp.getUsername())));
        boolean ssl = Boolean.TRUE.equals(smtp.getSsl());
        boolean startTls = !ssl && Boolean.TRUE.equals(smtp.getStartTls());
        props.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        if (ssl) {
            props.put("mail.smtp.ssl.trust", smtp.getHost());
        }
        return sender;
    }

    private record EmailVerificationMailContent(
            String headTitle,
            String headerSubtitle,
            String bodyMessage,
            String subject,
            String plainTextPrefix,
            String sendFailedError) {
    }
}
