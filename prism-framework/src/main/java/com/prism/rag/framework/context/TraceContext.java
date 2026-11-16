package com.prism.rag.framework.context;

import java.util.UUID;

/**
 * 链路上下文：用 ThreadLocal 携带 traceId / userId / conversationId。
 *
 * <p><b>要解决什么问题？</b></p>
 * <p>一次 HTTP 请求会跨线程：Web 线程收请求 → 检索线程查库 → 问答线程调模型 → SSE 线程写回。
 * 如果只靠方法参数层层传递 traceId，代码会被污染得没法看；
 * 如果干脆不传，线上出问题时日志里全是无主的 INFO，根本拼不出一次完整调用。</p>
 *
 * <p>所以标准做法是：<b>上下文跟着线程走</b>。请求入口放进去，线程结束前清掉。</p>
 *
 * <p><b>注意 ThreadLocal 在线程池里的坑：</b></p>
 * <p>{@code InheritableThreadLocal} 只在线程<b>创建</b>时复制一次。
 * 线程池里的线程是提前创建好反复复用的，第一次提交任务时父线程的值被复制过去，
 * 之后父线程 traceId 变了，池里的线程仍然拿着旧值——这会导致日志 traceId 串台。
 * 正确做法是：<b>提交任务时捕获</b>（在调用线程中），<b>执行任务时恢复</b>（在池线程中），
 * 执行完再还原。这正是 {@link TraceTaskDecorator} 干的事。</p>
 *
 * @see TraceTaskDecorator
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CONVERSATION_ID = new ThreadLocal<>();

    private TraceContext() {
    }

    /**
     * 生成一个新的 traceId。
     */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    public static void setTraceId(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String getTraceId() {
        return TRACE_ID.get();
    }

    /**
     * 取 traceId，没有就现生成一个（保证日志里永远有链路标识）。
     */
    public static String getOrCreateTraceId() {
        String traceId = TRACE_ID.get();
        if (traceId == null) {
            traceId = newTraceId();
            TRACE_ID.set(traceId);
        }
        return traceId;
    }

    public static void setUserId(String userId) {
        USER_ID.set(userId);
    }

    public static String getUserId() {
        return USER_ID.get();
    }

    public static void setConversationId(String conversationId) {
        CONVERSATION_ID.set(conversationId);
    }

    public static String getConversationId() {
        return CONVERSATION_ID.get();
    }

    /**
     * 清理当前线程的上下文。
     *
     * <p><b>必须调用</b>，否则线程池里的线程会一直带着上一个请求的 traceId，
     * 下游日志全是"张冠李戴"的链路。</p>
     */
    public static void clear() {
        TRACE_ID.remove();
        USER_ID.remove();
        CONVERSATION_ID.remove();
    }

    /**
     * 捕获当前线程的上下文快照（提交任务时调用）。
     */
    public static TraceSnapshot capture() {
        return new TraceSnapshot(TRACE_ID.get(), USER_ID.get(), CONVERSATION_ID.get());
    }

    /**
     * 把快照恢复到当前线程（任务执行时调用）。
     */
    public static void restore(TraceSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        TRACE_ID.set(snapshot.traceId());
        USER_ID.set(snapshot.userId());
        CONVERSATION_ID.set(snapshot.conversationId());
    }

    /**
     * 上下文快照（不可变值对象）。
     */
    public record TraceSnapshot(String traceId, String userId, String conversationId) {
    }
}
