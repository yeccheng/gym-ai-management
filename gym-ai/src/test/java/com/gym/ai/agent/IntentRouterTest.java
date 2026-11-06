package com.gym.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRouterTest {

    private final IntentRouter router = new IntentRouter();

    @Test
    void routesStrengthToCoach() {
        assertEquals(AgentRole.COACH, router.route("我的卧推是不是卡住了"));
    }

    @Test
    void routesDietToNutrition() {
        assertEquals(AgentRole.NUTRITION, router.route("今天蛋白吃够了吗"));
    }

    @Test
    void routesStatsToData() {
        assertEquals(AgentRole.DATA, router.route("帮我查一下上周练了几次"));
    }
}
