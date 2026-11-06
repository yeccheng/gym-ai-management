package com.gym.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会员周计划落库（JSON）。生成仍可自动，但改动作/组数/勾选必须进库，不能只写 localStorage。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeekPlanStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private volatile boolean tableReady = false;

    public void ensureTable() {
        if (tableReady) {
            return;
        }
        synchronized (this) {
            if (tableReady) {
                return;
            }
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS member_week_plan (
                      user_id     BIGINT       NOT NULL PRIMARY KEY,
                      week_start  DATE         NOT NULL,
                      plan_json   MEDIUMTEXT   NOT NULL,
                      done_json   TEXT,
                      update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                              ON UPDATE CURRENT_TIMESTAMP
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """);
            tableReady = true;
        }
    }

    public LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    public record StoredDay(String date, String weekday, String focus, String note,
                            List<StoredExercise> exercises, boolean restDay) {
    }

    public record StoredExercise(Long exerciseId, String name, Double targetWeightKg,
                                 Integer targetReps, Integer targetSets) {
    }

    public record StoredPlan(String weekStart, List<StoredDay> days, Map<String, Boolean> done) {
    }

    public StoredPlan load(long userId, LocalDate anyDayInWeek) {
        ensureTable();
        LocalDate start = weekStart(anyDayInWeek);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT week_start, plan_json, done_json FROM member_week_plan WHERE user_id=? LIMIT 1",
                userId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Object ws = row.get("week_start");
        LocalDate storedStart = ws instanceof java.sql.Date d ? d.toLocalDate()
                : LocalDate.parse(String.valueOf(ws));
        if (!storedStart.equals(start)) {
            return null;
        }
        try {
            List<StoredDay> days = objectMapper.readValue(
                    String.valueOf(row.get("plan_json")), new TypeReference<>() {});
            Map<String, Boolean> done = new LinkedHashMap<>();
            String doneJson = row.get("done_json") == null ? null : String.valueOf(row.get("done_json"));
            if (StringUtils.hasText(doneJson)) {
                done = objectMapper.readValue(doneJson, new TypeReference<>() {});
            }
            return new StoredPlan(start.toString(), days, done);
        } catch (Exception e) {
            log.warn("周计划反序列化失败 userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    public void save(long userId, LocalDate weekStart, List<StoredDay> days, Map<String, Boolean> done) {
        ensureTable();
        try {
            String planJson = objectMapper.writeValueAsString(days == null ? List.of() : days);
            String doneJson = objectMapper.writeValueAsString(done == null ? Map.of() : done);
            int updated = jdbc.update("""
                    UPDATE member_week_plan
                    SET week_start=?, plan_json=?, done_json=?, update_time=NOW()
                    WHERE user_id=?
                    """, weekStart, planJson, doneJson, userId);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO member_week_plan (user_id, week_start, plan_json, done_json, update_time)
                        VALUES (?,?,?,?,NOW())
                        """, userId, weekStart, planJson, doneJson);
            }
        } catch (Exception e) {
            throw new IllegalStateException("保存周计划失败: " + e.getMessage(), e);
        }
    }
}
