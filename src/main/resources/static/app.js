/* Tugline 控制台逻辑（原生 JS，轮询刷新） */
(() => {
  'use strict';

  const $ = (sel) => document.querySelector(sel);
  const state = {
    repos: [],
    selectedId: null,
    runs: [],
    runPage: 1,
    runPages: 1,
    runsTotal: 0,
    currentRun: null,
    logTab: 'run',
    timer: null,
  };

  // ---------- 基础 ----------

  function getToken() {
    return localStorage.getItem('tugline.token') || '';
  }

  async function api(path, opts = {}) {
    const headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
    const token = getToken();
    if (token) {
      headers.Authorization = 'Bearer ' + token;
    }
    const res = await fetch(path, { ...opts, headers });
    const body = await res.json().catch(() => ({}));
    if (res.status === 401 && !path.startsWith('/api/auth/login')) {
      showLogin();
      throw new Error('未登录或登录已过期');
    }
    if (!res.ok) {
      throw new Error(body.error || `请求失败 (${res.status})`);
    }
    return body;
  }

  // ---------- 登录鉴权 ----------

  function showLogin() {
    $('#login-mask').style.display = 'flex';
    $('#user-menu').style.display = 'none';
    $('#user-dropdown').style.display = 'none';
    loadCaptcha();
    if (state.timer) {
      clearInterval(state.timer);
      state.timer = null;
    }
  }

  /** 拉取登录验证码（SVG 直接渲染进 img） */
  let captchaId = '';
  async function loadCaptcha() {
    try {
      const res = await fetch('/api/auth/captcha');
      const data = await res.json();
      captchaId = data.captchaId;
      $('#login-captcha-img').src = 'data:image/svg+xml,' + encodeURIComponent(data.svg);
      $('#login-captcha').value = '';
    } catch (_) { /* 页面加载时验证码失败，点图片可重试 */ }
  }

  /** 登录成功后展示右上角用户管理 */
  function showUserMenu(username) {
    $('#user-name').textContent = username || 'admin';
    $('#user-menu').style.display = 'block';
  }

  async function doLogin(e) {
    e.preventDefault();
    const btn = $('#login-submit');
    btn.disabled = true;
    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: $('#login-username').value.trim(),
          password: $('#login-password').value,
          captchaId: captchaId,
          captchaCode: $('#login-captcha').value,
        }),
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        loadCaptcha();
        throw new Error(data.error || '登录失败');
      }
      localStorage.setItem('tugline.token', data.token);
      $('#login-mask').style.display = 'none';
      $('#login-error').style.display = 'none';
      $('#login-password').value = '';
      showUserMenu(data.username);
      window.dispatchEvent(new Event('tugline:login'));
      await refresh();
      state.timer = setInterval(refresh, 2000);
      toast('欢迎回来，' + data.username);
    } catch (err) {
      const box = $('#login-error');
      box.textContent = err.message;
      box.style.display = 'block';
    } finally {
      btn.disabled = false;
    }
  }

  function logout() {
    localStorage.removeItem('tugline.token');
    showLogin();
    toast('已退出登录');
  }

  function toast(msg, isErr = false) {
    const el = $('#toast');
    el.textContent = msg;
    el.className = 'toast' + (isErr ? ' err' : '');
    el.style.display = 'block';
    clearTimeout(el._t);
    el._t = setTimeout(() => { el.style.display = 'none'; }, 2600);
  }

  function fmtTime(ts) {
    if (!ts) return '—';
    const d = new Date(ts);
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
  }

  function fmtDur(ms) {
    if (!ms || ms < 0) return '—';
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    return `${m}m${s % 60}s`;
  }

  const STEP_ICONS = { PENDING: '○', RUNNING: '⟳', SUCCESS: '✅', FAILED: '❌' };

  // ---------- 渲染 ----------

  function selectedRepo() {
    return state.repos.find((r) => r.id === state.selectedId) || null;
  }

  function renderSidebar() {
    const list = $('#repo-list');
    list.innerHTML = '';
    state.repos.forEach((r) => {
      const item = document.createElement('div');
      item.className = 'repo-item' + (r.id === state.selectedId ? ' active' : '');
      item.innerHTML = `
        <span class="dot ${r.status || ''}"></span>
        <span class="repo-name">${escapeHtml(r.name)}</span>
        <span class="repo-port">:${r.port}</span>`;
      item.onclick = () => selectRepo(r.id);
      list.appendChild(item);
    });
  }

  function escapeHtml(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderDetail() {
    const r = selectedRepo();
    $('#empty-state').style.display = r ? 'none' : 'flex';
    $('#repo-detail').style.display = r ? 'block' : 'none';
    if (!r) return;

    $('#d-name').textContent = r.name;
    const chip = $('#d-status');
    chip.textContent = statusText(r.status);
    chip.className = 'chip ' + (r.status || '');

    $('#d-url').textContent = r.url;
    $('#d-url').href = r.url.replace(/\.git$/, '').replace(/x-access-token:[^@]*@/, '');
    $('#d-branch').textContent = r.branch;
    $('#d-port').textContent = ':' + r.port;
    $('#d-preview-path').textContent = r.previewPath || '/';
    $('#d-commit').textContent = (r.runtime && r.runtime.commit ? r.runtime.commit.slice(0, 8) : '—');

    const webhookUrl = `${location.origin}/api/webhook/${r.id}`;
    $('#d-webhook').value = webhookUrl;

    // 流水线
    const run = state.currentRun;
    renderPipeline(run);

    // 演示地址
    const up = r.status === 'UP';
    // 应用未在运行时「停止」按钮置灰
    $('#d-stop').disabled = !(r.status === 'UP' || r.status === 'STARTING');
    $('#preview-card').style.display = up ? 'block' : 'none';
    if (up) {
      const url = `http://${location.hostname}:${r.port}${r.previewPath || ''}`;
      $('#d-preview-url').textContent = url;
      $('#d-preview-url').href = url;
      $('#d-preview-open').href = url;
    }

    // 部署历史
    const tbody = $('#history-body');
    tbody.innerHTML = '';
    state.runs.forEach((run2) => {
      const tr = document.createElement('tr');
      const total = (run2.endAt || Date.now()) - run2.createdAt;
      tr.innerHTML = `
        <td class="mono">${fmtTime(run2.createdAt)}</td>
        <td>${run2.trigger === 'WEBHOOK' ? '🔗 Webhook' : '🖱 手动'}</td>
        <td class="mono">${run2.commit ? run2.commit.slice(0, 8) : '—'}</td>
        <td><span class="chip ${run2.status}">${run2.status === 'SUCCESS' ? '成功' : run2.status === 'FAILED' ? '失败' : '执行中'}</span></td>
        <td class="mono">${escapeHtml(run2.artifact || '—')}</td>
        <td class="mono">${fmtDur(total)}</td>
        <td><span class="row-link" data-run="${run2.id}">详情</span></td>`;
      tbody.appendChild(tr);
    });
    // 分页控件
    const pager = $('#history-pager');
    if (state.runPages > 1 || state.runsTotal > 0) {
      pager.style.display = 'flex';
      pager.innerHTML = `
        <button id="hp-prev" class="hp-btn" ${state.runPage <= 1 ? 'disabled' : ''}>‹ 上一页</button>
        <span class="hp-info">第 ${state.runPage} / ${state.runPages} 页 · 共 ${state.runsTotal} 次</span>
        <button id="hp-next" class="hp-btn" ${state.runPage >= state.runPages ? 'disabled' : ''}>下一页 ›</button>`;
      $('#hp-prev').onclick = () => goToRunPage(state.runPage - 1);
      $('#hp-next').onclick = () => goToRunPage(state.runPage + 1);
    } else {
      pager.style.display = 'none';
      pager.innerHTML = '';
    }
    tbody.querySelectorAll('.row-link').forEach((el) => {
      el.onclick = () => {
        const runId = el.dataset.run;
        const target = state.runs.find((x) => x.id === runId);
        if (target) {
          state.currentRun = target;
          renderDetail();
        }
      };
    });
  }

  function goToRunPage(p) {
    const next = Math.min(Math.max(p, 1), state.runPages);
    if (next === state.runPage) return;
    state.runPage = next;
    refresh();
  }

  function statusText(s) {
    return {
      UP: '运行中 · 健康', STARTING: '启动中', DOWN: '异常', EXITED: '已退出',
      STOPPED: '未运行', RUNNING: '流水线执行中', SUCCESS: '成功', FAILED: '失败',
    }[s] || (s || '—');
  }

  function renderPipeline(run) {
    const box = $('#pipeline-steps');
    const meta = $('#d-run-meta');
    const errBox = $('#d-run-error');
    // 应用停止后步骤恢复初始态
    const sel = selectedRepo();
    const resetVisual = !!sel && (sel.status === 'STOPPED' || sel.status === 'EXITED');

    if (!run) {
      box.innerHTML = '<div class="meta" style="padding:6px 2px">尚未执行流水线，点击「一键部署」开始。</div>';
      meta.textContent = '';
      errBox.style.display = 'none';
      findingsBox.style.display = 'none';
      return;
    }
    meta.textContent = `run ${run.id} · ${run.trigger === 'WEBHOOK' ? 'Webhook 触发' : '手动触发'} · ${fmtTime(run.createdAt)}`;

    box.innerHTML = '';
    run.steps.forEach((s, i) => {
      if (i > 0) {
        const arrow = document.createElement('span');
        arrow.className = 'step-arrow';
        arrow.textContent = '→';
        box.appendChild(arrow);
      }
      const dur = s.endAt && s.startAt ? fmtDur(s.endAt - s.startAt) : (s.status === 'RUNNING' ? '…' : '');
      const el = document.createElement('div');
      // 应用已停止 → 流水线步骤统一显示为初始态颜色
      const stoppedVisual = resetVisual || s.status === 'PENDING';
      el.className = 'step ' + (stoppedVisual ? 'PENDING' : s.status);
      el.innerHTML = `
        <span class="s-icon">${stoppedVisual ? '○' : (STEP_ICONS[s.status] || '○')}</span>
        <div>
          <div class="s-name">${escapeHtml(s.name)}</div>
          ${!stoppedVisual && dur ? `<div class="s-time">${dur}</div>` : ''}
        </div>`;
      box.appendChild(el);
    });

    if (run.status === 'FAILED' && run.error) {
      errBox.style.display = 'block';
      errBox.textContent = '⚠ ' + run.error;
    } else {
      errBox.style.display = 'none';
    }
  }

  // ---------- 数据加载 ----------

  async function refresh() {
    try {
      const repos = await api('/api/repos');
      // 保持选择不变
      if (!state.selectedId && repos.length) state.selectedId = repos[0].id;
      if (state.selectedId && !repos.find((r) => r.id === state.selectedId)) {
        state.selectedId = repos.length ? repos[0].id : null;
      }
      state.repos = repos;
      renderSidebar();

      const r = selectedRepo();
      if (r) {
        const data = await api(`/api/repos/${r.id}/runs?page=${state.runPage}&size=10`);
        state.runs = data.items || [];
        state.runPages = data.pages || 1;
        state.runsTotal = data.total || 0;
        // 关注中的 run：列表里 RUNNING 的优先，否则保持用户点选的
        const running = state.runs.find((x) => x.status === 'RUNNING');
        if (running) {
          const cur = state.currentRun;
          if (!cur || cur.status === 'RUNNING' || cur.id === running.id || !state.runs.find((x) => x.id === cur.id)) {
            state.currentRun = running;
          }
        } else if (!state.runs.find((x) => x.id === (state.currentRun || {}).id)) {
          state.currentRun = state.runs[0] || null;
        }
        renderDetail();
        await refreshLogs();
      } else {
        renderDetail();
      }
    } catch (e) {
      console.error(e);
    }
  }

  async function refreshLogs() {
    const r = selectedRepo();
    if (!r) return;
    const view = $('#log-view');
    try {
      if (state.logTab === 'run') {
        const run = state.currentRun;
        if (!run) { view.textContent = '（暂无流水线日志）'; return; }
        const stepKey = $('#log-step').value;
        const data = await api(`/api/runs/${run.id}/logs?step=${stepKey}&tail=300`);
        view.textContent = data.lines.join('\n') || '（该步骤暂无日志）';
      } else {
        const data = await api(`/api/repos/${r.id}/applog?tail=300`);
        view.textContent = data.lines.join('\n') || '（应用尚未启动）';
      }
      // 自动滚动到底
      if (nearBottom(view)) view.scrollTop = view.scrollHeight;
    } catch (e) {
      view.textContent = '日志加载失败: ' + e.message;
    }
  }

  function nearBottom(el) {
    return el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  function selectRepo(id) {
    state.selectedId = id;
    state.currentRun = null;
    state.runs = [];
    state.runPage = 1;
    renderSidebar();
    refresh();
  }

  // ---------- 操作 ----------

  async function deploy() {
    const r = selectedRepo();
    if (!r) return;
    try {
      const run = await api(`/api/repos/${r.id}/deploy`, { method: 'POST' });
      state.currentRun = run;
      state.logTab = 'run';
      $('#log-step').value = 'fetch';
      switchLogTab('run');
      toast('流水线已触发');
      refresh();
    } catch (e) { toast(e.message, true); }
  }

  async function stopApp() {
    const r = selectedRepo();
    if (!r) return;
    if (!confirm(`确认停止 ${r.name} 的应用进程？`)) return;
    try {
      await api(`/api/repos/${r.id}/stop`, { method: 'POST' });
      toast('已停止');
      refresh();
    } catch (e) { toast(e.message, true); }
  }

  async function restartApp() {
    const r = selectedRepo();
    if (!r) return;
    try {
      await api(`/api/repos/${r.id}/restart`, { method: 'POST' });
      toast('已用现有产物重启（未重新构建）');
      refresh();
    } catch (e) { toast(e.message, true); }
  }

  async function editPreviewPath() {
    const r = selectedRepo();
    if (!r) return;
    const next = prompt('设置演示地址的访问路径（如 /admin/index.html，留空表示根路径 /）', r.previewPath || '/');
    if (next === null) return;
    try {
      await api(`/api/repos/${r.id}/update`, {
        method: 'POST',
        body: JSON.stringify({ previewPath: next.trim() }),
      });
      toast('访问路径已更新');
      refresh();
    } catch (e) { toast(e.message, true); }
  }

  async function deleteRepo() {
    const r = selectedRepo();
    if (!r) return;
    if (!confirm(`移除仓库 ${r.name}？将停止应用并删除本地工作副本与记录。`)) return;
    try {
      await api(`/api/repos/${r.id}`, { method: 'DELETE' });
      toast('仓库已移除');
      state.selectedId = null;
      refresh();
    } catch (e) { toast(e.message, true); }
  }

  // ---------- 添加仓库弹窗 ----------

  function openAddRepo() {
    $('#modal-mask').style.display = 'flex';
    $('#f-url').focus();
  }

  function resetBranchSelect() {
    const sel = $('#f-branch');
    sel.disabled = true;
    sel.innerHTML = '<option value="">先填 Git 地址</option>';
  }

  /** 拉取远端分支填充下拉框；手动触发时错误弹 toast，自动触发静默 */
  async function fetchBranches(manual = false) {
    const url = $('#f-url').value.trim();
    const sel = $('#f-branch');
    if (!url) {
      if (manual) toast('请先填写 Git 地址', true);
      return;
    }
    sel.disabled = true;
    sel.innerHTML = '<option value="">获取中…</option>';
    try {
      const data = await api('/api/repos/branches', {
        method: 'POST',
        body: JSON.stringify({ url, token: $('#f-token').value.trim() }),
      });
      const prefer = ['main', 'master', 'develop'].find((b) => data.branches.includes(b));
      sel.innerHTML = data.branches
        .map((b) => `<option value="${escapeHtml(b)}">${escapeHtml(b)}</option>`)
        .join('');
      sel.value = prefer || data.branches[0];
      sel.disabled = false;
      if (manual) toast(`已获取 ${data.branches.length} 个分支`);
    } catch (e) {
      resetBranchSelect();
      if (manual) toast(e.message, true);
    }
  }

  function closeAddRepo() {
    $('#modal-mask').style.display = 'none';
    ['f-url', 'f-token', 'f-secret', 'f-health', 'f-preview', 'f-port'].forEach((id) => { $('#' + id).value = ''; });
    resetBranchSelect();
  }

  async function submitRepo() {
    const url = $('#f-url').value.trim();
    if (!url) { toast('请填写 Git 地址', true); return; }
    const port = parseInt($('#f-port').value, 10);
    const btn = $('#f-submit');
    btn.disabled = true;
    btn.textContent = '校验中…';
    try {
      const repo = await api('/api/repos', {
        method: 'POST',
        body: JSON.stringify({
          url,
          branch: $('#f-branch').value.trim() || 'main',
          token: $('#f-token').value.trim(),
          secret: $('#f-secret').value.trim(),
          healthPath: $('#f-health').value.trim(),
          previewPath: $('#f-preview').value.trim(),
          port: Number.isFinite(port) && port > 0 ? port : null,
        }),
      });
      closeAddRepo();
      toast(`${repo.name} 添加成功，点击「一键部署」发版`);
      state.selectedId = repo.id;
      refresh();
    } catch (e) {
      toast(e.message, true);
    } finally {
      btn.disabled = false;
      btn.textContent = '添加';
    }
  }

  // ---------- 日志 Tab ----------

  function switchLogTab(tab) {
    state.logTab = tab;
    document.querySelectorAll('.tab').forEach((t) => {
      t.classList.toggle('active', t.dataset.logtab === tab);
    });
    $('#log-step').style.visibility = tab === 'run' ? 'visible' : 'hidden';
    refreshLogs();
  }

  // ---------- 用户管理 / 修改密码 ----------

  function toggleUserDropdown() {
    const dd = $('#user-dropdown');
    dd.style.display = dd.style.display === 'none' ? 'block' : 'none';
  }

  function openChangePwd() {
    $('#user-dropdown').style.display = 'none';
    $('#pwd-error').style.display = 'none';
    ['f-oldpwd', 'f-newpwd', 'f-newpwd2'].forEach((id) => { $('#' + id).value = ''; });
    $('#pwd-mask').style.display = 'flex';
    $('#f-oldpwd').focus();
  }

  function closeChangePwd() {
    $('#pwd-mask').style.display = 'none';
  }

  async function submitPwd() {
    const err = (msg) => {
      const box = $('#pwd-error');
      box.textContent = msg;
      box.style.display = 'block';
    };
    const oldPwd = $('#f-oldpwd').value;
    const p1 = $('#f-newpwd').value;
    const p2 = $('#f-newpwd2').value;
    if (!oldPwd) return err('请输入当前密码');
    if (p1.length < 6) return err('新密码至少 6 位');
    if (p1 !== p2) return err('两次输入的新密码不一致');
    const btn = $('#pwd-submit');
    btn.disabled = true;
    try {
      await api('/api/auth/password', {
        method: 'POST',
        body: JSON.stringify({ oldPassword: oldPwd, newPassword: p1 }),
      });
      closeChangePwd();
      toast('密码已修改，下次登录请使用新密码');
    } catch (e) {
      err(e.message);
    } finally {
      btn.disabled = false;
    }
  }

  // ---------- 绑定 ----------

  window.UI = { openAddRepo, closeAddRepo, openChangePwd, closeChangePwd };

  document.addEventListener('DOMContentLoaded', () => {
    $('#btn-add-repo').onclick = openAddRepo;
    $('#f-submit').onclick = submitRepo;
    $('#f-branch-fetch').onclick = () => fetchBranches(true);
    $('#f-url').addEventListener('blur', () => fetchBranches(false));
    $('#modal-mask').addEventListener('click', (e) => {
      if (e.target === $('#modal-mask')) closeAddRepo();
    });
    $('#d-deploy').onclick = deploy;
    $('#d-stop').onclick = stopApp;
    $('#d-restart').onclick = restartApp;
    $('#d-delete').onclick = deleteRepo;
    $('#d-preview-path').onclick = editPreviewPath;
    $('#d-copy-wh').onclick = () => {
      const input = $('#d-webhook');
      navigator.clipboard.writeText(input.value).then(() => toast('Webhook 地址已复制'));
    };
    document.querySelectorAll('.tab').forEach((t) => {
      t.onclick = () => switchLogTab(t.dataset.logtab);
    });
    $('#log-step').onchange = refreshLogs;

    $('#user-btn').onclick = toggleUserDropdown;
    $('#btn-change-pwd').onclick = openChangePwd;
    $('#pwd-submit').onclick = submitPwd;
    $('#pwd-mask').addEventListener('click', (e) => {
      if (e.target === $('#pwd-mask')) closeChangePwd();
    });
    // 点击菜单外部时收起下拉
    document.addEventListener('click', (e) => {
      if (!e.target.closest('#user-menu')) $('#user-dropdown').style.display = 'none';
    });

    $('#login-form').addEventListener('submit', doLogin);
    $('#login-captcha-img').addEventListener('click', loadCaptcha);
    $('#btn-logout').onclick = logout;
    if (getToken()) {
      api('/api/auth/me').then((me) => {
        showUserMenu(me.username);
        refresh();
        state.timer = setInterval(refresh, 2000);
      }).catch(() => {});
    } else {
      showLogin();
    }
    switchLogTab('run');
  });
})();
