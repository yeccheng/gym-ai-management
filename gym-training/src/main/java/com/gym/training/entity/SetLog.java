package com.gym.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 训练组记录。
 *
 * <p>整个系统的数据地基。粒度到「组」而不是「练了什么动作」——
 * 只记动作名算不出容量，也算不出 e1RM，后面所有指标与 AI 结论都无从谈起。
 */
@Data
@TableName("set_log")
public class SetLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long sessionId;
    private Long userId;
    private Long exerciseId;

    private Integer setIndex;
    private Integer reps;
    private BigDecimal weightKg;

    /** 主观强度 6-10，用于把非力竭组折算成等效力竭次数 */
    private BigDecimal rpe;

    private Integer isWarmup;

    /** 有效组：由 TrainMetrics 判定后写入，不依赖前端传值 */
    private Integer isWorkingSet;

    /** 写入时计算的 e1RM，供趋势分析直接读取，避免每次查询重复计算 */
    private BigDecimal e1rm;

    private Integer isPr;
    private String tempo;
    private Integer restSec;

    /**
     * 训练发生时间。
     *
     * <p>与 createTime（录入时间）严格区分。用户周三补录周一的训练是常态，
     * 趋势分析若按录入时间取窗口，这组数据会落进周三那一周，
     * 周容量、e1RM 序列、平台期判定全部错位——
     * 一个看起来只是命名习惯的问题，实际会让整个诊断链路给出错误结论。
     */
    private LocalDateTime performedAt;

    private LocalDateTime createTime;
}
