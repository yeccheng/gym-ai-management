package com.gym.ai.autoconfigure;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每日 Token 预算。
 *
 * <p>为什么需要它：毕设演示时如果被人反复点「生成」，账单一晚上能烧掉几十块。
 * 更实际的场景是答辩现场网络抖动导致前端重试，一次点击变成多次调用。
 *
 * <p>单机版用 AtomicLong 足够——本项目是单体多模块，没有多实例，
 * 引入 Redis 计数属于为了技术而技术。
 */
public class TokenBudget {

    private final AtomicLong usedToday = new AtomicLong();
    private volatile LocalDate currentDay = LocalDate.now();
    private final long dailyLimit;

    public TokenBudget(long dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public boolean available(long tokens) {
        rotateIfNeeded();
        if (dailyLimit <= 0) {
            return true;
        }
        return usedToday.get() + tokens <= dailyLimit;
    }

    public void record(long tokens) {
        rotateIfNeeded();
        usedToday.addAndGet(tokens);
    }

    public long remaining() {
        rotateIfNeeded();
        if (dailyLimit <= 0) {
            return Long.MAX_VALUE;
        }
        return Math.max(dailyLimit - usedToday.get(), 0);
    }

    /**
     * 跨零点自动清零。
     * 双重检查锁：绝大多数请求走第一个 if 直接返回，不必进入同步块。
     */
    private void rotateIfNeeded() {
        LocalDate today = LocalDate.now();
        if (!today.equals(currentDay)) {
            synchronized (this) {
                if (!today.equals(currentDay)) {
                    usedToday.set(0);
                    currentDay = today;
                }
            }
        }
    }
}
