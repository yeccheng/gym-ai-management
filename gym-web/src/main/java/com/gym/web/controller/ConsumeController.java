package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.entity.ConsumeRecord;
import com.gym.system.service.ConsumeService;
import com.gym.web.security.RequireRole;
import com.gym.web.security.Role;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 消费计费：入场签到消次、课程消课（后者由预约完成时自动触发）。
 */
@Tag(name = "消费计费")
@RestController
@RequestMapping("/api/consume")
@RequiredArgsConstructor
public class ConsumeController {

    private final ConsumeService consumeService;

    @Operation(summary = "入场签到消次", description = "扣减会员一张有效次数卡的剩余次数，并写消课流水")
    @RequireRole({Role.COACH, Role.ADMIN})
    @PostMapping("/check-in")
    public R<Void> checkIn(@RequestParam Long memberId) {
        consumeService.checkIn(memberId);
        return R.ok();
    }

    @Operation(summary = "消费流水列表")
    @GetMapping("/records")
    public R<List<ConsumeRecord>> records() {
        return R.ok(consumeService.records());
    }
}
