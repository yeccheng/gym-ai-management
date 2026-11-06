package com.gym.web.service;

import com.gym.ai.rag.RagAnswer;
import com.gym.training.service.TrainingRecordService;
import com.gym.training.service.TrainingRecordService.ExerciseCueView;
import com.gym.training.service.TrainingRecordService.ExerciseView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ES / RAG 不可用时，用动作库要领做本地兜底，避免知识库页当场翻车。
 */
@Service
@RequiredArgsConstructor
public class LocalKnowledgeFallback {

    private final TrainingRecordService trainingRecordService;

    public RagAnswer answer(String question) {
        String q = question == null ? "" : question.trim();
        if (!StringUtils.hasText(q)) {
            return RagAnswer.refused("问题为空");
        }
        List<ExerciseView> hits = trainingRecordService.searchExercises(null, extractKeyword(q));
        if (hits.isEmpty()) {
            hits = trainingRecordService.searchExercises(null, null);
        }
        List<String> blocks = new ArrayList<>();
        List<String> cites = new ArrayList<>();
        int n = 0;
        for (ExerciseView ex : hits) {
            if (n >= 3) {
                break;
            }
            if (!nameMatches(q, ex.name())) {
                continue;
            }
            ExerciseCueView cue = trainingRecordService.exerciseCue(ex.id());
            if (cue == null || !cue.hasAny()) {
                continue;
            }
            n++;
            StringBuilder sb = new StringBuilder();
            sb.append("【").append(ex.name()).append("】\n");
            if (StringUtils.hasText(cue.steps())) {
                sb.append("怎么做：").append(cue.steps()).append('\n');
            }
            if (StringUtils.hasText(cue.commonMistakes())) {
                sb.append("别犯：").append(cue.commonMistakes()).append('\n');
            }
            if (StringUtils.hasText(cue.breathing())) {
                sb.append("呼吸：").append(cue.breathing()).append('\n');
            }
            if (StringUtils.hasText(cue.safetyTips())) {
                sb.append("安全：").append(cue.safetyTips()).append('\n');
            }
            blocks.add(sb.toString().trim());
            cites.add("本地要领-" + ex.id());
        }
        if (blocks.isEmpty()) {
            // 再放宽：取库里前 2 个有要领的基础动作
            for (ExerciseView ex : trainingRecordService.searchExercises("free", null)) {
                if (blocks.size() >= 2) {
                    break;
                }
                ExerciseCueView cue = trainingRecordService.exerciseCue(ex.id());
                if (cue == null || !cue.hasAny()) {
                    continue;
                }
                blocks.add("【" + ex.name() + "】\n怎么做：" + nullToEmpty(cue.steps())
                        + "\n别犯：" + nullToEmpty(cue.commonMistakes()));
                cites.add("本地要领-" + ex.id());
            }
        }
        if (blocks.isEmpty()) {
            return RagAnswer.refused("本地动作要领也未覆盖该问题；请到打卡页打开具体动作的「动作要领」");
        }
        RagAnswer ans = new RagAnswer();
        ans.setRefused(false);
        ans.setAnswer("（本地要领兜底，ES 未就绪时仍可看）\n\n" + String.join("\n\n", blocks));
        ans.setCitations(cites);
        ans.setRecalledSections(cites);
        return ans;
    }

    private static String extractKeyword(String q) {
        for (String k : List.of("卧推", "深蹲", "硬拉", "肩推", "划船", "引体", "弯举", "推举")) {
            if (q.contains(k)) {
                return k;
            }
        }
        return q.length() > 8 ? q.substring(0, 8) : q;
    }

    private static boolean nameMatches(String q, String name) {
        if (!StringUtils.hasText(name)) {
            return false;
        }
        if (q.contains(name) || name.length() >= 2 && q.contains(name.substring(0, Math.min(2, name.length())))) {
            return true;
        }
        for (String k : List.of("卧推", "深蹲", "硬拉", "肩推", "划船", "引体", "弯举")) {
            if (q.contains(k) && name.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
