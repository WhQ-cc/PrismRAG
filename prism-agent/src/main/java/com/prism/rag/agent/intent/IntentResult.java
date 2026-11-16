package com.prism.rag.agent.intent;

import java.util.List;

/**
 * 意图识别结果。
 *
 * @param intentName    命中的意图名
 * @param action        执行动作
 * @param kbIds         该意图路由到的知识库
 * @param score         匹配得分
 * @param matchedWords  命中的关键词
 * @param description   意图描述
 * @param response      DIRECT 动作的固定回复
 */
public record IntentResult(
        String intentName,
        String action,
        List<Long> kbIds,
        double score,
        List<String> matchedWords,
        String description,
        String response
) {
}
