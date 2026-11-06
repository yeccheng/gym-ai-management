package com.gym.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gym.ai.task.AiTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface AiTaskMapper extends BaseMapper<AiTask> {

    @Select("SELECT * FROM ai_task WHERE task_id = #{taskId} LIMIT 1")
    AiTask selectByTaskId(@Param("taskId") String taskId);

    /**
     * CAS 抢占执行权。
     * WHERE status = 0 是关键——没有这个条件，重启恢复时同一个任务
     * 会被两个 worker 同时执行，产出两份结果互相覆盖。
     */
    @Update("UPDATE ai_task SET status = 1, update_time = NOW() WHERE task_id = #{taskId} AND status = 0")
    int markRunning(@Param("taskId") String taskId);

    /**
     * WHERE status <> 2 保证重复回调不会覆盖已有成功结果，
     * 也让 finishSuccess 具备幂等性——SSE 重连导致的重复完成不会污染数据。
     */
    @Update("""
            UPDATE ai_task
            SET status = 2, result_json = #{result}, token_in = #{tokenIn},
                token_out = #{tokenOut}, elapsed_ms = #{elapsedMs}, finish_time = NOW()
            WHERE task_id = #{taskId} AND status <> 2
            """)
    int finishSuccess(@Param("taskId") String taskId,
                      @Param("result") String result,
                      @Param("tokenIn") Integer tokenIn,
                      @Param("tokenOut") Integer tokenOut,
                      @Param("elapsedMs") Integer elapsedMs);

    @Update("""
            UPDATE ai_task
            SET status = 3, error_msg = #{errorMsg}, elapsed_ms = #{elapsedMs}, finish_time = NOW()
            WHERE task_id = #{taskId} AND status <> 2
            """)
    int finishFailed(@Param("taskId") String taskId,
                     @Param("errorMsg") String errorMsg,
                     @Param("elapsedMs") Integer elapsedMs);

    /** 重启恢复用：捞出所有没跑完的任务 */
    @Select("SELECT * FROM ai_task WHERE status IN (0, 1) ORDER BY id ASC LIMIT 200")
    List<AiTask> selectUnfinished();

    /** 拉取式调度用：只捞排队中的，一次最多取队列容量个 */
    @Select("SELECT * FROM ai_task WHERE status = 0 ORDER BY id ASC LIMIT #{limit}")
    List<AiTask> selectQueued(@Param("limit") int limit);

    @Update("UPDATE ai_task SET status = 0, update_time = NOW() WHERE task_id = #{taskId}")
    int resetToQueued(@Param("taskId") String taskId);
}
