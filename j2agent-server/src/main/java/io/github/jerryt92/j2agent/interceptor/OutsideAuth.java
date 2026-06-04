package io.github.jerryt92.j2agent.interceptor;

import io.github.jerryt92.j2agent.mapper.mgb.ApiKeyInfoPoMapper;
import io.github.jerryt92.j2agent.model.po.mgb.ApiKeyInfoPo;
import io.github.jerryt92.j2agent.model.po.mgb.ApiKeyInfoPoExample;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

@Component
@DependsOn("flywayInitializer")
public class OutsideAuth {
    @Getter
    private final HashSet<String> authKeys = new HashSet<>();
    private final ApiKeyInfoPoMapper apiKeyInfoPoMapper;

    public OutsideAuth(ApiKeyInfoPoMapper apiKeyInfoPoMapper) {
        this.apiKeyInfoPoMapper = apiKeyInfoPoMapper;
    }

    @PostConstruct
    public void loadKeys() {
        authKeys.clear();
        ApiKeyInfoPoExample example = new ApiKeyInfoPoExample();
        example.or().andExpireTimeGreaterThan(System.currentTimeMillis());
        example.or().andExpireTimeIsNull();
        List<ApiKeyInfoPo> apiKeyInfoPos = apiKeyInfoPoMapper.selectByExample(example);
        apiKeyInfoPos.forEach(apiKeyInfoPo -> {
            authKeys.add(apiKeyInfoPo.getApiKey());
        });
    }

}
