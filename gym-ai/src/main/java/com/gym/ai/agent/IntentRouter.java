package com.gym.ai.agent;

import org.springframework.stereotype.Component;

/**
 * 意图路由：规则分诊，不额外调模型，省延迟也省 Token。
 *
 * <p>打分制：营养 / 数据 / 教练各计分，取最高；平局默认教练（训练是本系统主场景）。
 */
@Component
public class IntentRouter {

    public AgentRole route(String question) {
        if (question == null || question.isBlank()) {
            return AgentRole.COACH;
        }
        String q = question.toLowerCase();

        int nutrition = scoreNutrition(q);
        int data = scoreData(q);
        int coach = scoreCoach(q);

        if (nutrition >= data && nutrition >= coach && nutrition > 0) {
            return AgentRole.NUTRITION;
        }
        if (data >= coach && data > 0) {
            return AgentRole.DATA;
        }
        return AgentRole.COACH;
    }

    private static int scoreNutrition(String q) {
        int s = 0;
        if (containsAny(q, "吃", "饮食", "热量", "卡路里", "蛋白", "碳水", "脂肪",
                "营养", "餐", "早餐", "午餐", "晚餐", "加餐", "摄入", "tdee", "bmr",
                "减脂餐", "增肌餐", "喝了", "喝水")) {
            s += 3;
        }
        if (containsAny(q, "够不够", "超了吗", "超标")) {
            s += 1;
        }
        // 「练完吃啥」偏营养；纯力量词压分
        if (containsAny(q, "卧推", "深蹲", "硬拉", "平台期", "卡住", "举多少")
                && !containsAny(q, "吃", "饮食", "热量", "蛋白")) {
            s -= 2;
        }
        return Math.max(0, s);
    }

    private static int scoreData(String q) {
        int s = 0;
        if (containsAny(q, "查一下", "查下", "多少次", "几次", "几组", "记录",
                "数据", "统计", "列表", "汇总", "上周", "近一周", "近两周",
                "历史最好", "pb", "最好成绩")) {
            s += 3;
        }
        if (containsAny(q, "多少", "什么时候", "哪天")
                && containsAny(q, "练", "体重", "卧推", "深蹲", "硬拉")) {
            s += 2;
        }
        // 明确要建议 / 计划 / 怎么办 → 不是纯数据
        if (containsAny(q, "怎么办", "怎么练", "计划", "建议", "该不该", "下次举", "卡住了")) {
            s -= 3;
        }
        return Math.max(0, s);
    }

    private static int scoreCoach(String q) {
        int s = 0;
        if (containsAny(q, "训练", "计划", "教练", "怎么练", "安排", "减载",
                "容量", "平台", "卡住", "瓶颈", "进步", "退步", "下次",
                "举多少", "加重量", "破纪录", "卧推", "深蹲", "硬拉",
                "肩推", "肌群", "胸", "背", "腿", "推拉腿")) {
            s += 3;
        }
        if (containsAny(q, "涨没涨", "能推", "能蹲", "能拉", "该加量", "该减量")) {
            s += 2;
        }
        return s;
    }

    private static boolean containsAny(String q, String... keys) {
        for (String k : keys) {
            if (q.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
