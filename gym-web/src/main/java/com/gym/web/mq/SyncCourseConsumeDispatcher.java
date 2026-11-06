package com.gym.web.mq;

import com.gym.system.mq.CourseConsumeDispatcher;
import com.gym.system.mq.CourseConsumeMessage;
import com.gym.system.service.ConsumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 无 RocketMQ 时的同步兜底（gym.mq.course-consume-mode=sync）。
 * 答辩演示请用 rocketmq 模式。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "gym.mq.course-consume-mode", havingValue = "sync")
public class SyncCourseConsumeDispatcher implements CourseConsumeDispatcher {

    private final ConsumeService consumeService;

    @Override
    public void dispatch(CourseConsumeMessage message) {
        boolean inserted = consumeService.courseConsume(
                message.getBizNo(), message.getMemberId(), message.getCourseId());
        log.info("[消课同步] bizNo={} inserted={}", message.getBizNo(), inserted);
    }
}
