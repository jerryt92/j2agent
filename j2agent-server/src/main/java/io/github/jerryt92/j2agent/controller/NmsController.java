package io.github.jerryt92.j2agent.controller;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.annotation.RequiredRole;
import io.github.jerryt92.j2agent.server.api.NmsApi;
import io.github.jerryt92.j2agent.service.NmsConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredRole(RequiredRole.ADMIN)
public class NmsController implements NmsApi {

    private final NmsConfigService nmsConfigService;

    public NmsController(NmsConfigService nmsConfigService) {
        this.nmsConfigService = nmsConfigService;
    }

    @Override
    public ResponseEntity<Object> getNmsConfig() {
        return ResponseEntity.ok(nmsConfigService.getConfig());
    }

    @Override
    public ResponseEntity<Void> updateNmsConfig(Object body) {
        if (body == null) {
            return ResponseEntity.badRequest().build();
        }
        JSONObject config;
        if (body instanceof JSONObject jsonObject) {
            config = jsonObject;
        } else if (body instanceof Map<?, ?> mapBody) {
            config = new JSONObject(mapBody);
        } else {
            config = JSONObject.parseObject(JSONObject.toJSONString(body));
        }
        nmsConfigService.updateConfig(config);
        return ResponseEntity.ok().build();
    }
}
