package com.gym.web.service;

import com.gym.system.entity.Member;
import com.gym.system.entity.SysUser;
import com.gym.system.mapper.MemberMapper;
import com.gym.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 把会员档案钉进 AI 用户消息，避免「存了档案但教练装不知道」。
 */
@Service
@RequiredArgsConstructor
public class MemberProfilePrompt {

    private final SysUserMapper sysUserMapper;
    private final MemberMapper memberMapper;
    private final JdbcTemplate jdbcTemplate;

    public String append(long userId, String question) {
        if (!StringUtils.hasText(question)) {
            return question;
        }
        String snap = snapshot(userId);
        if (!StringUtils.hasText(snap)) {
            return question;
        }
        return question.trim() + "\n\n（学员档案：" + snap + "。回答时若相关请点到为止，不要复读档案。）";
    }

    private String snapshot(long userId) {
        try {
            SysUser u = sysUserMapper.selectById(userId);
            if (u == null || u.getMemberId() == null) {
                return "";
            }
            Member m = memberMapper.selectById(u.getMemberId());
            if (m == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            if (m.getHeightCm() != null) {
                sb.append("身高").append(m.getHeightCm().stripTrailingZeros().toPlainString()).append("cm ");
            }
            if (m.getGoal() != null) {
                sb.append("目标").append(goalText(m.getGoal())).append(' ');
            }
            if (m.getTrainLevel() != null) {
                sb.append("水平").append(levelText(m.getTrainLevel())).append(' ');
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT injury_note, target_weight_kg, calorie_goal, protein_goal_g FROM member WHERE id=? LIMIT 1",
                    m.getId());
            if (!rows.isEmpty()) {
                Map<String, Object> r = rows.get(0);
                if (r.get("target_weight_kg") != null) {
                    sb.append("目标体重").append(r.get("target_weight_kg")).append("kg ");
                }
                if (r.get("injury_note") != null && StringUtils.hasText(String.valueOf(r.get("injury_note")))) {
                    sb.append("伤病备注「").append(r.get("injury_note")).append("」 ");
                }
                if (r.get("calorie_goal") != null) {
                    sb.append("日热量目标").append(r.get("calorie_goal")).append(' ');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String goalText(int g) {
        return switch (g) {
            case 1 -> "增肌";
            case 2 -> "减脂";
            case 3 -> "力量";
            case 4 -> "塑形";
            case 5 -> "健康";
            default -> "综合";
        };
    }

    private static String levelText(int l) {
        return switch (l) {
            case 1 -> "新手";
            case 2 -> "中级";
            case 3 -> "高级";
            default -> "未知";
        };
    }
}
