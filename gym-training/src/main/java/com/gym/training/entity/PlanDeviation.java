package com.gym.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 训练计划执行偏差。
 *
 * <p>用户临时「减一组 / 换动作 / 状态差」时落库，下次生成计划时读取：
 * 连续 BAD_FEEL 或 SKIP_SET → 自动降强度；SWAP 多的动作 → 替换进计划。
 * 没有这张表，计划生成永远是「理想态」，和用户真实状态脱节。
 */
@Data
@TableName("plan_deviation")
public class PlanDeviation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private Long sessionId;
    private Long exerciseId;

    /** SKIP_SET / SWAP / BAD_FEEL / INJURY_HEAVY */
    private String type;

    private String note;
    private LocalDateTime createTime;
}
