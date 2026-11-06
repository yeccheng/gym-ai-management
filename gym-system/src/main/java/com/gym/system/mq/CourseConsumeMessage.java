package com.gym.system.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 课程消课 MQ 消息体。
 * bizNo 与 consume_record.uk_biz_no 对应，消费者靠它做幂等。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CourseConsumeMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String TOPIC = "gym-consume";

    private String bizNo;
    private Long memberId;
    private Long courseId;
    private Long bookingId;
}
