package com.prism.rag.agent.intent;

import com.prism.rag.common.text.Tokenizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则意图识别器：关键词打分 + 深度优先匹配。
 *
 * <h3>打分规则</h3>
 * <ol>
 *   <li>问题分词后，命中关键词 +1 分；</li>
 *   <li>关键词越"具体"分越高：命中长度 &gt;= 3 的关键词加 1.5 分
 *       （"报销"比"报"更有区分度）；</li>
 *   <li><b>层级加权</b>：越深的节点权重越高（1 + 0.3 × 深度），
 *       因为"报销标准"比"报销"更具体。</li>
 * </ol>
 *
 * <p>同分时取<b>更深的节点</b>——这正是树形结构相比平铺分类的价值。</p>
 */
@Component
public class RuleIntentResolver implements IntentResolver {

    private final IntentTree intentTree;

    public RuleIntentResolver(IntentTree intentTree) {
        this.intentTree = intentTree;
    }

    @Override
    public IntentResult resolve(String question, Long defaultKbId) {
        List<String> tokens = Tokenizer.tokenize(question);
        List<IntentNode> nodes = intentTree.allNodes();

        IntentNode best = null;
        double bestScore = 0;
        List<String> bestWords = new ArrayList<>();

        for (IntentNode node : nodes) {
            if (node.getKeywords() == null || node.getKeywords().isEmpty()) {
                continue;
            }
            double score = 0;
            List<String> matched = new ArrayList<>();
            for (String keyword : node.getKeywords()) {
                if (matches(tokens, keyword)) {
                    score += 1.0;
                    if (keyword.length() >= 3) {
                        score += 0.5;
                    }
                    matched.add(keyword);
                }
            }
            if (score <= 0) {
                continue;
            }
            // 层级加权：深度越大越具体
            score *= (1 + 0.3 * intentTree.depthOf(node));
            if (score > bestScore) {
                bestScore = score;
                best = node;
                bestWords = matched;
            }
        }

        if (best == null) {
            // 没命中任何意图：回落到默认知识库问答
            return new IntentResult("DEFAULT", "KNOWLEDGE",
                    defaultKbId == null ? List.of() : List.of(defaultKbId),
                    0, List.of(), "默认意图：按普通知识库问答处理", null);
        }
        List<Long> kbIds = best.getKbIds() == null || best.getKbIds().isEmpty()
                ? (defaultKbId == null ? List.of() : List.of(defaultKbId))
                : best.getKbIds();
        return new IntentResult(best.getName(), best.getAction(), kbIds,
                bestScore, bestWords, best.getDescription(), best.getResponse());
    }

    /**
     * 关键词是否命中：完全相等（分词后）或作为子串出现在原问题中。
     */
    private boolean matches(List<String> tokens, String keyword) {
        if (tokens.contains(keyword)) {
            return true;
        }
        String normalized = keyword.toLowerCase();
        for (String token : tokens) {
            if (token.contains(normalized) && normalized.length() >= 2) {
                return true;
            }
        }
        return false;
    }
}
