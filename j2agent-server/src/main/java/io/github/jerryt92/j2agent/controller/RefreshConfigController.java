package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.interceptor.OutsideAuth;
import io.github.jerryt92.j2agent.server.api.RefreshConfigApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefreshConfigController implements RefreshConfigApi {
    private final OutsideAuth outsideAuth;

    public RefreshConfigController(OutsideAuth outsideAuth) {
        this.outsideAuth = outsideAuth;
    }

    @Override
    public ResponseEntity<Object> refreshConfig() {
        Thread.startVirtualThread(outsideAuth::loadKeys);
        Thread.startVirtualThread(() -> {

        });
        return ResponseEntity.ok().build();
    }
}
