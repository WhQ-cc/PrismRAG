package com.prism.rag.rag.pipeline.chunk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分块策略注册表。
 *
 * <p>和 {@code ParserRegistry} 同理：新增策略 = 新增一个 {@code @Component} 实现，
 * 这里不用改。</p>
 */
@Component
public class ChunkStrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChunkStrategyRegistry.class);

    private final List<ChunkStrategy> strategies;

    public ChunkStrategyRegistry(List<ChunkStrategy> strategies) {
        this.strategies = strategies;
        log.info("[Chunk] 已注册分块策略 {} 个", strategies.size());
    }

    /**
     * 按名称获取策略，未找到则回退到滑窗（最通用）。
     */
    public ChunkStrategy resolve(String name) {
        if (name != null) {
            for (ChunkStrategy strategy : strategies) {
                if (strategy.name().equalsIgnoreCase(name)) {
                    return strategy;
                }
            }
        }
        log.warn("[Chunk] 未找到分块策略 {}，回退到 window", name);
        return strategies.stream()
                .filter(s -> s instanceof TokenWindowChunker)
                .findFirst()
                .orElseGet(TokenWindowChunker::new);
    }
}
