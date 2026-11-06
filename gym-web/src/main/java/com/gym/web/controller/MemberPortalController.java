package com.gym.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.gym.common.exception.BusinessException;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.common.security.UserContext;
import com.gym.system.entity.Booking;
import com.gym.system.entity.ConsumeRecord;
import com.gym.system.entity.Course;
import com.gym.system.entity.Member;
import com.gym.system.entity.MembershipCard;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.ConsumeRecordMapper;
import com.gym.system.mapper.MemberMapper;
import com.gym.system.mapper.MembershipCardMapper;
import com.gym.system.mapper.SysUserMapper;
import com.gym.system.service.BookingService;
import com.gym.system.service.ConsumeService;
import com.gym.system.service.CourseService;
import com.gym.training.service.CoachReminderService;
import com.gym.training.service.PlanDeviationService;
import com.gym.training.service.PlanDeviationService.IntensityHint;
import com.gym.training.service.TrainingRecordService;
import com.gym.training.service.TrainingRecordService.MuscleVolumeBoard;
import com.gym.training.service.TrainingRecordService.MuscleVolumeItem;
import com.gym.training.service.TrainingRecordService.PlateauAlert;
import com.gym.web.service.WeekPlanStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会员自己的场馆入口 + 周计划 + 提醒中心。
 * 不走 /api/** 管理员 CRUD，避免会员气质变回毕设后台。
 */
@Tag(name = "会员主页扩展")
@RestController
@RequestMapping("/training/portal")
@RequiredArgsConstructor
public class MemberPortalController {

    private final SysUserMapper sysUserMapper;
    private final MemberMapper memberMapper;
    private final CourseService courseService;
    private final BookingService bookingService;
    private final ConsumeService consumeService;
    private final MembershipCardMapper cardMapper;
    private final ConsumeRecordMapper consumeRecordMapper;
    private final CoachReminderService coachReminderService;
    private final PlanDeviationService planDeviationService;
    private final TrainingRecordService trainingRecordService;
    private final WeekPlanStore weekPlanStore;

    @Operation(summary = "可预约课程（近 14 天）")
    @GetMapping("/courses")
    public R<List<Course>> openCourses() {
        LocalDateTime from = LocalDateTime.now().minusHours(1);
        LocalDateTime to = LocalDateTime.now().plusDays(14);
        List<Course> list = courseService.list(new QueryWrapper<Course>()
                .in("status", 1, 2)
                .ge("course_time", from)
                .le("course_time", to)
                .orderByAsc("course_time")
                .last("LIMIT 30"));
        return R.ok(list);
    }

    @Operation(summary = "我的预约（含课程名人话）")
    @GetMapping("/bookings")
    public R<List<BookingView>> myBookings() {
        List<Booking> raw = bookingService.listByMember(requireMemberId());
        List<BookingView> out = new ArrayList<>();
        for (Booking b : raw) {
            Course c = b.getCourseId() == null ? null : courseService.getById(b.getCourseId());
            String statusLabel = switch (b.getStatus() == null ? 0 : b.getStatus()) {
                case 1 -> "已预约（可取消）";
                case 2 -> "已完成";
                case 3 -> "已取消";
                default -> "未知";
            };
            out.add(new BookingView(
                    b.getId(),
                    b.getCourseId(),
                    c == null ? ("课程#" + b.getCourseId()) : c.getName(),
                    c == null || c.getCourseTime() == null ? "" : c.getCourseTime().toString(),
                    c == null ? "" : (c.getRoom() == null ? "" : c.getRoom()),
                    b.getStatus(),
                    statusLabel,
                    "开课前可取消；完成后会记消课流水"));
        }
        return R.ok(out);
    }

    public record BookingView(Long id, Long courseId, String courseName, String courseTime,
                              String room, Integer status, String statusLabel, String ruleHint) {
    }

    @Operation(summary = "预约一门课")
    @PostMapping("/book")
    public R<Void> book(@RequestParam Long courseId) {
        courseService.book(requireMemberId(), courseId);
        return R.ok();
    }

    @Operation(summary = "取消我的预约")
    @PostMapping("/bookings/{id}/cancel")
    public R<Void> cancel(@PathVariable Long id) {
        Booking b = bookingService.getById(id);
        if (b == null || !requireMemberId().equals(b.getMemberId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能取消自己的预约");
        }
        courseService.cancelBooking(id);
        return R.ok();
    }

    @Operation(summary = "入场签到消次")
    @PostMapping("/check-in")
    public R<Map<String, Object>> checkIn() {
        Long memberId = requireMemberId();
        consumeService.checkIn(memberId);
        return R.ok(Map.of("ok", true, "message", "签到成功，已扣 1 次"));
    }

    @Operation(summary = "我的会员卡")
    @GetMapping("/cards")
    public R<List<MembershipCard>> myCards() {
        return R.ok(cardMapper.selectList(new QueryWrapper<MembershipCard>()
                .eq("member_id", requireMemberId())
                .orderByDesc("id")));
    }

    @Operation(summary = "我的消费流水")
    @GetMapping("/consume")
    public R<List<ConsumeRecord>> myConsume() {
        return R.ok(consumeRecordMapper.selectList(new QueryWrapper<ConsumeRecord>()
                .eq("member_id", requireMemberId())
                .orderByDesc("create_time")
                .last("LIMIT 40")));
    }

    @Operation(summary = "教练提醒中心（当前用户）")
    @GetMapping("/reminders")
    public R<List<String>> reminders() {
        return R.ok(coachReminderService.tipsForUser(UserContext.requireUserId()));
    }

    @Operation(summary = "下周训练日程（可编辑，落库）")
    @GetMapping("/week-plan")
    public R<WeekPlanView> weekPlan() {
        long userId = UserContext.requireUserId();
        IntensityHint hint = planDeviationService.intensityHint(userId);
        MuscleVolumeBoard vol = trainingRecordService.muscleVolumeBoard(userId, 4);
        List<PlateauAlert> plateaus = trainingRecordService.plateauAlerts(userId);
        List<String> gapMuscles = new ArrayList<>();
        if (vol != null && vol.items() != null) {
            for (MuscleVolumeItem it : vol.items()) {
                if ("BELOW_MEV".equals(it.verdict())) {
                    gapMuscles.add(it.muscle());
                }
            }
        }
        WeekPlanStore.StoredPlan stored = weekPlanStore.load(userId, LocalDate.now());
        if (stored != null && stored.days() != null && !stored.days().isEmpty()) {
            List<DayPlan> days = stored.days().stream().map(this::toDayPlan).toList();
            return R.ok(new WeekPlanView(hint.factor(), hint.reason(), gapMuscles, days,
                    stored.done() == null ? Map.of() : stored.done(), stored.weekStart(), true));
        }
        List<DayPlan> generated = generateDefaultWeek(hint, gapMuscles, plateaus);
        LocalDate weekStart = weekPlanStore.weekStart(LocalDate.now());
        List<WeekPlanStore.StoredDay> toStore = generated.stream().map(this::toStoredDay).toList();
        weekPlanStore.save(userId, weekStart, toStore, Map.of());
        return R.ok(new WeekPlanView(hint.factor(), hint.reason(), gapMuscles, generated,
                Map.of(), weekStart.toString(), false));
    }

    @Operation(summary = "保存周计划（换动作/改组数/休息日/勾选）")
    @PostMapping("/week-plan")
    public R<WeekPlanView> saveWeekPlan(@RequestBody SaveWeekPlanRequest body) {
        long userId = UserContext.requireUserId();
        LocalDate weekStart = weekPlanStore.weekStart(LocalDate.now());
        List<WeekPlanStore.StoredDay> days = new ArrayList<>();
        if (body != null && body.days() != null) {
            for (DayPlan d : body.days()) {
                days.add(toStoredDay(d));
            }
        }
        Map<String, Boolean> done = body == null || body.done() == null ? Map.of() : body.done();
        weekPlanStore.save(userId, weekStart, days, done);
        IntensityHint hint = planDeviationService.intensityHint(userId);
        return R.ok(new WeekPlanView(hint.factor(), hint.reason(), List.of(),
                days.stream().map(this::toDayPlan).toList(), done, weekStart.toString(), true));
    }

    @Operation(summary = "重新生成默认周计划（覆盖已改内容）")
    @PostMapping("/week-plan/regenerate")
    public R<WeekPlanView> regenerateWeekPlan() {
        long userId = UserContext.requireUserId();
        IntensityHint hint = planDeviationService.intensityHint(userId);
        MuscleVolumeBoard vol = trainingRecordService.muscleVolumeBoard(userId, 4);
        List<PlateauAlert> plateaus = trainingRecordService.plateauAlerts(userId);
        List<String> gapMuscles = new ArrayList<>();
        if (vol != null && vol.items() != null) {
            for (MuscleVolumeItem it : vol.items()) {
                if ("BELOW_MEV".equals(it.verdict())) {
                    gapMuscles.add(it.muscle());
                }
            }
        }
        List<DayPlan> generated = generateDefaultWeek(hint, gapMuscles, plateaus);
        LocalDate weekStart = weekPlanStore.weekStart(LocalDate.now());
        weekPlanStore.save(userId, weekStart, generated.stream().map(this::toStoredDay).toList(), Map.of());
        return R.ok(new WeekPlanView(hint.factor(), hint.reason(), gapMuscles, generated,
                Map.of(), weekStart.toString(), false));
    }

    private List<DayPlan> generateDefaultWeek(IntensityHint hint, List<String> gapMuscles,
                                              List<PlateauAlert> plateaus) {
        String plateauNote = plateaus.isEmpty() ? ""
                : plateaus.get(0).exerciseName() + (plateaus.get(0).plateau() ? "仍在平台期" : "刚破平台");
        String[] cycle = {"推（胸肩三头）", "拉（背二头）", "腿（蹲拉核心）", "恢复/有氧或补弱项",
                "推（变式+侧平举）", "拉（划船+面拉）", "腿或主动恢复"};
        String[][] presets = {
                {"杠铃卧推", "推举", "绳索夹胸"},
                {"引体向上", "杠铃划船", "面拉"},
                {"杠铃深蹲", "罗马尼亚硬拉", "腿举"},
                {"慢跑", "卷腹", "泡沫轴"},
                {"哑铃卧推", "侧平举", "绳索下压"},
                {"高位下拉", "哑铃划船", "二头弯举"},
                {"腿举", "腿弯举", "走路"}
        };
        LocalDate start = LocalDate.now();
        List<DayPlan> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = start.plusDays(i);
            String focus = cycle[i % cycle.length];
            boolean rest = focus.startsWith("恢复");
            String extra = "";
            if (i == 0 && !gapMuscles.isEmpty()) {
                extra = "优先补容量：" + String.join("、", gapMuscles.subList(0, Math.min(2, gapMuscles.size())));
            }
            if (i <= 1 && StringUtils.hasText(plateauNote) && focus.startsWith("推")) {
                extra = (extra.isEmpty() ? "" : extra + "；") + plateauNote + "→ 主项用变式/90%重量";
            }
            if (hint.factor() < 1.0) {
                extra = (extra.isEmpty() ? "" : extra + "；") + "强度×" + hint.factor() + "（" + hint.reason() + "）";
            }
            List<PlanExercise> exs = new ArrayList<>();
            for (String name : presets[i % presets.length]) {
                exs.add(new PlanExercise(null, name, null, 8, 3));
            }
            days.add(new DayPlan(d.toString(), d.getDayOfWeek().toString(), focus, extra, rest, exs));
        }
        return days;
    }

    private DayPlan toDayPlan(WeekPlanStore.StoredDay d) {
        List<PlanExercise> exs = new ArrayList<>();
        if (d.exercises() != null) {
            for (WeekPlanStore.StoredExercise e : d.exercises()) {
                exs.add(new PlanExercise(e.exerciseId(), e.name(), e.targetWeightKg(),
                        e.targetReps(), e.targetSets()));
            }
        }
        return new DayPlan(d.date(), d.weekday(), d.focus(), d.note(), d.restDay(), exs);
    }

    private WeekPlanStore.StoredDay toStoredDay(DayPlan d) {
        List<WeekPlanStore.StoredExercise> exs = new ArrayList<>();
        if (d.exercises() != null) {
            for (PlanExercise e : d.exercises()) {
                exs.add(new WeekPlanStore.StoredExercise(
                        e.exerciseId(), e.name(), e.targetWeightKg(), e.targetReps(), e.targetSets()));
            }
        }
        return new WeekPlanStore.StoredDay(
                d.date(), d.weekday(), d.focus(), d.note(), exs, d.restDay());
    }

    private Long requireMemberId() {
        SysUser user = sysUserMapper.selectById(UserContext.requireUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        if (user.getMemberId() == null) {
            Member m = new Member();
            String display = StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername();
            m.setName(display);
            m.setPhone("9" + String.format("%014d", Math.abs(user.getId() % 100_000_000_000_000L)));
            m.setGender(0);
            m.setGoal(3);
            m.setTrainLevel(2);
            m.setJoinDate(LocalDate.now());
            m.setStatus(1);
            m.setDeleted(0);
            memberMapper.insert(m);
            user.setMemberId(m.getId());
            sysUserMapper.updateById(user);
            return m.getId();
        }
        return user.getMemberId();
    }

    public record PlanExercise(Long exerciseId, String name, Double targetWeightKg,
                               Integer targetReps, Integer targetSets) {
    }

    public record DayPlan(String date, String weekday, String focus, String note,
                          boolean restDay, List<PlanExercise> exercises) {
        public DayPlan(String date, String weekday, String focus, String note) {
            this(date, weekday, focus, note, false, List.of());
        }
    }

    public record WeekPlanView(double intensityFactor, String intensityReason,
                               List<String> volumeGaps, List<DayPlan> days,
                               Map<String, Boolean> done, String weekStart, boolean persisted) {
    }

    public record SaveWeekPlanRequest(List<DayPlan> days, Map<String, Boolean> done) {
    }
}
