package io.github.jerryt92.j2agent.config.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent;
import io.github.jerryt92.j2agent.mapper.ext.ApiProviderConfigExtMapper;
import io.github.jerryt92.j2agent.mapper.mgb.ApiProviderConfigPoMapper;
import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiProviderConfigServiceCopyTest {

    private static final String COPY_SUFFIX = " (副本)";

    @Mock
    private ApiProviderConfigPoMapper mapper;
    @Mock
    private ApiProviderConfigExtMapper extMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ApiProviderConfigService service;

    @BeforeEach
    void setUp() {
        service = new ApiProviderConfigService(mapper, extMapper, objectMapper, eventPublisher);
    }

    @Test
    void deriveCopyName_appendsSuffix() {
        assertEquals("my-config" + COPY_SUFFIX, ApiProviderConfigService.deriveCopyName("my-config"));
    }

    @Test
    void deriveCopyName_truncatesWhenExceedsMaxLength() {
        String original = "x".repeat(128);
        String result = ApiProviderConfigService.deriveCopyName(original);
        assertEquals(128, result.length());
        assertTrue(result.endsWith(COPY_SUFFIX));
    }

    @Test
    void copy_createsEnabledNonCurrentConfigWithFullApiKey() throws Exception {
        ApiProviderConfigPo source = new ApiProviderConfigPo();
        source.setId("source-id");
        source.setApiType(ProviderTypes.API_TYPE_LLM);
        source.setConfigName("prod-openai");
        source.setProviderType(ProviderTypes.LLM_OPEN_AI);
        source.setConfigJson(objectMapper.writeValueAsString(Map.of(
                "modelName", "gpt-4o",
                "apiKey", "sk-secret-key-1234",
                "baseUrl", "https://api.openai.com")));
        source.setEnabled((short) 1);
        source.setIsCurrent((short) 1);
        source.setDescription("primary");

        when(mapper.selectByPrimaryKey("source-id")).thenReturn(source);
        when(mapper.insert(any(ApiProviderConfigPo.class))).thenAnswer(invocation -> {
            return 1;
        });

        ApiProviderConfigService.ProviderConfigView view = service.copy("source-id");

        assertEquals("prod-openai" + COPY_SUFFIX, view.configName());
        assertTrue(view.enabled());
        assertFalse(view.isCurrent());
        assertEquals("gpt-4o", view.config().get("modelName"));
        assertEquals("****1234", view.config().get("apiKey"));

        ArgumentCaptor<ApiProviderConfigPo> insertedCaptor = ArgumentCaptor.forClass(ApiProviderConfigPo.class);
        verify(mapper).insert(insertedCaptor.capture());
        ApiProviderConfigPo inserted = insertedCaptor.getValue();
        assertEquals((short) 1, inserted.getEnabled());
        assertEquals((short) 0, inserted.getIsCurrent());
        assertTrue(inserted.getId() != null && !inserted.getId().isBlank());

        @SuppressWarnings("unchecked")
        Map<String, Object> storedConfig = objectMapper.readValue(inserted.getConfigJson(), Map.class);
        assertEquals("sk-secret-key-1234", storedConfig.get("apiKey"));

        ArgumentCaptor<ProviderConfigChangedEvent> eventCaptor =
                ArgumentCaptor.forClass(ProviderConfigChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertEquals(ProviderTypes.API_TYPE_LLM, eventCaptor.getValue().getApiType());
        assertFalse(eventCaptor.getValue().isActiveSwitched());
        assertFalse(eventCaptor.getValue().isEmbeddingRuntimeChanged());
    }

    @Test
    void copy_whenSourceMissing_throws() {
        when(mapper.selectByPrimaryKey("missing-id")).thenReturn(null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.copy("missing-id"));
        assertTrue(ex.getMessage().contains("missing-id"));
    }
}
