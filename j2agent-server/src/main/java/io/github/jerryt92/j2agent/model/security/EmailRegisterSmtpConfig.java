package io.github.jerryt92.j2agent.model.security;

import lombok.Data;

/**
 * 邮箱注册 SMTP 配置。
 */
@Data
public class EmailRegisterSmtpConfig {
    private String host;
    private Integer port = 587;
    private String username;
    private String password;
    private String from;
    private Boolean ssl = false;
    private Boolean startTls = true;
}
