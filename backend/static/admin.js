const API = '/api';

function $(id) {
  return document.getElementById(id);
}

document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    tab.classList.add('active');
    $('tab-' + tab.dataset.tab).classList.add('active');
  });
});

function showStatus(id, msg, type = '') {
  const el = $(id);
  el.className = 'status ' + type;
  el.textContent = msg || '';
}

async function requestJson(url, options = {}) {
  const resp = await fetch(url, options);
  const text = await resp.text();
  let data = {};
  try {
    data = text ? JSON.parse(text) : {};
  } catch (e) {
    data = { success: false, message: text || '响应解析失败' };
  }
  if (!resp.ok) {
    throw new Error(data.detail || data.message || ('HTTP ' + resp.status));
  }
  return data;
}

// ===== 题库管理 =====
$('qb-import-btn').addEventListener('click', async () => {
  const file = $('qb-file').files[0];
  if (!file) return showStatus('qb-import-status', '请选择文件', 'error');

  const formData = new FormData();
  formData.append('file', file);
  showStatus('qb-import-status', '导入中...', '');
  try {
    const data = await requestJson(API + '/question-bank/import', { method: 'POST', body: formData });
    showStatus('qb-import-status', data.message, data.success ? 'success' : 'error');
    if (data.success) refreshStats();
  } catch (e) {
    showStatus('qb-import-status', '导入失败：' + e.message, 'error');
  }
});

$('qb-refresh-btn').addEventListener('click', refreshStats);

$('qb-clear-btn').addEventListener('click', async () => {
  if (!confirm('确定清空所有题库吗？此操作不可恢复。')) return;
  try {
    const data = await requestJson(API + '/question-bank/clear', { method: 'DELETE' });
    showStatus('qb-import-status', data.message || '已清空题库', 'success');
    refreshStats();
  } catch (e) {
    showStatus('qb-import-status', '清空失败：' + e.message, 'error');
  }
});

async function refreshStats() {
  try {
    const data = await requestJson(API + '/question-bank/stats');
    const container = $('qb-stats');
    if (!data.success || !data.stats || !data.stats.length) {
      container.innerHTML = '<p class="empty">暂无题库数据</p>';
      return;
    }
    container.innerHTML = '<div class="stats-grid">' + data.stats.map(s =>
      '<div class="stat-card"><h3>' + escapeHtml(s.subject || '未分类') + '</h3><p>总计：' + s.total + ' 题</p>' +
      Object.entries(s.types || {}).map(([t, c]) => '<p>' + escapeHtml(t) + '：' + c + ' 题</p>').join('') +
      '</div>'
    ).join('') + '</div>';
  } catch (e) {
    $('qb-stats').innerHTML = '<p class="error-text">加载失败：' + escapeHtml(e.message) + '</p>';
  }
}

// ===== 知识库管理 =====
$('kb-upload-btn').addEventListener('click', async () => {
  const file = $('kb-file').files[0];
  if (!file) return showStatus('kb-upload-status', '请选择文件', 'error');

  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', $('kb-name').value);
  formData.append('subject', $('kb-subject').value);
  formData.append('tags', $('kb-tags').value);

  showStatus('kb-upload-status', '上传解析中...', '');
  $('kb-upload-btn').disabled = true;
  try {
    const data = await requestJson(API + '/knowledge-base/upload', { method: 'POST', body: formData });
    showStatus('kb-upload-status', data.message, data.success ? 'success' : 'error');
    if (data.success) refreshKBList();
  } catch (e) {
    showStatus('kb-upload-status', '上传失败：' + e.message, 'error');
  } finally {
    $('kb-upload-btn').disabled = false;
  }
});

$('kb-refresh-btn').addEventListener('click', refreshKBList);

async function refreshKBList() {
  try {
    const data = await requestJson(API + '/knowledge-base/list');
    const container = $('kb-list');
    if (!data.items || !data.items.length) {
      container.innerHTML = '<p class="empty">暂无知识库</p>';
      return;
    }
    container.innerHTML = data.items.map(kb => `
      <div class="kb-item">
        <div class="kb-info">
          <div class="name">
            ${escapeHtml(kb.name)}
            <span class="badge ${kb.enabled ? 'enabled' : 'disabled'}">${kb.enabled ? '已启用' : '已禁用'}</span>
          </div>
          <div class="meta">科目：${escapeHtml(kb.subject || '-')} | 文件：${kb.fileCount} | 文本块：${kb.chunkCount} | ${escapeHtml((kb.tags || []).join(', ') || '无标签')}</div>
        </div>
        <div class="kb-actions">
          <button class="btn" onclick="toggleKB(${kb.id}, ${!kb.enabled})">${kb.enabled ? '禁用' : '启用'}</button>
          <button class="btn" onclick="reindexKB(${kb.id})">重建索引</button>
          <button class="btn danger" onclick="deleteKB(${kb.id})">删除</button>
        </div>
      </div>
    `).join('');
  } catch (e) {
    $('kb-list').innerHTML = '<p class="error-text">加载失败：' + escapeHtml(e.message) + '</p>';
  }
}

async function toggleKB(id, enabled) {
  const formData = new FormData();
  formData.append('enabled', enabled);
  await fetch(API + '/knowledge-base/' + id, { method: 'PUT', body: formData });
  refreshKBList();
}

async function deleteKB(id) {
  if (!confirm('确定删除这个知识库吗？向量索引也会被清除。')) return;
  await fetch(API + '/knowledge-base/' + id, { method: 'DELETE' });
  refreshKBList();
}

async function reindexKB(id) {
  if (!confirm('重建索引会占用一定资源，确认只重建当前知识库吗？')) return;
  const data = await requestJson(API + '/knowledge-base/' + id + '/reindex', { method: 'POST' });
  alert(data.message || '重建完成');
  refreshKBList();
}

// ===== AI设置 =====
$('ai-settings-save-btn').addEventListener('click', async () => {
  const payload = {
    vision: collectAiForm('vision'),
    index: collectAiForm('index'),
    fallback: collectAiForm('fallback'),
  };
  try {
    const data = await requestJson(API + '/settings/ai', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    showStatus('ai-settings-status', data.message || 'AI设置已保存', 'success');
  } catch (e) {
    showStatus('ai-settings-status', '保存失败：' + e.message, 'error');
  }
});

function collectAiForm(prefix) {
  return {
    endpoint: $(prefix + '-endpoint').value,
    key: $(prefix + '-key').value,
    model: $(prefix + '-model').value,
  };
}

async function loadAiSettings() {
  try {
    const data = await requestJson(API + '/settings/ai');
    const settings = data.settings || {};
    ['vision', 'index', 'fallback'].forEach(prefix => {
      const item = settings[prefix] || {};
      $(prefix + '-endpoint').value = item.endpoint || '';
      $(prefix + '-key').value = item.key || '';
      $(prefix + '-model').value = item.model || '';
    });
  } catch (e) {
    showStatus('ai-settings-status', '加载AI设置失败：' + e.message, 'error');
  }
}

// ===== 用户中心 =====
$('users-refresh-btn').addEventListener('click', refreshUsers);
$('user-create-btn').addEventListener('click', async () => {
  const phone = $('user-phone').value.trim();
  if (!phone) return showStatus('users-status', '请输入手机号', 'error');
  try {
    await requestJson(API + '/users/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ phone }),
    });
    showStatus('users-status', '用户已创建/查询成功', 'success');
    $('user-phone').value = '';
    refreshUsers();
  } catch (e) {
    showStatus('users-status', '操作失败：' + e.message, 'error');
  }
});

async function refreshUsers() {
  try {
    const data = await requestJson(API + '/users');
    const container = $('users-list');
    if (!data.items || !data.items.length) {
      container.innerHTML = '<p class="empty">暂无用户数据</p>';
      return;
    }
    container.innerHTML = `
      <table class="user-table">
        <thead><tr><th>手机号</th><th>权限</th><th>已用/总次数</th><th>剩余</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>
          ${data.items.map(user => `
            <tr>
              <td>${escapeHtml(user.phone)}</td>
              <td>${escapeHtml(user.planName || '-')}</td>
              <td>${user.quotaUsed || 0} / ${user.quotaTotal || 0}</td>
              <td>${user.quotaRemaining || 0}</td>
              <td>${escapeHtml(user.status || '-')}</td>
              <td>
                <select id="plan-${user.id}">
                  <option value="free">20次免费测试</option>
                  <option value="100">100次权限</option>
                  <option value="500">500次权限</option>
                </select>
                <button class="btn primary" onclick="openQuota(${user.id})">开通</button>
              </td>
            </tr>
          `).join('')}
        </tbody>
      </table>
    `;
  } catch (e) {
    $('users-list').innerHTML = '<p class="error-text">加载失败：' + escapeHtml(e.message) + '</p>';
  }
}

async function openQuota(userId) {
  const plan = $('plan-' + userId).value;
  try {
    const data = await requestJson(API + '/users/' + userId + '/quota', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ plan }),
    });
    showStatus('users-status', data.message || '权限已开通', 'success');
    refreshUsers();
  } catch (e) {
    showStatus('users-status', '开通失败：' + e.message, 'error');
  }
}

function escapeHtml(value) {
  return String(value ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

refreshStats();
refreshKBList();
loadAiSettings();
refreshUsers();
