package com.prism.rag.infra.ai.client;

import com.prism.rag.common.text.TextUtils;
import com.prism.rag.common.text.Tokenizer;
import com.prism.rag.infra.ai.config.AiProperties;
import com.prism.rag.infra.ai.model.ChatRequest;
import com.prism.rag.infra.ai.model.ChatResponse;
import com.prism.rag.infra.ai.model.ContextItem;
import com.prism.rag.infra.ai.model.ReActProtocol;
import com.prism.rag.infra.ai.spi.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * 离线 Mock 对话模型。
 *
 * <p><b>它不是"假装在思考"，而是真能干活。</b></p>
 * <p>Mock 收到的问题里带着检索上下文，它会做<b>抽取式回答</b>：
 * 用分词器算每条上下文与问题的词元重合度，挑出最相关的句子，
 * 拼成一段带引用编号的回答。</p>
 *
 * <p>所以在没有 Key、没有网络的环境下，页面上看到的答案<b>确实来自你的知识库</b>，
 * 只是措辞是模板拼的、语义理解能力不如真模型而已。
 * 这让"前端流式渲染、引用溯源、会话记忆"这些<b>工程能力</b>可以被完整验证。</p>
 *
 * <p>它同时支持 ReAct 协议分支：当提示词里出现 Observation，说明上一轮工具已经执行完，
 * 此时它会输出 Final Answer 而不是继续要工具。</p>
 */
public class MockChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(MockChatClient.class);

    private final AiProperties properties;

    public MockChatClient(AiProperties properties) {
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String model() {
        return "offline-mock-v1";
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        long start = System.currentTimeMillis();
        String content = buildAnswer(request);
        return ChatResponse.builder()
                .content(content)
                .provider(provider())
                .model(model())
                .promptTokens(ChatResponse.estimateTokens(request.getQuestion()))
                .completionTokens(ChatResponse.estimateTokens(content))
                .totalTokens(ChatResponse.estimateTokens(request.getQuestion()) + ChatResponse.estimateTokens(content))
                .latencyMillis(System.currentTimeMillis() - start)
                .build();
    }

    /**
     * 流式输出：把整段答案切成小块逐个吐出，模拟真模型的 token 级推送。
     *
     * <p><b>关键：每一步都检查线程中断标记。</b>
     * 用户点"停止生成"或关掉页面时，{@code Future.cancel(true)} 会中断本线程，
     * 这里必须及时退出，否则会白烧 CPU。</p>
     */
    @Override
    public void stream(ChatRequest request, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        String content = buildAnswer(request);
        try {
            int index = 0;
            int chunkSize = 6;
            while (index < content.length()) {
                // ① 随时检查是否被取消
                if (Thread.currentThread().isInterrupted()) {
                    log.debug("[Mock] 收到中断信号，停止流式输出");
                    listener.onError(new CancellationException("客户端已断开或用户主动取消"));
                    return;
                }
                int end = Math.min(index + chunkSize, content.length());
                listener.onDelta(content.substring(index, end));
                index = end;
                // ② 模拟网络延迟，让前端的打字机效果可见
                sleep(properties.getMockStreamDelayMillis());
            }
            listener.onComplete(ChatResponse.builder()
                    .content(content)
                    .provider(provider())
                    .model(model())
                    .promptTokens(ChatResponse.estimateTokens(request.getQuestion()))
                    .completionTokens(ChatResponse.estimateTokens(content))
                    .latencyMillis(System.currentTimeMillis() - start)
                    .build());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onError(new CancellationException("流式输出被中断"));
        }
    }

    /**
     * 构造回答内容。
     */
    private String buildAnswer(ChatRequest request) {
        String systemPrompt = request.getSystemPrompt() == null ? "" : request.getSystemPrompt();
        // ReAct 场景走另一套协议
        if (systemPrompt.contains(ReActProtocol.FINAL_ANSWER)
                || systemPrompt.contains("ReAct")
                || containsObservation(request)) {
            return buildReActAnswer(request);
        }
        return buildStandardAnswer(request);
    }

    /**
     * 判断本轮是否已经拿到了工具执行结果。
     */
    private boolean containsObservation(ChatRequest request) {
        if (request.getMemory() == null) {
            return false;
        }
        for (int i = request.getMemory().size() - 1; i >= 0; i--) {
            String content = request.getMemory().get(i).getContent();
            if (content != null && content.contains(ReActProtocol.OBSERVATION)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标准问答：抽取式回答。
     */
    private String buildStandardAnswer(ChatRequest request) {
        String question = request.getQuestion() == null ? "" : request.getQuestion();
        List<ContextItem> context = request.getContext() == null ? List.of() : request.getContext();

        if (context.isEmpty()) {
            return buildNoContextAnswer(question);
        }

        // ① 按与问题的词元重合度给上下文排序
        List<ContextItem> ranked = new ArrayList<>(context);
        Set<String> queryTokens = new HashSet<>(Tokenizer.tokenize(question));
        ranked.sort(Comparator.comparingInt(
                (ContextItem item) -> -overlap(queryTokens, item)).reversed());

        // ② 取最相关的 2 条，拼出带引用编号的回答
        StringBuilder answer = new StringBuilder();
        List<ContextItem> selected = ranked.stream()
                .filter(item -> overlap(queryTokens, item) > 0)
                .limit(2)
                .toList();
        if (selected.isEmpty()) {
            // 没有词元重合，但检索确实召回了内容，就取第一条
            selected = ranked.subList(0, Math.min(1, ranked.size()));
        }

        // ③ 如果有会话记忆，带一句上下文衔接
        String memoryHint = memoryHint(request);

        answer.append(memoryHint);
        for (int i = 0; i < selected.size(); i++) {
            ContextItem item = selected.get(i);
            String snippet = TextUtils.bestSnippet(item.getContent(), question, 220);
            if (i == 0) {
                answer.append("根据知识库《").append(item.getTitle()).append("》：\n");
            } else {
                answer.append("\n另外，《").append(item.getTitle()).append("》中提到：\n");
            }
            answer.append(snippet).append("（引用[").append(item.getIndex()).append("]）\n");
        }
        answer.append("\n---\n")
                .append("_本回答由内置离线 Mock 模型基于检索片段抽取生成，未调用真实大模型。_");
        return answer.toString();
    }

    /**
     * 没有检索到内容时的回答——同样要给出可执行的下一步，而不是"我不知道"。
     */
    private String buildNoContextAnswer(String question) {
        return "我在知识库里没有检索到与「" + question + "」直接相关的内容。\n\n"
                + "可以试试：\n"
                + "1. 换一种说法，把问题描述得更具体；\n"
                + "2. 到「知识库」页确认目标文档的入库流水线状态是否为 **已完成**；\n"
                + "3. 检查文档是否覆盖了该主题——双路召回（向量 + BM25）都没命中时，通常是知识本身缺失。";
    }

    /**
     * 会话记忆提示：把上一轮问题带一句，方便多轮对话衔接。
     */
    private String memoryHint(ChatRequest request) {
        if (request.getMemory() == null || request.getMemory().isEmpty()) {
            return "";
        }
        for (int i = request.getMemory().size() - 1; i >= 0; i--) {
            String role = request.getMemory().get(i).getRole();
            String content = request.getMemory().get(i).getContent();
            if ("user".equals(role) && content != null && !content.isBlank()) {
                return "结合上一轮的问题「" + TextUtils.truncate(content, 40) + "」，回答如下：\n\n";
            }
        }
        return "";
    }

    /**
     * ReAct 分支：输出下一步动作，或最终答案。
     */
    private String buildReActAnswer(ChatRequest request) {
        if (containsObservation(request)) {
            // 已经拿到工具结果 → 收尾
            String observation = lastObservation(request);
            String answer = "根据检索到的资料：" + TextUtils.truncate(observation, 600);
            return ReActProtocol.THOUGHT + " 已经拿到检索结果，可以组织最终答案了。\n"
                    + ReActProtocol.FINAL_ANSWER + " " + answer;
        }
        // 第一轮 → 请求检索
        String query = request.getQuestion() == null ? "" : request.getQuestion();
        return ReActProtocol.THOUGHT + " 这是一个知识库问答问题，需要先检索知识库获取依据。\n"
                + ReActProtocol.ACTION + " knowledge_search\n"
                + ReActProtocol.ACTION_INPUT + " {\"query\": \"" + escapeJson(query) + "\"}";
    }

    /**
     * 取出最后一条 Observation 的内容。
     */
    private String lastObservation(ChatRequest request) {
        for (int i = request.getMemory().size() - 1; i >= 0; i--) {
            String content = request.getMemory().get(i).getContent();
            if (content != null && content.contains(ReActProtocol.OBSERVATION)) {
                int idx = content.indexOf(ReActProtocol.OBSERVATION);
                return content.substring(idx + ReActProtocol.OBSERVATION.length()).trim();
            }
        }
        return "";
    }

    /**
     * 计算词元重合数量。
     */
    private int overlap(Set<String> queryTokens, ContextItem item) {
        if (queryTokens.isEmpty() || item.getContent() == null) {
            return 0;
        }
        Set<String> itemTokens = new HashSet<>(Tokenizer.tokenize(item.getContent()));
        int count = 0;
        for (String token : queryTokens) {
            if (itemTokens.contains(token)) {
                count++;
            }
        }
        return count;
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private void sleep(long millis) throws InterruptedException {
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }
}
