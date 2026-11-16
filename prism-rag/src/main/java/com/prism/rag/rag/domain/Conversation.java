package com.prism.rag.rag.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话。
 *
 * <p>{@link #summary} 存的是<b>滚动摘要</b>：当消息条数超过阈值时，
 * 把最早的若干轮压缩成一段摘要，代替原文进入 Prompt。
 * 这样既控制了 Token 成本，又不会像"只留最近 N 轮"那样把关键信息全丢掉。</p>
 *
 * <p>这是会话记忆的经典取舍：</p>
 * <ul>
 *   <li>全量带 → Token 成本爆炸，且长上下文会稀释注意力；</li>
 *   <li>只带最近 N 轮 → 便宜，但用户前面说过的重要约束（比如"我是管理员"）会丢；</li>
 *   <li><b>最近 N 轮 + 历史摘要</b> → 本项目采用这个方案。</li>
 * </ul>
 */
@Entity
@Table(name = "conversation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 绑定的知识库 */
    @Column(name = "kb_id")
    private Long kbId;

    /** 会话标题（默认取首个问题的前 N 个字） */
    @Column(length = 256)
    private String title;

    /** 滚动摘要 */
    @Lob
    @Column(name = "summary")
    private String summary;

    /** 消息条数 */
    @Column(name = "message_count")
    private Integer messageCount;

    /** 摘要的生成时间 */
    @Column(name = "summarized_at")
    private LocalDateTime summarizedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
