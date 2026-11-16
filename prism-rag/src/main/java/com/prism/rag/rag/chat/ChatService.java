package com.prism.rag.rag.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.rag.common.text.TextUtils;
import com.prism.rag.framework.sse.SseEmitterRegistry;
import com.prism.rag.framework.sse.SseSession;
import com.prism.rag.infra.ai.model.AiModelInfo;
import com.prism.rag.infra.ai.model.ChatMessage;
import com.prism.rag.infra.ai.model.ChatRequest;
import com.prism.rag.infra.ai.model.ChatResponse;
import com.prism.rag.infra.ai.model.ContextItem;
import com.prism.rag.infra.ai.spi.ChatClient;
import com.prism.rag.rag.chat.dto.AskRequest;
import com.prism.rag.rag.domain.Conversation;
import com.prism.rag.rag.repository.ConversationRepository;
import com.prism.rag.rag.retrieval.RetrievalHit;
import com.prism.rag.rag.retrieval.RetrievalResult;
import com.prism.rag.rag.retrieval.RetrievalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * 流式问答服务。
 *
 * <h3>一次提问的完整链路</h3>
 * <pre>
 *   前端发起 SSE
 *      │
 *      ├─ 注册 SseSession（拿到取消开关）
 *      ├─ 提交到 chatExecutor（专用线程池）
 *      │
 *      └─ 后台线程：
 *           ① 建/取会话
 *           ② 读会话记忆（摘要 + 最近 N 轮）
 *           ③ 双路召回（向量 + BM25）→ RRF 融合
 *           ④ 组装 Prompt（记忆 + 上下文 + 问题）
 *           ⑤ 模型流式生成 → 逐块推 delta 事件
 *           ⑥ 落库（用户消息 + 助手消息 + 引用）
 *           ⑦ 推 done 事件，关闭连接
 * </pre>
 *
 * <h3>SSE 事件协议</h3>
 * <table border="1">
 *   <caption>事件说明</caption>
 *   <tr><th>event</th><th>含义</th></tr>
 *   <tr><td>meta</td><td>会话元信息（会话 ID、模型、耗时起点）</td></tr>
 *   <tr><td>thinking</td><td>当前处理阶段（前端显示"正在检索…"）</td></tr>
 *   <tr><td>retrieval</td><td>两路召回的原始命中（可视化检索漏斗）</td></tr>
 *   <tr><td>citation</td><td>引用列表（可展开原文）</td></tr>
 *   <tr><td>delta</td><td>回答增量文本</td></tr>
 *   <tr><td>done</td><td>结束（携带 token 与耗时）</td></tr>
 *   <tr><td>error</td><td>出错</td></tr>
 * </table>
 *
 * <h3>断开取消是怎么做到的？</h3>
 * <p>浏览器关闭页面时，Tomcat 会触发 {@code SseEmitter} 的 error/completion 回调，
 * 我们在回调里调用 {@code session.cancel()}：
 * 它会中断正在跑的 {@code Future}（{@code cancel(true)}）→ 模型流式循环检测到线程中断 →
 * 停止输出、释放线程。<b>没有这一步，用户关掉页面后后端还会继续烧 Token。</b></p>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** SSE 连接超时 */
    @Value("${prism.chat.sse-timeout-millis:180000}")
    private long sseTimeoutMillis;

    /** 默认检索条数 */
    @Value("${prism.chat.top-k:5}")
    private int defaultTopK;

    private final ChatClient chatClient;
    private final AiModelInfo modelInfo;
    private final RetrievalService retrievalService;
    private final ConversationMemoryService memoryService;
    private final ConversationRepository conversationRepository;
    private final SseEmitterRegistry sseRegistry;
    private final ExecutorService chatExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ChatService(ChatClient chatClient,
                       AiModelInfo modelInfo,
                       RetrievalService retrievalService,
                       ConversationMemoryService memoryService,
                       ConversationRepository conversationRepository,
                       SseEmitterRegistry sseRegistry,
                       @Qualifier("chatExecutor") ExecutorService chatExecutor) {
        this.chatClient = chatClient;
        this.modelInfo = modelInfo;
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.conversationRepository = conversationRepository;
        this.sseRegistry = sseRegistry;
        this.chatExecutor = chatExecutor;
    }

    /**
     * 发起一次流式问答。
     *
     * @return SSE 连接对象
     */
    public SseEmitter streamAsk(AskRequest request) {
        String sessionId = request.getSessionId() == null || request.getSessionId().isBlank()
                ? UUID.randomUUID().toString()
                : request.getSessionId();

        // ① 注册会话，拿到"取消开关"
        SseSession session = sseRegistry.register(sessionId, sseTimeoutMillis);

        // ② 提交到专用线程池执行（HTTP 线程立即返回，不占用 Tomcat 工作线程）
        try {
            Future<?> future = chatExecutor.submit(() -> runAsk(request, session));
            session.bindFuture(future);
        } catch (RejectedExecutionException e) {
            // 线程池满了：明确告诉前端"服务繁忙"，而不是让请求挂着
            log.warn("[Chat] 线程池已满，拒绝会话 {}", sessionId);
            session.send("error", Map.of("message", "服务繁忙，请稍后重试"));
            session.complete();
        }
        return session.getEmitter();
    }

    /**
     * 主动取消一次问答（前端"停止生成"按钮）。
     */
    public boolean cancel(String sessionId) {
        return sseRegistry.cancel(sessionId);
    }

    /**
     * 后台执行的主体逻辑。
     */
    private void runAsk(AskRequest request, SseSession session) {
        long start = System.currentTimeMillis();
        StringBuilder answer = new StringBuilder();
        try {
            // ① 会话
            Conversation conversation = ensureConversation(request);
            Long conversationId = conversation.getId();
            session.send("meta", Map.of(
                    "sessionId", session.getSessionId(),
                    "conversationId", conversationId,
                    "provider", modelInfo.chatProvider(),
                    "model", modelInfo.chatModel(),
                    "knowledgeBaseId", request.getKbId() == null ? "" : request.getKbId()
            ));

            // ② 会话记忆
            session.send("thinking", Map.of("stage", "读取会话记忆", "detail", "摘要 + 最近若干轮"));
            List<ChatMessage> memory = memoryService.loadMemory(conversationId);

            // ③ 双路召回
            session.send("thinking", Map.of("stage", "双路召回", "detail", "向量余弦 + BM25 倒排并行执行"));
            int topK = request.getTopK() == null || request.getTopK() <= 0 ? defaultTopK : request.getTopK();
            RetrievalResult retrieval = retrievalService.retrieve(request.getQuestion(), request.getKbId(), topK);

            // ④ 组装上下文与引用
            List<ContextItem> context = new ArrayList<>();
            List<Citation> citations = new ArrayList<>();
            int index = 1;
            for (RetrievalHit hit : retrieval.hits()) {
                context.add(ContextItem.builder()
                        .index(index)
                        .title(hit.title())
                        .content(hit.content())
                        .knowledgeBaseId(hit.kbId())
                        .chunkId(hit.chunkId())
                        .build());
                citations.add(new Citation(
                        index, hit.chunkId(), hit.docId(), hit.title(),
                        TextUtils.bestSnippet(hit.content(), request.getQuestion(), 160),
                        hit.content(), hit.score(), hit.channels()));
                index++;
            }

            // 检索漏斗可视化数据
            session.send("retrieval", Map.of(
                    "vector", retrieval.vectorHits().stream().map(h -> Map.of(
                            "chunkId", h.chunkId(), "score", h.score())).toList(),
                    "bm25", retrieval.bm25Hits().stream().map(h -> Map.of(
                            "chunkId", h.chunkId(), "score", h.score())).toList(),
                    "fused", retrieval.hits().stream().map(h -> Map.of(
                            "chunkId", h.chunkId(), "score", h.score(),
                            "channels", h.channels())).toList(),
                    "degraded", retrieval.degraded(),
                    "degradeReason", retrieval.degradeReason() == null ? "" : retrieval.degradeReason()
            ));
            session.send("citation", citations);

            // ⑤ 模型流式生成
            session.send("thinking", Map.of("stage", "生成回答", "detail",
                    modelInfo.real() ? "真实模型 " + modelInfo.chatModel() : "离线 Mock 模型"));
            ChatRequest chatRequest = ChatRequest.builder()
                    .systemPrompt(Boolean.TRUE.equals(request.getDeepThinking())
                            ? PromptTemplates.RAG_SYSTEM_DEEP : PromptTemplates.RAG_SYSTEM)
                    .memory(memory)
                    .context(context)
                    .question(request.getQuestion())
                    .stream(true)
                    .build();

            chatClient.stream(chatRequest, new ChatClient.ChatStreamListener() {
                @Override
                public void onDelta(String delta) {
                    // 每次推送前检查取消标记：用户点"停止生成"或断开页面后立即停止
                    if (session.isCancelled() || Thread.currentThread().isInterrupted()) {
                        throw new CancellationException("会话已取消");
                    }
                    answer.append(delta);
                    if (!session.send("delta", Map.of("text", delta))) {
                        throw new CancellationException("连接已断开");
                    }
                }

                @Override
                public void onComplete(ChatResponse response) {
                    log.debug("[Chat] 模型流结束，token={}", response.getTotalTokens());
                }

                @Override
                public void onError(Throwable error) {
                    if (!(error instanceof CancellationException)) {
                        log.error("[Chat] 模型流异常: {}", error.getMessage(), error);
                    }
                }
            });

            // ⑥ 落库
            String citationsJson = writeJson(citations);
            memoryService.append(conversationId, "user", request.getQuestion(), null, null, null, null);
            memoryService.append(conversationId, "assistant", answer.toString(), citationsJson,
                    ChatResponse.estimateTokens(answer.toString()),
                    System.currentTimeMillis() - start, modelInfo.chatModel());
            memoryService.maybeSummarize(conversationId, this::summarize);

            // ⑦ 结束
            session.send("done", Map.of(
                    "conversationId", conversationId,
                    "elapsedMillis", System.currentTimeMillis() - start,
                    "chars", answer.length(),
                    "tokens", ChatResponse.estimateTokens(answer.toString()),
                    "citationCount", citations.size()
            ));
            session.complete();

        } catch (CancellationException e) {
            // 正常取消：不算错误
            log.info("[Chat] 会话 {} 被取消", session.getSessionId());
        } catch (Exception e) {
            log.error("[Chat] 问答失败: {}", e.getMessage(), e);
            session.send("error", Map.of("message", "问答失败: " + e.getMessage()));
            session.complete();
        }
    }

    /**
     * 同步问答（不做流式），供 Agent 工具调用使用。
     *
     * @return 回答内容
     */
    public String askSync(AskRequest request) {
        Conversation conversation = ensureConversation(request);
        List<ChatMessage> memory = memoryService.loadMemory(conversation.getId());
        int topK = request.getTopK() == null || request.getTopK() <= 0 ? defaultTopK : request.getTopK();
        RetrievalResult retrieval = retrievalService.retrieve(request.getQuestion(), request.getKbId(), topK);

        List<ContextItem> context = new ArrayList<>();
        int index = 1;
        for (RetrievalHit hit : retrieval.hits()) {
            context.add(ContextItem.builder()
                    .index(index++).title(hit.title()).content(hit.content())
                    .knowledgeBaseId(hit.kbId()).chunkId(hit.chunkId()).build());
        }

        ChatRequest chatRequest = ChatRequest.builder()
                .systemPrompt(PromptTemplates.RAG_SYSTEM)
                .memory(memory)
                .context(context)
                .question(request.getQuestion())
                .stream(false)
                .build();
        ChatResponse response = chatClient.complete(chatRequest);
        String content = response.getContent() == null ? "" : response.getContent();

        memoryService.append(conversation.getId(), "user", request.getQuestion(), null, null, null, null);
        memoryService.append(conversation.getId(), "assistant", content, null,
                response.getTotalTokens(), response.getLatencyMillis(), modelInfo.chatModel());
        return content;
    }

    /**
     * 创建或复用会话。
     */
    private Conversation ensureConversation(AskRequest request) {
        if (request.getConversationId() != null) {
            Conversation existing = conversationRepository.findById(request.getConversationId()).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        Conversation conversation = new Conversation();
        conversation.setKbId(request.getKbId());
        conversation.setTitle(TextUtils.truncate(request.getQuestion(), 24));
        conversation.setMessageCount(0);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(conversation);
    }

    /**
     * 生成会话摘要。
     */
    private String summarize(String history) {
        ChatRequest request = ChatRequest.builder()
                .systemPrompt(PromptTemplates.SUMMARY_SYSTEM)
                .question(history)
                .stream(false)
                .build();
        ChatResponse response = chatClient.complete(request);
        return response.getContent();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
