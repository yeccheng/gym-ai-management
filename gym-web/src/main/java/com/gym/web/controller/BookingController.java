package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.Booking;
import com.gym.system.service.BookingService;
import com.gym.system.service.CourseService;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 预约管理：预约 / 取消 / 完成。完成预约后经 RocketMQ 异步消课。
 */
@Tag(name = "预约管理")
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;
    private final CourseService courseService;

    @Operation(summary = "预约列表（不传 memberId 则返回全部）")
    @GetMapping
    public R<List<Booking>> listByMember(@RequestParam(required = false) Long memberId) {
        if (memberId != null) {
            return R.ok(bookingService.listByMember(memberId));
        }
        return R.ok(bookingService.list());
    }

    @Operation(summary = "删除预约")
    @RequireRole(Role.ADMIN)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        bookingService.removeById(id);
        return R.ok();
    }

    @Operation(summary = "预约详情")
    @GetMapping("/{id}")
    public R<Booking> get(@PathVariable Long id) {
        return R.ok(bookingService.getById(id));
    }

    @Operation(summary = "预约课程")
    @PostMapping("/book")
    public R<Void> book(@RequestParam Long memberId, @RequestParam Long courseId) {
        courseService.book(memberId, courseId);
        return R.ok();
    }

    @Operation(summary = "取消预约")
    @PostMapping("/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        courseService.cancelBooking(id);
        return R.ok();
    }

    @Operation(summary = "完成预约（异步消课）",
            description = "先改预约状态，再发 gym-consume；消费者按 biz_no 幂等扣次")
    @PostMapping("/{id}/complete")
    public R<Void> complete(@PathVariable Long id) {
        bookingService.complete(id);
        return R.ok();
    }
}
