package com.gym.web.job;

import com.gym.training.service.CoachReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 每天晚上 21:00 跑教练提醒。
 *
 * <p>cron 用 Asia/Shanghai，避免服务器 UTC 导致「晚上 9 点」跑成早上。
 * 推送本身是打日志——毕设不接厂商推送 SDK，答辩看日志即可证明任务执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoachReminderJob {

    private final CoachReminderService coachReminderService;

    @Scheduled(cron = "0 0 21 * * ?", zone = "Asia/Shanghai")
    public void eveningRemind() {
        log.info("[教练提醒] 定时任务触发（每天 21:00）");
        int n = coachReminderService.runEveningCheck();
        log.info("[教练提醒] 定时任务结束，产出 {} 条", n);
    }
}
