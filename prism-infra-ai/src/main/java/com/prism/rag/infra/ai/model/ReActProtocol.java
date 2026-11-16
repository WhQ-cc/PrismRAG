package com.prism.rag.infra.ai.model;

/**
 * ReAct 协议常量。
 *
 * <p>ReAct = Reasoning + Acting（推理 + 行动）。模型需要按固定格式输出，
 * 程序再按格式解析。这个类把"格式约定"集中管理，
 * 免得提示词和解析代码各写一份、改了一处忘了另一处。</p>
 *
 * <p>典型的一轮输出长这样：</p>
 * <pre>
 * Thought: 我需要先查一下知识库，看看有没有关于报销标准的说明。
 * Action: knowledge_search
 * Action Input: {"query": "报销标准"}
 * </pre>
 *
 * <p>拿到工具执行结果后，把 Observation 拼回去再问一次，模型就会给出：</p>
 * <pre>
 * Thought: 已经拿到资料，可以回答了。
 * Final Answer: 差旅报销标准为……
 * </pre>
 */
public final class ReActProtocol {

    private ReActProtocol() {
    }

    /** 推理标记 */
    public static final String THOUGHT = "Thought:";

    /** 行动标记 */
    public static final String ACTION = "Action:";

    /** 行动入参标记 */
    public static final String ACTION_INPUT = "Action Input:";

    /** 工具执行结果标记 */
    public static final String OBSERVATION = "Observation:";

    /** 最终答案标记 */
    public static final String FINAL_ANSWER = "Final Answer:";
}
