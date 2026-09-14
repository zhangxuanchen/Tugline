/**
 * Tugline Agent 侧边栏（M1 骨架对话）
 * - fetch + ReadableStream 手动解析 SSE（POST + JWT 头，EventSource 做不到）
 * - 事件：token（流式文本）/ tool / toolresult（M3）/ ping（保活）/ done / error
 * - 会话：列表 / 新建 / 删除 / 切换，落盘 localStorage
 */
(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const chatEl = $('#agent-chat');
  const inputEl = $('#agent-input');
  const sendEl = $('#agent-send');
  const stopEl = $('#agent-stop');
  const stateDot = $('#agent-state-dot');
  const stateText = $('#agent-state-text');
  const sessionSel = $('#agent-session-select');

  const state = {
    session: localStorage.getItem('tugline.agentSession') || '',
    busy: false,
    ctrl: null,
  };

  const authHeaders = () => {
    const t = localStorage.getItem('tugline.token');
    return t ? { Authorization: 'Bearer ' + t } : {};
  };

  // ---------- 状态展示 ----------

  function setState(mode) {
    stateDot.classList.remove('busy', 'wait');
    if (mode === 'busy') {
      stateDot.classList.add('busy');
      stateText.textContent = '思考中…';
    } else if (mode === 'wait') {
      stateDot.classList.add('wait');
      stateText.textContent = '等待回答';
    } else {
      stateText.textContent = '空闲';
    }
  }

  function setBusy(busy) {
    state.busy = busy;
    sendEl.disabled = busy;
    stopEl.style.display = busy ? 'inline-block' : 'none';
    setState(busy ? 'busy' : 'idle');
    if (!busy) inputEl.focus();
  }

  // ---------- Markdown 最小渲染 ----------

  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function renderMd(src) {
    // 1) 逃逸 HTML
    let text = escapeHtml(src);
    // 2) 代码围栏 ```lang ... ```
    const blocks = [];
    text = text.replace(/```\w*\n([\s\S]*?)```/g, (_, code) => {
      blocks.push('<pre><code>' + code.replace(/\n$/, '') + '</code></pre>');
      return '\u0000B' + (blocks.length - 1) + '\u0000';
    });
    // 3) 行内转换（逐行处理标题/列表/段落）
    const lines = text.split('\n');
    let html = '';
    let inList = false;
    for (const line of lines) {
      const trimmed = line.trim();
      if (/^\u0000B\d+\u0000$/.test(trimmed)) {
        if (inList) { html += '</ul>'; inList = false; }
        html += trimmed;
        continue;
      }
      let h = null;
      let m = trimmed.match(/^###\s+(.*)/) || trimmed.match(/^##\s+(.*)/) || trimmed.match(/^#\s+(.*)/);
      if (m) h = m[1];
      if (h !== null) {
        if (inList) { html += '</ul>'; inList = false; }
        html += '<h3>' + inline(h) + '</h3>';
        continue;
      }
      m = trimmed.match(/^[-*]\s+(.*)/);
      if (m) {
        if (!inList) { html += '<ul>'; inList = true; }
        html += '<li>' + inline(m[1]) + '</li>';
        continue;
      }
      if (inList) { html += '</ul>'; inList = false; }
      if (trimmed === '') continue;
      html += '<p>' + inline(trimmed) + '</p>';
    }
    if (inList) html += '</ul>';
    return html.replace(/\u0000B(\d+)\u0000/g, (_, i) => blocks[+i]);

    function inline(s) {
      return s
        .replace(/`([^`]+)`/g, '<code>$1</code>')
        .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    }
  }

  // ---------- 消息气泡 ----------

  function addMsg(kind, content) {
    const wrap = document.createElement('div');
    wrap.className = 'agent-msg ' + kind;
    const tag = document.createElement('div');
    tag.className = 'am-tag';
    tag.textContent = kind === 'user' ? '你' : kind === 'error' ? '异常' : '助手';
    const bubble = document.createElement('div');
    bubble.className = 'am-bubble';
    if (content != null) bubble.innerHTML = renderMd(content);
    wrap.appendChild(tag);
    wrap.appendChild(bubble);
    chatEl.appendChild(wrap);
    chatEl.scrollTop = chatEl.scrollHeight;
    return bubble;
  }

  function showEmptyHint() {
    if (!chatEl.querySelector('.agent-msg')) {
      const hint = document.createElement('div');
      hint.className = 'agent-empty';
      hint.innerHTML = '和 Tugline 助手聊点什么吧<br>比如「这个平台是干什么的？」';
      chatEl.appendChild(hint);
    }
  }

  function clearHints() {
    chatEl.querySelectorAll('.agent-empty, .agent-gate').forEach((el) => el.remove());
  }

  // ---------- SSE 解析 ----------

  function parseSseBlock(raw) {
    let event = 'message';
    const dataLines = [];
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    return { event, data: dataLines.join('\n') };
  }

  // ---------- 发送一轮 ----------

  async function send() {
    const text = inputEl.value.trim();
    if (!text || state.busy) return;
    // 无会话时先静默创建，避免落到 default 会话
    if (!state.session) {
      try {
        const r = await fetch('/api/agent/sessions', {
          method: 'POST',
          headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        });
        if (r.ok) {
          const s = await r.json();
          state.session = s.id;
          localStorage.setItem('tugline.agentSession', s.id);
        }
      } catch (ignored) {
      }
    }
    clearHints();
    addMsg('user', text);
    inputEl.value = '';
    inputEl.style.height = 'auto';

    const bubble = addMsg('assistant', '');
    bubble.dataset.raw = '';
    setBusy(true);

    state.ctrl = new AbortController();
    let errored = false;
    try {
      const resp = await fetch('/api/agent/chat', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ sessionId: state.session, message: text }),
        signal: state.ctrl.signal,
      });
      if (!resp.ok || !resp.body) throw new Error('HTTP ' + resp.status);
      const reader = resp.body.getReader();
      const dec = new TextDecoder();
      let buf = '';
      let renderPending = false;
      let turnEnded = false; // 收到 done/error 即视为本轮结束，不等流物理关闭
      while (!turnEnded) {
        const { done, value } = await reader.read();
        if (done) break;
        buf += dec.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n\n')) >= 0) {
          const block = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          const { event, data } = parseSseBlock(block);
          if (event === 'token' && data) {
            bubble.dataset.raw += data;
            if (!renderPending) {
              renderPending = true;
              setTimeout(() => {
                renderPending = false;
                bubble.innerHTML = renderMd(bubble.dataset.raw);
                chatEl.scrollTop = chatEl.scrollHeight;
              }, 60);
            }
          } else if (event === 'error') {
            errored = true;
            turnEnded = true;
            bubble.closest('.agent-msg').className = 'agent-msg error';
            bubble.textContent = data || '未知错误';
          } else if (event === 'done') {
            turnEnded = true;
          } else if (event === 'tool') {
            const t = document.createElement('div');
            t.className = 'am-tag';
            t.textContent = '🔧 ' + data;
            bubble.parentNode.appendChild(t);
          }
          // ping：无需渲染
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        errored = true;
        bubble.closest('.agent-msg').className = 'agent-msg error';
        bubble.textContent = '请求失败：' + e.message;
      }
    } finally {
      state.ctrl = null;
      if (!errored && !bubble.textContent && !bubble.dataset.raw) {
        bubble.closest('.agent-msg').remove();
      } else if (!errored) {
        bubble.innerHTML = renderMd(bubble.dataset.raw);
      }
      setBusy(false);
      showEmptyHint();
      loadSessions(); // 刷新标题/列表
    }
  }

  // ---------- 会话管理 ----------

  async function loadSessions() {
    try {
      const resp = await fetch('/api/agent/sessions', { headers: authHeaders() });
      if (!resp.ok) return;
      const list = await resp.json();
      sessionSel.innerHTML = '';
      if (!list.length) {
        // 列表为空时清掉本地残留的旧会话 id，避免继续往已删除的「幽灵会话」发消息
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = '暂无会话';
        sessionSel.appendChild(opt);
        if (state.session) {
          state.session = '';
          localStorage.removeItem('tugline.agentSession');
          chatEl.innerHTML = '';
        }
        showEmptyHint();
        return;
      }
      for (const s of list) {
        const opt = document.createElement('option');
        opt.value = s.id;
        opt.textContent = s.title || s.id;
        sessionSel.appendChild(opt);
      }
      if (!state.session || !list.some((s) => s.id === state.session)) {
        state.session = list[0].id;
        localStorage.setItem('tugline.agentSession', state.session);
        await openSession(state.session);
      }
      sessionSel.value = state.session;
    } catch (ignored) {
    }
  }

  async function openSession(id) {
    if (!id) {
      chatEl.innerHTML = '';
      showEmptyHint();
      return;
    }
    try {
      const resp = await fetch('/api/agent/sessions/' + encodeURIComponent(id) + '/messages', {
        headers: authHeaders(),
      });
      if (!resp.ok) return;
      const data = await resp.json();
      chatEl.innerHTML = '';
      for (const m of data.messages || []) {
        addMsg(m.role === 'user' ? 'user' : 'assistant', m.content);
      }
      showEmptyHint();
    } catch (ignored) {
    }
  }

  async function newSession() {
    try {
      const resp = await fetch('/api/agent/sessions', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
      });
      if (!resp.ok) return;
      const s = await resp.json();
      state.session = s.id;
      localStorage.setItem('tugline.agentSession', s.id);
      chatEl.innerHTML = '';
      showEmptyHint();
      await loadSessions();
      inputEl.focus();
    } catch (ignored) {
    }
  }

  async function deleteSession() {
    if (!state.session) return;
    if (!confirm('删除当前会话？历史消息不可恢复。')) return;
    try {
      await fetch('/api/agent/sessions/' + encodeURIComponent(state.session), {
        method: 'DELETE',
        headers: authHeaders(),
      });
    } catch (ignored) {
    }
    state.session = '';
    localStorage.removeItem('tugline.agentSession');
    chatEl.innerHTML = '';
    await loadSessions();
  }

  // ---------- 折叠 ----------

  function applyCollapsed(collapsed) {
    document.body.classList.toggle('agent-collapsed', collapsed);
    localStorage.setItem('tugline.agentCollapsed', collapsed ? '1' : '');
  }

  // ---------- 拖拽调宽 ----------

  function setupResizer() {
    const sidebar = $('#agent-sidebar');
    const resizer = $('#agent-resizer');
    const KEY = 'tugline.agentWidth';
    const MIN = 300;
    const saved = parseInt(localStorage.getItem(KEY) || '', 10);
    if (saved >= MIN) sidebar.style.width = saved + 'px';

    let startX = 0, startW = 0;
    resizer.addEventListener('pointerdown', (e) => {
      startX = e.clientX;
      startW = sidebar.getBoundingClientRect().width;
      resizer.setPointerCapture(e.pointerId);
      document.body.classList.add('agent-resizing');
      e.preventDefault();
    });
    resizer.addEventListener('pointermove', (e) => {
      if (!document.body.classList.contains('agent-resizing')) return;
      // 向左拖 = 变宽；限制在 [MIN, 视口 70%]
      const w = Math.min(Math.max(startW + (startX - e.clientX), MIN),
        Math.floor(window.innerWidth * 0.7));
      sidebar.style.width = w + 'px';
    });
    const finish = (e) => {
      if (!document.body.classList.contains('agent-resizing')) return;
      document.body.classList.remove('agent-resizing');
      try { resizer.releasePointerCapture(e.pointerId); } catch (ignored) { }
      localStorage.setItem(KEY, String(Math.round(sidebar.getBoundingClientRect().width)));
    };
    resizer.addEventListener('pointerup', finish);
    resizer.addEventListener('pointercancel', finish);
  }

  // ---------- 引导态 ----------

  async function checkGate() {
    try {
      const resp = await fetch('/api/agent/config', { headers: authHeaders() });
      if (!resp.ok) return;
      const cfg = await resp.json();
      if (cfg.enabled && cfg.keyConfigured) {
        // 已可用：清理历史 gate 与禁用态（修「切回个人 AK 后按钮仍灰」的状态残留）
        clearHints();
        if (!state.busy) {
          inputEl.disabled = false;
          sendEl.disabled = false;
        }
        return;
      }
      clearHints();
      const gate = document.createElement('div');
      gate.className = 'agent-gate';
      gate.innerHTML =
        '<div class="agent-gate-icon">⚡</div>' +
        '<div class="agent-gate-title">Agent 未启用</div>' +
        '<div class="agent-gate-desc">' +
        (cfg.enabled
          ? '尚未配置大模型 AK。<br>可点击下方按钮为本账号配置个人 AK，<br>或由管理员设置 <code>DASHSCOPE_API_KEY</code>（或 LLM_API_KEY）后重启服务。'
          : 'Agent 模块已关闭（TUGLINE_AGENT_ENABLED=false）。') +
        '</div>';
      const btn = document.createElement('button');
      btn.className = 'btn btn-primary';
      btn.type = 'button';
      btn.textContent = '配置 AI 密钥';
      btn.onclick = openAkDialog;
      gate.appendChild(btn);
      chatEl.appendChild(gate);
      inputEl.disabled = true;
      sendEl.disabled = true;
    } catch (ignored) {
    }
  }

  // ---------- AI 密钥管理弹窗 ----------

  const akMask = $('#ak-mask');
  const akActiveSel = $('#ak-active-select');
  const akList = $('#ak-list');
  const akError = $('#ak-error');
  const akVendorsEl = $('#ak-vendors');
  const akFormEl = $('#ak-vendor-form');
  const akAddBtn = $('#ak-add-btn');

  // 服务商模板：选哪家渲染哪家的表单；落盘协议统一为 provider=dashscope|openai
  const AK_VENDORS = [
    { id: 'dashscope', label: '通义千问', provider: 'dashscope', baseUrl: '', baseUrlRequired: false, model: 'qwen-plus' },
    { id: 'deepseek',  label: 'DeepSeek', provider: 'openai',   baseUrl: 'https://api.deepseek.com',           model: 'deepseek-chat' },
    { id: 'zhipu',     label: '智谱 GLM', provider: 'openai',   baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
    { id: 'moonshot',  label: 'Kimi',     provider: 'openai',   baseUrl: 'https://api.moonshot.cn/v1',         model: 'moonshot-v1-8k' },
    { id: 'openai',    label: 'OpenAI',   provider: 'openai',   baseUrl: 'https://api.openai.com/v1',          model: 'gpt-4o-mini' },
    { id: 'custom',    label: '自定义兼容', provider: 'openai',  baseUrl: '', baseUrlRequired: true,            model: '' },
  ];
  let akVendorSel = null;

  function renderAkVendors() {
    akVendorsEl.innerHTML = '';
    AK_VENDORS.forEach((v) => {
      const card = document.createElement('button');
      card.type = 'button';
      card.className = 'ak-vendor' + (akVendorSel && akVendorSel.id === v.id ? ' on' : '');
      card.textContent = v.label;
      card.onclick = () => {
        akVendorSel = v;
        renderAkVendors();
        renderVendorForm();
      };
      akVendorsEl.appendChild(card);
    });
  }

  /** 按所选服务商动态渲染专属表单：字段、默认值逐家不同 */
  function renderVendorForm() {
    akError.style.display = 'none';
    const v = akVendorSel;
    if (!v) {
      akFormEl.innerHTML = '';
      akAddBtn.style.display = 'none';
      return;
    }
    const needUrl = v.provider === 'openai';
    akFormEl.innerHTML =
      '<label class="login-field"><span>API Key</span>' +
      `<input id="ak-f-key" type="password" placeholder="${v.label} 的 API Key（sk-...）" autocomplete="new-password"/></label>` +
      (needUrl
        ? '<label class="login-field"><span>Base URL' + (v.baseUrlRequired ? '（必填）' : '') + '</span>' +
          `<input id="ak-f-url" placeholder="https://..." value="${v.baseUrl}"/></label>`
        : '') +
      '<label class="login-field"><span>模型（可选）</span>' +
      `<input id="ak-f-model" placeholder="留空用默认" value="${v.model}"/></label>`;
    akAddBtn.style.display = 'inline-block';
  }

  function openAkDialog() {
    akMask.style.display = 'flex';
    akError.style.display = 'none';
    renderAkVendors();
    renderVendorForm();
    refreshAk();
  }

  function closeAkDialog() {
    akMask.style.display = 'none';
  }

  async function refreshAk() {
    try {
      const resp = await fetch('/api/agent/keys', { headers: authHeaders() });
      if (!resp.ok) return;
      const data = await resp.json();
      const items = data.items || [];
      // 当前使用选择框：全局默认 + 各条 AK
      akActiveSel.innerHTML = '';
      const optGlobal = document.createElement('option');
      optGlobal.value = '';
      optGlobal.textContent = '全局默认（环境变量 / 配置文件）';
      akActiveSel.appendChild(optGlobal);
      items.forEach((k) => {
        const opt = document.createElement('option');
        opt.value = k.id;
        opt.textContent = `${k.name}（${k.provider} · ${k.keyMasked}）`;
        akActiveSel.appendChild(opt);
      });
      akActiveSel.value = data.activeId || '';
      akActiveSel.onchange = async () => {
        await fetch('/api/agent/keys/active', {
          method: 'POST',
          headers: { ...authHeaders(), 'Content-Type': 'application/json' },
          body: JSON.stringify({ id: akActiveSel.value }),
        });
        checkGate();
      };
      // 已保存 AK 列表
      akList.innerHTML = '';
      if (!items.length) {
        akList.innerHTML = '<div class="ak-empty">还没有保存的 AK，在下方新增一条即可。</div>';
      }
      items.forEach((k) => {
        const row = document.createElement('div');
        row.className = 'ak-item' + (k.id === data.activeId ? ' active' : '');
        row.innerHTML =
          `<div class="ak-item-main"><b>${escapeHtml(k.name)}</b>` +
          `<span class="ak-chip">${escapeHtml(k.provider)}</span>` +
          `<span class="ak-chip">${escapeHtml(k.keyMasked)}</span>` +
          (k.model ? `<span class="ak-chip">${escapeHtml(k.model)}</span>` : '') +
          (k.id === data.activeId ? '<span class="ak-chip on">当前使用</span>' : '') +
          '</div>';
        const del = document.createElement('button');
        del.className = 'btn btn-ghost ak-del';
        del.type = 'button';
        del.textContent = '删除';
        del.onclick = async () => {
          if (!confirm(`删除 AK「${k.name}」？`)) return;
          await fetch('/api/agent/keys/' + k.id, { method: 'DELETE', headers: authHeaders() });
          refreshAk();
          checkGate();
        };
        row.appendChild(del);
        akList.appendChild(row);
      });
    } catch (ignored) {
    }
  }

  async function addAk() {
    akError.style.display = 'none';
    const v = akVendorSel;
    if (!v) return;
    const body = {
      name: v.label,
      provider: v.provider,
      apiKey: ($('#ak-f-key') || {}).value ? $('#ak-f-key').value.trim() : '',
      model: ($('#ak-f-model') || {}).value ? $('#ak-f-model').value.trim() : '',
      baseUrl: $('#ak-f-url') ? $('#ak-f-url').value.trim() : '',
    };
    if (!body.apiKey) {
      akError.textContent = 'API Key 不能为空';
      akError.style.display = 'block';
      return;
    }
    if (v.baseUrlRequired && !body.baseUrl) {
      akError.textContent = '该服务商需要填写 Base URL';
      akError.style.display = 'block';
      return;
    }
    try {
      const resp = await fetch('/api/agent/keys', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!resp.ok) {
        const msg = await resp.json().catch(() => ({}));
        akError.textContent = msg.error || msg.message || `保存失败（${resp.status}）`;
        akError.style.display = 'block';
        return;
      }
      const { id } = await resp.json();
      // 新增即设为当前使用
      await fetch('/api/agent/keys/active', {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({ id }),
      });
      akVendorSel = null;
      renderAkVendors();
      renderVendorForm();
      refreshAk();
      checkGate();
    } catch (e) {
      akError.textContent = '保存失败：' + e.message;
      akError.style.display = 'block';
    }
  }

  // ---------- 初始化 ----------

  function init() {
    applyCollapsed(localStorage.getItem('tugline.agentCollapsed') === '1');
    setupResizer();

    $('#agent-collapse').onclick = () => applyCollapsed(true);
    $('#agent-fab').onclick = () => applyCollapsed(false);
    $('#agent-new-session').onclick = newSession;
    $('#agent-del-session').onclick = deleteSession;
    sessionSel.onchange = () => {
      state.session = sessionSel.value;
      localStorage.setItem('tugline.agentSession', state.session);
      openSession(state.session);
    };
    sendEl.onclick = send;
    stopEl.onclick = () => {
      if (state.ctrl) state.ctrl.abort();
    };
    // AI 密钥管理弹窗
    $('#btn-ak-manage').onclick = openAkDialog;
    $('#ak-close').onclick = closeAkDialog;
    $('#ak-add-btn').onclick = addAk;
    akMask.addEventListener('click', (e) => {
      if (e.target === akMask) closeAkDialog();
    });
    inputEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    });
    inputEl.addEventListener('input', () => {
      inputEl.style.height = 'auto';
      inputEl.style.height = Math.min(inputEl.scrollHeight, 140) + 'px';
    });

    showEmptyHint();
    checkGate();
    loadSessions();
    // 登录成功后重新检查配置与加载会话（init 时可能还未登录）
    window.addEventListener('tugline:login', () => {
      inputEl.disabled = false;
      sendEl.disabled = false;
      checkGate();
      loadSessions();
    });
  }

  init();
})();
