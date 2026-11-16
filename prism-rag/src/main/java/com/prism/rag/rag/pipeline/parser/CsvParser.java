package com.prism.rag.rag.pipeline.parser;

import com.prism.rag.rag.pipeline.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 解析器：每一行转成一个可独立检索的片段。
 *
 * <p><b>表格类文档的特殊考虑：</b>如果把整个 CSV 塞进一个 chunk，
 * 检索命中后模型看到的是一张几十行的表，很难定位到具体哪一行。
 * 把每一行变成"字段名=字段值"的自然语言形式，既能被检索到，也便于模型理解。</p>
 *
 * <pre>
 *   原始：姓名,部门,职级
 *        张三,技术部,P7
 *   转换：第2行：姓名=张三；部门=技术部；职级=P7
 * </pre>
 */
@Component
public class CsvParser implements DocumentParser {

    @Override
    public boolean supports(String sourceType) {
        return "csv".equalsIgnoreCase(sourceType);
    }

    @Override
    public ParsedDocument parse(String title, String content) {
        List<ParsedDocument.Section> sections = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return new ParsedDocument(title, "", sections);
        }

        String[] lines = content.split("\\r?\\n");
        if (lines.length == 0) {
            return new ParsedDocument(title, "", sections);
        }

        // 第一行作为表头
        List<String> headers = splitCsvLine(lines[0]);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> values = splitCsvLine(line);
            StringBuilder sb = new StringBuilder("第").append(i + 1).append("行：");
            for (int c = 0; c < values.size(); c++) {
                String header = c < headers.size() ? headers.get(c) : ("列" + (c + 1));
                sb.append(header).append('=').append(values.get(c)).append('；');
            }
            sections.add(new ParsedDocument.Section(null, sb.toString()));
        }
        return new ParsedDocument(title, ParsedDocument.joinSections(sections), sections);
    }

    /**
     * 简单的 CSV 行切分（支持双引号包裹的逗号）。
     */
    private List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }
}
