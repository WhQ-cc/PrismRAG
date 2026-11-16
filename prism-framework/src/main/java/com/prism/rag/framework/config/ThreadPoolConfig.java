package com.prism.rag.framework.config;

import com.prism.rag.framework.context.TraceTaskDecorator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程池隔离配置。
 *
 * <p><b>为什么一个应用要开五个线程池？</b></p>
 * <p>因为不同业务对"延迟"和"吞吐"的诉求完全不同，如果全挤在一个池子里，
 * 会出现典型的<b>级联雪崩</b>：</p>
 * <blockquote>
 * 早上运维导入了一份 300 页的 PDF，触发入库流水线。
 * 入库过程要调用向量模型做 800 次向量化，假设这些任务都占用了同一个线程池——
 * 此时用户在前端问了一个问题，问答线程被向量化任务挤满，
 * <b>检索和生成拿不到线程，页面就一直转圈</b>。
 * 一个后台任务，把前台功能打挂了。
 * </blockquote>
 *
 * <p>所以本项目按"业务负载特征"把线程池拆开，核心原则三条：</p>
 * <ol>
 *   <li><b>慢任务与快任务分离</b>：入库（分钟级）绝不能和检索（毫秒级）抢线程。</li>
 *   <li><b>对外请求与内部计算分离</b>：调用大模型（网络 IO）不应该阻塞本地计算。</li>
 *   <li><b>有界队列 + 明确拒绝策略</b>：队列无限长等于没有背压，最终 OOM。</li>
 * </ol>
 *
 * <p>本项目五个池的分工：</p>
 * <table border="1">
 *   <caption>线程池隔离清单</caption>
 *   <tr><th>池</th><th>承载</th><th>特征</th></tr>
 *   <tr><td>ingest</td><td>入库流水线主控</td><td>慢、批量，队列中等</td></tr>
 *   <tr><td>embedding</td><td>向量化（调模型）</td><td>网络 IO 密集，与计算分离</td></tr>
 *   <tr><td>retrieval</td><td>双路召回</td><td>要求低延迟，队列短</td></tr>
 *   <tr><td>chat</td><td>SSE 流式问答</td><td>长连接占用，必须独立</td></tr>
 *   <tr><td>misc</td><td>Agent 工具、杂项异步</td><td>兜底池</td></tr>
 * </table>
 *
 * <p><b>为什么直接用 {@link ThreadPoolExecutor} 而不是 Spring 的 ThreadPoolTaskExecutor？</b><br>
 * 因为 Spring 的 {@code ThreadPoolTaskExecutor} 只实现了 {@code Executor}，
 * <b>没有 {@code submit()}，也就拿不到 {@code Future}</b>，
 * 而 SSE 问答的"断开取消"必须依赖 {@code Future.cancel(true)} 去中断线程。
 * 所以这里直接用 JDK 原生的 {@code ThreadPoolExecutor}（它实现 {@code ExecutorService}）。</p>
 *
 * <p><b>换成动态线程池？</b>生产上可以用「oneThread」这类动态线程池框架，
 * 把核心/最大线程数、队列长度放到配置中心实时调整，并接入监控告警。
 * 本项目先用固定参数把"隔离"的思路讲清楚。</p>
 */
@Configuration
public class ThreadPoolConfig {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolConfig.class);

    /** 入库流水线：解析→分块→向量化→持久化的编排线程 */
    public static final String INGEST_EXECUTOR = "ingestExecutor";

    /** 向量化线程池：批量调用 Embedding 模型 */
    public static final String EMBEDDING_EXECUTOR = "embeddingExecutor";

    /** 检索线程池：向量路 + BM25 路的并行召回 */
    public static final String RETRIEVAL_EXECUTOR = "retrievalExecutor";

    /** 问答线程池：SSE 流式生成，一个连接占一个线程直到流结束 */
    public static final String CHAT_EXECUTOR = "chatExecutor";

    /** 杂项线程池：Agent 工具调用、后台维护任务 */
    public static final String MISC_EXECUTOR = "miscExecutor";

    /** 全部线程池，应用关闭时统一收尾 */
    private final List<ExecutorService> pools = new ArrayList<>();

    /**
     * 入库流水线主控池。
     *
     * <p>拒绝策略用 {@code CallerRunsPolicy}（由提交线程自己跑）：
     * 宁可让提交者变慢，也不能把任务丢掉——入库任务是用户显式发起的。</p>
     */
    @Bean(name = INGEST_EXECUTOR)
    public ExecutorService ingestExecutor() {
        return build("prism-ingest", 4, 8, 256, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 向量化池。
     *
     * <p>独立出来的核心原因：向量化全是"等模型返回"的网络 IO，
     * 和本地的解析/分块计算混在一起会白白占着 CPU 却什么都干不了。</p>
     */
    @Bean(name = EMBEDDING_EXECUTOR)
    public ExecutorService embeddingExecutor() {
        return build("prism-embed", 8, 16, 512, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 检索池。
     *
     * <p>双路召回会并行发起两个子任务，池子至少要有 2 个线程才能真正并行。
     * 队列刻意开短（64），因为检索是"宁可失败也不能等"——
     * 队列积压意味着用户已经等了很久了。</p>
     */
    @Bean(name = RETRIEVAL_EXECUTOR)
    public ExecutorService retrievalExecutor() {
        return build("prism-retrieval", 8, 16, 64, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 问答池。
     *
     * <p>SSE 是长连接：一个流式回答可能持续几十秒，期间线程一直被占着。
     * 如果这个池和别的业务共用，几十个用户同时聊天就能把所有线程吃光。</p>
     *
     * <p>拒绝策略用 {@code AbortPolicy}：池满时直接抛异常，
     * 由 ChatService 捕获后给前端推一个 error 事件。
     * 用 CallerRuns 会让 HTTP 线程接管 SSE 输出，导致 Tomcat 工作线程被长期占用，
     * 那是另一种雪崩。</p>
     */
    @Bean(name = CHAT_EXECUTOR)
    public ExecutorService chatExecutor() {
        return build("prism-chat", 16, 32, 128, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 杂项池。
     */
    @Bean(name = MISC_EXECUTOR)
    public ExecutorService miscExecutor() {
        return build("prism-misc", 4, 8, 256, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 构建一个带命名线程、支持上下文透传的线程池。
     *
     * <p>线程必须有可读名字（prism-chat-1、prism-ingest-3……），
     * 否则线上 dump 出来一堆 "pool-3-thread-7"，排查问题时根本分不清是谁。</p>
     */
    private ExecutorService build(String namePrefix, int core, int max, int queueCapacity,
                                  RejectedExecutionHandler rejection) {
        final TaskDecorator decorator = new TraceTaskDecorator();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                core, max, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new NamedThreadFactory(namePrefix),
                rejection) {
            @Override
            public void execute(Runnable command) {
                // 提交任务时捕获上下文、执行时恢复（见 TraceTaskDecorator）
                super.execute(decorator.decorate(command));
            }
        };
        pools.add(executor);
        return executor;
    }

    /**
     * 应用关闭时优雅停机：先停止接收新任务，再等存量任务跑完。
     */
    @PreDestroy
    public void shutdown() {
        log.info("[ThreadPool] 开始关闭 {} 个线程池", pools.size());
        for (ExecutorService pool : pools) {
            pool.shutdown();
        }
        for (ExecutorService pool : pools) {
            try {
                if (!pool.awaitTermination(20, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }
        log.info("[ThreadPool] 全部线程池已关闭");
    }

    /**
     * 命名线程工厂：线程名可读，便于线上排查。
     */
    public static class NamedThreadFactory implements java.util.concurrent.ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(1);

        public NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, prefix + "-" + counter.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
