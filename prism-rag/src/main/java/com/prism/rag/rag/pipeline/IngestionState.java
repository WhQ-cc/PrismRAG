package com.prism.rag.rag.pipeline;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 入库状态机。
 *
 * <h3>为什么一定要用状态机，而不是随便改一个 status 字段？</h3>
 * <p>想象一下没有状态机的版本：解析节点里写 {@code task.setStatus("PARSING")}，
 * 失败处理里写 {@code task.setStatus("FAILED")}，重试逻辑里写 {@code task.setStatus("PENDING")}。
 * 时间一长，就会出现"任务已经 COMPLETED 了，某处一厢情愿把它改成了 PARSING"这种脏状态，
 * 而且没人能在第一时间发现。</p>
 *
 * <p>状态机把这三条规则写死在代码里：</p>
 * <ol>
 *   <li><b>合法流转是显式的</b>：每个状态能去哪些状态，在 {@link #ALLOWED} 里一目了然；</li>
 *   <li><b>非法流转直接抛异常</b>：在开发期就暴露出来，而不是等到线上数据错乱；</li>
 *   <li><b>终态明确</b>：COMPLETED / FAILED / CANCELLED 是终态，只能通过"重试"回到 PENDING。</li>
 * </ol>
 *
 * <h3>本状态机的流转图</h3>
 * <pre>
 *   PENDING ──→ PARSING ──→ CHUNKING ──→ EMBEDDING ──→ PERSISTING ──→ COMPLETED
 *      │           │            │             │              │
 *      └───────────┴────────────┴─────────────┴──────────────┴──→ FAILED
 *      └───────────┴────────────┴─────────────┴──────────────┴──→ CANCELLED
 *
 *   FAILED   ──(重试)──→ PENDING
 *   CANCELLED──(重试)──→ PENDING
 * </pre>
 */
public enum IngestionState {

    /** 已创建，等待处理 */
    PENDING("待处理"),

    /** 解析中：把原始内容转成结构化文本 */
    PARSING("解析中"),

    /** 分块中：把结构化文本切成 chunk */
    CHUNKING("分块中"),

    /** 向量化中：给每个 chunk 算向量 */
    EMBEDDING("向量化中"),

    /** 持久化中：写数据库 + 更新检索索引 */
    PERSISTING("持久化中"),

    /** 完成 */
    COMPLETED("已完成"),

    /** 失败（可重试） */
    FAILED("失败"),

    /** 已取消（可重试） */
    CANCELLED("已取消");

    private final String label;

    IngestionState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 合法流转表。
     */
    private static final Map<IngestionState, Set<IngestionState>> ALLOWED = Map.of(
            PENDING, EnumSet.of(PARSING, FAILED, CANCELLED),
            PARSING, EnumSet.of(CHUNKING, FAILED, CANCELLED),
            CHUNKING, EnumSet.of(EMBEDDING, FAILED, CANCELLED),
            EMBEDDING, EnumSet.of(PERSISTING, FAILED, CANCELLED),
            PERSISTING, EnumSet.of(COMPLETED, FAILED, CANCELLED),
            COMPLETED, EnumSet.of(PENDING),
            FAILED, EnumSet.of(PENDING),
            CANCELLED, EnumSet.of(PENDING)
    );

    /**
     * 是否是终态（不能直接流转到其他状态，只能重试）。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /**
     * 是否允许流转到目标状态。
     */
    public boolean canTransitionTo(IngestionState target) {
        return target != null && ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /**
     * 断言流转合法，非法直接抛异常。
     *
     * <p><b>开发期快速失败 &gt; 线上数据错乱。</b></p>
     */
    public void assertTransitionTo(IngestionState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "非法的入库状态流转: " + this + "(" + label + ") -> " + target
                            + "，合法目标: " + ALLOWED.getOrDefault(this, Collections.emptySet()));
        }
    }

    /**
     * 进度百分比（前端进度条用）。
     */
    public int progress() {
        return switch (this) {
            case PENDING -> 0;
            case PARSING -> 20;
            case CHUNKING -> 40;
            case EMBEDDING -> 65;
            case PERSISTING -> 90;
            case COMPLETED -> 100;
            case FAILED, CANCELLED -> 0;
        };
    }
}
