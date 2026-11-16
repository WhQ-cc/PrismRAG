package com.prism.rag.agent.react;

import java.util.List;

/**
 * 一次 ReAct 执行的完整结果。
 *
 * @param answer        最终答案
 * @param steps         执行步骤
 * @param totalSteps    实际步数
 * @param success       是否正常收尾（未超步数上限）
 * @param elapsedMillis 耗时
 */
public record ReActRun(
        String answer,
        List<ReActStep> steps,
        int totalSteps,
        boolean success,
        long elapsedMillis
) {
}
