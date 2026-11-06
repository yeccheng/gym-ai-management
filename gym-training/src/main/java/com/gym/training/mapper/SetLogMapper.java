package com.gym.training.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.training.entity.SetLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface SetLogMapper extends BaseMapper<SetLog> {

    /**
     * 查询某用户在时间区间内的全部组记录。
     * 周报复盘、肌群容量统计、e1RM 趋势三条链路共用这一个查询。
     *
     * <p><b>含 is_demo=1。</b>样板历史挂在该用户自己账号上，曲线 / AI 必须能看见，
     * 否则「今天刚记第 6 组」会被误判成「八周几乎没练」。
     * is_demo 只用于「可单独清掉样板、不动真实打卡」，不是趋势查询的过滤条件。
     *
     * <p>按 performed_at 而不是 create_time 过滤。
     * 补录场景下（周三录入周一的训练），按录入时间取窗口会让数据落进错误的那一周，
     * 周容量与平台期判定随之错位。
     */
    @Select("""
            SELECT s.* FROM set_log s
            INNER JOIN workout_session ws ON ws.id = s.session_id
            WHERE s.user_id = #{userId}
              AND COALESCE(ws.deleted, 0) = 0
              AND s.performed_at >= #{from}
              AND s.performed_at < #{to}
            ORDER BY s.performed_at ASC
            """)
    List<SetLog> selectByUserAndRange(@Param("userId") Long userId,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);

    /**
     * 查询某动作最近 N 次的 e1RM 序列，用于平台期检测。
     * 取每组的最大值，再按时间正序排列成序列。含样板历史（见 selectByUserAndRange）。
     */
    @Select("""
            SELECT s.* FROM set_log s
            INNER JOIN workout_session ws ON ws.id = s.session_id
            WHERE s.user_id = #{userId}
              AND s.exercise_id = #{exerciseId}
              AND s.is_warmup = 0
              AND COALESCE(ws.deleted, 0) = 0
            ORDER BY s.performed_at DESC
            LIMIT #{limit}
            """)
    List<SetLog> selectRecentByExercise(@Param("userId") Long userId,
                                        @Param("exerciseId") Long exerciseId,
                                        @Param("limit") int limit);

    /**
     * 批量写入。
     *
     * <p>BaseMapper 只有单条 insert，一次训练十几组、半年一千多组，
     * 逐条插就是一千多次网络往返。
     * 调用方按 200 行分块，避开 MySQL 的 max_allowed_packet 上限——
     * 不分包的话，一次插入行数到了某个临界值就会整批失败，而且报错信息看不出是包太大。
     */
    @Insert("""
            <script>
            INSERT INTO set_log (session_id, user_id, exercise_id, set_index, reps, weight_kg,
                                 rpe, is_warmup, is_working_set, e1rm, is_pr, tempo, rest_sec,
                                 performed_at, create_time)
            VALUES
            <foreach collection="list" item="s" separator=",">
              (#{s.sessionId}, #{s.userId}, #{s.exerciseId}, #{s.setIndex}, #{s.reps},
               #{s.weightKg}, #{s.rpe}, #{s.isWarmup}, #{s.isWorkingSet}, #{s.e1rm},
               #{s.isPr}, #{s.tempo}, #{s.restSec}, #{s.performedAt}, #{s.createTime})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<SetLog> list);

    /** 某次训练里某个动作的全部组 */
    @Select("""
            SELECT * FROM set_log
            WHERE session_id = #{sessionId}
            ORDER BY exercise_id, set_index ASC
            """)
    List<SetLog> selectBySession(@Param("sessionId") Long sessionId);

    /** 某次训练中某个动作的最后一组（再来一组时取重量×次数模板） */
    @Select("""
            SELECT * FROM set_log
            WHERE session_id = #{sessionId}
              AND exercise_id = #{exerciseId}
              AND is_warmup = 0
            ORDER BY set_index DESC
            LIMIT 1
            """)
    SetLog selectLastWorkingSet(@Param("sessionId") Long sessionId,
                                @Param("exerciseId") Long exerciseId);

    /** 当前会话里某动作已有组数，用来算下一组的 set_index */
    @Select("""
            SELECT COALESCE(MAX(set_index), 0) FROM set_log
            WHERE session_id = #{sessionId} AND exercise_id = #{exerciseId}
            """)
    int maxSetIndex(@Param("sessionId") Long sessionId, @Param("exerciseId") Long exerciseId);

    /**
     * 近 N 天练得最多的动作 ID（排除热身组），打卡页「最近常用」快捷栏用。
     * 含样板历史，否则冷启动快捷栏永远空、卧推还要往下翻。
     */
    @Select("""
            SELECT s.exercise_id
            FROM set_log s
            INNER JOIN workout_session ws ON ws.id = s.session_id
            WHERE s.user_id = #{userId}
              AND s.is_warmup = 0
              AND COALESCE(ws.deleted, 0) = 0
              AND s.performed_at >= #{from}
            GROUP BY s.exercise_id
            ORDER BY COUNT(*) DESC
            LIMIT #{limit}
            """)
    List<Long> selectTopExerciseIds(@Param("userId") Long userId,
                                    @Param("from") LocalDateTime from,
                                    @Param("limit") int limit);

    /**
     * 真实打卡组数（会话 is_demo=0）。
     * 「有没有训练记录」以组为准，不能只数空会话——用户存了组就必须算有。
     */
    @Select("""
            SELECT COUNT(*) FROM set_log s
            INNER JOIN workout_session ws ON ws.id = s.session_id
            WHERE s.user_id = #{userId}
              AND COALESCE(ws.is_demo, 0) = 0
              AND COALESCE(ws.deleted, 0) = 0
            """)
    long countRealSets(@Param("userId") Long userId);

    /**
     * 该用户任意组数（含样板）。「我的」页有没有可画的曲线看这个，
     * 不能只看 is_demo=0——否则半年样板被挡在门外，图上只剩今天一个点。
     */
    @Select("""
            SELECT COUNT(*) FROM set_log s
            INNER JOIN workout_session ws ON ws.id = s.session_id
            WHERE s.user_id = #{userId}
              AND COALESCE(ws.deleted, 0) = 0
            """)
    long countAnySets(@Param("userId") Long userId);
}
