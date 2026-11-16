package com.prism.rag.framework.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE 连接注册表。
 *
 * <p><b>它解决什么问题？</b></p>
 * <p>前端发起 SSE 后，浏览器和后端之间是一条长连接。服务端必须记住"谁连着"，
 * 否则就无法做到：</p>
 * <ol>
 *   <li>用户点"停止生成" → 后端找到对应的会话并中断任务；</li>
 *   <li>客户端意外断开 → 立刻感知并释放后端资源（而不是等超时）。</li>
 * </ol>
 *
 * <p>Spring 的 {@code SseEmitter} 本身提供了 {@code onCompletion / onTimeout / onError} 回调，
 * 我们在这里把它们和注册表打通，形成一个闭环：</p>
 * <pre>
 *   前端发起  →  register()  →  加入注册表
 *        ↑                          ↓
 *   正常结束  ←  complete()  ←  任务跑完
 *        ↑
 *   断开/超时 → 回调触发  →  cancel() + 移出注册表
 * </pre>
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** sessionId → 会话 */
    private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册一个 SSE 会话。
     *
     * @param sessionId 会话 ID（前端生成或后端生成）
     * @param timeout   超时时间（毫秒）
     * @return SSE 会话对象
     */
    public SseSession register(String sessionId, long timeout) {
        SseEmitter emitter = new SseEmitter(timeout);
        SseSession session = new SseSession(sessionId, emitter);

        // 正常完成：移出注册表
        emitter.onCompletion(() -> {
            sessions.remove(sessionId);
            log.debug("[SSE] 会话 {} 正常结束", sessionId);
        });
        // 超时：等价于断开，取消后端任务
        emitter.onTimeout(() -> {
            log.warn("[SSE] 会话 {} 超时，取消后端任务", sessionId);
            session.cancel();
            sessions.remove(sessionId);
        });
        // 出错（含客户端断开）：取消后端任务
        emitter.onError(throwable -> {
            log.debug("[SSE] 会话 {} 出错，取消后端任务: {}", sessionId, throwable.getMessage());
            session.cancel();
            sessions.remove(sessionId);
        });

        sessions.put(sessionId, session);
        log.debug("[SSE] 注册会话 {}，当前活跃连接数 {}", sessionId, sessions.size());
        return session;
    }

    /**
     * 主动取消某个会话（对应前端"停止生成"按钮）。
     */
    public boolean cancel(String sessionId) {
        SseSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.cancel();
        return true;
    }

    /**
     * 移除会话（不取消任务）。
     */
    public void remove(String sessionId) {
        sessions.remove(sessionId);
    }

    public SseSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 当前活跃连接数（可展示在管理端）。
     */
    public int activeCount() {
        return sessions.size();
    }

    public Collection<SseSession> all() {
        return sessions.values();
    }

    /**
     * 广播事件给所有会话（例如"知识库已更新"这类系统通知）。
     */
    public void broadcast(String event, Object payload) {
        for (SseSession session : sessions.values()) {
            if (!session.send(event, payload)) {
                sessions.remove(session.getSessionId());
            }
        }
    }

    /**
     * 应用关闭时统一收尾。
     */
    public void shutdown() {
        log.info("[SSE] 关闭全部 {} 个活跃连接", sessions.size());
        for (SseSession session : sessions.values()) {
            try {
                session.getEmitter().complete();
            } catch (Exception ignored) {
                // 忽略
            }
        }
        sessions.clear();
    }
}
