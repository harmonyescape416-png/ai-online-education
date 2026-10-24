package com.kzai.learning.points;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
interface PointRuleMapper extends BaseMapper<PointRule> {
}

@Mapper
interface PointAccountMapper extends BaseMapper<PointAccount> {
    @Select("SELECT user_id, total_points, update_time FROM point_account WHERE user_id = #{userId} FOR UPDATE")
    PointAccount lockById(@Param("userId") long userId);
}

@Mapper
interface PointLedgerMapper extends BaseMapper<PointLedger> {
}

@Mapper
interface PointPendingAwardMapper extends BaseMapper<PointPendingAward> {
}

@Mapper
interface CheckinMapper extends BaseMapper<CheckinRecord> {
}

@Mapper
interface RankPeriodMapper extends BaseMapper<RankPeriod> {
}

@Mapper
interface RankEntryMapper extends BaseMapper<RankEntry> {
}
