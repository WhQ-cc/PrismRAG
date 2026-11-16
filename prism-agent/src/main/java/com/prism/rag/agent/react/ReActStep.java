package com.prism.rag.agent.react;

/**
 * ReAct 执行步骤（用于前端展示"智能体在想什么、做了什么"）。
 *
 * @param step       步骤序号（从 1 开始）
 * @param thought    模型推理
 * @param action     调用的工具
 * @param actionInput 工具参数
 * @param observation 工具返回
 * @param success    本步是否成功
 */
public record ReActStep(
        int step,
        String thought,
        String action,
        String actionInput,
        String observation,
        boolean success
) {
}
