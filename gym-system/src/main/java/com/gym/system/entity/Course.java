package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程。
 *
 * <p>status：1 可预约 2 已满 3 已结束 4 已取消。
 * version 用于预约时的乐观锁，防止并发超卖。
 */
@Data
@TableName("course")
public class Course {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long coachId;

    private Integer capacity;
    private Integer bookedCnt;

    private LocalDateTime courseTime;
    private Integer durationMin;
    private String room;

    /** 1 可预约 2 已满 3 已结束 4 已取消 */
    private Integer status;

    /** 乐观锁，防止超卖 */
    private Integer version;
}
