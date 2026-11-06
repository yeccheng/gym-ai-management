package com.gym.system.mq;

/**
 * 课程消课派发：预约完成后异步扣次。
 * 默认实现走 RocketMQ；本地无 MQ 时可切同步实现。
 */
public interface CourseConsumeDispatcher {

    void dispatch(CourseConsumeMessage message);
}
