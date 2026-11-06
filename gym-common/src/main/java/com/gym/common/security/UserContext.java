package com.gym.common.security;

/**
 * 当前登录用户的线程上下文。
 *
 * <p><b>存在的直接原因：Function Calling 的越权问题。</b>
 *
 * <p>工具方法如果把 userId 写进 {@code @ToolParam}，它就是模型可见的入参。
 * 而模型的入参是从一句自然语言里"理解"出来的——它猜错、被上下文带偏、
 * 或者被用户一句"顺便帮我看看 10086 号卧推多少"诱导过去，都可能查到别人的数据。
 * 这类漏洞的特点是不报错、不越权提示，安静地把 A 的数据送到 B 面前。
 *
 * <p>治这个病不能靠 prompt 里写"只能查当前用户"，prompt 是建议，不是边界。
 * 真正的边界是：让模型<b>根本没有这个参数可填</b>。
 * 把 userId 从工具的 JSON Schema 里彻底移除，改由服务端在方法内部从上下文取，
 * 模型无论怎么输出都构造不出一个跨用户的调用。
 *
 * <p>代价是 ThreadLocal 在异步线程里会失效。
 * 本项目的应对是：异步 AI 任务在提交前把 userId 存进任务记录，
 * 线程池里的执行线程拿到任务后先 {@link #setUserId(Long)} 再跑，结束在 finally 里 clear。
 * 这个取舍值得讲清楚——不是所有场景都能这么用，
 * 但它换来的是"越权在结构上不可能发生"，而不是"我们记得处理了"。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        CURRENT.set(userId);
    }

    public static Long getUserId() {
        return CURRENT.get();
    }

    /** 取不到就抛异常，绝不返回一个 0 或 -1 让调用链继续往下走 */
    public static long requireUserId() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("当前线程没有绑定用户，工具调用必须在用户请求上下文中执行");
        }
        return id;
    }

    /**
     * 必须清理。线程是复用的，不清就等于把上一个用户借给下一个用户。
     * Tomcat 的 200 个工作线程里藏着 200 个残留的用户 ID，这不是理论风险。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
