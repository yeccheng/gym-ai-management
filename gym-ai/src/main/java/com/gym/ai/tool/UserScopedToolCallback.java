package com.gym.ai.tool;

import com.gym.common.security.UserContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 把用户身份绑到「工具执行点」上，而不是绑在线程上。
 *
 * <p><b>它解决的是什么问题。</b>
 *
 * <p>工具的 userId 不写入参（安全设计，见 {@link UserContext}），只能在方法内部从
 * ThreadLocal 取。同步接口下这是成立的：工具方法和请求在同一条线程上。
 * 但流式（SSE）下不成立——Controller 返回 Flux 后 Tomcat 线程就释放了，
 * 而模型响应、工具回调是 reactor-netty 的事件循环线程推过来的，那条线程上
 * 从来没有人 set 过 UserContext，工具一律抛「当前线程没有绑定用户」。
 *
 * <p>先在 Flux.defer 里 set、subscribeOn(boundedElastic) 也没用：
 * 这只保证「流的订阅」发生在某条线程上，管不到模型客户端内部回调所用的线程。
 * 线程是谁的、什么时候切走，由模型客户端的实现决定，业务代码控制不了。
 *
 * <p><b>所以换个绑定点。</b>
 * 工具回调是框架的固定扩展点：无论模型在哪条线程上决定调工具，
 * 真正执行 Java 方法一定会经过 {@link ToolCallback#call(String)}。
 * 在这一层套一个装饰器，进方法前 setUserId、出方法后 clear，
 * 身份就跟线程彻底解耦了——谁来调、在哪条线程调，都一样生效。
 *
 * <p>同时保留了原有的安全边界：userId 依然不进工具的 JSON Schema，
 * 模型依然构造不出跨用户的调用。这里注入的是「当前请求已经认证过的那个用户」，
 * 不是模型传进来的任何东西。
 */
public final class UserScopedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final long userId;

    private UserScopedToolCallback(ToolCallback delegate, long userId) {
        this.delegate = delegate;
        this.userId = userId;
    }

    /** 把已有回调包一层，使它在任何线程上执行时都能拿到 userId */
    public static ToolCallback wrap(ToolCallback delegate, long userId) {
        return new UserScopedToolCallback(delegate, userId);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    /**
     * 身份只在这一个方法的作用域内有效。
     * 用完必须 clear：执行工具的是框架线程池里的线程，会被下一个请求复用，
     * 残留的 userId 等于把上一个用户借给下一个用户。
     */
    @Override
    public String call(String toolInput, ToolContext toolContext) {
        UserContext.setUserId(userId);
        try {
            return delegate.call(toolInput, toolContext);
        } finally {
            UserContext.clear();
        }
    }
}
