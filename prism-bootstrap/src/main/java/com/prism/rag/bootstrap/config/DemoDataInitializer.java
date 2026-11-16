package com.prism.rag.bootstrap.config;

import com.prism.rag.rag.domain.KnowledgeBase;
import com.prism.rag.rag.service.IngestionService;
import com.prism.rag.rag.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 演示数据初始化。
 *
 * <p>第一次启动时自动创建一个示例知识库并灌入几篇制度文档，
 * 这样用户 clone 下来<b>什么都不用做</b>，打开页面就能提问、就能看到效果。</p>
 *
 * <p>用 H2 文件库时数据会保留，所以只在"知识库为空"时才初始化，避免重复灌数据。</p>
 */
@Component
public class DemoDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final IngestionService ingestionService;

    public DemoDataInitializer(KnowledgeBaseService knowledgeBaseService,
                               IngestionService ingestionService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.ingestionService = ingestionService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        List<KnowledgeBase> existing = knowledgeBaseService.list();
        if (!existing.isEmpty()) {
            log.info("[Demo] 已存在 {} 个知识库，跳过演示数据初始化", existing.size());
            return;
        }

        KnowledgeBase kb = knowledgeBaseService.create("员工制度知识库", "差旅报销、请假审批、技术规范等示例制度文档");
        log.info("[Demo] 创建演示知识库 id={} 《{}》", kb.getId(), kb.getName());

        // ① 差旅报销制度
        ingestionService.submit(kb.getId(), "差旅报销制度", "差旅报销制度.md", "markdown", """
                # 差旅报销制度

                ## 住宿标准
                差旅住宿费实行限额报销制度。一线城市（北京、上海、广州、深圳）住宿标准为每晚 600 元，
                二线城市为每晚 450 元，其他城市为每晚 350 元。
                超出标准部分由员工自行承担，确有特殊情况的需事前取得部门总监的书面批准。

                ## 交通标准
                市内交通费按实际发生额报销，凭票据结算，单次超过 200 元的需说明事由。
                城际交通以高铁二等座为默认标准，飞行仅在总监出差或紧急情况下可乘坐经济舱。

                ## 报销时限
                员工应在出差结束后的 30 个自然日内提交报销申请，超过 60 天未提交的需要在系统中补充说明，
                财务部有权对超期未报的费用进行暂缓处理。

                ## 审批流程
                报销金额在 5000 元以下的，由部门总监审批；5000 元以上的，需要额外增加财务负责人审批。
                跨部门出差还需要人力资源部会签。

                ## 发票要求
                所有报销必须提供增值税发票，发票抬头必须与公司全称一致。
                餐饮发票需注明就餐人数与事由，注明不清的将被退回。
                """);

        // ② 请假制度
        ingestionService.submit(kb.getId(), "请假与考勤制度", "请假与考勤制度.md", "markdown", """
                # 请假与考勤制度

                ## 年假
                员工入职满 1 年享有 5 天年假，满 3 年 10 天，满 5 年 15 天。
                年假需提前 3 个工作日在系统提交申请，经部门负责人审批后生效。
                当年未使用的年假最多可结转 5 天到次年第一季度使用。

                ## 病假
                连续病假 3 个工作日以内的，凭本人申请由部门负责人审批；
                超过 3 个工作日的必须提供二级及以上医院出具的病假条和挂号凭证，
                病假期间工资按当地最低工资标准的 80% 发放。

                ## 事假
                事假为无薪假，每次申请需说明事由与预计时长，单次超过 5 个工作日的需人力资源部审批。

                ## 迟到与早退
                迟到或早退 30 分钟以内记迟到一次，当月累计 3 次以上扣发当月全勤奖 200 元。
                连续 3 个月累计迟到 10 次以上的，公司可以解除劳动合同。

                ## 远程办公
                每周五为默认远程办公日，如需到岗办公需提前在工作群报备。
                """);

        // ③ 技术规范
        ingestionService.submit(kb.getId(), "服务接口技术规范", "服务接口技术规范.md", "markdown", """
                # 服务接口技术规范

                ## 接口命名
                RESTful 接口路径统一使用小写字母与中划线，例如 /api/knowledge-bases。
                资源名使用复数名词，单个资源使用其 ID 定位，禁止在路径中包含动词。

                ## 响应格式
                所有接口统一返回统一信封，包含 code、message、data、traceId 四个字段。
                code 为 0 表示成功，其余为业务错误码。data 在失败时为空。

                ## 错误码规范
                错误码前两位对齐 HTTP 语义：40 开头表示请求方问题，50 开头表示服务端问题，
                后三位为业务细分。禁止复用已经发布过的错误码。

                ## 幂等性
                写操作接口必须支持幂等，调用方需传递幂等键，服务端以幂等键去重。
                幂等键的有效期默认为 24 小时。

                ## 限流
                对外接口需要做限流保护，单用户每秒不超过 20 次请求。
                触发限流时返回 429 状态码与 Retry-After 响应头。

                ## 日志规范
                日志必须包含 traceId，异常堆栈只记录在服务端日志中，
                禁止把堆栈或数据库语句返回给前端。
                """);

        log.info("[Demo] 演示数据初始化完成，3 篇文档已进入入库流水线");
    }
}
