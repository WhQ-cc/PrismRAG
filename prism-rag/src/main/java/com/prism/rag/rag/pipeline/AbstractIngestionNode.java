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

    /**
     * <b>必须是 static 的！</b>
     *
     * <p>这是一个真实踩过的坑：如果写成
     * {@code protected final Logger log = LoggerFactory.getLogger(getClass());}
     * （实例字段 + 初始化器），在 {@code PersistNode} 上加了 {@code @Transactional} 之后会 NPE：</p>
     *
     * <pre>
     *   Cannot invoke "org.slf4j.Logger.error(String, Object[])" because "this.log" is null
     * </pre>
     *
     * <p><b>原因：</b>{@code @Transactional} 用 CGLIB 生成代理，代理对象由 Objenesis 创建，
     * <b>不会执行构造方法，也就不会执行字段初始化器</b>。
     * 而 {@code execute()} 声明为 {@code final}——CGLIB <b>不能重写 final 方法</b>，
     * 于是它不会被转发到真实对象，而是直接在"未初始化的代理对象"上执行，
     * 访问实例字段 {@code log} 自然就是 null。</p>
     *
     * <p><b>两种修法：</b></p>
     * <ol>
     *   <li>日志字段用 {@code static final}（静态字段由类加载器初始化，与实例无关）——本项目采用；</li>
     *   <li>或者去掉 {@code final}，让方法能被代理转发。</li>
     * </ol>
     */
    protected static final Logger log = LoggerFactory.getLogger(AbstractIngestionNode.class);

    /**
     * 执行节点。
     *
     * <p><b>为什么必须保留 {@code final}？</b></p>
     * <p>启动日志里会看到这条 WARN，它是<b>预期之内</b>的，不是 bug：</p>
     * <pre>
     * Unable to proxy interface-implementing method [public final void ...execute(...)]
     * because it is marked as final
     * </pre>
     * <p>反过来想就清楚了：{@code @Transactional} 标在子类的 {@code doExecute()} 上，
     * 它<b>必须</b>经过代理才能生效。如果 {@code execute()} 不是 final，
     * CGLIB 就会代理它并把调用转发给真实对象，那么内部的 {@code this.doExecute()}
     * 就是在真实对象上执行的 —— <b>绕过了代理，事务失效</b>。</p>
     * <p>而现在 {@code execute()} 是 final，CGLIB 无法代理它，
     * 于是它直接在"代理对象"上执行，里面的 {@code this.doExecute()}
     * 恰好是<b>非 final</b> 的 → 被拦截 → 转发到真实对象 → <b>事务正常生效</b>。</p>
     * <p>所以这个 final 是有意为之，配套的代价是：代理对象没有执行字段初始化器，
     * 所以日志字段必须是 {@code static final}（见上方注释）。</p>
     */
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
