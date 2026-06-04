package io.github.jerryt92.j2agent.service;

import io.github.jerryt92.j2agent.event.PropertiesUpdatedEvent;
import io.github.jerryt92.j2agent.mapper.mgb.PropertiesPoMapper;
import io.github.jerryt92.j2agent.model.PropertyDto;
import io.github.jerryt92.j2agent.model.po.mgb.PropertiesPo;
import io.github.jerryt92.j2agent.model.po.mgb.PropertiesPoExample;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@DependsOn("flywayInitializer")
public class PropertiesService {
    public static final String RETRIEVE_TOP_K = "RETRIEVE_TOP_K";
    public static final String RETRIEVE_METRIC_TYPE = "RETRIEVE_METRIC_TYPE";
    // score比较表达式
    public static final String RETRIEVE_METRIC_SCORE_COMPARE_EXPR = "RETRIEVE_METRIC_SCORE_COMPARE_EXPR";
    public static final String RETRIEVE_DENSE_WEIGHT = "RETRIEVE_DENSE_WEIGHT";
    public static final String RETRIEVE_SPARSE_WEIGHT = "RETRIEVE_SPARSE_WEIGHT";
    /** 是否允许邮箱自助注册 */
    public static final String USER_EMAIL_REGISTER_ENABLED = "user-email-register-enabled";
    /** 邮箱注册 SMTP 配置 JSON */
    public static final String USER_EMAIL_REGISTER_SMTP_JSON = "user-email-register-smtp-json";
    /** 是否启用邮箱注册白名单 */
    public static final String USER_EMAIL_REGISTER_WHITELIST_ENABLED = "user-email-register-whitelist-enabled";
    /** 邮箱注册白名单规则，逗号分隔 */
    public static final String USER_EMAIL_REGISTER_WHITELIST_RULES = "user-email-register-whitelist-rules";
    /** 邮箱不在白名单时的自定义拒绝提示 */
    public static final String USER_EMAIL_REGISTER_WHITELIST_DENIED_MESSAGE = "user-email-register-whitelist-denied-message";
    /** NMS 网管连接配置 JSON */
    public static final String NMS_CONFIG_JSON = "nms-config-json";

    private final Map<String, String> properties = new HashMap<>();
    private final PropertiesPoMapper propertiesPoMapper;
    private final SqlSessionFactory sqlSessionFactory;
    private final ApplicationEventPublisher eventPublisher;

    public PropertiesService(PropertiesPoMapper propertiesPoMapper,
                             SqlSessionFactory sqlSessionFactory,
                             ApplicationEventPublisher eventPublisher) {
        this.propertiesPoMapper = propertiesPoMapper;
        this.sqlSessionFactory = sqlSessionFactory;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void init() {
        List<PropertiesPo> propertiesPos = propertiesPoMapper.selectByExample(new PropertiesPoExample());
        for (PropertiesPo propertiesPo : propertiesPos) {
            properties.put(propertiesPo.getPropertyName(), propertiesPo.getPropertyValue());
        }
    }

    public String getProperty(String propertyName) {
        String propertyValue = properties.get(propertyName);
        if (propertyValue == null) {
            PropertiesPo propertiesPo = propertiesPoMapper.selectByPrimaryKey(propertyName);
            if (propertiesPo != null) {
                propertyValue = propertiesPo.getPropertyValue();
                properties.put(propertyName, propertyValue);
            }
        }
        return propertyValue;
    }

    public Map<String, String> getProperties(Collection<String> propertyNames) {
        Map<String, String> result = new HashMap<>();
        if (CollectionUtils.isEmpty(propertyNames)) {
            return result;
        }
        for (String propertyName : propertyNames) {
            String propertyValue = getProperty(propertyName);
            result.put(propertyName, propertyValue);
        }
        return result;
    }

    public void putProperty(List<PropertyDto> propertyDtoList) {
        try (SqlSession sqlSession = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            PropertiesPoMapper batchPropertiesPoMapper = sqlSession.getMapper(PropertiesPoMapper.class);
            for (PropertyDto propertyDto : propertyDtoList) {
                PropertiesPo propertiesPo = new PropertiesPo();
                propertiesPo.setPropertyName(propertyDto.getPropertyName());
                propertiesPo.setPropertyValue(propertyDto.getPropertyValue());
                int updated = batchPropertiesPoMapper.updateByPrimaryKeySelective(propertiesPo);
                if (updated == 0) {
                    batchPropertiesPoMapper.insertSelective(propertiesPo);
                }
                properties.put(propertyDto.getPropertyName(), propertyDto.getPropertyValue());
            }
            sqlSession.commit();
        }
        if (!CollectionUtils.isEmpty(propertyDtoList)) {
            List<String> propertyNames = propertyDtoList.stream()
                    .map(PropertyDto::getPropertyName)
                    .toList();
            eventPublisher.publishEvent(new PropertiesUpdatedEvent(propertyNames));
        }
    }
}
