package com.prism.rag.agent.intent;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图节点（意图树的一个节点）。
 *
 * <h3>意图树是什么？</h3>
 * <p>用户的问题不是千篇一律的："报销标准是多少"要查 HR 知识库，
 * "帮我查下订单 SO123 到哪了"要调业务系统，"你好"只是闲聊。
 * 意图树就是把这些路由规则组织成<b>树</b>：</p>
 * <pre>
 *   根节点
 *    ├── 知识问答
 *    │    ├── 制度咨询  → kbIds=[1]  关键词: 报销/假期/制度/规定
 *    │    └── 技术支持  → kbIds=[2]  关键词: 报错/部署/接口
 *    ├── 业务办理    → action=TOOL   关键词: 查询/下单/审批
 *    └── 闲聊        → action=CHAT   关键词: 你好/谢谢
 * </pre>
 *
 * <p><b>为什么要用树而不是一个 if-else？</b></p>
 * <ul>
 *   <li>树天然表达"先粗分类、再细分类"的层级关系，路由更准；</li>
 *   <li>意图配置可以外置（数据库/配置文件），改路由不用改代码、不用发版；</li>
 *   <li>节点可以挂不同的执行策略（走知识库 / 走工具 / 直接闲聊）。</li>
 * </ul>
 *
 * <p>本项目把意图树放在 agent 模块，并且<b>默认不接管问答主流程</b>——
 * 问答仍然走 {@code ChatService} 的固定链路（更快更可控）。
 * 意图树由 Agent 链路使用，这个取舍和 ragent 的 v1/v2 并存思路一致。</p>
 */
public class IntentNode {

    /** 节点名 */
    private String name;

    /** 节点描述（会告诉大模型这个意图是干什么的） */
    private String description;

    /** 命中该意图的关键词 */
    private List<String> keywords = new ArrayList<>();

    /** 子节点 */
    private List<IntentNode> children = new ArrayList<>();

    /** 该意图路由到的知识库 */
    private List<Long> kbIds = new ArrayList<>();

    /**
     * 命中后的动作：
     * KNOWLEDGE=走知识库问答；TOOL=调工具；CHAT=普通对话；DIRECT=直接返回节点配置的话术
     */
    private String action = "KNOWLEDGE";

    /** DIRECT 动作下直接返回的话术 */
    private String response;

    public IntentNode() {
    }

    public IntentNode(String name, String description, List<String> keywords) {
        this.name = name;
        this.description = description;
        this.keywords = keywords;
    }

    public IntentNode addChild(IntentNode child) {
        this.children.add(child);
        return this;
    }

    public IntentNode kbIds(List<Long> kbIds) {
        this.kbIds = kbIds;
        return this;
    }

    public IntentNode action(String action) {
        this.action = action;
        return this;
    }

    public IntentNode response(String response) {
        this.response = response;
        return this;
    }

    /**
     * 深度优先遍历所有节点。
     */
    public List<IntentNode> flatten() {
        List<IntentNode> all = new ArrayList<>();
        all.add(this);
        for (IntentNode child : children) {
            all.addAll(child.flatten());
        }
        return all;
    }

    // ---------------- getter / setter ----------------

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }

    public List<IntentNode> getChildren() {
        return children;
    }

    public void setChildren(List<IntentNode> children) {
        this.children = children;
    }

    public List<Long> getKbIds() {
        return kbIds;
    }

    public void setKbIds(List<Long> kbIds) {
        this.kbIds = kbIds;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getResponse() {
        return response;
    }

    public void setResponse(String response) {
        this.response = response;
    }
}
