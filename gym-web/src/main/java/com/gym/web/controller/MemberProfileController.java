package com.gym.web.controller;

import com.gym.common.exception.BusinessException;
import com.gym.common.result.R;
import com.gym.common.result.ResultCode;
import com.gym.common.security.UserContext;
import com.gym.system.entity.Member;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.MemberMapper;
import com.gym.system.mapper.SysUserMapper;
import com.gym.training.metric.TrainMetrics;
import com.gym.training.metric.TrainMetrics.ActivityLevel;
import com.gym.training.service.TrainingRecordService;
import com.gym.training.service.TrainingRecordService.BodySnapshot;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 会员自己的练铁档案（不是管理员 CRUD）。
 */
@Tag(name = "个人档案")
@RestController
@RequestMapping("/training/profile")
@RequiredArgsConstructor
public class MemberProfileController {

    private final SysUserMapper sysUserMapper;
    private final MemberMapper memberMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TrainingRecordService trainingRecordService;

    @Operation(summary = "读取我的练铁档案")
    @GetMapping
    public R<ProfileView> get() {
        ensureExtraColumns();
        return R.ok(toView(requireMember()));
    }

    @Operation(summary = "更新我的练铁档案")
    @PutMapping
    public R<ProfileView> update(@RequestBody ProfileUpdate body) {
        ensureExtraColumns();
        if (body == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "档案不能为空");
        }
        Member m = requireMember();
        if (body.heightCm() != null) {
            if (body.heightCm() < 100 || body.heightCm() > 250) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "身高请填 100～250 cm");
            }
            m.setHeightCm(BigDecimal.valueOf(body.heightCm()));
        }
        if (body.goal() != null) {
            if (body.goal() < 1 || body.goal() > 5) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "目标无效");
            }
            m.setGoal(body.goal());
        }
        if (body.trainLevel() != null) {
            if (body.trainLevel() < 1 || body.trainLevel() > 3) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "训练水平无效");
            }
            m.setTrainLevel(body.trainLevel());
        }
        if (StringUtils.hasText(body.displayName())) {
            String name = body.displayName().trim();
            m.setName(name);
            SysUser u = sysUserMapper.selectById(UserContext.requireUserId());
            if (u != null) {
                u.setRealName(name);
                sysUserMapper.updateById(u);
            }
        }
        if (body.trainingYears() != null) {
            if (body.trainingYears() < 0 || body.trainingYears() > 40) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "训练年限请填 0～40");
            }
            // 用 joinDate 反推：现在往前推 years*12 个月
            m.setJoinDate(LocalDate.now().minusMonths(Math.round(body.trainingYears() * 12)));
        }
        memberMapper.updateById(m);
        if (body.injuryNote() != null) {
            String note = body.injuryNote().trim();
            if (note.length() > 120) {
                note = note.substring(0, 120);
            }
            jdbcTemplate.update("UPDATE member SET injury_note = ? WHERE id = ?", note, m.getId());
        }
        if (body.targetWeightKg() != null) {
            if (body.targetWeightKg() < 30 || body.targetWeightKg() > 250) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "目标体重不合理");
            }
            jdbcTemplate.update("UPDATE member SET target_weight_kg = ? WHERE id = ?",
                    body.targetWeightKg(), m.getId());
        }
        if (body.calorieGoal() != null) {
            jdbcTemplate.update("UPDATE member SET calorie_goal = ? WHERE id = ?",
                    body.calorieGoal(), m.getId());
        }
        if (body.proteinGoalG() != null) {
            jdbcTemplate.update("UPDATE member SET protein_goal_g = ? WHERE id = ?",
                    body.proteinGoalG(), m.getId());
        }
        return R.ok(toView(requireMember()));
    }

    /**
     * 按 Mifflin-St Jeor 算 BMR/TDEE，再按档案目标给出日热量与蛋白目标。
     * 与智能体工具 getNutritionPlan 同一套公式——模型不算数，代码算准。
     * apply=true 时写回 calorie_goal / protein_goal_g。
     */
    @Operation(summary = "按公式生成营养目标", description = "BMR/TDEE + 减脂-500 / 增肌+300；可选写回档案")
    @PostMapping("/nutrition-suggest")
    public R<NutritionSuggestView> nutritionSuggest(@RequestBody(required = false) NutritionSuggestRequest body) {
        ensureExtraColumns();
        NutritionSuggestRequest req = body == null ? new NutritionSuggestRequest(null, null, null, null, null, null, null) : body;
        Member m = requireMember();
        long uid = UserContext.requireUserId();

        Double height = req.heightCm() != null ? req.heightCm()
                : (m.getHeightCm() == null ? null : m.getHeightCm().doubleValue());
        Double weight = req.weightKg();
        if (weight == null) {
            BodySnapshot snap = trainingRecordService.latestBody(uid);
            if (snap != null && snap.available() && snap.weightKg() != null) {
                weight = snap.weightKg();
            }
        }
        if (weight == null && m.getWeightKg() != null) {
            weight = m.getWeightKg().doubleValue();
        }

        int age = resolveAge(m, req.age());
        boolean male = resolveMale(m, req.sex());
        ActivityLevel level = parseActivity(req.activityLevel());
        int goal = req.goal() != null ? req.goal() : (m.getGoal() == null ? 3 : m.getGoal());

        if (height == null || height < 100 || height > 250) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先在档案里填身高（100～250 cm）");
        }
        if (weight == null || weight < 30 || weight > 250) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请先记一笔当前体重，或在请求里带 weightKg");
        }
        if (age <= 0 || age > 90) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "年龄不合理");
        }

        double bmr = TrainMetrics.bmr(weight, height, age, male);
        double tdee = TrainMetrics.tdee(bmr, level);
        int cut = (int) Math.round(tdee - 500);
        int bulk = (int) Math.round(tdee + 300);
        int maintain = (int) Math.round(tdee);

        String mode;
        int calorieGoal;
        String tip;
        if (goal == 2) { // 减脂
            mode = "cut";
            calorieGoal = Math.max(cut, 1200);
            tip = "减脂：TDEE−500kcal，约每周掉 0.4～0.5kg；蛋白按体重给够，优先保肌。";
        } else if (goal == 1) { // 增肌
            mode = "bulk";
            calorieGoal = bulk;
            tip = "增肌：TDEE+300kcal，盈余别过大；蛋白与训练容量一起涨。";
        } else if (goal == 4) { // 塑形：轻亏
            mode = "cut";
            calorieGoal = Math.max((int) Math.round(tdee - 300), 1200);
            tip = "塑形：轻度热量缺口（约 −300），配合力量训练重塑体态。";
        } else {
            mode = "maintain";
            calorieGoal = maintain;
            tip = "维持/力量向：先吃到 TDEE 附近，把训练质量拉起来再调热量。";
        }

        TrainMetrics.Macro macro = TrainMetrics.macro(calorieGoal, weight);
        int protein = (int) Math.round(macro.proteinG());
        int fat = (int) Math.round(macro.fatG());
        int carb = (int) Math.round(macro.carbG());

        boolean applied = false;
        if (Boolean.TRUE.equals(req.apply())) {
            jdbcTemplate.update("UPDATE member SET calorie_goal = ?, protein_goal_g = ? WHERE id = ?",
                    calorieGoal, protein, m.getId());
            // 身高若请求带来且档案空，顺手补上
            if (m.getHeightCm() == null && req.heightCm() != null) {
                m.setHeightCm(BigDecimal.valueOf(req.heightCm()));
                memberMapper.updateById(m);
            }
            if (req.goal() != null && req.goal() >= 1 && req.goal() <= 5) {
                m.setGoal(req.goal());
                memberMapper.updateById(m);
            }
            applied = true;
        }

        return R.ok(new NutritionSuggestView(
                true,
                "",
                (int) Math.round(bmr),
                (int) Math.round(tdee),
                mode,
                calorieGoal,
                protein,
                fat,
                carb,
                cut,
                bulk,
                age,
                male ? "male" : "female",
                level.name().toLowerCase(),
                weight,
                height,
                goal,
                applied,
                tip));
    }

    private static int resolveAge(Member m, Integer reqAge) {
        if (reqAge != null && reqAge > 0) {
            return reqAge;
        }
        if (m.getBirthday() != null) {
            int a = Period.between(m.getBirthday(), LocalDate.now()).getYears();
            if (a > 0 && a < 90) {
                return a;
            }
        }
        return 25; // 档案未填生日时的答辩默认值
    }

    private static boolean resolveMale(Member m, String sex) {
        if (StringUtils.hasText(sex)) {
            return !"female".equalsIgnoreCase(sex.trim());
        }
        // member.gender：0 未知 1 男 2 女
        if (m.getGender() != null && m.getGender() == 2) {
            return false;
        }
        return true;
    }

    private static ActivityLevel parseActivity(String raw) {
        if (!StringUtils.hasText(raw)) {
            return ActivityLevel.MODERATE;
        }
        String key = raw.trim().toUpperCase().replace('-', '_');
        for (ActivityLevel level : ActivityLevel.values()) {
            if (level.name().equals(key)) {
                return level;
            }
        }
        return ActivityLevel.MODERATE;
    }

    private void ensureExtraColumns() {
        ensureColumn("injury_note",
                "ALTER TABLE member ADD COLUMN injury_note VARCHAR(120) NULL COMMENT '伤病备注' AFTER train_level");
        ensureColumn("target_weight_kg",
                "ALTER TABLE member ADD COLUMN target_weight_kg DECIMAL(5,1) NULL COMMENT '目标体重kg' AFTER weight_kg");
        ensureColumn("calorie_goal",
                "ALTER TABLE member ADD COLUMN calorie_goal INT NULL COMMENT '每日热量目标' AFTER target_weight_kg");
        ensureColumn("protein_goal_g",
                "ALTER TABLE member ADD COLUMN protein_goal_g INT NULL COMMENT '每日蛋白目标g' AFTER calorie_goal");
    }

    private void ensureColumn(String col, String ddl) {
        try {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS "
                            + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'member' "
                            + "AND COLUMN_NAME = ?",
                    Integer.class, col);
            if (n != null && n == 0) {
                jdbcTemplate.execute(ddl);
            }
        } catch (Exception ignored) {
            // 无权限改表时跳过
        }
    }

    private Member requireMember() {
        SysUser user = sysUserMapper.selectById(UserContext.requireUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "请先登录");
        }
        if (user.getMemberId() != null) {
            Member existing = memberMapper.selectById(user.getMemberId());
            if (existing != null) {
                return existing;
            }
        }
        // demo / 老账号可能没有挂 member——现场补一张练铁档案，别让「我的」空白
        Member m = new Member();
        String display = StringUtils.hasText(user.getRealName()) ? user.getRealName().trim() : user.getUsername();
        if ("demo".equalsIgnoreCase(display)) {
            display = "铁友";
        }
        m.setName(display);
        m.setPhone("9" + String.format("%014d", Math.abs(user.getId() % 100_000_000_000_000L)));
        m.setGender(0);
        m.setGoal(3);
        m.setTrainLevel(2);
        m.setHeightCm(BigDecimal.valueOf(175));
        m.setJoinDate(LocalDate.now().minusMonths(6));
        m.setStatus(1);
        m.setDeleted(0);
        memberMapper.insert(m);
        user.setMemberId(m.getId());
        sysUserMapper.updateById(user);
        return m;
    }

    private ProfileView toView(Member m) {
        String injury = null;
        Double targetW = null;
        Integer calGoal = null;
        Integer proGoal = null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT injury_note, target_weight_kg, calorie_goal, protein_goal_g "
                            + "FROM member WHERE id = ? LIMIT 1", m.getId());
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                if (row.get("injury_note") != null) {
                    injury = String.valueOf(row.get("injury_note"));
                }
                if (row.get("target_weight_kg") != null) {
                    targetW = ((Number) row.get("target_weight_kg")).doubleValue();
                }
                if (row.get("calorie_goal") != null) {
                    calGoal = ((Number) row.get("calorie_goal")).intValue();
                }
                if (row.get("protein_goal_g") != null) {
                    proGoal = ((Number) row.get("protein_goal_g")).intValue();
                }
            }
        } catch (Exception ignored) {
            // 列尚未建好时忽略
        }
        Integer months = m.getJoinDate() == null ? null
                : (int) ChronoUnit.MONTHS.between(m.getJoinDate(), LocalDate.now());
        Double years = months == null ? null : Math.round(months / 12.0 * 10) / 10.0;
        return new ProfileView(
                m.getName(),
                m.getHeightCm() == null ? null : m.getHeightCm().doubleValue(),
                m.getWeightKg() == null ? null : m.getWeightKg().doubleValue(),
                targetW,
                m.getGoal(),
                m.getTrainLevel(),
                m.getJoinDate() == null ? null : m.getJoinDate().toString(),
                injury,
                months,
                years,
                calGoal,
                proGoal);
    }

    public record ProfileView(String displayName, Double heightCm, Double weightKg,
                              Double targetWeightKg, Integer goal, Integer trainLevel,
                              String joinDate, String injuryNote, Integer trainingMonths,
                              Double trainingYears, Integer calorieGoal, Integer proteinGoalG) {
    }

    public record ProfileUpdate(String displayName, Double heightCm, Integer goal,
                                Integer trainLevel, String injuryNote, Double targetWeightKg,
                                Double trainingYears, Integer calorieGoal, Integer proteinGoalG) {
    }

    public record NutritionSuggestRequest(Integer age, String sex, String activityLevel,
                                          Double weightKg, Double heightCm, Integer goal,
                                          Boolean apply) {
    }

    public record NutritionSuggestView(boolean available, String reason,
                                       int bmr, int tdee, String mode,
                                       int calorieGoal, int proteinGoalG,
                                       int fatGoalG, int carbGoalG,
                                       int cutCalories, int bulkCalories,
                                       int ageUsed, String sexUsed, String activityUsed,
                                       double weightKgUsed, double heightCmUsed, int goalUsed,
                                       boolean applied, String tip) {
    }
}
