package com.prism.rag.rag.pipeline.parser;

import com.prism.rag.rag.pipeline.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 纯文本解析器：按空行切段。
 *
 * <p>这是所有解析器的兜底实现——任何没被专门处理的格式，都按纯文本处理，
 * 保证流水线不会因为"不认识这个格式"而失败。</p>
 */
@Component
public class PlainTextParser implements DocumentParser {

    @Override
    public boolean supports(String sourceType) {
        return true;
    }

    @Override
    public ParsedDocument parse(String title, String content) {
        List<ParsedDocument.Section> sections = new ArrayList<>();
        if (content != null && !content.isBlank()) {
            // 按空行分段，保留原始顺序
            String[] blocks = content.split("\\r?\\n\\s*\\r?\\n");
            for (String block : blocks) {
                String trimmed = block.trim();
                if (!trimmed.isEmpty()) {
                    sections.add(new ParsedDocument.Section(null, trimmed));
                }
            }
        }
        return new ParsedDocument(title, ParsedDocument.joinSections(sections), sections);
    }
}
