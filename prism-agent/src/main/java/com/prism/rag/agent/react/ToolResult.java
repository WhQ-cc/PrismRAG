package com.prism.rag.agent.react;

/**
 * 工具执行结果。
 *
 * @param success 是否成功
 * @param output  输出内容（会作为 Observation 喂回模型）
 * @param error   错误信息（失败时）
 */
public record ToolResult(boolean success, String output, String error) {

    public static ToolResult ok(String output) {
        return new ToolResult(true, output, null);
    }

    public static ToolResult fail(String error) {
        return new ToolResult(false, null, error);
    }
}
