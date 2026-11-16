package com.prism.rag.framework.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次 SSE 会话。
 *
 * <p>把"发事件"和"能不能发"两件事封装在一起：</p>
 * <ul>
 *   <li>{@link #send(String, Object)} 发送事件；一旦发送失败（客户端已断开），
 *       会把本次会话标记为 cancelled，后续事件直接丢弃，不再白跑 IO。</li>
 *   <li>{@link #cancel()} 主动取消：中断后端正在跑的生成任务。</li>
 * </ul>
 *
 * <p><b>为什么要"取消"？</b>用户点了"停止生成"或者直接关掉页面，
 * 后端的模型调用还在烧 Token。好的实现必须能感知断开并及时止损——
 * 这也是 SSE 相比 WebSocket 更"轻"但需要额外处理的地方。</p>
 */
public class SseSession {

    private static final Logger log = LoggerFactory.getLogger(SseSession.class);

    private final String sessionId;
    private final SseEmitter emitter;

    /** 是否已被取消（客户端断开 / 用户主动停止） */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 后端生成任务，cancel 时会中断它 */
    private volatile Future<?> future;

    public SseSession(String sessionId, SseEmitter emitter) {
        this.sessionId = sessionId;
        this.emitter = emitter;
    }

    public String getSessionId() {
        return sessionId;
    }

    public SseEmitter getEmitter() {
        return emitter;
    }

    public void bindFuture(Future<?> future) {
        this.future = future;
    }

    /**
     * 发送一个 SSE 事件。
     *
     * @param event   事件名（前端按名分发：delta / citation / done ...）
     * @param payload 事件数据
     * @return true 表示发送成功；false 表示会话已取消或发送失败
     */
    public boolean send(String event, Object payload) {
        if (cancelled.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(payload));
            return true;
        } catch (IOException | IllegalStateException e) {
            // 客户端断开时 Spring 会抛 IllegalStateException / IOException
            // 这是"正常"的断开路径，不算错误，打个 debug 即可
            log.debug("[SSE] 会话 {} 发送失败，判定为客户端断开: {}", sessionId, e.getMessage());
            cancelled.set(true);
            return false;
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 取消会话：标记取消 + 中断后端任务 + 关闭 emitter。
     */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        log.info("[SSE] 会话 {} 被取消", sessionId);
        Future<?> f = this.future;
        if (f != null && !f.isDone()) {
            // true = 允许中断正在运行的任务
            f.cancel(true);
        }
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 连接可能已经断了，忽略
        }
    }

    /**
     * 正常结束。
     */
    public void complete() {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 忽略
        }
    }
}
