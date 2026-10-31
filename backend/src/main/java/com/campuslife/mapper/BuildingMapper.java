package com.campuslife.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campuslife.entity.Building;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BuildingMapper extends BaseMapper<Building> {

    @Select("SELECT id, school_id AS schoolId, name, match_keywords AS matchKeywords, default_rider_id AS defaultRiderId, status FROM building WHERE school_id=#{schoolId} AND status=1 ORDER BY id")
    List<Building> listBySchool(@Param("schoolId") Long schoolId);

    @Select("SELECT id, school_id AS schoolId, name, match_keywords AS matchKeywords, default_rider_id AS defaultRiderId, status FROM building WHERE id=#{id}")
    Building byId(@Param("id") Long id);
}
