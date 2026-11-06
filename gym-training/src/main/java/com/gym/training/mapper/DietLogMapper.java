package com.gym.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.training.entity.DietLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface DietLogMapper extends BaseMapper<DietLog> {

    /** 查某一天的全部饮食记录，按餐次排序 */
    @Select("""
            SELECT d.* FROM diet_log d
            WHERE d.user_id = #{userId}
              AND d.log_date = #{date}
            ORDER BY d.meal_type ASC, d.create_time ASC
            """)
    List<DietLog> selectByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);
}
