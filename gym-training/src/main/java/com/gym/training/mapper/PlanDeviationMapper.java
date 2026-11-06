package com.gym.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.training.entity.PlanDeviation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface PlanDeviationMapper extends BaseMapper<PlanDeviation> {

    @Select("""
            SELECT * FROM plan_deviation
            WHERE user_id = #{userId}
              AND create_time >= #{from}
            ORDER BY create_time DESC
            """)
    List<PlanDeviation> selectRecent(@Param("userId") Long userId, @Param("from") LocalDateTime from);
}
