package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.SessionInfoDto;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import io.github.jerryt92.j2agent.server.api.SessionApi;
import io.github.jerryt92.j2agent.service.security.LoginService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController implements SessionApi {
    private final LoginService loginService;

    public SessionController(LoginService loginService) {
        this.loginService = loginService;
    }

    public ResponseEntity<SessionInfoDto> getSessionInfo() {
        UserContextBo session = loginService.getSession();
        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        SessionInfoDto dto = new SessionInfoDto();
        dto.setUserId(session.getUserId());
        dto.setUsername(session.getUsername());
        dto.setRole(session.getRole().getValue());
        return ResponseEntity.ok(dto);
    }
}
