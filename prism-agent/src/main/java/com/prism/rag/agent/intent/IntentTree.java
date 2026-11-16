package com.prism.rag.agent.intent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 意图树。
 *
 * <p>本实现内置了一棵通用的企业知识库示例树。
 * <b>生产环境应该把它做成可配置的</b>（存数据库，管理端可编辑），
 * 但结构完全一样——这正是把意图抽象成树节点的好处。</p>
 */
@Component
public class IntentTree {

    private static final Logger log = LoggerFactory.getLogger(IntentTree.class);

    private final IntentNode root;

    public IntentTree() {
        this.root = buildDefaultTree();
        log.info("[Intent] 意图树加载完成，节点数 {}", allNodes().size());
    }

    /**
     * 构建默认意图树。
     */
    private IntentNode buildDefaultTree() {
        IntentNode root = new IntentNode("ROOT", "根节点", List.of());

        // —— 知识问答 ——
        IntentNode knowledge = new IntentNode("KNOWLEDGE", "针对知识库内容的问答", List.of("知识", "文档", "资料"));
        knowledge.addChild(new IntentNode("POLICY", "制度与流程咨询（报销、假期、审批标准等）",
                List.of("报销", "差旅", "假期", "制度", "规定", "标准", "流程", "政策")));
        knowledge.addChild(new IntentNode("TECH", "技术支持与故障排查",
                List.of("报错", "部署", "接口", "异常", "故障", "崩溃", "排查")));
        root.addChild(knowledge);

        // —— 业务办理（走工具，不走知识库）——
        root.addChild(new IntentNode("BUSINESS", "查询业务数据或办理业务", List.of("查询", "下单", "审批", "提交", "订单"))
                .action("TOOL"));

        // —— 闲聊 ——
        root.addChild(new IntentNode("CHITCHAT", "打招呼与闲聊", List.of("你好", "您好", "谢谢", "在吗", "hello"))
                .action("CHAT")
                .response("你好，我是知识库助手，有什么可以帮你的？可以直接问我制度、流程、技术相关的问题。"));

        return root;
    }

    public IntentNode root() {
        return root;
    }

    /**
     * 所有节点（深度优先）。
     */
    public List<IntentNode> allNodes() {
        return root.flatten();
    }

    /**
     * 节点深度（根为 0）。
     */
    public int depthOf(IntentNode target) {
        return depthOf(root, target, 0);
    }

    private int depthOf(IntentNode current, IntentNode target, int depth) {
        if (current == target) {
            return depth;
        }
        for (IntentNode child : current.getChildren()) {
            int found = depthOf(child, target, depth + 1);
            if (found >= 0) {
                return found;
            }
        }
        return -1;
    }

    /**
     * 导出成便于前端展示的结构。
     */
    public List<Object> toTreeView() {
        List<Object> views = new ArrayList<>();
        for (IntentNode child : root.getChildren()) {
            views.add(nodeView(child));
        }
        return views;
    }

    private Object nodeView(IntentNode node) {
        java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("name", node.getName());
        view.put("description", node.getDescription());
        view.put("action", node.getAction());
        view.put("keywords", node.getKeywords());
        view.put("kbIds", node.getKbIds());
        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            List<Object> children = new ArrayList<>();
            for (IntentNode child : node.getChildren()) {
                children.add(nodeView(child));
            }
            view.put("children", children);
        }
        return view;
    }
}
