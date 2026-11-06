package com.gym.web.mq;

import com.gym.system.mq.CourseConsumeMessage;
import com.gym.system.service.ConsumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 异步消课消费者：扣次 + 写流水；biz_no 幂等，重复投递不会双扣。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gym.mq.course-consume-mode", havingValue = "rocketmq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = CourseConsumeMessage.TOPIC,
        consumerGroup = "gym-consume-consumer-group"
)
public class CourseConsumeConsumer implements RocketMQListener<CourseConsumeMessage> {

    private final ConsumeService consumeService;

    @Override
    public void onMessage(CourseConsumeMessage message) {
        if (message == null || message.getBizNo() == null) {
            log.warn("[消课MQ] 空消息，丢弃");
            return;
        }
        boolean inserted = consumeService.courseConsume(
                message.getBizNo(), message.getMemberId(), message.getCourseId());
        log.info("[消课MQ] 消费完成 bizNo={} bookingId={} inserted={}",
                message.getBizNo(), message.getBookingId(), inserted);
    }
}
