package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.RegisterEnabledDto;
import io.github.jerryt92.j2agent.model.RegisterRequestDto;
import io.github.jerryt92.j2agent.model.RegisterSendCodeRequestDto;
import io.github.jerryt92.j2agent.server.api.RegisterApi;
import io.github.jerryt92.j2agent.service.security.CaptchaService;
import io.github.jerryt92.j2agent.service.security.EmailRegisterService;
import io.github.jerryt92.j2agent.service.security.EmailVerificationService;
import io.github.jerryt92.j2agent.service.security.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮箱自助注册公开接口（auth 路径，无需登录）。
 */
@RestController
public class RegisterController implements RegisterApi {

    private final EmailRegisterService emailRegisterService;
    private final EmailVerificationService emailVerificationService;
    private final CaptchaService captchaService;
    private final UserService userService;

    public RegisterController(EmailRegisterService emailRegisterService,
                              EmailVerificationService emailVerificationService,
                              CaptchaService captchaService,
                              UserService userService) {
        this.emailRegisterService = emailRegisterService;
        this.emailVerificationService = emailVerificationService;
        this.captchaService = captchaService;
        this.userService = userService;
    }

    @Override
    public ResponseEntity<RegisterEnabledDto> getRegisterEnabled() {
        return ResponseEntity.ok(emailRegisterService.getEnabledStatus());
    }

    @Override
    public ResponseEntity<Void> sendRegisterCode(RegisterSendCodeRequestDto request) {
        emailRegisterService.requireEnabled();
        if (request == null || request.getEmail() == null) {
            return ResponseEntity.badRequest().build();
        }
        String email = request.getEmail().trim();
        emailRegisterService.requireEmailAllowed(email);
        if (!captchaService.verifyCaptchaCode(request.getValidateCode(), request.getHash())) {
            return ResponseEntity.status(401).build();
        }
        String code = emailVerificationService.issueCode(email);
        emailRegisterService.sendVerificationMail(email, code);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> registerByEmail(RegisterRequestDto request) {
        emailRegisterService.requireEnabled();
        if (request != null && request.getEmail() != null) {
            emailRegisterService.requireEmailAllowed(request.getEmail().trim());
        }
        userService.registerByEmail(request);
        return ResponseEntity.ok().build();
    }
}
