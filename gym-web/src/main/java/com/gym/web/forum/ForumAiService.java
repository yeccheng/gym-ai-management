package com.gym.web.forum;

import com.gym.ai.autoconfigure.GymAiException;
import com.gym.ai.autoconfigure.GymAiTemplate;
import com.gym.system.entity.ForumPost;
import com.gym.system.service.ForumService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 论坛 AI 三件套：内容审核、智能摘要、求助帖 AI 教练回帖。
 * 统一走 {@link GymAiTemplate}（dev=DeepSeek / local=Ollama Qwen）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ForumAiService {

    private final GymAiTemplate gymAiTemplate;
    private final ForumService forumService;
    private final SensitiveWordFilter sensitiveWordFilter;

    public record AuditResult(boolean pass, String reason) {
    }

    /** DFA 兜底 + LLM 审核。AI 不可用时：仅 DFA 通过则放行并标「人工待复核」说明。 */
    public AuditResult audit(String title, String content) {
        String hit = sensitiveWordFilter.findFirst(title + " " + content);
        if (hit != null) {
            return new AuditResult(false, "命中敏感词：" + hit);
        }
        try {
            String sys = """
                    你是健身房社区内容审核员。只判断是否适合公开讨论健身相关内容。
                    输出严格两行：
                    PASS 或 REJECT
                    简短中文理由（不超过40字）
                    拒绝：辱骂、色情、违禁品、引流诈骗、与健身无关的广告。
                    健身经验分享、训练求助、饮食讨论一律 PASS。
                    """;
            String out = gymAiTemplate.chat("标题：" + title + "\n正文：\n" + content, sys);
            if (!StringUtils.hasText(out)) {
                return new AuditResult(true, "AI 无输出，DFA 已过，暂予通过");
            }
            String first = out.lines().findFirst().orElse("").trim().toUpperCase();
            String reason = out.lines().skip(1).findFirst().orElse("AI 审核").trim();
            if (first.startsWith("REJECT") || first.contains("拒绝")) {
                return new AuditResult(false, reason);
            }
            return new AuditResult(true, reason.isEmpty() ? "AI 审核通过" : reason);
        } catch (GymAiException e) {
            log.warn("[forum-ai] 审核降级：{}", e.getMessage());
            return new AuditResult(true, "AI 暂不可用，DFA 已过：" + e.getMessage());
        } catch (Exception e) {
            log.warn("[forum-ai] 审核异常降级", e);
            return new AuditResult(true, "审核异常降级，DFA 已过");
        }
    }

    public String summarize(String title, String content) {
        try {
            String sys = "你是健身社区编辑。用一句中文概括帖子要点，不超过60字，不要引号，不要前缀。";
            String body = content.length() > 1500 ? content.substring(0, 1500) : content;
            String out = gymAiTemplate.chat("标题：" + title + "\n" + body, sys);
            if (!StringUtils.hasText(out)) {
                return null;
            }
            String s = out.replace('\n', ' ').trim();
            return s.length() > 200 ? s.substring(0, 200) : s;
        } catch (Exception e) {
            log.warn("[forum-ai] 摘要失败：{}", e.getMessage());
            return null;
        }
    }

    /** 分类=求助 且审核通过后，以 AI 身份写一条顶层评论。 */
    public void maybeCoachReply(ForumPost post) {
        if (post == null || post.getCategory() == null
                || post.getCategory() != ForumService.CAT_HELP
                || post.getStatus() == null
                || post.getStatus() != ForumService.STATUS_PASS) {
            return;
        }
        // 拒审后再通过会再次触发；已有 AI 顶层评则跳过，避免刷屏
        if (forumService.hasAiTopComment(post.getId())) {
            return;
        }
        try {
            String sys = """
                    你是健身房的 AI 教练，在社区「求助」帖下简短回复。
                    要求：中文、务实、可执行；给 2～4 条建议；提醒疼痛就医；不超过 220 字；不要用 Markdown 标题。
                    """;
            String body = post.getContent().length() > 1200
                    ? post.getContent().substring(0, 1200) : post.getContent();
            String reply = gymAiTemplate.chat("求助标题：" + post.getTitle() + "\n" + body, sys);
            if (!StringUtils.hasText(reply)) {
                return;
            }
            String text = reply.trim();
            if (text.length() > 900) {
                text = text.substring(0, 900);
            }
            // userId 用帖主，靠 is_ai=1 区分展示；避免虚构不存在的系统账号
            forumService.addComment(post.getUserId(), post.getId(), 0L, text, true);
        } catch (Exception e) {
            log.warn("[forum-ai] 教练回帖失败 postId={}: {}", post.getId(), e.getMessage());
        }
    }
}
