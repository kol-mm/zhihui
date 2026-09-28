<template>
  <section class="page-stack">
    <div class="page-toolbar">
      <div>
        <h3>模型密钥</h3>
        <p>AI 问答和内容审核调用模型服务时使用的密钥。只有超级管理员可以查看和管理；保存后只显示首尾几位，不能再读出完整密钥。</p>
      </div>
      <el-button type="primary" :icon="Plus" :disabled="!storage.available" @click="openAdd">添加密钥</el-button>
    </div>

    <el-alert v-if="loadError" type="error" :title="loadError" show-icon :closable="false" />
    <el-alert v-if="!storage.available && storage.problem" type="warning" :title="storage.problem" show-icon :closable="false" />

    <section class="surface admin-list-surface">
      <div class="surface-head">
        <div>
          <h3>当前使用</h3>
          <p>
            「AI 与系统」中选择的是<strong>{{ providerLabel(configured.provider || '') || '—' }}</strong>
            <template v-if="configured.provider === 'anthropic'"> · {{ claudeModel }}</template>
            <template v-else-if="configured.provider === 'local'">，不调用模型服务，这里的密钥暂时不会被使用</template>
          </p>
        </div>
      </div>
      <div class="ai-key-providers">
        <article v-for="info in providers" :key="info.provider" :class="{ selected: info.provider === configured.provider }">
          <header>
            <strong>{{ info.label }}</strong>
            <el-tag v-if="info.provider === configured.provider" size="small" effect="plain">正在调用</el-tag>
          </header>
          <p><el-tag size="small" :type="inUseTone(info.inUse.source)">{{ inUseText(info) }}</el-tag></p>
          <footer>
            <el-button size="small" :loading="testing === 'provider:' + info.provider" :disabled="info.inUse.source === 'none'"
                       @click="testProvider(info)">测试当前密钥</el-button>
            <span v-if="providerResults[info.provider]" class="ai-key-result" :class="{ bad: !providerResults[info.provider].ok }">
              {{ providerResults[info.provider].message }}
            </span>
          </footer>
        </article>
      </div>
    </section>

    <section class="surface admin-list-surface">
      <div class="surface-head"><div><h3>已保存的密钥</h3><p>每个模型服务同一时间只启用一个；其余作为备用，需要时一键切换。</p></div></div>
      <el-table v-loading="loading" class="admin-desktop-table" :data="keys">
        <el-table-column label="名称" min-width="150">
          <template #default="scope"><strong>{{ scope.row.name }}</strong></template>
        </el-table-column>
        <el-table-column label="模型服务" width="150">
          <template #default="scope">{{ providerLabel(scope.row.provider) }}</template>
        </el-table-column>
        <el-table-column label="密钥" min-width="170">
          <template #default="scope"><code class="ai-key-hint">{{ scope.row.hint }}</code></template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="scope"><el-tag size="small" :type="scope.row.active ? 'success' : 'info'">{{ scope.row.active ? '使用中' : '备用' }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近测试" min-width="200">
          <template #default="scope">
            <span class="stacked-status">
              <span :class="{ 'ai-key-result': true, bad: scope.row.lastTestOk === false }">{{ testLabel(scope.row) }}</span>
              <small v-if="scope.row.lastTestMessage" class="muted-text">{{ scope.row.lastTestMessage }}</small>
              <small v-if="scope.row.lastTestedAt" class="muted-text">{{ formatDate(scope.row.lastTestedAt) }}</small>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="scope">
            <span class="ai-key-actions">
              <el-button text type="primary" :loading="testing === 'key:' + scope.row.id" @click="testKey(scope.row)">测试</el-button>
              <el-button v-if="!scope.row.active" text type="success" @click="activate(scope.row)">启用</el-button>
              <el-button v-else text type="warning" @click="deactivate(scope.row)">停用</el-button>
              <el-button text type="primary" @click="openEdit(scope.row)">修改</el-button>
              <el-button text type="danger" @click="remove(scope.row)">删除</el-button>
            </span>
          </template>
        </el-table-column>
      </el-table>

      <div class="admin-mobile-cards">
        <article v-for="key in keys" :key="key.id">
          <header>
            <span class="stacked-status"><strong>{{ key.name }}</strong><code class="ai-key-hint">{{ key.hint }}</code></span>
            <el-tag size="small" :type="key.active ? 'success' : 'info'">{{ key.active ? '使用中' : '备用' }}</el-tag>
          </header>
          <p>{{ providerLabel(key.provider) }} · {{ testLabel(key) }}<template v-if="key.lastTestMessage">：{{ key.lastTestMessage }}</template></p>
          <footer>
            <el-button plain type="primary" :loading="testing === 'key:' + key.id" @click="testKey(key)">测试</el-button>
            <el-button v-if="!key.active" plain type="success" @click="activate(key)">启用</el-button>
            <el-button v-else plain type="warning" @click="deactivate(key)">停用</el-button>
            <el-button plain type="primary" @click="openEdit(key)">修改</el-button>
            <el-button plain type="danger" @click="remove(key)">删除</el-button>
          </footer>
        </article>
      </div>
      <el-empty v-if="!loading && !keys.length" description="还没有在平台中保存模型密钥" />
    </section>

    <el-dialog v-model="addOpen" title="添加模型密钥" width="min(540px, 94vw)" :close-on-click-modal="false" @closed="addForm.secret = ''">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="模型服务">
          <el-select v-model="addForm.provider" aria-label="模型服务" @change="addForm.activate = activateByDefault(addForm.provider, keys)">
            <el-option v-for="info in providers" :key="info.provider" :label="info.label" :value="info.provider" />
          </el-select>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="addForm.name" :maxlength="NAME_MAX" show-word-limit placeholder="例如：生产环境主密钥" />
        </el-form-item>
        <el-form-item label="密钥">
          <el-input v-model="addForm.secret" type="password" show-password autocomplete="off" aria-label="密钥"
                    :placeholder="addForm.provider === 'anthropic' ? 'sk-ant-api03-…' : '服务商提供的 API Key'" />
          <small class="muted-text">保存时加密，之后只显示首尾几位。{{ addForm.provider === 'anthropic' ? '请使用 Anthropic 控制台中的普通 API 密钥，不要使用管理密钥（sk-ant-admin…）。' : '' }}</small>
        </el-form-item>
        <el-checkbox v-model="addForm.activate">保存后立即启用（替换 {{ providerLabel(addForm.provider) }} 当前使用的密钥）</el-checkbox>
      </el-form>
      <p v-if="problem" class="ai-key-problem" role="alert">{{ problem }}</p>
      <template #footer>
        <el-button @click="addOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitAdd">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editOpen" title="修改模型密钥" width="min(540px, 94vw)" :close-on-click-modal="false" @closed="editForm.secret = ''">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="editForm.name" :maxlength="NAME_MAX" show-word-limit />
        </el-form-item>
        <el-form-item label="新的密钥（选填）">
          <el-input v-model="editForm.secret" type="password" show-password autocomplete="off" aria-label="新的密钥" placeholder="留空则保留原来的密钥" />
          <small class="muted-text">在服务商那里轮换密钥后，把新密钥填在这里；原来的密钥会被覆盖。</small>
        </el-form-item>
      </el-form>
      <p v-if="problem" class="ai-key-problem" role="alert">{{ problem }}</p>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { getData, isSessionExpiredError, postData, toUserMessage } from '../api/client';
import { formatDate } from '../utils/statusLabels';
import {
  NAME_MAX, activateByDefault, addProblem, deletionConsequence, editProblem, inUseText, inUseTone, providerLabel, testLabel,
  type AddKeyForm, type ProviderInfo, type ProviderKey,
} from '../utils/aiKeys';

type Overview = {
  keys: ProviderKey[];
  providers: ProviderInfo[];
  storage: { available: boolean; problem: string | null };
  configured: { provider: string | null; model: string | null };
  claudeDefaultModel: string;
};
type TestResult = { ok: boolean; message: string; key: ProviderKey | null };

const props = defineProps<{ refreshKey: number }>();

const keys = ref<ProviderKey[]>([]);
const providers = ref<ProviderInfo[]>([]);
const storage = ref<Overview['storage']>({ available: true, problem: null });
const configured = ref<Overview['configured']>({ provider: null, model: null });
const claudeDefault = ref('claude-opus-5');
const loading = ref(false);
const loadError = ref('');
const testing = ref('');
const providerResults = ref<Record<string, { ok: boolean; message: string }>>({});

const addOpen = ref(false);
const addForm = ref<AddKeyForm>({ provider: 'anthropic', name: '', secret: '', activate: true });
const editOpen = ref(false);
const editing = ref<ProviderKey | null>(null);
const editForm = ref({ name: '', secret: '' });
const problem = ref('');
const saving = ref(false);

const claudeModel = computed(() => {
  const model = (configured.value.model || '').trim();
  return model.startsWith('claude-') ? model : `${claudeDefault.value}（默认）`;
});

function notifyError(error: unknown) {
  if (isSessionExpiredError(error)) return;
  ElMessage.error(toUserMessage(error));
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const overview = await getData<Overview>('/ai/admin/provider-keys');
    keys.value = overview.keys;
    providers.value = overview.providers;
    storage.value = overview.storage;
    configured.value = overview.configured;
    claudeDefault.value = overview.claudeDefaultModel;
  } catch (error) {
    loadError.value = toUserMessage(error);
  } finally {
    loading.value = false;
  }
}

function openAdd() {
  const provider = configured.value.provider === 'openai-compatible' ? 'openai-compatible' : 'anthropic';
  addForm.value = { provider, name: '', secret: '', activate: activateByDefault(provider, keys.value) };
  problem.value = '';
  addOpen.value = true;
}

async function submitAdd() {
  problem.value = addProblem(addForm.value) || '';
  if (problem.value) return;
  saving.value = true;
  try {
    await postData('/ai/admin/provider-keys/add', { ...addForm.value, secret: addForm.value.secret.trim() });
    addOpen.value = false;
    ElMessage.success(addForm.value.activate ? '密钥已保存并启用' : '密钥已保存为备用');
    await load();
  } catch (error) {
    problem.value = toUserMessage(error);
  } finally {
    saving.value = false;
  }
}

function openEdit(key: ProviderKey) {
  editing.value = key;
  editForm.value = { name: key.name, secret: '' };
  problem.value = '';
  editOpen.value = true;
}

async function submitEdit() {
  if (!editing.value) return;
  problem.value = editProblem(editing.value.provider, editForm.value.name, editForm.value.secret) || '';
  if (problem.value) return;
  saving.value = true;
  try {
    const request: Record<string, unknown> = { keyId: editing.value.id, name: editForm.value.name.trim() };
    if (editForm.value.secret.trim()) request.secret = editForm.value.secret.trim();
    await postData('/ai/admin/provider-keys/update', request);
    editOpen.value = false;
    ElMessage.success('密钥已更新');
    await load();
  } catch (error) {
    problem.value = toUserMessage(error);
  } finally {
    saving.value = false;
  }
}

async function activate(key: ProviderKey) {
  try {
    await postData('/ai/admin/provider-keys/activate', { keyId: key.id });
    ElMessage.success(`${providerLabel(key.provider)} 已改用「${key.name}」`);
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function deactivate(key: ProviderKey) {
  const info = providers.value.find(item => item.provider === key.provider);
  try {
    await ElMessageBox.confirm(`停用后 ${providerLabel(key.provider)} 将改用环境变量 ${info?.environmentVariable ?? ''} 中的密钥；如果没有配置，调用会失败。`,
      `停用「${key.name}」`, { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' });
  } catch {
    return;
  }
  try {
    await postData('/ai/admin/provider-keys/deactivate', { keyId: key.id });
    ElMessage.success('密钥已停用');
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function remove(key: ProviderKey) {
  const info = providers.value.find(item => item.provider === key.provider);
  try {
    await ElMessageBox.confirm(`${deletionConsequence(key, info)}删除后无法恢复，只能重新填写。`, `删除「${key.name}」`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' });
  } catch {
    return;
  }
  try {
    await postData('/ai/admin/provider-keys/delete', { keyId: key.id });
    ElMessage.success('密钥已删除');
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function testKey(key: ProviderKey) {
  testing.value = 'key:' + key.id;
  try {
    const result = await postData<TestResult>('/ai/admin/provider-keys/test', { keyId: key.id });
    (result.ok ? ElMessage.success : ElMessage.warning)(result.message);
    await load();
  } catch (error) {
    notifyError(error);
  } finally {
    testing.value = '';
  }
}

async function testProvider(info: ProviderInfo) {
  testing.value = 'provider:' + info.provider;
  try {
    const result = await postData<TestResult>('/ai/admin/provider-keys/test', { provider: info.provider });
    providerResults.value = { ...providerResults.value, [info.provider]: { ok: result.ok, message: result.message } };
    if (result.key || info.inUse.source === 'stored') await load();
  } catch (error) {
    notifyError(error);
  } finally {
    testing.value = '';
  }
}

onMounted(() => void load());
// The page asks for a refresh by changing this number.
watch(() => props.refreshKey, () => void load());
</script>

<style scoped>
.ai-key-providers {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}

.ai-key-providers article {
  display: grid;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid rgba(127, 127, 127, 0.22);
  border-radius: 10px;
}

.ai-key-providers article.selected {
  border-color: var(--el-color-primary);
}

.ai-key-providers header,
.ai-key-providers footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ai-key-providers p {
  margin: 0;
}

.ai-key-hint {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.ai-key-result {
  font-size: 13px;
  color: var(--el-color-success);
}

.ai-key-result.bad {
  color: var(--el-color-danger);
}

.ai-key-actions {
  display: inline-flex;
  flex-wrap: nowrap;
  gap: 2px;
}

.ai-key-actions .el-button {
  margin-left: 0;
  padding-left: 6px;
  padding-right: 6px;
}

.ai-key-problem {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
