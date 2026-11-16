# PrismRAG · 一个从 0 手写的 Java 检索增强问答（RAG）工程

> 参考 [nageoffer/ragent](https://github.com/nageoffer/ragent) 的分层思想，用 **JDK 21 + Spring Boot 3.5** 从零实现。
> 目标不是"跑通一个 Demo"，而是让一个**初级 Java 工程师能通过 `git log` 读懂整个系统的逻辑与写法**。

```
┌─────────────────────────────────────────────────────────────────┐
│  🔮 PrismRAG   双路召回 + RRF 融合 + SSE 流式 + 可插拔模型       │
│  无需任何 API Key，clone 下来就能跑通全链路                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 一、30 秒跑起来

```bash
# 1. 构建（需要 JDK 21 + Maven 3.9）
mvn -T 1C clean install -DskipTests

# 2. 启动
java -jar prism-bootstrap/target/prism-rag.jar

# 3. 打开浏览器
#    http://localhost:8080
```

启动后会自动建一个「员工制度知识库」并灌入 3 篇演示文档，**什么都不用配置**。

### 接上真实大模型（可选）

```bash
# Windows PowerShell
$env:OPENAI_API_KEY="sk-xxxxxxxx"
$env:AI_BASE_URL="https://api.deepseek.com"     # 任何 OpenAI 兼容服务
$env:AI_CHAT_MODEL="deepseek-chat"
java -jar prism-bootstrap/target/prism-rag.jar
```

**填了 Key 自动切真实模型，没填用内置离线 Mock，业务代码一行不改。**

---

## 二、这套系统实现了什么

| 能力 | 实现位置 | 关键设计 |
|---|---|---|
| 入库流水线 | `prism-rag/.../pipeline` | 解析→分块→向量化→持久化，四节点模板方法 + **状态机** + **线程池隔离** |
| 双路召回 | `prism-rag/.../retrieval` | 向量余弦 + BM25 倒排索引，**RRF** 倒数排名融合 |
| 流式问答 | `prism-rag/.../chat` | **SSE** 逐字输出 + **会话记忆**（近窗+摘要）+ **引用溯源** + **断开取消** |
| 可插拔模型 | `prism-infra-ai` | 两个 SPI 接口 + 自动切换（离线 Mock ⇄ OpenAI 兼容真实模型） |
| 智能体 | `prism-agent` | **意图树**路由 + **ReAct** 推理-行动-观察循环（真实可跑） |
| 前端 | `prism-web` | 苹果风**玻璃态**单页应用，零构建链，原生 JS + EventSource |

---

## 三、模块分层（自下而上，只依赖下层）

```
prism-common     通用层    统一响应 R、错误码、全局异常、中文 bigram 分词器
     ▲
prism-framework  框架层    5 个隔离线程池、Trace 上下文透传、SSE 连接注册表
     ▲
prism-infra-ai   模型层    ChatClient / EmbeddingClient 两个 SPI + Mock/真实实现
     ▲
prism-rag        业务层    知识库、Pipeline、双路召回、会话、SSE 问答
     ▲
prism-agent      智能体层  意图树、ReAct 引擎、工具注册表
     ▲
prism-bootstrap  装配层    启动类、配置、REST/SSE 接口
     ▲
prism-web        展现层    玻璃态前端（静态资源）
```

**分层的意义**：换模型 / 换向量库 / 换检索策略，需求只落在一个模块里，问答主流程一行都不用改。

---

## 四、Git 树导读（★ 最重要的部分）

`git log` 本身就是一份教程。**每个提交只做一件事，提交信息里写了"为什么这么做"。**

```bash
git log --oneline          # 看全景
git show <commit>          # 看某一步的完整改动与讲解
```

| # | 提交 | 这一步在解决什么问题 |
|---|---|---|
| 01 | `chore: 初始化多模块骨架` | 分层为什么要这么切？七个模块的依赖方向 |
| 02 | `feat(common): 统一响应 + 异常 + 中文分词器` | 前端为什么要统一信封？中文怎么切词？ |
| 03 | `feat(framework): 线程池隔离 + 上下文透传 + SSE 注册表` | **为什么要开 5 个线程池**？ThreadLocal 在线程池里为什么会串台？ |
| 04 | `feat(infra-ai): 可插拔模型客户端` | **没有 Key 怎么跑通全链路**？怎么做到换模型不改业务代码？ |
| 05 | `feat(rag): 领域模型与 JPA 持久化` | 入库是异步的，为什么状态必须落库？ |
| 06 | `feat(pipeline): 入库流水线 + 状态机` | **为什么状态机比 status 字段强**？四节点怎么拆分？ |
| 07 | `feat(retrieval): 双路召回 + RRF 融合` | **为什么向量和 BM25 都要有**？为什么不能加权求和？ |
| 08 | `feat(chat): SSE + 会话记忆 + 引用 + 断开取消` | 记忆怎么控成本？**用户关页面后后端怎么止损**？ |
| 09 | `feat(agent): 意图树 + ReAct 引擎` | 什么时候该用固定 Workflow，什么时候该用 Agent？ |
| 10 | `feat(bootstrap): 装配 + 接口 + 演示数据` | 多模块 Spring Boot 的三个必踩坑 |
| 11 | `feat(web): 玻璃态前端` | 玻璃态的 CSS 到底该怎么写？SSE 怎么消费？ |
| 12 | `docs: 重型组件接入指南` | 想上 RocketMQ / Redis / Milvus，从哪下手？ |

---

## 五、一次提问的完整链路

```
浏览器 EventSource
   │  GET /api/chat/stream?question=...
   ▼
ChatController → ChatService.streamAsk()
   │  ① 注册 SseSession（拿到"取消开关"）
   │  ② 提交到 chatExecutor（专用线程池，HTTP 线程立即返回）
   ▼
后台线程
   ├─ ③ 建/取会话 → ConversationMemoryService.loadMemory()
   │     └─ 摘要 + 最近 N 轮原文
   ├─ ④ RetrievalService.retrieve()
   │     ├─ 向量路：EmbeddingClient.embed(question) → 内存向量库余弦检索
   │     ├─ BM25 路：倒排索引打分
   │     └─ RRF 融合（只看排名，绕开量纲不可比问题）
   ├─ ⑤ 组装 ContextItem + Citation
   ├─ ⑥ ChatClient.stream() → 逐块推 delta
   │     └─ 每次推送前检查 session.isCancelled()
   ├─ ⑦ 落库（用户消息 + 助手消息 + 引用 JSON）
   └─ ⑧ 推 done，关闭连接

客户端断开 → SseEmitter.onError/onCompletion → session.cancel()
             → Future.cancel(true) 中断模型流线程 → 停止输出、释放线程
```

---

## 六、入库流水线的状态机

```
PENDING ──→ PARSING ──→ CHUNKING ──→ EMBEDDING ──→ PERSISTING ──→ COMPLETED
   │           │            │             │              │
   └───────────┴────────────┴─────────────┴──────────────┴──→ FAILED
   └───────────┴────────────┴─────────────┴──────────────┴──→ CANCELLED

FAILED / CANCELLED ──(重试)──→ PENDING
```

- 合法流转写在 `IngestionState.ALLOWED` 里，**非法流转直接抛异常**（开发期就暴露脏状态）
- 每个节点的耗时与结果写进 `IngestionTask.nodeLog`，前端「流水线」页直接可视化
- 线程池隔离：`ingest`（流水线主控）/ `embedding`（调模型）/ `retrieval`（召回）/ `chat`（流式问答）/ `misc`

---

## 七、核心设计决策速查

| 决策 | 理由 |
|---|---|
| 不用 Spring AI / LangChain4j | 自己写 100 行把请求体、流式解析、错误处理摊开，看清全貌；且不被框架版本绑架 |
| 中文用 bigram 分词 | 单字歧义大、整词切不出，bigram 落在"召回够准、切分够细"的平衡点 |
| 本地 Hashing Trick 做离线向量 | 无模型文件、无网络、无 GPU，也能真实跑通"入库→索引→余弦召回"全链路 |
| RRF 而不是加权求和 | 余弦(0~1) 与 BM25(无上界) 量纲不可比；RRF 只看排名，无需调参 |
| 模型只依赖两个 SPI 接口 | 换供应商 = 新增一个实现类 + 改一行配置 |
| SSE 用 GET | 浏览器 `EventSource` 只支持 GET；需要 POST 得用 fetch + ReadableStream |
| 静态前端不用构建链 | 少一层 node_modules/webpack 噪音，看后端的人也能直接改前端 |

---

## 八、目录结构

```
prism-rag-parent/
├── prism-common/      统一响应、异常、分词、向量工具
├── prism-framework/   线程池隔离、Trace 透传、SSE 注册表、全局异常
├── prism-infra-ai/    ChatClient / EmbeddingClient SPI + Mock + OpenAI 兼容
├── prism-rag/         实体、仓储、Pipeline、检索索引、会话、SSE 问答
│   ├── domain/        JPA 实体
│   ├── repository/    Spring Data 仓储
│   ├── pipeline/      状态机、节点、解析器、分块器
│   ├── retrieval/     向量库、BM25、RRF、检索编排
│   ├── chat/          会话记忆、Prompt、引用、SSE 问答
│   └── service/       知识库管理、入库提交
├── prism-agent/       意图树、ReAct 引擎、工具
├── prism-web/         玻璃态前端（index.html + glass.css + app.js）
├── prism-bootstrap/   启动类、配置、Controller、演示数据
└── docs/              中文教学文档（重���组件接入指南）
```

---

## 九、中文教学文档

| 文档 | 内容 |
|---|---|
| [`docs/01-架构总览.md`](docs/01-架构总览.md) | 分层、依赖方向、一次请求的调用链 |
| [`docs/02-入库流水线与状态机.md`](docs/02-入库流水线与状态机.md) | 四节点、状态机、幂等重跑、节点日志 |
| [`docs/03-双路召回与RRF融合.md`](docs/03-双路召回与RRF融合.md) | BM25 公式推导、倒排索引、RRF 推导 |
| [`docs/04-SSE流式问答与会话记忆.md`](docs/04-SSE流式问答与会话记忆.md) | 事件协议、记忆策略、断开取消的完整链路 |
| [`docs/05-可插拔模型与Mock降级.md`](docs/05-可插拔模型与Mock降级.md) | SPI 设计、自动切换、熔断降级路线 |
| [`docs/06-接入RocketMQ.md`](docs/06-接入RocketMQ.md) | **完整可编译的**生产/消费端代码、事务消息、幂等 |
| [`docs/07-接入Redis.md`](docs/07-接入Redis.md) | 缓存、会话存储、分布式锁、排队限流 |
| [`docs/08-意图树.md`](docs/08-意图树.md) | 树形路由、模型分类实现、动态配置 |
| [`docs/09-ReAct-Agent.md`](docs/09-ReAct-Agent.md) | 工具抽象、循环引擎、写操作人工确认 |
| [`docs/10-前端玻璃态设计.md`](docs/10-前端玻璃态设计.md) | 玻璃态 CSS 配方、SSE 消费、视图组织 |
| [`docs/11-踩坑清单.md`](docs/11-踩坑清单.md) | 本项目真实踩过的 8 个坑（含 CGLIB 代理 NPE） |

---

## 十、跑起来的验证路径

```bash
# 1) 入库流水线（演示数据会自动跑）
curl http://localhost:8080/api/tasks | jq

# 2) SSE 流式问答
curl -N "http://localhost:8080/api/chat/stream?question=出差住宿标准&kbId=1"

# 3) 同步问答
curl -X POST http://localhost:8080/api/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"question":"年假有多少天","kbId":1}'

# 4) ReAct 执行（会看到 思考→行动→观察 的步骤）
curl -X POST http://localhost:8080/api/agent/react \
  -H "Content-Type: application/json" \
  -d '{"question":"查一下差旅住宿标准","kbId":1}'

# 5) 系统状态
curl http://localhost:8080/api/system/info | jq
```

---

## 十一、下一步可以往哪走

本项目是**骨架完整、留有扩展点**的工程。常见演进路线（每一项都有对应文档）：

| 方向 | 接入点 | 文档 |
|---|---|---|
| 任务可靠性 / 横向扩容 | `IngestionService.submit()` 改发 MQ | [06-RocketMQ](docs/06-接入RocketMQ.md) |
| 会话共享 / 缓存 | `ConversationMemoryService` | [07-Redis](docs/07-接入Redis.md) |
| 向量库升级 | 实现 `VectorStore` 接口 | [03-双路召回](docs/03-双路召回与RRF融合.md) |
| 模型多候选 + 熔断 | `AiAutoConfiguration` | [05-可插拔模型](docs/05-可插拔模型与Mock降级.md) |
| 意图动态配置 | `IntentTree` 落库 | [08-意图树](docs/08-意图树.md) |
| 写操作人工确认 | `ReActEngine` 工具注册 | [09-ReAct](docs/09-ReAct-Agent.md) |

---

## 十二、技术栈

| 分类 | 选型 | 说明 |
|---|---|---|
| 语言 | Java 21 | record / switch 表达式 / 文本块 |
| 框架 | Spring Boot 3.5.7 | web + data-jpa + validation |
| 存储 | H2（文件模式）→ 可换 MySQL | 开箱即用，零配置 |
| AI 接入 | **无第三方 SDK**，JDK HttpClient 手写 | 不被框架版本绑架 |
| 前端 | 原生 HTML/CSS/JS | 零构建链 |
| 构建 | Maven 多模块 | 7 个模块，分层清晰 |

> 本项目只为教学与演示而生，**未接入鉴权、多租户、审计与限流**，生产化请参考 `docs/` 中的演进路线。

---

## 十三、许可证

[MIT License](./LICENSE) © 2026 Aquaner

代码、文档、注释均为中文，欢迎：
- **Fork** 做自己的版本（换模型、换向量库、加业务链路）
- **提 Issue** 指出看不懂或讲错的地方——这比提 PR 更能帮到作者
- **提 PR** 补充新的扩展点（比如新的检索通道、新的解析器、新的工具）

---

**返回**：[Git 树导读](#四git-树导读-最重要的部分) · [快速开始](#一30-秒跑起来) · [踩坑清单](./docs/11-踩坑清单.md)

