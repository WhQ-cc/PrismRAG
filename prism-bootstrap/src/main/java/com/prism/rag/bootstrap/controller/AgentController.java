package com.prism.rag.bootstrap.controller;

import com.prism.rag.agent.intent.IntentResolver;
import com.prism.rag.agent.intent.IntentResult;
import com.prism.rag.agent.intent.IntentTree;
import com.prism.rag.agent.react.ReActEngine;
import com.prism.rag.agent.react.ReActRun;
import com.prism.rag.common.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 智能体接口：意图识别与 ReAct 执行。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final ReActEngine reActEngine;
    private final IntentResolver intentResolver;
    private final IntentTree intentTree;

    public AgentController(ReActEngine reActEngine, IntentResolver intentResolver, IntentTree intentTree) {
        this.reActEngine = reActEngine;
        this.intentResolver = intentResolver;
        this.intentTree = intentTree;
    }

    /**
     * 执行一次 ReAct 任务，返回完整步骤（前端可展示"智能体思考过程"）。
     */
    @PostMapping("/react")
    public R<ReActRun> react(@RequestBody Map<String, Object> body) {
        String question = String.valueOf(body.getOrDefault("question", ""));
        Long kbId = body.get("kbId") == null ? null : Long.valueOf(String.valueOf(body.get("kbId")));
        return R.ok(reActEngine.run(question, kbId));
    }

    /**
     * 意图识别。
     */
    @PostMapping("/intent")
    public R<IntentResult> intent(@RequestBody Map<String, Object> body) {
        String question = String.valueOf(body.getOrDefault("question", ""));
        Long kbId = body.get("kbId") == null ? null : Long.valueOf(String.valueOf(body.get("kbId")));
        return R.ok(intentResolver.resolve(question, kbId));
    }

    /**
     * 意图树结构（前端可视化）。
     */
    @GetMapping("/intent-tree")
    public R<List<Object>> intentTree() {
        return R.ok(intentTree.toTreeView());
    }
}
