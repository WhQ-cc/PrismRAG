package com.prism.rag.infra.ai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话请求。
 *
 * <p>RAG 场景下，一次模型调用其实由四块内容拼成：</p>
 * <pre>
 *   systemPrompt（人设与规则）
 *     + memory（会话记忆，最近几轮）
 *     + context（检索到的知识片段）
 *     + question（用户当前问题）
 * </pre>
 * <p>这个类把这四块显式建模，好处是：</p>
 * <ul>
 *   <li>换模型时，不用改业务层的 Prompt 拼装逻辑；</li>
 *   <li>Mock 实现可以按照同样的结构工作，从而做到"离线也能跑通全链路"；</li>
 *   <li>想做 Token 预算控制时，只需要盯住这几个字段的长度。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /** 系统提示词：定义角色、回答规则、引用格式 */
    private String systemPrompt;

    /** 会话记忆：历史消息（角色 + 内容） */
    @Builder.Default
    private List<ChatMessage> memory = new ArrayList<>();

    /** 检索到的知识片段 */
    @Builder.Default
    private List<ContextItem> context = new ArrayList<>();

    /** 用户当前问题 */
    private String question;

    /** 温度，越低越稳定 */
    @Builder.Default
    private Double temperature = 0.3;

    /** 最大生成 token */
    @Builder.Default
    private Integer maxTokens = 1024;

    /** 是否流式 */
    @Builder.Default
    private Boolean stream = false;

    /**
     * 把 memory / context / question 拼成 messages 列表。
     *
     * <p>这是"Prompt 组装"的标准化实现——真实模型和 Mock 模型都用同一套组装逻辑，
     * 保证两条路径看到的信息完全一致。</p>
     */
    public List<ChatMessage> toMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        if (memory != null) {
            messages.addAll(memory);
        }
        if (context != null && !context.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("以下是知识库检索到的参考资料，请仅依据这些资料回答问题，并标注引用编号：\n");
            for (ContextItem item : context) {
                sb.append('[').append(item.getIndex()).append("] 《").append(item.getTitle())
                        .append("》\n").append(item.getContent()).append("\n\n");
            }
            messages.add(ChatMessage.user(sb.toString()));
        }
        if (question != null && !question.isBlank()) {
            messages.add(ChatMessage.user(question));
        }
        return messages;
    }
}
