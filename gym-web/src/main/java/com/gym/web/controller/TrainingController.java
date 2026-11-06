package com.gym.web.controller;

import com.gym.common.exception.BusinessException;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.common.security.UserContext;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.SysUserMapper;
import com.gym.training.entity.BodyRecord;
import com.gym.training.entity.DietLog;
import com.gym.training.entity.PlanDeviation;
import com.gym.training.entity.WorkoutSession;
import com.gym.training.service.CoachReminderService;
import com.gym.training.service.PlanDeviationService;
import com.gym.training.service.PlanDeviationService.IntensityHint;
import com.gym.training.service.TrainingRecordService;
import com.gym.training.service.TrainingRecordService.BodySnapshot;
import com.gym.training.service.TrainingRecordService.ChartPoint;
import com.gym.training.service.TrainingRecordService.ChartSeries;
import com.gym.training.service.TrainingRecordService.DietDayView;
import com.gym.training.service.TrainingRecordService.ExerciseCueView;
import com.gym.training.service.TrainingRecordService.ExerciseView;
import com.gym.training.service.TrainingRecordService.LastSessionView;
import com.gym.training.service.TrainingRecordService.SaveBodyRequest;
import com.gym.training.service.TrainingRecordService.SaveDietRequest;
import com.gym.training.service.TrainingRecordService.SaveSetRequest;
import com.gym.training.service.TrainingRecordService.SetView;
import com.gym.training.service.TrainingRecordService.TodaySessionView;
import com.gym.training.service.TrainingRecordService.WeeklyVolumePoint;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 训练打卡与图表入口。
 *
 * <p>业务逻辑在 gym-training 的 {@link TrainingRecordService}，
 * Controller 只做鉴权身份透传与 Knife4j 标注——多模块边界要干净。
 */
@Tag(name = "训练打卡", description = "上次同部位快捷复用 / 手动确认记组 / 动作库标签检索 / 主力力量曲线 / 平台期提示")
@RestController
@RequestMapping("/training")
@RequiredArgsConstructor
public class TrainingController {

    private final TrainingRecordService trainingRecordService;
    private final PlanDeviationService planDeviationService;
    private final CoachReminderService coachReminderService;
    private final SysUserMapper sysUserMapper;

    @Operation(summary = "上次同部位动作列表",
            description = "打开训练页的默认数据：上次训练做过的动作 + 每项最后一组重量×次数，供「再来一组」一键填入")
    @GetMapping("/last-same-part")
    public R<LastSessionView> lastSamePart() {
        return R.ok(trainingRecordService.lastSamePart(UserContext.requireUserId()));
    }

    @Operation(summary = "今日训练会话（含已记各组）")
    @GetMapping("/today")
    public R<TodaySessionView> today() {
        return R.ok(trainingRecordService.todaySession(UserContext.requireUserId()));
    }

    @Operation(summary = "训练历史列表", description = "按日倒序，含动作名摘要与容量")
    @GetMapping("/history")
    public R<List<TrainingRecordService.SessionSummary>> history(
            @RequestParam(defaultValue = "40") int limit) {
        return R.ok(trainingRecordService.historySessions(UserContext.requireUserId(), limit));
    }

    @Operation(summary = "某次训练详情")
    @GetMapping("/sessions/{id}")
    public R<TrainingRecordService.SessionDetail> sessionDetail(@PathVariable("id") String id) {
        return R.ok(trainingRecordService.sessionDetail(UserContext.requireUserId(), parseLongId(id)));
    }

    @Operation(summary = "整课复用处方", description = "返回该课各动作目标重量×次数×组数，供今日打卡")
    @GetMapping("/sessions/{id}/reuse")
    public R<List<TrainingRecordService.ReuseExercise>> reuseSession(@PathVariable("id") String id) {
        return R.ok(trainingRecordService.reuseSession(UserContext.requireUserId(), parseLongId(id)));
    }

    @Operation(summary = "删除一组")
    @PostMapping("/sets/{id}/delete")
    public R<Void> deleteSet(@PathVariable("id") String id) {
        trainingRecordService.deleteSet(UserContext.requireUserId(), parseLongId(id));
        return R.ok();
    }

    @Operation(summary = "下一组建议重量", description = "确定性渐进超负荷：打满 +2.5 / 差1维持 / 更差 −2.5")
    @GetMapping("/suggest-weight")
    public R<TrainingRecordService.NextWeightSuggestion> suggestWeight(
            @RequestParam String exerciseId,
            @RequestParam(required = false) Integer targetReps) {
        return R.ok(trainingRecordService.suggestNextWeight(
                UserContext.requireUserId(), parseLongId(exerciseId), targetReps));
    }

    @Operation(summary = "习惯统计", description = "连续打卡天数 + 本周完成率")
    @GetMapping("/habit")
    public R<TrainingRecordService.HabitStats> habit() {
        return R.ok(trainingRecordService.habitStats(UserContext.requireUserId()));
    }

    private static long parseLongId(String id) {
        try {
            return Long.parseLong(id.trim());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "ID 无效");
        }
    }

    @Operation(summary = "确保今日会话存在", description = "可选传入 sessionType：1推 2拉 3腿 4上肢 5下肢 6全身 7有氧")
    @PostMapping("/today/ensure")
    public R<Map<String, Object>> ensureToday(@RequestParam(required = false) Integer sessionType) {
        WorkoutSession s = trainingRecordService.ensureTodaySession(UserContext.requireUserId(), sessionType);
        return R.ok(Map.of("sessionId", s.getId(), "date", s.getWorkoutDate().toString()));
    }

    @Operation(summary = "记一组",
            description = "传 setId 则更新该组；e1RM 由服务端算。demo 账号开启 story-lock 时不能正式组改写卧推/肩推。")
    @PostMapping("/sets")
    public R<SetView> saveSet(@RequestBody SaveSetRequest request) {
        long uid = UserContext.requireUserId();
        SysUser user = sysUserMapper.selectById(uid);
        String username = user == null ? null : user.getUsername();
        SaveSetRequest gated = new SaveSetRequest(
                request.sessionId(), request.setId(), request.exerciseId(),
                request.weightKg(), request.reps(), request.rpe(), request.warmup(),
                username);
        return R.ok(trainingRecordService.saveSet(uid, gated));
    }

    @Operation(summary = "动作库检索",
            description = "tag=free|machine|dumbbell|bodyweight；keyword 模糊匹配名称/别名。" +
                    "按 action_level 升序（基础主动作在前），再按名称长度。")
    @GetMapping("/exercises")
    public R<List<ExerciseView>> exercises(@RequestParam(required = false) String tag,
                                           @RequestParam(required = false) String keyword) {
        return R.ok(trainingRecordService.searchExercises(tag, keyword));
    }

    @Operation(summary = "新增自定义动作",
            description = "库里没有的动作可自建；同名已存在则返回已有条目。建完可立刻记组。")
    @PostMapping("/exercises")
    public R<ExerciseView> createExercise(@RequestBody TrainingRecordService.CreateExerciseRequest request) {
        return R.ok(trainingRecordService.createCustomExercise(request));
    }

    @Operation(summary = "最近常用动作", description = "近几周练得最多的动作（含样板历史）；冷启动回落三大项")
    @GetMapping("/exercises/recent")
    public R<List<ExerciseView>> recentExercises(
            @RequestParam(defaultValue = "56") int days,
            @RequestParam(defaultValue = "5") int limit) {
        return R.ok(trainingRecordService.recentFrequentExercises(
                UserContext.requireUserId(), days, limit));
    }

    @Operation(summary = "是否有可画曲线的训练记录", description = "含样板历史；「我的」页据此切换用户曲线 / 空状态示例图")
    @GetMapping("/has-real-data")
    public R<Map<String, Object>> hasRealData() {
        boolean has = trainingRecordService.hasRealTrainingData(UserContext.requireUserId());
        return R.ok(Map.of("hasRealData", has));
    }

    @Operation(summary = "记一笔饮食")
    @PostMapping("/diet")
    public R<DietLog> saveDiet(@RequestBody SaveDietRequest request) {
        return R.ok(trainingRecordService.saveDiet(UserContext.requireUserId(), request));
    }

    @Operation(summary = "查某日饮食")
    @GetMapping("/diet")
    public R<List<DietLog>> listDiet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(trainingRecordService.listDiet(UserContext.requireUserId(), date));
    }

    @Operation(summary = "某日饮食汇总", description = "总热量/总蛋白 + 明细，给「我的」页看板")
    @GetMapping("/diet/day")
    public R<DietDayView> dietDay(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return R.ok(trainingRecordService.dietDay(UserContext.requireUserId(), date));
    }

    @Operation(summary = "近几天饮食历史")
    @GetMapping("/diet/history")
    public R<List<DietDayView>> dietHistory(@RequestParam(defaultValue = "7") int days) {
        return R.ok(trainingRecordService.dietHistory(UserContext.requireUserId(), days));
    }

    @Operation(summary = "删除一笔饮食")
    @PostMapping("/diet/{id}/delete")
    public R<Void> deleteDiet(@PathVariable("id") String id) {
        long dietId;
        try {
            dietId = Long.parseLong(id.trim());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "饮食 ID 无效");
        }
        trainingRecordService.deleteDiet(UserContext.requireUserId(), dietId);
        return R.ok();
    }

    @Operation(summary = "复制某日饮食到今天", description = "默认复制昨天；减脂最高频「和昨天一样」")
    @PostMapping("/diet/copy")
    public R<DietDayView> copyDiet(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from) {
        return R.ok(trainingRecordService.copyDietDay(UserContext.requireUserId(), from));
    }

    @Operation(summary = "记体重", description = "同一天重复提交会覆盖，避免噪声点")
    @PostMapping("/body")
    public R<BodyRecord> saveBody(@RequestBody SaveBodyRequest request) {
        return R.ok(trainingRecordService.saveBodyWeight(UserContext.requireUserId(), request));
    }

    @Operation(summary = "最近体重快照")
    @GetMapping("/body/latest")
    public R<BodySnapshot> latestBody() {
        return R.ok(trainingRecordService.latestBody(UserContext.requireUserId()));
    }

    @Operation(summary = "动作要领", description = "步骤/常见错误/呼吸/安全提示，记组时展示")
    @GetMapping("/exercises/{id}/cues")
    public R<ExerciseCueView> exerciseCues(@PathVariable("id") String id) {
        long eid;
        try {
            eid = Long.parseLong(id.trim());
        } catch (Exception e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "动作 ID 无效");
        }
        return R.ok(trainingRecordService.exerciseCue(eid));
    }

    @Operation(summary = "肌群周容量看板",
            description = "与 AI getMuscleVolume 同源：周均有效组 vs MEV/MAV/MRV，缺口肌群排前")
    @GetMapping("/charts/muscle-volume")
    public R<TrainingRecordService.MuscleVolumeBoard> muscleVolume(
            @RequestParam(defaultValue = "4") int weeks) {
        return R.ok(trainingRecordService.muscleVolumeBoard(UserContext.requireUserId(), weeks));
    }

    @Operation(summary = "主力力量曲线（三大项 + 肩推/划船）")
    @GetMapping("/charts/big3")
    public R<ChartSeries> big3(@RequestParam(defaultValue = "12") int weeks) {
        return R.ok(trainingRecordService.big3Trend(UserContext.requireUserId(), weeks));
    }

    @Operation(summary = "平台期提示", description = "卡住 / 刚破平台，给「我的」页主动提示卡")
    @GetMapping("/alerts/plateau")
    public R<List<TrainingRecordService.PlateauAlert>> plateauAlerts() {
        return R.ok(trainingRecordService.plateauAlerts(UserContext.requireUserId()));
    }

    @Operation(summary = "每周训练量柱状图")
    @GetMapping("/charts/weekly-volume")
    public R<List<WeeklyVolumePoint>> weeklyVolume(@RequestParam(defaultValue = "12") int weeks) {
        return R.ok(trainingRecordService.weeklyVolume(UserContext.requireUserId(), weeks));
    }

    @Operation(summary = "体重趋势线")
    @GetMapping("/charts/body-weight")
    public R<List<ChartPoint>> bodyWeight(@RequestParam(defaultValue = "12") int weeks) {
        return R.ok(trainingRecordService.bodyWeightTrend(UserContext.requireUserId(), weeks));
    }

    @Operation(summary = "记录计划执行偏差",
            description = "type=SKIP_SET|SWAP|BAD_FEEL|INJURY_HEAVY。下次生成计划时会按偏差自动调整强度")
    @PostMapping("/deviation")
    public R<PlanDeviation> deviation(@RequestBody DeviationRequest request) {
        return R.ok(planDeviationService.record(
                UserContext.requireUserId(),
                request.sessionId(),
                request.exerciseId(),
                request.type(),
                request.note()));
    }

    @Operation(summary = "查看下次计划应采用的强度系数",
            description = "答辩演示：连续状态差 → factor=0.9")
    @GetMapping("/deviation/hint")
    public R<IntensityHint> deviationHint() {
        return R.ok(planDeviationService.intensityHint(UserContext.requireUserId()));
    }

    @Operation(summary = "手动触发教练提醒扫描",
            description = "答辩演示用。正式环境由每天 21:00 的 @Scheduled 触发，结果打在应用日志 [教练提醒]")
    @PostMapping("/coach/remind-now")
    public R<Map<String, Object>> remindNow() {
        int n = coachReminderService.runEveningCheck();
        return R.ok(Map.of("reminderCount", n, "message", "已执行，详见应用日志 [教练提醒]"));
    }

    public record DeviationRequest(Long sessionId, Long exerciseId, String type, String note) {
    }
}
