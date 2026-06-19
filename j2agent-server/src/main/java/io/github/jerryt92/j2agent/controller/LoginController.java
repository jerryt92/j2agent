package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.AuthResultDto;
import io.github.jerryt92.j2agent.model.LoginRequestDto;
import io.github.jerryt92.j2agent.model.PowCaptchaResp;
import io.github.jerryt92.j2agent.model.SlideCaptchaResp;
import io.github.jerryt92.j2agent.model.Track;
import io.github.jerryt92.j2agent.model.ValidateCaptchaDto;
import io.github.jerryt92.j2agent.model.ValidatePowCaptchaDto;
import io.github.jerryt92.j2agent.model.VerifySlideCaptcha200Response;
import io.github.jerryt92.j2agent.server.api.LoginApi;
import io.github.jerryt92.j2agent.service.security.CaptchaService;
import io.github.jerryt92.j2agent.service.security.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController implements LoginApi {
    private final LoginService loginService;
    private final CaptchaService captchaService;
    private final HttpServletRequest request;

    public LoginController(LoginService loginService, CaptchaService captchaService, HttpServletRequest request) {
        this.loginService = loginService;
        this.captchaService = captchaService;
        this.request = request;
    }

    @Override
    public ResponseEntity<AuthResultDto> login(LoginRequestDto loginRequestDto) {
        AuthResultDto authResult = loginService.login(
                loginRequestDto.getUsername(),
                loginRequestDto.getPassword(),
                loginRequestDto.getValidateCode(),
                loginRequestDto.getHash());
        if (authResult == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(authResult);
    }

    @Override
    public ResponseEntity<SlideCaptchaResp> getSlideCaptcha() {
        return ResponseEntity.ok(captchaService.genSlideCaptcha());
    }

    @Override
    public ResponseEntity<VerifySlideCaptcha200Response> verifySlideCaptcha(ValidateCaptchaDto validateCaptchaDto) {
        VerifySlideCaptcha200Response response = new VerifySlideCaptcha200Response();
        if (validateCaptchaDto == null || validateCaptchaDto.getTrack() == null) {
            response.setResult(false);
            return ResponseEntity.ok(response);
        }
        Float sliderX = validateCaptchaDto.getSliderX();
        String hash = validateCaptchaDto.getHash();
        Track[] trackArray = validateCaptchaDto.getTrack().toArray(new Track[0]);
        String code = captchaService.verifySlideCaptchaGetCaptchaCode(sliderX, hash, trackArray);
        if (code != null) {
            response.setResult(true);
            response.setCode(code);
        } else {
            response.setResult(false);
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<PowCaptchaResp> getPowCaptcha() {
        return ResponseEntity.ok(captchaService.genPowCaptcha());
    }

    @Override
    public ResponseEntity<VerifySlideCaptcha200Response> verifyPowCaptcha(ValidatePowCaptchaDto validatePowCaptchaDto) {
        VerifySlideCaptcha200Response response = new VerifySlideCaptcha200Response();
        if (validatePowCaptchaDto == null) {
            response.setResult(false);
            return ResponseEntity.ok(response);
        }
        String hash = validatePowCaptchaDto.getHash();
        String powNonce = validatePowCaptchaDto.getPowNonce();
        String code = captchaService.verifyPowCaptchaGetCaptchaCode(hash, powNonce);
        if (code != null) {
            response.setResult(true);
            response.setCode(code);
        } else {
            response.setResult(false);
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> logout() {
        loginService.logout(request);
        return ResponseEntity.ok().build();
    }
}
