package io.github.jerryt92.j2agent.mapper.mgb;

import io.github.jerryt92.j2agent.model.po.mgb.ApiProviderConfigPo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * api_provider_config 表的访问层，覆盖 LLM/Embedding 等提供商配置的 CRUD 与「设为当前」所需操作。
 */
@Mapper
public interface ApiProviderConfigPoMapper {

    /** 主键查询 */
    ApiProviderConfigPo selectByPrimaryKey(@Param("id") Long id);

    /** 按 api_type 查询全部，按 id 升序返回 */
    List<ApiProviderConfigPo> selectByApiType(@Param("apiType") String apiType);

    /** 同一 api_type 下按名称查询，用于唯一性校验 */
    ApiProviderConfigPo selectByApiTypeAndName(@Param("apiType") String apiType,
                                               @Param("configName") String configName);

    /** 取该 api_type 下当前生效（is_current=1 且 enabled=1）的配置 */
    ApiProviderConfigPo selectCurrentByApiType(@Param("apiType") String apiType);

    /** 插入；id 通过 useGeneratedKeys 回填 */
    int insert(ApiProviderConfigPo record);

    /** 按主键全字段更新 */
    int updateByPrimaryKey(ApiProviderConfigPo record);

    /** 按主键删除 */
    int deleteByPrimaryKey(@Param("id") Long id);

    /** 将该 api_type 下所有记录的 is_current 置为 0 */
    int clearCurrentByApiType(@Param("apiType") String apiType);

    /** 将指定 id 设置为当前生效（不修改其它字段） */
    int markCurrent(@Param("id") Long id, @Param("updateTime") Long updateTime);
}
