package com.prism.rag.rag.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 节点模板方法基类。
 *
 * <p>把"所有节点都要做的事"固化在这里：</p>
 * <ol>
 *   <li>状态流转（并校验合法性）；</li>
 *   <li>计时；</li>
 *   <li>成功/失败日志记录；</li>
 *   <li>异常统一标记为 FAILED 并向上抛出。</li>
 * </ol>
 * <p>子类只需要关心自己的业务逻辑（{@link #doExecute}），
 * 这就是<b>模板方法模式</b>——流程固定，步骤可扩展。</p>
 */
public abstract class AbstractIngestionNode implements IngestionNode {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public final void execute(IngestionContext context) {
        // ① 流转状态（非法流转会在这里抛异常）
        context.transitionTo(targetState());
        context.setCurrentNode(name());

        long start = System.nanoTime();
        try {
            doExecute(context);
            long cost = (System.nanoTime() - start) / 1_000_000;
            context.log(name(), "SUCCESS", cost, describeResult(context));
            log.info("[Pipeline] 节点 {} 执行成功，耗时 {}ms", name(), cost);
        } catch (Exception e) {
            long cost = (System.nanoTime() - start) / 1_000_000;
            context.log(name(), "FAILED", cost, e.getMessage());
            context.fail(e);
            log.error("[Pipeline] 节点 {} 执行失败，耗时 {}ms: {}", name(), cost, e.getMessage());
            throw e;
        }
    }

    /**
     * 子类实现的业务逻辑。
     */
    protected abstract void doExecute(IngestionContext context);

    /**
     * 节点结果的简短描述（写进任务日志）。
     */
    protected String describeResult(IngestionContext context) {
        return null;
    }
}
