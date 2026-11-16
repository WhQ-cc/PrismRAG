package com.prism.rag.framework.context;

import org.springframework.core.task.TaskDecorator;

/**
 * 上下文透传装饰器。
 *
 * <p>Spring 的 {@code ThreadPoolTaskExecutor#setTaskDecorator} 会在<b>提交任务的线程</b>里
 * 调用 {@link #decorate(Runnable)} 拿到包装后的任务，再把这个包装任务丢进池子。
 * 于是"捕获"发生在提交方线程，"恢复"发生在执行方线程——正好解决 ThreadLocal 跨线程池失效的问题。</p>
 *
 * <p>执行完必须把原上下文还原（而不是直接 remove），
 * 否则如果这个池线程本身还带着别的上下文（比如嵌套提交），会被误删。</p>
 *
 * <p><b>生产环境进阶：</b>阿里的 TransmittableThreadLocal(TTL) 能解决更复杂的情况
 * （比如线程池内的线程再派生线程）。本项目用最朴素的方式实现同等效果，
 * 目的是让读者理解"为什么需要它"，而不是依赖一个黑盒。</p>
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // ① 在提交线程捕获上下文快照
        TraceContext.TraceSnapshot snapshot = TraceContext.capture();
        return () -> {
            // ② 在执行线程先备份原上下文
            TraceContext.TraceSnapshot previous = TraceContext.capture();
            try {
                // ③ 恢复提交时的上下文
                TraceContext.restore(snapshot);
                runnable.run();
            } finally {
                // ④ 无论成功失败都还原，避免污染下一个任务
                TraceContext.restore(previous);
            }
        };
    }
}
