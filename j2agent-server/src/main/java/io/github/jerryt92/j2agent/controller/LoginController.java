package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.LoginRequestDto;
import io.github.jerryt92.j2agent.model.PowCaptchaResp;
import io.github.jerryt92.j2agent.model.SlideCaptchaResp;
import io.github.jerryt92.j2agent.model.Track;
import io.github.jerryt92.j2agent.model.ValidateCaptchaDto;
import io.github.jerryt92.j2agent.model.ValidatePowCaptchaDto;
import io.github.jerryt92.j2agent.model.VerifySlideCaptcha200Response;
import io.github.jerryt92.j2agent.model.security.SessionBo;
import io.github.jerryt92.j2agent.server.api.LoginApi;
import io.github.jerryt92.j2agent.service.security.CaptchaService;
import io.github.jerryt92.j2agent.service.security.LoginService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController implements LoginApi {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final LoginService loginService;
    private final CaptchaService captchaService;

    public LoginController(HttpServletRequest request, HttpServletResponse response, LoginService loginService, CaptchaService captchaService) {
        this.request = request;
        this.response = response;
        this.loginService = loginService;
        this.captchaService = captchaService;
    }

    @Override
    public ResponseEntity<Void> login(LoginRequestDto loginRequestDto) {
        SessionBo sessionBo = loginService.login(loginRequestDto.getUsername(), loginRequestDto.getPassword(), loginRequestDto.getValidateCode(), loginRequestDto.getHash());
        if (sessionBo != null) {
            int port = request.getServerPort();
            Cookie cookie = new Cookie("SESSION-" + port, sessionBo.getSessionId());
            cookie.setHttpOnly(true); // 阻止JavaScript 访问 Cookie
            cookie.setPath("/");      // 根据实际需求设置 path
            response.addCookie(cookie);
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok().build();
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
        // 将 List<Track> 转换为 Track[]
        Track[] trackArray = validateCaptchaDto.getTrack().toArray(new Track[0]);
        // 调用服务方法
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
        Cookie[] cookies = request.getCookies();
        int port = request.getServerPort();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("SESSION-" + port)) {
                    if (cookie.getValue() != null) {
                        loginService.logout(cookie.getValue());
                    }
                }
            }
        }
        return ResponseEntity.ok().build();
    }
}
