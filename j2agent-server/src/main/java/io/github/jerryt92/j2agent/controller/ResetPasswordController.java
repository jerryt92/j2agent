package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.ResetPasswordRequestDto;
import io.github.jerryt92.j2agent.model.ResetPasswordSendCodeRequestDto;
import io.github.jerryt92.j2agent.model.po.mgb.UserPo;
import io.github.jerryt92.j2agent.server.api.ResetPasswordApi;
import io.github.jerryt92.j2agent.service.security.CaptchaService;
import io.github.jerryt92.j2agent.service.security.EmailRegisterService;
import io.github.jerryt92.j2agent.service.security.EmailVerificationService;
import io.github.jerryt92.j2agent.service.security.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;

/**
 * 邮箱找回密码公开接口（auth 路径，无需登录）。
 */
@RestController
public class ResetPasswordController implements ResetPasswordApi {

    private final EmailRegisterService emailRegisterService;
    private final EmailVerificationService emailVerificationService;
    private final CaptchaService captchaService;
    private final UserService userService;

    public ResetPasswordController(EmailRegisterService emailRegisterService,
                                 EmailVerificationService emailVerificationService,
                                 CaptchaService captchaService,
                                 UserService userService) {
        this.emailRegisterService = emailRegisterService;
        this.emailVerificationService = emailVerificationService;
        this.captchaService = captchaService;
        this.userService = userService;
    }

    @Override
    public ResponseEntity<Void> sendResetPasswordCode(ResetPasswordSendCodeRequestDto request) {
        if (request == null || request.getEmail() == null) {
            return ResponseEntity.badRequest().build();
        }
        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);
        if (!captchaService.verifyCaptchaCode(request.getValidateCode(), request.getHash())) {
            return ResponseEntity.status(401).build();
        }
        UserPo user = userService.findByEmail(email);
        if (user == null) {
            return ResponseEntity.ok().build();
        }
        String code = emailVerificationService.issueResetCode(email);
        emailRegisterService.sendResetPasswordMail(email, code);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> resetPasswordByEmail(ResetPasswordRequestDto request) {
        userService.resetPasswordByEmail(request);
        return ResponseEntity.ok().build();
    }
}
