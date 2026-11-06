package com.gym.ai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gym.ai.review.WeeklyReviewService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 周报复盘的任务处理器。
 *
 * <p>注意它不做任何任务管理——不碰状态、不碰线程池、不碰 SSE。
 * 它只负责把 requestJson 解析成业务入参，然后返回一个 Flux。
 * 这也是加新 AI 功能时唯一需要写的文件。
 */
@Slf4j
@Component
public class WeeklyReviewHandler implements AiTaskHandler {

    private final WeeklyReviewService weeklyReviewService;
    private final ObjectMapper objectMapper;

    public WeeklyReviewHandler(WeeklyReviewService weeklyReviewService, ObjectMapper objectMapper) {
        this.weeklyReviewService = weeklyReviewService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AiTaskType type() {
        return AiTaskType.WEEKLY_REVIEW;
    }

    @Override
    public Flux<String> execute(Long userId, String requestJson) {
        return weeklyReviewService.streamReview(userId, parseWeekStart(requestJson));
    }

    /**
     * 解析周起始日。缺省取本周一——周报按自然周聚合，
     * 但用户随时可能补记上周数据，所以允许显式指定任意一天。
     */
    LocalDate parseWeekStart(String requestJson) {
        if (!StringUtils.hasText(requestJson)) {
            return currentWeekMonday();
        }
        try {
            JsonNode node = objectMapper.readTree(requestJson);
            String text = node.path("weekStart").asText(null);
            return StringUtils.hasText(text) ? LocalDate.parse(text) : currentWeekMonday();
        } catch (Exception e) {
            log.warn("解析周报请求失败，使用本周一。request={}", requestJson);
            return currentWeekMonday();
        }
    }

    private LocalDate currentWeekMonday() {
        return LocalDate.now().with(DayOfWeek.MONDAY);
    }
}
