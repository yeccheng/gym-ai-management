package com.gym.ai.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 userId 的滑动窗口多轮记忆（进程内）。
 *
 * <p>毕设演示足够；重启清空。生产可换成 Redis List，接口不用改。
 */
@Component
public class AgentChatMemory {

    private final ConcurrentHashMap<Long, Deque<Turn>> store = new ConcurrentHashMap<>();

    /** 保留最近多少条消息（用户+助手各算一条） */
    @Value("${gym.ai.memory-window:8}")
    private int windowSize;

    public String formatContext(long userId) {
        Deque<Turn> turns = store.get(userId);
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【近期对话（供参考，以本轮问题为准）】\n");
        for (Turn t : turns) {
            sb.append(t.roleLabel()).append(": ").append(t.text()).append('\n');
        }
        return sb.toString().trim();
    }

    public void append(long userId, AgentRole agent, String userText, String assistantText) {
        if (userId <= 0) {
            return;
        }
        Deque<Turn> turns = store.computeIfAbsent(userId, id -> new ArrayDeque<>());
        synchronized (turns) {
            turns.addLast(new Turn("用户", trim(userText)));
            turns.addLast(new Turn("助手[" + agent.displayName() + "]", trim(assistantText)));
            while (turns.size() > Math.max(2, windowSize)) {
                turns.removeFirst();
            }
        }
    }

    public void clear(long userId) {
        store.remove(userId);
    }

    public List<Turn> snapshot(long userId) {
        Deque<Turn> turns = store.get(userId);
        if (turns == null) {
            return List.of();
        }
        synchronized (turns) {
            return new ArrayList<>(turns);
        }
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        return t.length() > 800 ? t.substring(0, 800) + "…" : t;
    }

    public record Turn(String roleLabel, String text) {
    }
}
