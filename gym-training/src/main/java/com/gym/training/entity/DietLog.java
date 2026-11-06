package com.gym.training.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 饮食热量粗略记录。
 *
 * <p>粒度刻意粗：只记餐次 + 食物名 + 热量/蛋白，不做营养成分库。
 * 智能体问「今天吃了多少」时够用；精细到克的营养分析不在毕设范围内。
 */
@Data
@TableName("diet_log")
public class DietLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private LocalDate logDate;

    /** 1早餐 2午餐 3晚餐 4加餐 */
    private Integer mealType;

    private String foodName;
    private Integer calories;
    private BigDecimal proteinG;
    private String note;
    private LocalDateTime createTime;
}
