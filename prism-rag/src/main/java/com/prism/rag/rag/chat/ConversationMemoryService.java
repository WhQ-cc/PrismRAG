package com.prism.rag.rag.chat;

import com.prism.rag.infra.ai.model.ChatMessage;
import com.prism.rag.rag.domain.ChatMessageEntity;
import com.prism.rag.rag.domain.Conversation;
import com.prism.rag.rag.repository.ChatMessageRepository;
import com.prism.rag.rag.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话记忆服务。
 *
 * <h3>记忆策略：最近 N 轮 + 历史摘要</h3>
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │  [历史摘要]  ← 老消息压缩而成，永久保留      │
 *   ├──────────────────────────────────────────────┤
 *   │  最近 N 轮消息  ← 原文，细节完整              │
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>为什么不只用最近 N 轮？</h3>
 * <p>用户第一轮说"我是财务部的，出差住宿标准按 city 一类执行"，
 * 第 20 轮问"那我的住宿标准是多少"——如果只带最近几轮，
 * 模型根本不知道"我的"指的是财务部。摘要机制就是来解决这种"跨轮约束丢失"的。</p>
 *
 * <h3>为什么不带全量？</h3>
 * <p>Token 成本和注意力稀释。20 轮对话全量塞进去，
 * 既烧钱，又会让真正的关键信息淹没在无关内容里。</p>
 */
@Service
public class ConversationMemoryService {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemoryService.class);

    /** 携带进 Prompt 的最近消息条数 */
    @Value("${prism.chat.memory-messages:12}")
    private int memoryMessages;

    /** 超过多少条消息触发一次摘要 */
    @Value("${prism.chat.summary-threshold:24}")
    private int summaryThreshold;

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository messageRepository;

    public ConversationMemoryService(ConversationRepository conversationRepository,
                                     ChatMessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    /**
     * 加载会话记忆（摘要 + 最近 N 条消息）。
     *
     * @param conversationId 会话 ID
     * @return 按时间顺序排列的记忆消息
     */
    public List<ChatMessage> loadMemory(Long conversationId) {
        List<ChatMessage> memory = new ArrayList<>();
        if (conversationId == null) {
            return memory;
        }
        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) {
            return memory;
        }

        // ① 摘要放在最前面（相当于"之前我们聊过……"）
        if (conversation.getSummary() != null && !conversation.getSummary().isBlank()) {
            memory.add(ChatMessage.user("【历史对话摘要】\n" + conversation.getSummary()));
        }

        // ② 最近 N 条原文消息
        List<ChatMessageEntity> all = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        int from = Math.max(0, all.size() - memoryMessages);
        for (ChatMessageEntity entity : all.subList(from, all.size())) {
            memory.add(new ChatMessage(entity.getRole(), entity.getContent()));
        }
        return memory;
    }

    /**
     * 保存一条消息。
     */
    public ChatMessageEntity append(Long conversationId, String role, String content,
                                    String citationsJson, Integer tokenCount, Long latencyMillis, String model) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setConversationId(conversationId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCitations(citationsJson);
        entity.setTokenCount(tokenCount);
        entity.setLatencyMillis(latencyMillis);
        entity.setModel(model);
        entity.setCreatedAt(LocalDateTime.now());
        ChatMessageEntity saved = messageRepository.save(entity);

        Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation != null) {
            conversation.setMessageCount((conversation.getMessageCount() == null ? 0 : conversation.getMessageCount()) + 1);
            conversation.setUpdatedAt(LocalDateTime.now());
            conversationRepository.save(conversation);
        }
        return saved;
    }

    /**
     * 触发摘要（消息数超阈值时调用）。
     *
     * <p>摘要只保留"有信息量"的部分：老消息被压缩进 summary，
     * 数据库里的原始消息<b>不删除</b>（前端仍可查看完整历史），
     * 只是不再逐条进入 Prompt。</p>
     *
     * @param conversationId 会话 ID
     * @param summarizer    摘要生成函数（由 ChatService 注入真实的模型调用）
     */
    public void maybeSummarize(Long conversationId, java.util.function.Function<String, String> summarizer) {
        List<ChatMessageEntity> all = messageRepository.findByConversationIdOrderByIdAsc(conversationId);
        if (all.size() < summaryThreshold) {
            return;
        }
        // 只压缩"即将被挤出窗口"的那部分
        int compressCount = all.size() - memoryMessages;
        if (compressCount <= 0) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (ChatMessageEntity entity : all.subList(0, compressCount)) {
            text.append(entity.getRole()).append(": ").append(entity.getContent()).append('\n');
        }
        try {
            String summary = summarizer.apply(text.toString());
            Conversation conversation = conversationRepository.findById(conversationId).orElse(null);
            if (conversation != null && summary != null && !summary.isBlank()) {
                String merged = conversation.getSummary() == null || conversation.getSummary().isBlank()
                        ? summary
                        : conversation.getSummary() + " " + summary;
                conversation.setSummary(merged);
                conversation.setSummarizedAt(LocalDateTime.now());
                conversation.setUpdatedAt(LocalDateTime.now());
                conversationRepository.save(conversation);
                log.info("[Memory] 会话 {} 已生成摘要（压缩 {} 条消息）", conversationId, compressCount);
            }
        } catch (Exception e) {
            // 摘要失败不影响主流程，只是这轮多带一点历史而已
            log.warn("[Memory] 生成会话摘要失败: {}", e.getMessage());
        }
    }
}
