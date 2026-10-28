package com.campuslife.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AppConfigMapper {

    @Select("SELECT config_key AS configKey, config_value AS configValue, remark FROM app_config ORDER BY config_key")
    List<Map<String, Object>> listAll();

    @Select("SELECT config_value FROM app_config WHERE config_key = #{key}")
    String value(@Param("key") String key);

    /** 只更新已存在的 key，避免被任意写入新配置。 */
    @Update("UPDATE app_config SET config_value = #{value} WHERE config_key = #{key}")
    int update(@Param("key") String key, @Param("value") String value);
}
