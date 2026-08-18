const API = '/api';

// Tab switching
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
    tab.classList.add('active');
    document.getElementById('tab-' + tab.dataset.tab).classList.add('active');
  });
});

// ===== 题库管理 =====
document.getElementById('qb-import-btn').addEventListener('click', async () => {
  const fileInput = document.getElementById('qb-file');
  const file = fileInput.files[0];
  if (!file) return showStatus('qb-import-status', '请选择文件', 'error');

  const formData = new FormData();
  formData.append('file', file);

  showStatus('qb-import-status', '导入中...', '');
  try {
    const resp = await fetch(API + '/question-bank/import', { method: 'POST', body: formData });
    const data = await resp.json();
    showStatus('qb-import-status', data.message, data.success ? 'success' : 'error');
    if (data.success) refreshStats();
  } catch (e) {
    showStatus('qb-import-status', '导入失败: ' + e.message, 'error');
  }
});

document.getElementById('qb-refresh-btn').addEventListener('click', refreshStats);

document.getElementById('qb-clear-btn').addEventListener('click', async () => {
  if (!confirm('确定要清空所有题库吗？此操作不可恢复。')) return;
  const resp = await fetch(API + '/question-bank/clear', { method: 'DELETE' });
  const data = await resp.json();
  showStatus('qb-import-status', data.message, 'success');
  refreshStats();
});

async function refreshStats() {
  try {
    const resp = await fetch(API + '/question-bank/stats');
    const data = await resp.json();
    const container = document.getElementById('qb-stats');
    if (!data.success || !data.stats.length) {
      container.innerHTML = '<p style="margin-top:12px;color:#888;">暂无题库数据</p>';
      return;
    }
    container.innerHTML = '<div class="stats-grid">' + data.stats.map(s =>
      '<div class="stat-card"><h3>' + s.subject + '</h3><p>总计: ' + s.total + ' 题</p>' +
      Object.entries(s.types).map(([t, c]) => '<p>' + t + ': ' + c + ' 题</p>').join('') +
      '</div>'
    ).join('') + '</div>';
  } catch (e) {
    document.getElementById('qb-stats').innerHTML = '<p style="color:#c00;">加载失败</p>';
  }
}

// ===== 知识库管理 =====
document.getElementById('kb-upload-btn').addEventListener('click', async () => {
  const fileInput = document.getElementById('kb-file');
  const file = fileInput.files[0];
  if (!file) return showStatus('kb-upload-status', '请选择文件', 'error');

  const formData = new FormData();
  formData.append('file', file);
  formData.append('name', document.getElementById('kb-name').value);
  formData.append('subject', document.getElementById('kb-subject').value);
  formData.append('tags', document.getElementById('kb-tags').value);

  showStatus('kb-upload-status', '上传解析中...', '');
  document.getElementById('kb-upload-btn').disabled = true;
  try {
    const resp = await fetch(API + '/knowledge-base/upload', { method: 'POST', body: formData });
    const data = await resp.json();
    showStatus('kb-upload-status', data.message, data.success ? 'success' : 'error');
    if (data.success) refreshKBList();
  } catch (e) {
    showStatus('kb-upload-status', '上传失败: ' + e.message, 'error');
  }
  document.getElementById('kb-upload-btn').disabled = false;
});

document.getElementById('kb-refresh-btn').addEventListener('click', refreshKBList);

async function refreshKBList() {
  try {
    const resp = await fetch(API + '/knowledge-base/list');
    const data = await resp.json();
    const container = document.getElementById('kb-list');
    if (!data.items.length) {
      container.innerHTML = '<p style="margin-top:12px;color:#888;">暂无知识库</p>';
      return;
    }
    container.innerHTML = data.items.map(kb => `
      <div class="kb-item">
        <div class="kb-info">
          <div class="name">
            ${kb.name}
            <span class="badge ${kb.enabled ? 'enabled' : 'disabled'}">${kb.enabled ? '已启用' : '已禁用'}</span>
          </div>
          <div class="meta">
            科目: ${kb.subject || '-'} | 文件: ${kb.fileCount} | 文本块: ${kb.chunkCount} | ${kb.tags.join(', ') || '无标签'}
          </div>
        </div>
        <div class="kb-actions">
          <button class="btn" onclick="toggleKB(${kb.id}, ${!kb.enabled})">${kb.enabled ? '禁用' : '启用'}</button>
          <button class="btn" onclick="reindexKB(${kb.id})">重建索引</button>
          <button class="btn danger" onclick="deleteKB(${kb.id})">删除</button>
        </div>
      </div>
    `).join('');
  } catch (e) {
    document.getElementById('kb-list').innerHTML = '<p style="color:#c00;">加载失败</p>';
  }
}

async function toggleKB(id, enabled) {
  const formData = new FormData();
  formData.append('enabled', enabled);
  await fetch(API + '/knowledge-base/' + id, { method: 'PUT', body: formData });
  refreshKBList();
}

async function deleteKB(id) {
  if (!confirm('确定删除此知识库？向量索引也将被清除。')) return;
  await fetch(API + '/knowledge-base/' + id, { method: 'DELETE' });
  refreshKBList();
}

async function reindexKB(id) {
  const resp = await fetch(API + '/knowledge-base/' + id + '/reindex', { method: 'POST' });
  const data = await resp.json();
  alert(data.message);
  refreshKBList();
}

// ===== 系统设置 =====
document.getElementById('settings-save-btn').addEventListener('click', async () => {
  const endpoint = document.getElementById('rag-endpoint').value;
  const key = document.getElementById('rag-key').value;
  const model = document.getElementById('rag-model').value;

  try {
    const resp = await fetch(API + '/settings/rag', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ endpoint, key, model })
    });
    const data = await resp.json();
    showStatus('settings-status', data.message, data.success ? 'success' : 'error');
  } catch (e) {
    showStatus('settings-status', '保存失败', 'error');
  }
});

// ===== helpers =====
function showStatus(id, msg, type) {
  const el = document.getElementById(id);
  el.className = 'status ' + type;
  el.textContent = msg;
}

// Initial load
refreshStats();
refreshKBList();
