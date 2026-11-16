package com.prism.rag.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话消息。
 *
 * <p>每条消息都要落库，原因有三个：</p>
 * <ol>
 *   <li><b>会话记忆</b>：下一轮问答要把历史消息喂给模型；</li>
 *   <li><b>引用溯源</b>：{@link #citations} 存了这条回答引用了哪些 chunk，
 *       刷新页面后引用卡片依然在（否则只能靠前端内存存，关掉就没了）；</li>
 *   <li><b>可观测</b>：耗时、token 数都留着，方便算成本和做优化。</li>
 * </ol>
 */
@Entity
@Table(name = "chat_message",
        indexes = @Index(name = "idx_msg_conversation", columnList = "conversation_id"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /** 角色：user / assistant / system */
    @Column(nullable = false, length = 16)
    private String role;

    /** 消息内容 */
    @Lob
    @Column(name = "content")
    private String content;

    /** 引用列表（JSON 数组字符串） */
    @Lob
    @Column(name = "citations")
    private String citations;

    /** 输出 token */
    @Column(name = "token_count")
    private Integer tokenCount;

    /** 耗时（毫秒） */
    @Column(name = "latency_millis")
    private Long latencyMillis;

    /** 使用的模型 */
    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
