package com.prism.rag.agent.react;

import java.util.Map;

/**
 * 工具（Tool）定义。
 *
 * <p>工具是 Agent 的"手"。ReAct 循环里，模型说"我要调 knowledge_search"，
 * 引擎就找到对应的 {@link Tool} 执行，把结果作为 Observation 喂回模型。</p>
 *
 * <p><b>参数校验很重要：</b>模型给的参数可能缺字段、类型不对。
 * 工具实现里必须自己校验，不能信任模型的输出——
 * 这是 Agent 场景最常见的安全问题来源。</p>
 */
public interface Tool {

    /**
     * 工具名（模型用它来调用）。
     */
    String name();

    /**
     * 工具描述（会进 Prompt，必须写清楚"什么时候该用它"）。
     */
    String description();

    /**
     * 参数说明。
     */
    String parameters();

    /**
     * 执行工具。
     *
     * @param arguments 参数（来自模型的 Action Input）
     * @return 执行结果
     */
    ToolResult execute(Map<String, Object> arguments);
}
