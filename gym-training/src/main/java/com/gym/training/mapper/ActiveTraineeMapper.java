package com.gym.training.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/** 近一段有训练记录的用户——教练提醒任务的扫描范围 */
public interface ActiveTraineeMapper {

    @Select("""
            SELECT DISTINCT user_id FROM workout_session
            WHERE deleted = 0 AND COALESCE(is_demo, 0) = 0 AND workout_date >= #{from}
            """)
    List<Long> selectActiveUserIds(@Param("from") LocalDate from);
}
