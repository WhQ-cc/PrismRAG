/* ==========================================================================
   PrismRAG 前端逻辑（原生 JS，无构建链）
   --------------------------------------------------------------------------
   【为什么不用 Vue/React？】
   这个前端只做三件事：发 SSE、渲染流式文本、切换几个页面。
   引入构建链会带来 node_modules、webpack/vite 配置、打包产物这些"与业务无关"的复杂度，
   对理解后端主链路没有帮助。所以这里用原生 JS + EventSource，
   想看后端逻辑的人打开这个文件就能看懂数据是怎么流动的。

   【SSE 事件协议（与后端 ChatService 对应）】
     meta      会话元信息（会话ID、模型）
     thinking  当前处理阶段
     retrieval 两路召回与融合结果
     citation  引用列表
     delta     回答增量文本
     done      结束
     error     出错
   ========================================================================== */

// ---------------------------------------------------------------- 全局状态
const state = {
    kbList: [],
    selectedKb: null,
    conversationId: null,
    currentSource: null,   // 当前 EventSource
    currentSessionId: null,
    streaming: false
};

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[c]));

const VIEW_META = {
    chat: ['智能问答', '双路召回 + RRF 融合 + SSE 流式输出'],
    kb: ['知识库管理', '文档入库、状态跟踪'],
    pipeline: ['入库流水线', '解析 → 分块 → 向量化 → 持久化，状态机管控'],
    agent: ['Agent / ReAct', '意图树路由与多步工具调用'],
    system: ['系统状态', '模型、索引与连接数']
};

// ---------------------------------------------------------------- 通用工具
function toast(message, isError) {
    const el = $('toast');
    el.textContent = message;
    el.classList.toggle('error', !!isError);
    el.classList.add('show');
    clearTimeout(el._timer);
    el._timer = setTimeout(() => el.classList.remove('show'), 2600);
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        headers: {'Content-Type': 'application/json'},
        ...options
    });
    const body = await response.json();
    // 统一响应体：code === 0 才算成功
    if (body.code !== 0) {
        throw new Error(body.message || '请求失败');
    }
    return body.data;
}

function timeText(value) {
    if (!value) return '-';
    return String(value).replace('T', ' ').slice(0, 19);
}

// ---------------------------------------------------------------- 视图切换
/**
 * 切换视图。
 *
 * <p>用 URL hash 记录当前视图，好处有三个：</p>
 * <ul>
 *   <li>刷新页面不会丢当前视图；</li>
 *   <li>可以把某个页面直接分享给别人（比如"知识库管理"页）；</li>
 *   <li>浏览器前进/后退键天然可用。</li>
 * </ul>
 */
function switchView(view, updateHash = true) {
    if (!VIEW_META[view]) {
        view = 'chat';
    }
    document.querySelectorAll('.nav-item').forEach((n) => {
        n.classList.toggle('active', n.dataset.view === view);
    });
    document.querySelectorAll('.view').forEach((v) => v.classList.remove('active'));
    $('view-' + view).classList.add('active');
    $('view-title').textContent = VIEW_META[view][0];
    $('view-desc').textContent = VIEW_META[view][1];
    if (updateHash && location.hash !== '#' + view) {
        location.hash = view;
    }
    if (view === 'kb') loadKbPage();
    if (view === 'pipeline') loadTasks();
    if (view === 'agent') loadIntentTree();
    if (view === 'system') loadSystem();
}

document.querySelectorAll('.nav-item').forEach((item) => {
    item.addEventListener('click', () => switchView(item.dataset.view));
});

window.addEventListener('hashchange', () => {
    switchView(location.hash.replace('#', ''), false);
});

// ---------------------------------------------------------------- 主题切换
$('theme-toggle').addEventListener('click', () => {
    const root = document.documentElement;
    const next = root.dataset.theme === 'dark' ? 'light' : 'dark';
    root.dataset.theme = next;
    $('theme-toggle').textContent = next === 'dark' ? '🌙' : '☀️';
    localStorage.setItem('prism-theme', next);
});
(function initTheme() {
    const saved = localStorage.getItem('prism-theme');
    if (saved) {
        document.documentElement.dataset.theme = saved;
        $('theme-toggle').textContent = saved === 'dark' ? '🌙' : '☀️';
    }
})();

// ---------------------------------------------------------------- 系统状态
async function loadSystem() {
    try {
        const info = await api('/api/system/info');
        renderModelCards(info.model);
        renderIndexCards(info.index);
    } catch (e) {
        toast(e.message, true);
    }
}

function renderModelCards(model) {
    const items = [
        {label: '对话模型', value: model.chatModel},
        {label: '向量模型', value: model.embeddingModel},
        {label: '向量维度', value: model.embeddingDimension},
        {label: '服务地址', value: model.endpoint}
    ];
    $('model-cards').innerHTML = items.map((i) => `
        <div class="card">
            <div class="card-title">${esc(i.label)}</div>
            <div class="card-desc mono">${esc(i.value)}</div>
        </div>`).join('');
}

function renderIndexCards(index) {
    const items = [
        {label: '向量条目', value: index.vectorCount},
        {label: '向量维度', value: index.vectorDimension},
        {label: 'BM25 分块数', value: index.bm25ChunkCount},
        {label: 'BM25 词项数', value: index.bm25TermCount}
    ];
    $('index-cards').innerHTML = items.map((i) => `
        <div class="card">
            <div class="metric">${esc(i.value)}</div>
            <div class="card-desc">${esc(i.label)}</div>
        </div>`).join('');
}

async function refreshStatus() {
    try {
        const info = await api('/api/system/info');
        const model = info.model;
        const badge = $('badge-model');
        badge.className = 'badge ' + (model.real ? 'real' : 'mock');
        badge.innerHTML = `<span class="dot"></span><span>${model.real ? '真实模型' : '离线 Mock'} · ${esc(model.chatModel)}</span>`;
        $('side-model').textContent = model.real ? '真实模型' : 'Mock';
        $('side-vectors').textContent = info.index.vectorCount;
        $('side-terms').textContent = info.index.bm25TermCount;
        $('side-conn').textContent = info.sseActiveConnections;
        $('badge-index').textContent = `索引 ${info.index.vectorCount} 向量 / ${info.index.bm25TermCount} 词项`;
    } catch (e) {
        /* 静默失败：状态栏不是关键路径 */
    }
}
setInterval(refreshStatus, 5000);

// ---------------------------------------------------------------- 知识库
async function loadKbList() {
    state.kbList = await api('/api/kb');
    const options = state.kbList.map((kb) =>
        `<option value="${kb.id}">${esc(kb.name)}</option>`).join('');
    $('kb-select').innerHTML = options;
    $('new-doc-kb').innerHTML = options;
    if (!state.selectedKb && state.kbList.length) {
        state.selectedKb = state.kbList[0].id;
        $('kb-select').value = state.selectedKb;
    }
}

async function loadKbPage() {
    try {
        await loadKbList();
        $('kb-cards').innerHTML = state.kbList.map((kb) => `
            <div class="card">
                <div class="card-title">${esc(kb.name)}</div>
                <div class="card-desc" style="margin-bottom:10px">${esc(kb.description || '—')}</div>
                <div class="status-row"><span>文档</span><span>${kb.documentCount}</span></div>
                <div class="status-row"><span>分块</span><span>${kb.chunkCount}</span></div>
            </div>`).join('') || '<div class="empty">还没有知识库</div>';

        const kbId = $('new-doc-kb').value || (state.kbList[0] && state.kbList[0].id);
        const docs = kbId ? await api(`/api/kb/${kbId}/documents`) : [];
        $('doc-table').querySelector('tbody').innerHTML = docs.map((doc) => `
            <tr>
                <td>${esc(doc.title)}</td>
                <td class="mono">${esc(doc.sourceType)}</td>
                <td>${doc.chunkCount ?? 0}</td>
                <td><span class="state ${esc(doc.status)}">${esc(doc.status)}</span></td>
                <td class="mono">${timeText(doc.createdAt)}</td>
                <td style="text-align:right">
                    <button class="btn" style="padding:5px 12px;font-size:12px"
                            onclick="deleteDoc(${doc.id})">删除</button>
                </td>
            </tr>`).join('') || '<tr><td colspan="6" class="empty">该知识库下还没有文档</td></tr>';
    } catch (e) {
        toast(e.message, true);
    }
}

window.deleteDoc = async function (docId) {
    if (!confirm('确认删除该文档？其分块与索引会一并清理。')) return;
    try {
        await api(`/api/documents/${docId}`, {method: 'DELETE'});
        toast('已删除');
        loadKbPage();
        refreshStatus();
    } catch (e) {
        toast(e.message, true);
    }
};

$('btn-add-doc').addEventListener('click', async () => {
    const kbId = $('new-doc-kb').value;
    const title = $('new-doc-title').value.trim();
    const content = $('new-doc-content').value;
    if (!kbId || !title || !content.trim()) {
        toast('请填写知识库、标题和正文', true);
        return;
    }
    try {
        await api(`/api/kb/${kbId}/documents`, {
            method: 'POST',
            body: JSON.stringify({
                title,
                fileName: title + '.md',
                sourceType: $('new-doc-type').value,
                content
            })
        });
        toast('已提交入库，流水线正在执行');
        $('new-doc-title').value = '';
        $('new-doc-content').value = '';
        setTimeout(loadKbPage, 1200);
        setTimeout(refreshStatus, 1800);
    } catch (e) {
        toast(e.message, true);
    }
});

// ---------------------------------------------------------------- 流水线
const FLOW_NODES = ['PENDING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'PERSISTING', 'COMPLETED'];

async function loadTasks() {
    try {
        const tasks = await api('/api/tasks');
        $('task-table').querySelector('tbody').innerHTML = tasks.map((task) => `
            <tr style="cursor:pointer" onclick="showTaskLog(${task.id})">
                <td>${task.id}</td>
                <td>${esc(task.docTitle)}</td>
                <td><span class="state ${esc(task.state)}">${esc(task.state)}</span></td>
                <td>
                    <div class="funnel-track" style="min-width:70px">
                        <div class="funnel-fill" style="width:${task.progress ?? 0}%"></div>
                    </div>
                </td>
                <td class="mono">${task.costMillis ?? '-'} ms</td>
                <td style="text-align:right">
                    ${(task.state === 'FAILED' || task.state === 'CANCELLED')
                    ? `<button class="btn" style="padding:5px 12px;font-size:12px"
                             onclick="event.stopPropagation();retryTask(${task.id})">重试</button>` : ''}
                </td>
            </tr>`).join('') || '<tr><td colspan="6" class="empty">还没有入库任务</td></tr>';
    } catch (e) {
        toast(e.message, true);
    }
}

window.retryTask = async function (taskId) {
    try {
        await api(`/api/tasks/${taskId}/retry`, {method: 'POST'});
        toast('已重新提交');
        setTimeout(loadTasks, 900);
    } catch (e) {
        toast(e.message, true);
    }
};

window.showTaskLog = async function (taskId) {
    try {
        const task = await api(`/api/tasks/${taskId}`);
        const logs = JSON.parse(task.nodeLog || '[]');
        const currentIndex = FLOW_NODES.indexOf(task.state);
        const flowHtml = FLOW_NODES.map((node, i) => {
            let cls = '';
            if (task.state === 'FAILED') {
                cls = i === currentIndex ? 'failed' : '';
            } else if (task.state === 'COMPLETED') {
                cls = 'done';
            } else if (i < currentIndex) {
                cls = 'done';
            } else if (i === currentIndex) {
                cls = 'current';
            }
            return `<div class="flow-node ${cls}">${node}</div>` +
                (i < FLOW_NODES.length - 1 ? '<span class="flow-arrow">→</span>' : '');
        }).join('');

        $('task-log').innerHTML = `
            <div class="flow" style="margin-bottom:14px">${flowHtml}</div>
            <div class="card-desc" style="margin-bottom:12px">
                文档：<b>${esc(task.docTitle)}</b>　总耗时 ${task.costMillis ?? '-'} ms
                ${task.errorMessage ? `　错误：<span style="color:var(--danger)">${esc(task.errorMessage)}</span>` : ''}
            </div>
            ${logs.map((log) => `
                <div class="step" style="margin-bottom:8px">
                    <div class="step-no" style="${log.status === 'FAILED'
                    ? 'background:linear-gradient(135deg,#fb7185,#f43f5e)' : ''}">
                        ${log.status === 'FAILED' ? '!' : '✓'}
                    </div>
                    <div class="step-content">
                        <div class="step-label">${esc(log.node)} · ${log.costMs}ms</div>
                        <div>${esc(log.detail || '—')}</div>
                    </div>
                </div>`).join('')}`;
    } catch (e) {
        toast(e.message, true);
    }
};

// ---------------------------------------------------------------- Agent
$('btn-react').addEventListener('click', async () => {
    const question = $('react-question').value.trim();
    if (!question) {
        toast('请输入任务描述', true);
        return;
    }
    $('react-steps').innerHTML = '<div class="empty">执行中…</div>';
    try {
        const run = await api('/api/agent/react', {
            method: 'POST',
            body: JSON.stringify({question, kbId: state.selectedKb})
        });
        const stepsHtml = (run.steps || []).map((step) => `
            <div class="step">
                <div class="step-no">${step.step}</div>
                <div class="step-content">
                    ${step.thought ? `<div class="step-label">思考</div><div>${esc(step.thought)}</div>` : ''}
                    ${step.action ? `<div class="step-label">行动</div><div class="mono">${esc(step.action)} ${esc(step.actionInput || '')}</div>` : ''}
                    <div class="step-label">${step.action ? '观察' : '输出'}</div>
                    <div>${esc(step.observation || '')}</div>
                </div>
            </div>`).join('');
        $('react-steps').innerHTML = stepsHtml +
            `<div class="step">
                <div class="step-no" style="background:linear-gradient(135deg,#4ade80,#22d3ee)">✓</div>
                <div class="step-content">
                    <div class="step-label">最终答案</div>
                    <div>${esc(run.answer)}</div>
                </div>
            </div>`;
    } catch (e) {
        $('react-steps').innerHTML = `<div class="empty">执行失败：${esc(e.message)}</div>`;
    }
});

async function loadIntentTree() {
    try {
        const tree = await api('/api/agent/intent-tree');
        const render = (nodes) => nodes.map((node) => `
            <div class="tree-node" style="margin-left:${node.children ? '0' : '18px'}">
                <div class="tree-label">
                    <b>${esc(node.name)}</b>
                    <span class="state">${esc(node.action)}</span>
                    ${(node.keywords || []).map((k) => `<span class="kw">${esc(k)}</span>`).join('')}
                </div>
                <div class="card-desc">${esc(node.description || '')}</div>
                ${node.children ? render(node.children) : ''}
            </div>`).join('');
        $('intent-tree').innerHTML = render(tree);
    } catch (e) {
        toast(e.message, true);
    }
}

// ---------------------------------------------------------------- 问答
const samples = ['出差去上海，住宿费标准是多少？需要谁审批？',
    '年假有多少天？什么时候需要提前申请？',
    '接口的错误码规范是怎么约定的？',
    '写操作接口怎么做幂等？'];
document.querySelectorAll('.sample').forEach((btn, i) => {
    btn.addEventListener('click', () => {
        $('question').value = samples[i];
        $('question').focus();
    });
});

const questionInput = $('question');
questionInput.addEventListener('input', () => {
    questionInput.style.height = 'auto';
    questionInput.style.height = Math.min(questionInput.scrollHeight, 168) + 'px';
});
questionInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        send();
    }
});
$('btn-send').addEventListener('click', send);
$('btn-stop').addEventListener('click', stopStream);

function addMessage(role, html) {
    const scroll = $('chat-scroll');
    $('welcome').style.display = 'none';
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + role;
    wrap.innerHTML = `
        <div class="avatar ${role}">${role === 'user' ? '🧑' : '🔮'}</div>
        <div class="msg-body">
            <div class="msg-role">${role === 'user' ? '你' : 'PrismRAG 助手'}</div>
            ${html}
        </div>`;
    scroll.appendChild(wrap);
    scroll.scrollTop = scroll.scrollHeight;
    return wrap;
}

function send() {
    const question = questionInput.value.trim();
    if (!question || state.streaming) return;

    addMessage('user', `<div class="bubble">${esc(question)}</div>`);
    questionInput.value = '';
    questionInput.style.height = 'auto';

    // sessionId 用于"停止生成"，后端据此中断任务
    const sessionId = 's-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8);
    state.currentSessionId = sessionId;
    state.streaming = true;
    $('btn-send').disabled = true;
    $('btn-stop').style.display = 'inline-block';

    const kbId = $('kb-select').value;
    const deep = $('deep-thinking').checked ? 'true' : 'false';
    const url = `/api/chat/stream?question=${encodeURIComponent(question)}`
        + `&kbId=${kbId}&sessionId=${sessionId}&deepThinking=${deep}`
        + (state.conversationId ? `&conversationId=${state.conversationId}` : '');

    // 【SSE 核心】EventSource 会自动重连，但这里服务端只推一次事件流，
    // 因此在 done/error 后主动 close()，避免浏览器反复重连造成重复请求。
    const source = new EventSource(url);
    state.currentSource = source;

    const bot = addMessage('assistant', '<div class="thinking-bar"><span class="pulse"></span>正在思考…</div>');
    const bubble = bot.querySelector('.thinking-bar');
    let answerEl = null;
    let answerText = '';
    let citations = [];

    source.addEventListener('meta', (e) => {
        const meta = JSON.parse(e.data);
        state.conversationId = meta.conversationId;
        $('conv-hint').textContent = '会话 #' + meta.conversationId;
        if (bubble) {
            bubble.querySelector('span:last-child').textContent =
                '思考中（' + (meta.provider === 'mock' ? '离线 Mock 模型' : meta.model) + '）…';
        }
    });

    source.addEventListener('thinking', (e) => {
        const info = JSON.parse(e.data);
        if (bubble) {
            bubble.querySelector('span:last-child').textContent = info.stage + '：' + (info.detail || '') + '…';
        }
    });

    source.addEventListener('retrieval', (e) => {
        const r = JSON.parse(e.data);
        renderFunnel(bot, r);
    });

    source.addEventListener('citation', (e) => {
        citations = JSON.parse(e.data);
    });

    source.addEventListener('delta', (e) => {
        const delta = JSON.parse(e.data);
        answerText += delta.text;
        if (!answerEl) {
            if (bubble) bubble.remove();
            answerEl = document.createElement('div');
            answerEl.className = 'bubble';
            bot.querySelector('.msg-body').appendChild(answerEl);
        }
        answerEl.textContent = answerText;
        answerEl.innerHTML = esc(answerText) + '<span class="cursor"></span>';
        bot.querySelector('.msg-body').scrollIntoView({block: 'end', behavior: 'smooth'});
    });

    source.addEventListener('done', (e) => {
        const done = JSON.parse(e.data);
        if (answerEl) {
            answerEl.innerHTML = esc(answerText);
        }
        if (citations && citations.length) {
            renderCitations(bot, citations);
        }
        const meta = bot.querySelector('.msg-role');
        meta.innerHTML += `<span class="chip">${done.elapsedMillis}ms</span>
            <span class="chip">${done.tokens} tok</span>
            <span class="chip">${done.citationCount} 引用</span>`;
        finish();
    });

    source.addEventListener('error', (e) => {
        // EventSource 的 error 既可能是网络错误，也可能是服务端 error 事件
        if (e.data) {
            try {
                const err = JSON.parse(e.data);
                toast(err.message || '问答出错', true);
            } catch (ignore) {
                toast('连接中断', true);
            }
        }
        finish();
    });

    source.onerror = () => {
        // 服务端正常结束时也会触发 onerror，这里只在仍在流式状态时提示
        if (state.streaming) {
            toast('连接已断开', true);
        }
        finish();
    };
}

function finish() {
    state.streaming = false;
    $('btn-send').disabled = false;
    $('btn-stop').style.display = 'none';
    if (state.currentSource) {
        state.currentSource.close();
        state.currentSource = null;
    }
}

async function stopStream() {
    if (state.currentSessionId) {
        try {
            await fetch(`/api/chat/cancel?sessionId=${state.currentSessionId}`, {method: 'POST'});
            toast('已请求停止生成');
        } catch (e) {
            toast('停止失败', true);
        }
    }
    finish();
}

function renderFunnel(bot, r) {
    const max = Math.max(
        ...r.vector.map((x) => x.score), ...r.bm25.map((x) => x.score), 0.0001);
    const vSum = r.vector.reduce((a, b) => a + b.score, 0);
    const bSum = r.bm25.reduce((a, b) => a + b.score, 0);
    let html = `<div class="funnel">
        <div class="funnel-title"><span>检索漏斗（双路召回 → RRF 融合）</span>
            <span>${r.degraded ? '⚠️ ' + esc(r.degradeReason) : '两路均正常'}</span></div>
        <div class="funnel-row">
            <span class="funnel-label">向量路</span>
            <div class="funnel-track"><div class="funnel-fill" style="width:${Math.min(100, vSum / max * 100)}%"></div></div>
            <span class="funnel-num">${r.vector.length} 条</span>
        </div>
        <div class="funnel-row">
            <span class="funnel-label">BM25 路</span>
            <div class="funnel-track"><div class="funnel-fill bm25" style="width:${Math.min(100, bSum / max * 100)}%"></div></div>
            <span class="funnel-num">${r.bm25.length} 条</span>
        </div>
        <div class="funnel-row">
            <span class="funnel-label">RRF 融合</span>
            <div class="funnel-track"><div class="funnel-fill" style="width:${r.fused.length ? 100 : 0}%"></div></div>
            <span class="funnel-num">${r.fused.length} 条</span>
        </div>
    </div>`;
    const old = bot.querySelector('.funnel');
    if (old) old.remove();
    bot.querySelector('.msg-body').insertAdjacentHTML('beforeend', html);
}

function renderCitations(bot, citations) {
    const html = `<div class="citations">${citations.map((c) => `
        <div class="citation" onclick="this.classList.toggle('open')">
            <div class="citation-head">
                <span class="caret">▶</span>
                <span class="citation-index">${c.index}</span>
                <span class="citation-title">${esc(c.title)}</span>
                <span class="citation-chips">
                    ${(c.channels || []).map((ch) => `<span class="chip ${ch}">${ch === 'vector' ? '向量命中' : '关键词命中'}</span>`).join('')}
                </span>
            </div>
            <div class="citation-body">${esc(c.content)}</div>
        </div>`).join('')}</div>`;
    bot.querySelector('.msg-body').insertAdjacentHTML('beforeend', html);
}

// ---------------------------------------------------------------- 启动
(async function init() {
    try {
        await loadKbList();
        await refreshStatus();
    } catch (e) {
        toast('初始化失败：' + e.message, true);
    }
    // 根据 URL hash 还原视图（刷新/分享链接时保持原页面）
    switchView(location.hash.replace('#', '') || 'chat', false);
})();
