package com.gym.ai.task;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 异步任务。
 *
 * <p>为什么必须落库：本地 7b 模型生成一份周报要 8~15 秒，生成一份周期化计划要 1~2 分钟。
 * 同步 HTTP 会撞上网关超时。落库之后任务有了独立生命周期——
 * 可以查询、可以重试、进程重启后还能恢复。
 */
@Data
@TableName("ai_task")
public class AiTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 对外暴露的任务 ID，前端只认这个 */
    private String taskId;

    private Long userId;
    private Integer taskType;
    private Integer status;

    /** 请求快照，重试与复盘都靠它，不靠前端重传 */
    private String requestJson;

    private String resultJson;
    private String errorMsg;

    private Integer tokenIn;
    private Integer tokenOut;
    private Integer elapsedMs;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime finishTime;
}
