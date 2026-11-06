package com.gym.web.forum;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 简易 DFA 敏感词过滤，作为 AI 审核的兜底（漏判时仍能拦硬伤词）。
 * 词表刻意短小，答辩演示够用；生产应外置词库。
 */
@Component
public class SensitiveWordFilter {

    private final Node root = new Node();

    public SensitiveWordFilter() {
        String[] words = {
                "赌博", "色情", "毒品", "枪支", "暴力恐吓",
                "代练刷单", "售卖发票", "加微信嫖"
        };
        for (String w : words) {
            add(w);
        }
    }

    private void add(String word) {
        Node cur = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            cur = cur.children.computeIfAbsent(c, k -> new Node());
        }
        cur.end = true;
    }

    /** @return 命中的第一个敏感词；无命中返回 null */
    public String findFirst(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            Node cur = root;
            for (int j = i; j < chars.length; j++) {
                cur = cur.children.get(chars[j]);
                if (cur == null) {
                    break;
                }
                if (cur.end) {
                    return text.substring(i, j + 1);
                }
            }
        }
        return null;
    }

    private static final class Node {
        private final Map<Character, Node> children = new HashMap<>();
        private boolean end;
    }
}
