package com.prism.rag.rag.pipeline.node;

import com.prism.rag.rag.pipeline.AbstractIngestionNode;
import com.prism.rag.rag.pipeline.IngestionContext;
import com.prism.rag.rag.pipeline.IngestionState;
import com.prism.rag.rag.pipeline.parser.ParserRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 节点 1：解析。
 *
 * <p>把原始内容交给对应的解析器，产出干净的纯文本 + 结构化章节。</p>
 */
@Component
@Order(1)
public class ParseNode extends AbstractIngestionNode {

    private final ParserRegistry parserRegistry;

    public ParseNode(ParserRegistry parserRegistry) {
        this.parserRegistry = parserRegistry;
    }

    @Override
    public String name() {
        return "parse";
    }

    @Override
    public IngestionState targetState() {
        return IngestionState.PARSING;
    }

    @Override
    protected void doExecute(IngestionContext context) {
        var parser = parserRegistry.resolve(context.getSourceType());
        var parsed = parser.parse(context.getTitle(), context.getRawContent());
        if (parsed.fullText() == null || parsed.fullText().isBlank()) {
            throw new IllegalStateException("解析结果为空，文档可能没有有效内容");
        }
        context.setParsed(parsed);
    }

    @Override
    protected String describeResult(IngestionContext context) {
        int sections = context.getParsed() == null ? 0 : context.getParsed().sections().size();
        return "解析出 " + sections + " 个章节";
    }
}
