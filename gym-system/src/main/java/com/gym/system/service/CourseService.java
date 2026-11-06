package com.gym.system.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.system.entity.Booking;
import com.gym.system.entity.Course;
import com.gym.system.mapper.BookingMapper;
import com.gym.system.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 课程与预约。
 *
 * <p>预约采用乐观锁（version 字段）防并发超卖：
 * 更新条件里带上旧 version，若并发时版本已被改，受影响行数为 0，视为冲突重试。
 */
@Service
@RequiredArgsConstructor
public class CourseService extends ServiceImpl<CourseMapper, Course> {

    private final BookingMapper bookingMapper;

    public static final int STATUS_OPEN = 1;
    public static final int STATUS_FULL = 2;
    public static final int STATUS_ENDED = 3;
    public static final int STATUS_CANCELLED = 4;

    public static final int BOOKING_BOOKED = 1;
    public static final int BOOKING_DONE = 2;
    public static final int BOOKING_CANCELLED = 3;

    /** 预约课程：原子自增 booked_cnt 并写入预约记录 */
    @Transactional(rollbackFor = Exception.class)
    public void book(Long memberId, Long courseId) {
        Course course = getById(courseId);
        if (course == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "课程不存在");
        }
        if (course.getStatus() != STATUS_OPEN) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "课程当前不可预约");
        }
        if (course.getBookedCnt() >= course.getCapacity()) {
            throw new BusinessException(ResultCode.CONFLICT, "课程名额已满");
        }
        // 同一会员对同一课程只允许一条有效预约（表上有 uk_member_course 唯一约束，这里前置拦截，避免触发 500）
        long already = bookingMapper.selectCount(new QueryWrapper<Booking>()
                .eq("member_id", memberId).eq("course_id", courseId).eq("status", BOOKING_BOOKED));
        if (already > 0) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "该会员已预约该课程");
        }

        int rows = baseMapper.update(null, new UpdateWrapper<Course>()
                .eq("id", courseId)
                .eq("status", STATUS_OPEN)
                .eq("version", course.getVersion())
                .lt("booked_cnt", course.getCapacity())
                .setSql("booked_cnt = booked_cnt + 1, version = version + 1, "
                        + "status = IF(booked_cnt + 1 >= capacity, 2, 1)"));
        if (rows == 0) {
            throw new BusinessException(ResultCode.CONFLICT, "预约冲突或名额已满，请重试");
        }

        Booking booking = new Booking();
        booking.setMemberId(memberId);
        booking.setCourseId(courseId);
        booking.setStatus(BOOKING_BOOKED);
        bookingMapper.insert(booking);
    }

    /** 取消预约：回退 booked_cnt 并将课程恢复可预约 */
    public void cancelBooking(Long bookingId) {
        Booking booking = bookingMapper.selectById(bookingId);
        if (booking == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "预约记录不存在");
        }
        if (booking.getStatus() != BOOKING_BOOKED) {
            throw new BusinessException(ResultCode.BIZ_ERROR, "该预约不可取消");
        }
        booking.setStatus(BOOKING_CANCELLED);
        bookingMapper.updateById(booking);

        Course course = getById(booking.getCourseId());
        if (course != null) {
            baseMapper.update(null, new UpdateWrapper<Course>()
                    .eq("id", course.getId())
                    .eq("version", course.getVersion())
                    .gt("booked_cnt", 0)
                    .setSql("booked_cnt = booked_cnt - 1, version = version + 1, status = 1"));
        }
    }
}
