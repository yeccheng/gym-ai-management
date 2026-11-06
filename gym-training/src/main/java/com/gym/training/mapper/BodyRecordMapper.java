package com.gym.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.training.entity.BodyRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface BodyRecordMapper extends BaseMapper<BodyRecord> {

    /**
     * 取最近 N 条体测记录（倒序）。
     * 取回后在 Java 里反转成正序，趋势计算的序列必须是时间递增的。
     */
    @Select("""
            SELECT b.* FROM body_record b
            WHERE b.user_id = #{userId}
            ORDER BY b.record_date DESC
            LIMIT #{limit}
            """)
    List<BodyRecord> selectRecent(@Param("userId") Long userId, @Param("limit") int limit);

    /** 按日期区间取，用于「近几周体重变化」 */
    @Select("""
            SELECT b.* FROM body_record b
            WHERE b.user_id = #{userId}
              AND b.record_date >= #{from}
              AND b.record_date <= #{to}
            ORDER BY b.record_date ASC
            """)
    List<BodyRecord> selectByRange(@Param("userId") Long userId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to);
}
