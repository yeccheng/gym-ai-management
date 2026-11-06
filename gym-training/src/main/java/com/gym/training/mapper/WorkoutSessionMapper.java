package com.gym.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.training.entity.WorkoutSession;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

public interface WorkoutSessionMapper extends BaseMapper<WorkoutSession> {

    /**
     * 最近一次训练（不含今天；含样板历史）。
     * 「再来一组」要能带出上周/样板里的卧推重量，不能因为 is_demo=1 就空手而归。
     */
    @Select("""
            SELECT * FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0
              AND workout_date < #{before}
            ORDER BY workout_date DESC, id DESC
            LIMIT 1
            """)
    WorkoutSession selectLastBefore(@Param("userId") Long userId, @Param("before") LocalDate before);

    /**
     * 当天训练会话（不按 is_demo 过滤）。
     * 打卡页要能找到「误标成示例」的今日会话，再 markReal；
     * 若这里加 is_demo=0，脏数据会查不到 → 又新建空会话 → 旧组孤儿化。
     */
    @Select("""
            SELECT * FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0
              AND workout_date = #{date}
            ORDER BY id DESC
            LIMIT 1
            """)
    WorkoutSession selectByUserAndDate(@Param("userId") Long userId, @Param("date") LocalDate date);

    /** 近 N 天真实会话（提醒任务等仍只要真实打卡） */
    @Select("""
            SELECT * FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0 AND is_demo = 0
              AND workout_date >= #{from}
            ORDER BY workout_date ASC
            """)
    List<WorkoutSession> selectFromDate(@Param("userId") Long userId, @Param("from") LocalDate from);

    /** 近区间全部会话（含样板），曲线旁注「减载 / 出差」用 */
    @Select("""
            SELECT * FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0
              AND workout_date >= #{from}
            ORDER BY workout_date ASC
            """)
    List<WorkoutSession> selectAllFromDate(@Param("userId") Long userId, @Param("from") LocalDate from);

    /** 是否有真实会话（is_demo=0）；组数请用 SetLogMapper.countRealSets */
    @Select("""
            SELECT COUNT(*) FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0 AND COALESCE(is_demo, 0) = 0
            """)
    long countRealSessions(@Param("userId") Long userId);

    /** 是否有样板会话（is_demo=1）；启动灌数幂等用，有了就别再灌一千条 */
    @Select("""
            SELECT COUNT(*) FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0 AND COALESCE(is_demo, 0) = 1
            """)
    long countDemoSessions(@Param("userId") Long userId);

    /** 把误标成示例的会话改回真实（用户打卡写入后兜底） */
    @org.apache.ibatis.annotations.Update("""
            UPDATE workout_session SET is_demo = 0
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int markReal(@Param("id") Long id, @Param("userId") Long userId);

    /** 训练历史列表：按日期倒序（含样板，和曲线口径一致） */
    @Select("""
            SELECT * FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0
            ORDER BY workout_date DESC, id DESC
            LIMIT #{limit}
            """)
    List<WorkoutSession> selectRecent(@Param("userId") Long userId, @Param("limit") int limit);

    /** 连续打卡：近 N 天有组的真实训练日 */
    @Select("""
            SELECT DISTINCT workout_date FROM workout_session
            WHERE user_id = #{userId} AND deleted = 0 AND COALESCE(is_demo, 0) = 0
              AND workout_date >= #{from}
            ORDER BY workout_date DESC
            """)
    List<LocalDate> selectRealDatesFrom(@Param("userId") Long userId, @Param("from") LocalDate from);
}
