package com.gym.training.service;

import com.gym.common.exception.BusinessException;
import com.gym.common.result.ResultCode;
import com.gym.training.entity.PlanDeviation;
import com.gym.training.mapper.PlanDeviationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 记录计划执行偏差，供下次计划生成读取。
 */
@Service
@RequiredArgsConstructor
public class PlanDeviationService {

    private static final Set<String> ALLOWED = Set.of("SKIP_SET", "SWAP", "BAD_FEEL", "INJURY_HEAVY");

    private final PlanDeviationMapper mapper;

    public PlanDeviation record(long userId, Long sessionId, Long exerciseId, String type, String note) {
        if (!StringUtils.hasText(type) || !ALLOWED.contains(type.toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "偏差类型只能是 SKIP_SET / SWAP / BAD_FEEL / INJURY_HEAVY");
        }
        PlanDeviation d = new PlanDeviation();
        d.setUserId(userId);
        d.setSessionId(sessionId);
        d.setExerciseId(exerciseId);
        d.setType(type.toUpperCase(Locale.ROOT));
        d.setNote(note);
        d.setCreateTime(LocalDateTime.now());
        mapper.insert(d);
        return d;
    }

    /** 近 14 天偏差，计划生成时调用：连续 BAD_FEEL≥2 → 强度 ×0.9 */
    public IntensityHint intensityHint(long userId) {
        List<PlanDeviation> recent = mapper.selectRecent(userId, LocalDateTime.now().minusDays(14));
        long badFeel = recent.stream().filter(d -> "BAD_FEEL".equals(d.getType())).count();
        long skip = recent.stream().filter(d -> "SKIP_SET".equals(d.getType())).count();
        double factor = 1.0;
        String reason = "近两周无显著偏差，按原计划执行";
        if (badFeel >= 2) {
            factor = 0.9;
            reason = "近两周有 " + badFeel + " 次「状态差」，建议强度下调 10%";
        } else if (skip >= 3) {
            factor = 0.95;
            reason = "近两周多次减组，建议容量略降";
        }
        return new IntensityHint(factor, reason, recent.size());
    }

    public record IntensityHint(double factor, String reason, int deviationCount) {
    }
}
