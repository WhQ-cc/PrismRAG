package com.prism.rag.agent.react;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 当前时间工具。
 *
 * <p>看似鸡肋，但它是 ReAct 教学里最好用的示例工具：
 * 它的输出是确定的，所以你可以清楚地看到"工具结果被当成 Observation 喂回模型"这个过程。</p>
 */
@Component
public class CurrentTimeTool implements Tool {

    @Override
    public String name() {
        return "current_time";
    }

    @Override
    public String description() {
        return "获取当前的日期和时间。当用户问『现在几点』『今天几号』时使用。";
    }

    @Override
    public String parameters() {
        return "{}";
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm:ss"));
        return ToolResult.ok("当前时间是：" + now);
    }
}
