package com.gym.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("workout_session")
public class WorkoutSession {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private LocalDate workoutDate;

    /** 1推 2拉 3腿 4上肢 5下肢 6全身 7有氧 */
    private Integer sessionType;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationMin;

    /** 主观准备度 1-5，过低时系统自动给出降载建议 */
    private Integer readinessScore;
    private BigDecimal sleepHours;
    private BigDecimal bodyWeightKg;

    /** 由 TrainMetrics 计算后写入，不交给大模型 */
    private BigDecimal totalVolume;

    private String note;

    /**
     * 1 = 可单独清除的样板历史；0 = 用户真实打卡。
     * 样板挂在该用户账号上，曲线 / AI /「再来一组」一律计入；
     * 本字段只区分「重建演示时能否清掉」，不是「假装没练过」。
     */
    private Integer isDemo;

    private Integer deleted;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
