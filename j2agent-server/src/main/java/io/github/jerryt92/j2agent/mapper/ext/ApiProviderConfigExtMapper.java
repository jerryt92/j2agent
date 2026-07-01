package io.github.jerryt92.j2agent.mapper.ext;

import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * api_provider_config 领域查询与「设为当前」操作（勿写入 mgb 包，避免 MBG 覆盖）。
 */
@Mapper
public interface ApiProviderConfigExtMapper {

    @Select("""
            select id,
                   api_type as apiType,
                   config_name as configName,
                   provider_type as providerType,
                   config_json as configJson,
                   enabled,
                   is_current as isCurrent,
                   description,
                   create_time as createTime,
                   update_time as updateTime
            from api_provider_config
            where api_type = #{apiType}
            order by id asc
            """)
    List<ApiProviderConfigPo> selectByApiType(@Param("apiType") String apiType);

    @Select("""
            select id,
                   api_type as apiType,
                   config_name as configName,
                   provider_type as providerType,
                   config_json as configJson,
                   enabled,
                   is_current as isCurrent,
                   description,
                   create_time as createTime,
                   update_time as updateTime
            from api_provider_config
            where api_type = #{apiType}
              and is_current = 1
              and enabled = 1
            order by id asc
            limit 1
            """)
    ApiProviderConfigPo selectCurrentByApiType(@Param("apiType") String apiType);

    @Update("""
            update api_provider_config
            set is_current = 0
            where api_type = #{apiType}
              and is_current = 1
            """)
    int clearCurrentByApiType(@Param("apiType") String apiType);

    @Update("""
            update api_provider_config
            set is_current = 1,
                enabled = 1,
                update_time = #{updateTime}
            where id = #{id}
            """)
    int markCurrent(@Param("id") Long id, @Param("updateTime") Long updateTime);
}
