package com.prism.rag.rag.pipeline.parser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 解析器注册表：按来源类型挑解析器。
 *
 * <p>Spring 会把所有 {@link DocumentParser} 实现注入进来，
 * 我们在多个实现里挑出 {@code supports(sourceType)} 返回 true 的那个。
 * 新增解析器只要加个 {@code @Component}，这里一行都不用改。</p>
 *
 * <p><b>兜底策略很重要：</b>如果没有任何解析器支持该类型，
 * 退回到纯文本解析器，而不是抛异常。入库流程不能因为"格式怪一点"就整个失败。</p>
 */
@Component
public class ParserRegistry {

    private static final Logger log = LoggerFactory.getLogger(ParserRegistry.class);

    private final List<DocumentParser> parsers;

    public ParserRegistry(List<DocumentParser> parsers) {
        this.parsers = parsers;
        log.info("[Parser] 已注册解析器 {} 个: {}", parsers.size(),
                parsers.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    /**
     * 获取匹配的解析器。
     */
    public DocumentParser resolve(String sourceType) {
        for (DocumentParser parser : parsers) {
            if (parser.supports(sourceType)) {
                return parser;
            }
        }
        // 理论上 PlainTextParser 兜底，不会走到这里
        log.warn("[Parser] 没有解析器支持类型 {}，回退到纯文本", sourceType);
        return parsers.stream()
                .filter(p -> p instanceof PlainTextParser)
                .findFirst()
                .orElseGet(() -> new PlainTextParser());
    }
}
