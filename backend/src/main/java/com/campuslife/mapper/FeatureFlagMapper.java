package com.campuslife.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface FeatureFlagMapper {

    /** 管理端列表：带修改信息，便于追溯是谁在什么时候关的。 */
    @Select("""
        SELECT flag_key AS flagKey, enabled, remark, updated_by AS updatedBy, updated_at AS updatedAt
        FROM feature_flag ORDER BY flag_key
        """)
    List<Map<String, Object>> listAll();

    @Update("""
        UPDATE feature_flag SET enabled = #{enabled}, remark = #{remark}, updated_by = #{operator}
        WHERE flag_key = #{key}
        """)
    int update(@Param("key") String key, @Param("enabled") int enabled,
               @Param("remark") String remark, @Param("operator") String operator);
}
