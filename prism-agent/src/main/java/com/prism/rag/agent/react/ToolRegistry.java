package com.prism.rag.agent.react;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 *
 * <p>所有 {@link Tool} 实现被 Spring 收集进来，按名字索引。
 * 新增工具 = 新增一个 {@code @Component}，引擎和 Prompt 自动带上它。</p>
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        for (Tool tool : toolList) {
            tools.put(tool.name(), tool);
        }
        log.info("[ReAct] 已注册工具 {} 个: {}", tools.size(), tools.keySet());
    }

    /**
     * 按名字获取工具。
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * 所有工具。
     */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    /**
     * 生成给模型看的工具清单（会拼进 ReAct 的系统提示词）。
     */
    public String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools.values()) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description())
                    .append("；参数：").append(tool.parameters()).append('\n');
        }
        return sb.toString();
    }
}
