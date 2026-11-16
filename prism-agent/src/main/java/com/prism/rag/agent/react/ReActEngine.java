package com.prism.rag.agent.react;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prism.rag.infra.ai.model.ChatMessage;
import com.prism.rag.infra.ai.model.ChatRequest;
import com.prism.rag.infra.ai.model.ChatResponse;
import com.prism.rag.infra.ai.model.ReActProtocol;
import com.prism.rag.infra.ai.spi.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 执行引擎。
 *
 * <h3>ReAct 是什么？</h3>
 * <p>ReAct = <b>R</b>easoning + <b>Act</b>ing。核心循环是：</p>
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │ 1. 模型输出 Thought + Action + Action Input   │
 *   │ 2. 引擎执行工具，把结果作为 Observation 喂回  │
 *   │ 3. 模型看到 Observation，决定下一步            │
 *   │ 4. 模型输出 Final Answer → 结束                │
 *   └──────────────────────────────────────────────┘
 * </pre>
 *
 * <h3>和固定 Workflow 的区别</h3>
 * <table border="1">
 *   <caption>Workflow vs Agent</caption>
 *   <tr><th></th><th>Workflow（问答主链路）</th><th>Agent（ReAct）</th></tr>
 *   <tr><td>路径</td><td>代码写死</td><td>模型自己决定</td></tr>
 *   <tr><td>检索次数</td><td>固定 1 次</td><td>0~N 次，由模型决定</td></tr>
 *   <tr><td>可控性</td><td>高</td><td>低</td></tr>
 *   <tr><td>适合</td><td>常规问答</td><td>需要多步推理、调用外部系统</td></tr>
 * </table>
 * <p><b>两者并存</b>才是正确做法：常规问题走 Workflow（更快更稳），
 * 复杂任务才交给 Agent。</p>
 *
 * <h3>必须有步数上限</h3>
 * <p>模型可能陷入死循环（反复调同一个工具）。{@code maxSteps} 是硬性熔断，
 * 超过就返回当前最佳结果，绝不让请求无限循环下去。</p>
 */
@Service
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    /** 最大执行步数（熔断） */
    @Value("${prism.agent.max-steps:6}")
    private int maxSteps;

    private final ChatClient chatClient;
    private final ToolRegistry toolRegistry;
    private final ReActParser parser = new ReActParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReActEngine(ChatClient chatClient, ToolRegistry toolRegistry) {
        this.chatClient = chatClient;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行一次 ReAct 任务。
     *
     * @param question 用户问题
     * @param kbId     知识库（供检索工具使用）
     */
    public ReActRun run(String question, Long kbId) {
        long start = System.currentTimeMillis();
        List<ReActStep> steps = new ArrayList<>();
        List<ChatMessage> history = new ArrayList<>();
        String finalAnswer = null;
        boolean success = false;

        for (int i = 1; i <= maxSteps; i++) {
            // ① 让模型决策
            ChatRequest request = ChatRequest.builder()
                    .systemPrompt(buildSystemPrompt())
                    .memory(new ArrayList<>(history))
                    .question(question)
                    .stream(false)
                    .build();

            ChatResponse response;
            try {
                response = chatClient.complete(request);
            } catch (Exception e) {
                log.error("[ReAct] 模型调用失败: {}", e.getMessage());
                steps.add(new ReActStep(i, null, null, null, "模型调用失败: " + e.getMessage(), false));
                break;
            }

            String output = response.getContent() == null ? "" : response.getContent();
            ReActParser.Parsed parsed = parser.parse(output);

            // ② 模型给出最终答案 → 收尾
            if (parsed.hasFinalAnswer()) {
                finalAnswer = parsed.finalAnswer();
                steps.add(new ReActStep(i, parsed.thought(), null, null, finalAnswer, true));
                success = true;
                break;
            }

            // ③ 模型要求调用工具
            if (parsed.hasAction()) {
                history.add(ChatMessage.assistant(output));
                Tool tool = toolRegistry.get(parsed.action());
                if (tool == null) {
                    String observation = "未知工具：" + parsed.action()
                            + "。可用工具：" + toolRegistry.all().stream().map(Tool::name).toList();
                    history.add(ChatMessage.user(ReActProtocol.OBSERVATION + " " + observation));
                    steps.add(new ReActStep(i, parsed.thought(), parsed.action(), parsed.actionInput(), observation, false));
                    continue;
                }

                Map<String, Object> arguments = parseArguments(parsed.actionInput());
                // 如果模型没给 kbId，注入当前会话的知识库
                if (!arguments.containsKey("kbId") && kbId != null) {
                    arguments.put("kbId", kbId);
                }

                ToolResult result;
                try {
                    result = tool.execute(arguments);
                } catch (Exception e) {
                    result = ToolResult.fail("工具执行异常: " + e.getMessage());
                }

                String observation = result.success()
                        ? result.output()
                        : ("工具执行失败：" + result.error());
                history.add(ChatMessage.user(ReActProtocol.OBSERVATION + " " + observation));
                steps.add(new ReActStep(i, parsed.thought(), parsed.action(),
                        parsed.actionInput(), observation, result.success()));
                log.info("[ReAct] 第 {} 步 调用工具 {} 成功={}", i, parsed.action(), result.success());
                continue;
            }

            // ④ 模型没有按格式输出：纠正一次
            log.warn("[ReAct] 第 {} 步输出无法解析: {}", i, TextUtilsPreview.truncate(output, 200));
            history.add(ChatMessage.assistant(output));
            history.add(ChatMessage.user(ReActProtocol.OBSERVATION
                    + " 你的输出无法解析，请严格使用 Action / Action Input / Final Answer 格式。"));
            steps.add(new ReActStep(i, parsed.thought(), null, null, "输出格式无法解析", false));
        }

        if (finalAnswer == null) {
            finalAnswer = "已达到最大执行步数（" + maxSteps + "），未能得出最终答案。"
                    + "请缩小问题范围后重试，或改用知识库问答链路。";
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[ReAct] 执行结束，步数 {}，成功={}，耗时 {}ms", steps.size(), success, elapsed);
        return new ReActRun(finalAnswer, steps, steps.size(), success, elapsed);
    }

    /**
     * 构造 ReAct 系统提示词。
     */
    private String buildSystemPrompt() {
        return """
                你是一个可以使用工具的智能助手，请使用 ReAct（推理 + 行动）模式解决问题。

                可用工具：
                %s

                严格按以下格式输出，每轮只输出一段：

                Thought: 你的推理
                Action: 工具名
                Action Input: {"参数名": "参数值"}

                或者当你已经能够回答时：

                Thought: 你的推理
                Final Answer: 最终答案

                工具执行结果会以 Observation: 开头的形式返回给你。
                """.formatted(toolRegistry.describeForPrompt());
    }

    /**
     * 解析 Action Input（模型给的 JSON 参数）。
     */
    private Map<String, Object> parseArguments(String actionInput) {
        Map<String, Object> arguments = new HashMap<>();
        if (actionInput == null || actionInput.isBlank()) {
            return arguments;
        }
        try {
            return objectMapper.readValue(actionInput, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[ReAct] Action Input 不是合法 JSON，按原文处理: {}", actionInput);
            arguments.put("_raw", actionInput);
            return arguments;
        }
    }

    /**
     * 小工具（避免为了截断字符串再引入一个工具类）。
     */
    static final class TextUtilsPreview {
        static String truncate(String text, int max) {
            if (text == null) {
                return "";
            }
            return text.length() <= max ? text : text.substring(0, max) + "…";
        }
    }
}
