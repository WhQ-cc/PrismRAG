package com.prism.rag.agent.intent;

/**
 * 意图识别器（SPI）。
 *
 * <p>本项目提供两个实现思路：</p>
 * <ol>
 *   <li>{@link RuleIntentResolver}：基于关键词的规则匹配（默认，无需模型、毫秒级）；</li>
 *   <li>模型分类：把意图列表作为 Prompt 交给大模型，让它输出意图名
 *       （更准但要调模型，详见 {@code docs/08-意图树.md}）。</li>
 * </ol>
 * <p>业务只依赖这个接口，换实现不影响调用方。</p>
 */
public interface IntentResolver {

    /**
     * 识别意图。
     *
     * @param question    用户问题
     * @param defaultKbId 默认知识库（意图没指定时使用）
     */
    IntentResult resolve(String question, Long defaultKbId);
}
