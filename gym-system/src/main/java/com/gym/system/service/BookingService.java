package com.gym.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.Booking;
import com.gym.system.mapper.BookingMapper;
import com.gym.system.mq.CourseConsumeDispatcher;
import com.gym.system.mq.CourseConsumeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 预约记录查询与完成。
 *
 * <p>完成预约只改状态并派发消课消息；真正扣次由 RocketMQ 消费者异步执行。
 */
@Service
@RequiredArgsConstructor
public class BookingService extends ServiceImpl<BookingMapper, Booking> {

    private final CourseConsumeDispatcher courseConsumeDispatcher;

    public List<Booking> listByMember(Long memberId) {
        return list(new QueryWrapper<Booking>()
                .eq("member_id", memberId)
                .orderByDesc("create_time"));
    }

    /** 完成预约并异步触发课程消课 */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long bookingId) {
        Booking b = getById(bookingId);
        if (b == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "预约记录不存在");
        }
        if (b.getStatus() != CourseService.BOOKING_BOOKED) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "该预约状态不可完成");
        }
        b.setStatus(CourseService.BOOKING_DONE);
        updateById(b);

        String bizNo = ConsumeService.newBizNo();
        courseConsumeDispatcher.dispatch(new CourseConsumeMessage(
                bizNo, b.getMemberId(), b.getCourseId(), bookingId));
    }
}
