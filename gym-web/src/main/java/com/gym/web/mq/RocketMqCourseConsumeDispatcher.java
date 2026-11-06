package com.gym.web.mq;

import com.gym.system.mq.CourseConsumeDispatcher;
import com.gym.system.mq.CourseConsumeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 预约完成后把消课丢进 RocketMQ，接口立刻返回，高峰不堵在扣次上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gym.mq.course-consume-mode", havingValue = "rocketmq", matchIfMissing = true)
public class RocketMqCourseConsumeDispatcher implements CourseConsumeDispatcher {

    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public void dispatch(CourseConsumeMessage message) {
        rocketMQTemplate.syncSend(
                CourseConsumeMessage.TOPIC,
                MessageBuilder.withPayload(message).build());
        log.info("[消课MQ] 已发送 topic={} bizNo={} bookingId={} memberId={}",
                CourseConsumeMessage.TOPIC, message.getBizNo(),
                message.getBookingId(), message.getMemberId());
    }
}
