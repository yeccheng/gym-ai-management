package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 课程预约。
 * status：1 已预约 2 已完成 3 已取消 4 超时未支付。
 */
@Data
@TableName("booking")
public class Booking {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;
    private Long courseId;

    /** 1 已预约 2 已完成 3 已取消 4 超时未支付 */
    private Integer status;

    private LocalDateTime createTime;
}
