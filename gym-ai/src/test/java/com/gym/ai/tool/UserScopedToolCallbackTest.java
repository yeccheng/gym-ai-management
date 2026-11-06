package com.gym.ai.tool;

import com.gym.common.security.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁死「工具在任何线程上执行都能拿到用户身份」这条保证。
 *
 * <p>这几条断言对应的正是 SSE 流式场景的真实故障：
 * 工具被模型客户端的内部线程调用，那条线程上从来没有 set 过 UserContext。
 */
class UserScopedToolCallbackTest {

    /** 模拟真实工具：内部靠 UserContext 取用户，取不到就抛 */
    private static final ToolCallback FAKE_TOOL = new ToolCallback() {
        @Override
        public ToolDefinition getToolDefinition() {
            return new ToolDefinition() {
                @Override
                public String name() {
                    return "fakeTool";
                }

                @Override
                public String description() {
                    return "测试用工具";
                }

                @Override
                public String inputSchema() {
                    return "{}";
                }
            };
        }

        @Override
        public String call(String toolInput) {
            return String.valueOf(UserContext.requireUserId());
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return call(toolInput);
        }
    };

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 未包装的回调在没有用户上下文的线程上必然失败() {
        // 对照组：先证明问题真实存在，否则"包装后通过"这条断言没有意义
        AtomicReference<String> error = new AtomicReference<>("没有抛异常");
        Thread t = new Thread(() -> {
            try {
                FAKE_TOOL.call("{}");
            } catch (IllegalStateException e) {
                error.set(e.getMessage());
            }
        });
        t.start();
        await(t);

        assertTrue(error.get().contains("当前线程没有绑定用户"),
                "未包装时应报「当前线程没有绑定用户」，实际：" + error.get());
    }

    @Test
    void 包装后即使在没有用户上下文的线程上也能拿到正确用户() {
        ToolCallback wrapped = UserScopedToolCallback.wrap(FAKE_TOOL, 42L);

        AtomicReference<Object> result = new AtomicReference<>();
        Thread t = new Thread(() -> result.set(wrapped.call("{}")));
        t.start();
        await(t);

        assertEquals("42", result.get(), "工具应拿到包装时绑定的 userId=42");
    }

    @Test
    void 工具执行完毕后必须清理线程上的用户() {
        ToolCallback wrapped = UserScopedToolCallback.wrap(FAKE_TOOL, 42L);

        AtomicReference<Long> 残留 = new AtomicReference<>();
        Thread t = new Thread(() -> {
            wrapped.call("{}");
            残留.set(UserContext.getUserId());
        });
        t.start();
        await(t);

        assertNull(残留.get(), "工具执行完后线程上的 UserContext 必须是干净的，"
                + "否则线程池复用时会把上一个用户借给下一个用户");
    }

    @Test
    void 包装不改变工具的对外定义() {
        ToolCallback wrapped = UserScopedToolCallback.wrap(FAKE_TOOL, 42L);
        assertEquals("fakeTool", wrapped.getToolDefinition().name(),
                "装饰器必须原样透出工具定义，否则模型看到的工具签名会变");
    }

    private static void await(Thread t) {
        try {
            t.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
