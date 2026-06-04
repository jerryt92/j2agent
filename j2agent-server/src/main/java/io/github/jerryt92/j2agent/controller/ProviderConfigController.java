package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.annotation.RequiredRole;
import io.github.jerryt92.j2agent.model.ProviderConfigDto;
import io.github.jerryt92.j2agent.model.ProviderConfigUpsertDto;
import io.github.jerryt92.j2agent.server.api.ProviderConfigApi;
import io.github.jerryt92.j2agent.service.providerconfig.ApiProviderConfigService;
import io.github.jerryt92.j2agent.service.providerconfig.ApiProviderConfigService.ProviderConfigView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型提供商配置（LLM/Embedding）REST 端点。
 */
@RestController
@RequiredRole(RequiredRole.ADMIN)
public class ProviderConfigController implements ProviderConfigApi {

    private final ApiProviderConfigService service;

    public ProviderConfigController(ApiProviderConfigService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<ProviderConfigDto>> listProviderConfigs(String apiType) {
        List<ProviderConfigDto> dtos = service.list(apiType).stream()
                .map(ProviderConfigController::toDto)
                .toList();
        return ResponseEntity.ok(dtos);
    }

    @Override
    public ResponseEntity<ProviderConfigDto> createProviderConfig(ProviderConfigUpsertDto body) {
        String apiType = body.getApiType() == null ? null : body.getApiType().getValue();
        boolean enabled = body.getEnabled() == null || body.getEnabled();
        boolean makeCurrent = body.getMakeCurrent() != null && body.getMakeCurrent();
        ProviderConfigView view = service.create(
                apiType,
                body.getConfigName(),
                body.getProviderType(),
                body.getConfig(),
                body.getDescription(),
                enabled,
                makeCurrent);
        return ResponseEntity.ok(toDto(view));
    }

    @Override
    public ResponseEntity<ProviderConfigDto> updateProviderConfig(Long id, ProviderConfigUpsertDto body) {
        boolean enabled = body.getEnabled() == null || body.getEnabled();
        ProviderConfigView view = service.update(
                id,
                body.getConfigName(),
                body.getProviderType(),
                body.getConfig(),
                body.getDescription(),
                enabled);
        return ResponseEntity.ok(toDto(view));
    }

    @Override
    public ResponseEntity<Void> deleteProviderConfig(Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<ProviderConfigDto> activateProviderConfig(Long id) {
        return ResponseEntity.ok(toDto(service.activate(id)));
    }

    /**
     * 服务层视图转 DTO；apiKey 已在服务层脱敏。
     */
    private static ProviderConfigDto toDto(ProviderConfigView view) {
        ProviderConfigDto dto = new ProviderConfigDto();
        dto.setId(view.id());
        dto.setApiType(ProviderConfigDto.ApiTypeEnum.fromValue(view.apiType()));
        dto.setConfigName(view.configName());
        dto.setProviderType(view.providerType());
        dto.setConfig(view.config());
        dto.setEnabled(view.enabled());
        dto.setIsCurrent(view.isCurrent());
        dto.setDescription(view.description());
        dto.setCreateTime(view.createTime());
        dto.setUpdateTime(view.updateTime());
        return dto;
    }
}
