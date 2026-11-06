package com.gym.web.controller;

import com.gym.common.result.R;
import com.gym.system.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 运营统计看板数据。
 */
@Tag(name = "运营统计")
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @Operation(summary = "经营概览")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        return R.ok(statsService.overview());
    }
}
