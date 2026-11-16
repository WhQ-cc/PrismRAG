package com.prism.rag.rag.pipeline.parser;

import com.prism.rag.rag.pipeline.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown 解析器：按标题切分章节，并剥离 Markdown 标记。
 *
 * <p><b>为什么 Markdown 要专门处理？</b>因为 Markdown 的标题天然就是最好的分段依据。
 * "# 第二章 差旅报销"下面的一整段，就是一个语义完整、自带标题的 chunk——
 * 直接按标题切，比按字数硬切准确得多。</p>
 *
 * <p>同时把标记语法清掉（{@code #}、{@code **}、{@code `}、链接），
 * 这些符号对模型没有价值，反而占用 token。</p>
 */
@Component
public class MarkdownParser implements DocumentParser {

    /** 标题行：# ~ ###### 后跟空格 */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");

    @Override
    public boolean supports(String sourceType) {
        return "markdown".equalsIgnoreCase(sourceType) || "md".equalsIgnoreCase(sourceType);
    }

    @Override
    public ParsedDocument parse(String title, String content) {
        List<ParsedDocument.Section> sections = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return new ParsedDocument(title, "", sections);
        }

        String currentHeading = null;
        StringBuilder currentBody = new StringBuilder();

        for (String line : content.split("\\r?\\n")) {
            Matcher matcher = HEADING.matcher(line.trim());
            if (matcher.matches()) {
                // 遇到新标题：先把上一节收尾
                flush(sections, currentHeading, currentBody);
                currentHeading = clean(matcher.group(2));
                currentBody.setLength(0);
            } else {
                currentBody.append(line).append('\n');
            }
        }
        flush(sections, currentHeading, currentBody);

        return new ParsedDocument(title, ParsedDocument.joinSections(sections), sections);
    }

    private void flush(List<ParsedDocument.Section> sections, String heading, StringBuilder body) {
        String text = clean(body.toString());
        if (!text.isEmpty() || heading != null) {
            sections.add(new ParsedDocument.Section(heading, text));
        }
        body.setLength(0);
    }

    /**
     * 剥离 Markdown 标记。
     */
    private String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw;
        // 代码块围栏
        text = text.replaceAll("(?s)```.*?```", " ");
        // 行内代码
        text = text.replaceAll("`([^`]*)`", "$1");
        // 加粗 / 斜体
        text = text.replaceAll("\\*\\*([^*]+)\\*\\*", "$1");
        text = text.replaceAll("\\*([^*]+)\\*", "$1");
        // 图片与链接：只保留可读文字
        text = text.replaceAll("!\\[[^\\]]*]\\(([^)]*)\\)", "$1");
        text = text.replaceAll("\\[([^\\]]*)]\\(([^)]*)\\)", "$1");
        // 引用符号、列表符号
        text = text.replaceAll("(?m)^\\s*>\\s?", "");
        text = text.replaceAll("(?m)^\\s*[-*+]\\s+", "");
        text = text.replaceAll("(?m)^\\s*\\d+\\.\\s+", "");
        // 水平分割线
        text = text.replaceAll("(?m)^\\s*(-{3,}|\\*{3,}|_{3,})\\s*$", "");
        return text.trim();
    }
}
