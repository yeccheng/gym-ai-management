package com.gym.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消课流水。
 *
 * <p>biz_no 唯一键，消课接口用 UUID 生成，配合 MQ 重复消费时做幂等。
 * consume_type：1 课程消课 2 入场消次。
 */
@Data
@TableName("consume_record")
public class ConsumeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizNo;
    private Long memberId;
    private Long cardId;
    private Long courseId;

    private Integer consumeCount;

    /** 1 课程消课 2 入场消次 */
    private Integer consumeType;

    private LocalDateTime createTime;
}
