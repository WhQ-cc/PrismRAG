package com.prism.rag.agent.react;

import com.prism.rag.infra.ai.model.ReActProtocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 输出解析器。
 *
 * <p>把模型输出的自由文本解析成结构化的动作指令。</p>
 *
 * <h3>解析为什么要用"位置"而不是纯正则？</h3>
 * <p>因为 {@code "Action:"} 和 {@code "Action Input:"} 都是 Action 家族，
 * 纯正则很容易把 {@code "Action Input:"} 误判成 {@code "Action:"}。
 * 这里用<b>先找 Action Input，再找 Action</b>的顺序 + 边界检查，
 * 保证解析稳定。</p>
 *
 * <h3>模型不按格式输出怎么办？</h3>
 * <p>这是常态，不是异常。所以解析器要能识别"什么都没解析出来"，
 * 由引擎把一条"请按格式回答"的 Observation 喂回去，而不是直接抛异常终止。</p>
 */
public class ReActParser {

    private static final Pattern THOUGHT = Pattern.compile(
            Pattern.quote(ReActProtocol.THOUGHT) + "\\s*([\\s\\S]*?)(?=\\n\\s*" + Pattern.quote(ReActProtocol.ACTION)
                    + "|\\n\\s*" + Pattern.quote(ReActProtocol.FINAL_ANSWER) + "|$)");

    private static final Pattern ACTION_INPUT = Pattern.compile(
            Pattern.quote(ReActProtocol.ACTION_INPUT) + "\\s*(\\{[\\s\\S]*?\\})");

    private static final Pattern ACTION = Pattern.compile(
            Pattern.quote(ReActProtocol.ACTION) + "\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private static final Pattern FINAL_ANSWER = Pattern.compile(
            Pattern.quote(ReActProtocol.FINAL_ANSWER) + "\\s*([\\s\\S]*)$");

    /**
     * 解析结果。
     *
     * @param thought     推理内容
     * @param action      工具名（没有则为 null）
     * @param actionInput 工具参数原文（JSON 字符串）
     * @param finalAnswer 最终答案（没有则为 null）
     */
    public record Parsed(String thought, String action, String actionInput, String finalAnswer) {

        public boolean hasAction() {
            return action != null && !action.isBlank();
        }

        public boolean hasFinalAnswer() {
            return finalAnswer != null && !finalAnswer.isBlank();
        }
    }

    /**
     * 解析模型输出。
     */
    public Parsed parse(String output) {
        if (output == null || output.isBlank()) {
            return new Parsed(null, null, null, null);
        }
        String text = output.trim();

        // ① 先定位 Action Input 的位置（它比 Action 更长更具体）
        Matcher inputMatcher = ACTION_INPUT.matcher(text);
        String actionInput = null;
        int inputStart = -1;
        if (inputMatcher.find()) {
            actionInput = inputMatcher.group(1);
            inputStart = inputMatcher.start();
        }

        // ② 定位 Action（必须出现在 Action Input 之前，否则是 "Action Input:" 里的 Action）
        Matcher actionMatcher = ACTION.matcher(text);
        String action = null;
        if (actionMatcher.find()) {
            if (inputStart < 0 || actionMatcher.start() < inputStart) {
                action = actionMatcher.group(1);
            }
        }

        // ③ 定位 Final Answer
        Matcher finalMatcher = FINAL_ANSWER.matcher(text);
        String finalAnswer = finalMatcher.find() ? finalMatcher.group(1).trim() : null;

        // ④ 如果同时出现 Action 和 Final Answer，以 Final Answer 为准（说明模型想收尾）
        if (finalAnswer != null && !finalAnswer.isBlank() && action == null) {
            action = null;
            actionInput = null;
        }

        // ⑤ 提取 Thought
        Matcher thoughtMatcher = THOUGHT.matcher(text);
        String thought = thoughtMatcher.find() ? thoughtMatcher.group(1).trim() : null;

        return new Parsed(thought, action, actionInput, finalAnswer);
    }
}
