package com.prism.rag.agent.react;

import com.prism.rag.rag.retrieval.RetrievalHit;
import com.prism.rag.rag.retrieval.RetrievalResult;
import com.prism.rag.rag.retrieval.RetrievalService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识检索工具：把知识库检索包装成 Agent 可调用的工具。
 *
 * <p>这是 RAG 与 Agent 的<b>结合点</b>：</p>
 * <pre>
 *   纯 RAG（Workflow）：问题 → 固定路径检索 → 生成
 *   Agentic RAG：      问题 → 模型自己决定要不要检索、检索几次 → 生成
 * </pre>
 * <p>同一个 {@code RetrievalService} 被两种链路复用，
 * 差别只在于"谁来决定要不要调用它"。</p>
 */
@Component
public class KnowledgeSearchTool implements Tool {

    private final RetrievalService retrievalService;

    public KnowledgeSearchTool(RetrievalService retrievalService) {
        this.retrievalService = retrievalService;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "在知识库中检索与问题相关的资料。当你需要事实性依据（制度、流程、技术文档）时使用。";
    }

    @Override
    public String parameters() {
        return "{\"query\": \"检索关键词\", \"kbId\": 知识库ID(可选)\"}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        // ① 参数校验：不能信任模型的输出
        Object queryObj = arguments.get("query");
        if (queryObj == null || queryObj.toString().isBlank()) {
            return ToolResult.fail("缺少参数 query");
        }
        String query = queryObj.toString();
        Long kbId = null;
        Object kbObj = arguments.get("kbId");
        if (kbObj != null) {
            try {
                kbId = Long.valueOf(kbObj.toString());
            } catch (NumberFormatException ignored) {
                // kbId 非法就当作没传，不因此让整轮失败
            }
        }

        try {
            RetrievalResult result = retrievalService.retrieve(query, kbId, 5);
            if (result.hits().isEmpty()) {
                return ToolResult.ok("未检索到相关资料。可以尝试换一种关键词重新检索，或直接回答『知识库中暂无相关资料』。");
            }
            StringBuilder sb = new StringBuilder();
            sb.append("检索到 ").append(result.hits().size()).append(" 条资料：\n");
            for (int i = 0; i < result.hits().size(); i++) {
                RetrievalHit hit = result.hits().get(i);
                sb.append('[').append(i + 1).append("] 《").append(hit.title()).append("》 ")
                        .append(hit.content()).append('\n');
            }
            return ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolResult.fail("检索失败: " + e.getMessage());
        }
    }
}
