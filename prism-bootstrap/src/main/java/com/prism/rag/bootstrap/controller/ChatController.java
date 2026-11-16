package com.prism.rag.bootstrap.controller;

import com.prism.rag.common.api.R;
import com.prism.rag.rag.chat.ChatService;
import com.prism.rag.rag.chat.dto.AskRequest;
import com.prism.rag.rag.domain.ChatMessageEntity;
import com.prism.rag.rag.domain.Conversation;
import com.prism.rag.rag.repository.ChatMessageRepository;
import com.prism.rag.rag.repository.ConversationRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答接口。
 *
 * <p>SSE 用 GET 是因为浏览器的 {@code EventSource} 只支持 GET。
 * 如果需要 POST 形式的 SSE，前端得用 {@code fetch} + {@code ReadableStream} 自己解析。</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    public ChatController(ChatService chatService,
                          ConversationRepository conversationRepository,
                          ChatMessageRepository messageRepository) {
        this.chatService = chatService;
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 流式问答（SSE）。
     *
     * @param question       用户问题
     * @param kbId           知识库 ID
     * @param conversationId 会话 ID
     * @param sessionId      会话 ID（前端生成，用于"停止生成"）
     * @param deepThinking   是否深度思考
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String question,
                             @RequestParam(required = false) Long kbId,
                             @RequestParam(required = false) Long conversationId,
                             @RequestParam(required = false) String sessionId,
                             @RequestParam(defaultValue = "false") boolean deepThinking) {
        AskRequest request = new AskRequest();
        request.setQuestion(question);
        request.setKbId(kbId);
        request.setConversationId(conversationId);
        request.setSessionId(sessionId);
        request.setDeepThinking(deepThinking);
        return chatService.streamAsk(request);
    }

    /**
     * 同步问答（不做流式），便于用 curl 快速验证。
     */
    @PostMapping("/ask")
    public R<Map<String, Object>> ask(@RequestBody AskRequest request) {
        String answer = chatService.askSync(request);
        Map<String, Object> data = new HashMap<>();
        data.put("answer", answer);
        data.put("conversationId", request.getConversationId());
        return R.ok(data);
    }

    /**
     * 取消一次进行中的问答（前端"停止生成"按钮）。
     */
    @PostMapping("/cancel")
    public R<Map<String, Object>> cancel(@RequestParam String sessionId) {
        boolean cancelled = chatService.cancel(sessionId);
        Map<String, Object> data = new HashMap<>();
        data.put("cancelled", cancelled);
        return R.ok(data);
    }

    /**
     * 会话列表。
     */
    @GetMapping("/conversations")
    public R<List<Conversation>> conversations() {
        return R.ok(conversationRepository.findTop50ByOrderByUpdatedAtDesc());
    }

    /**
     * 会话消息历史。
     */
    @GetMapping("/conversations/{conversationId}/messages")
    public R<List<ChatMessageEntity>> messages(@PathVariable Long conversationId) {
        return R.ok(messageRepository.findByConversationIdOrderByIdAsc(conversationId));
    }
}
