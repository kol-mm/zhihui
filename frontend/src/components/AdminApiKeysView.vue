<template>
  <section class="page-stack">
    <div class="page-toolbar">
      <div>
        <h3>接口密钥</h3>
        <p>外部系统调用平台 API 的凭证。完整密钥只在创建或轮换时显示一次；只有超级管理员可以查看和管理。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建密钥</el-button>
    </div>

    <section class="surface admin-list-surface">
      <div class="admin-filter-bar api-key-filters">
        <el-radio-group v-model="filter" size="small" aria-label="按状态筛选接口密钥">
          <el-radio-button value="live">有效与已过期</el-radio-button>
          <el-radio-button value="revoked">已撤销</el-radio-button>
          <el-radio-button value="all">全部</el-radio-button>
        </el-radio-group>
        <span>共 <strong>{{ shown.length }}</strong> 个</span>
      </div>
      <el-alert v-if="loadError" type="error" :title="loadError" show-icon :closable="false" />

      <el-table v-loading="loading" class="admin-desktop-table" :data="shown">
        <el-table-column label="名称" min-width="150">
          <template #default="scope">
            <span class="stacked-status"><strong>{{ scope.row.name }}</strong><small class="muted-text">由 {{ personLabel(scope.row.createdBy) }} 创建于 {{ formatDate(scope.row.createdAt) }}</small></span>
          </template>
        </el-table-column>
        <el-table-column label="密钥" width="140">
          <template #default="scope"><code class="api-key-prefix">{{ scope.row.prefix }}…</code></template>
        </el-table-column>
        <el-table-column label="权限" min-width="190">
          <template #default="scope">
            <span class="api-key-scopes">
              <el-tag v-for="name in scope.row.scopes" :key="name" size="small" effect="plain" :type="isWrite(name) ? 'warning' : 'info'">{{ scopeLabel(name, scopes) }}</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="代为操作" min-width="140">
          <template #default="scope">
            <span v-if="scope.row.actingUser">{{ personLabel(scope.row.actingUser) }}</span>
            <span v-else class="muted-text">匿名（只读）</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="scope"><el-tag size="small" :type="keyStatusType(scope.row.status)">{{ keyStatusLabel(scope.row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="最近使用" width="130">
          <template #default="scope">{{ scope.row.lastUsedAt ? formatDate(scope.row.lastUsedAt) : '从未使用' }}</template>
        </el-table-column>
        <el-table-column label="有效期至" width="120">
          <template #default="scope">{{ scope.row.revokedAt ? '已于 ' + formatDate(scope.row.revokedAt) + ' 撤销' : scope.row.expiresAt ? scope.row.expiresAt.slice(0, 10) : '永久' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="scope">
            <span v-if="scope.row.status !== 'REVOKED'" class="api-key-actions">
              <el-button text type="primary" @click="openEdit(scope.row)">修改</el-button>
              <el-button text type="warning" @click="rotate(scope.row)">轮换</el-button>
              <el-button text type="danger" @click="revoke(scope.row)">撤销</el-button>
            </span>
            <span v-else class="muted-text">由 {{ personLabel(scope.row.revokedBy) }} 撤销</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="admin-mobile-cards">
        <article v-for="key in shown" :key="key.id">
          <header>
            <span class="stacked-status"><strong>{{ key.name }}</strong><code class="api-key-prefix">{{ key.prefix }}…</code></span>
            <el-tag size="small" :type="keyStatusType(key.status)">{{ keyStatusLabel(key.status) }}</el-tag>
          </header>
          <p class="api-key-scopes"><el-tag v-for="name in key.scopes" :key="name" size="small" effect="plain" :type="isWrite(name) ? 'warning' : 'info'">{{ scopeLabel(name, scopes) }}</el-tag></p>
          <p>{{ key.actingUser ? '代为 ' + personLabel(key.actingUser) + ' 操作' : '匿名（只读）' }} · {{ key.lastUsedAt ? '最近使用 ' + formatDate(key.lastUsedAt) : '从未使用' }} · {{ key.expiresAt ? '有效期至 ' + key.expiresAt.slice(0, 10) : '永久有效' }}</p>
          <footer v-if="key.status !== 'REVOKED'">
            <el-button type="primary" plain @click="openEdit(key)">修改</el-button>
            <el-button type="warning" plain @click="rotate(key)">轮换</el-button>
            <el-button type="danger" plain @click="revoke(key)">撤销</el-button>
          </footer>
        </article>
      </div>
      <el-empty v-if="!loading && !shown.length" :description="filter === 'revoked' ? '没有已撤销的接口密钥' : '还没有接口密钥'" />
    </section>

    <el-dialog v-model="formOpen" :title="editing ? '修改接口密钥' : '新建接口密钥'" width="min(560px, 94vw)" :close-on-click-modal="false" @closed="revealPending">
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="form.name" :maxlength="NAME_MAX" show-word-limit placeholder="例如：数据中心同步" />
        </el-form-item>
        <el-form-item label="权限">
          <el-checkbox-group v-model="form.scopes" class="api-key-scope-choices">
            <el-checkbox v-for="scope in scopes" :key="scope.name" :value="scope.name">
              {{ scope.label }} <code>{{ scope.name }}</code>
              <el-tag v-if="scope.write" size="small" type="warning" effect="plain">写入</el-tag>
            </el-checkbox>
          </el-checkbox-group>
          <small class="muted-text">任何密钥都不能访问管理接口、账号、私信、通知和 AI 问答。</small>
        </el-form-item>
        <el-form-item :label="writing ? '代为操作的账号（必填）' : '代为操作的账号（选填）'">
          <el-select v-model="form.actingUserId" filterable remote clearable :remote-method="searchAccounts" :loading="searching"
                     placeholder="输入用户名或昵称搜索" aria-label="代为操作的账号" class="api-key-account">
            <el-option v-for="person in accounts" :key="person.id" :label="personLabel(person)" :value="person.id" />
          </el-select>
          <small class="muted-text">{{ writing ? '写入的帖子、评论和知识都会归属这个账号。只能选择正常状态的普通账号，不能是管理员。' : '不指定时，密钥以匿名身份读取公开内容。' }}</small>
        </el-form-item>
        <el-form-item label="有效期">
          <el-radio-group v-model="form.expiry">
            <el-radio v-for="choice in EXPIRY_CHOICES" :key="choice.value" :value="choice.value">{{ choice.label }}</el-radio>
          </el-radio-group>
          <el-date-picker v-if="form.expiry === 'custom'" v-model="form.customDate" type="date" value-format="YYYY-MM-DD"
                          placeholder="最后有效的日期" :disabled-date="beforeToday" aria-label="最后有效的日期" />
        </el-form-item>
      </el-form>
      <p v-if="problem" class="api-key-problem" role="alert">{{ problem }}</p>
      <template #footer>
        <el-button @click="formOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ editing ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretOpen" class="reset-code-dialog" :title="secret?.rotated ? '接口密钥已轮换' : '接口密钥已创建'"
               width="min(560px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="false" @closed="secret = null">
      <template v-if="secret">
        <p>这是「{{ secret.name }}」的完整密钥，<strong>只显示这一次</strong>。{{ secret.rotated ? '旧密钥已经失效。' : '' }}</p>
        <div class="reset-code-value api-key-secret" data-testid="api-key-secret">{{ secret.value }}</div>
        <el-alert type="warning" :closable="false" show-icon title="关闭此窗口后无法再次查看。请立即保存到安全的地方，不要提交到代码仓库或发到聊天群；遗失后只能轮换出一个新密钥。" />
        <p class="reset-code-meta">在请求头中携带它即可调用接口，例如：</p>
        <code class="api-key-example">{{ curlExample(origin, secret.value, secret.scopes) }}</code>
      </template>
      <template #footer>
        <el-button :icon="CopyDocument" @click="copySecret">复制密钥</el-button>
        <el-button type="primary" @click="secretOpen = false">我已保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { CopyDocument, Plus } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { getData, isSessionExpiredError, postData, toUserMessage } from '../api/client';
import { formatDate } from '../utils/statusLabels';
import {
  EXPIRY_CHOICES, NAME_MAX, curlExample, emptyForm, formFor, formProblem, keyRequest, keyStatusLabel, keyStatusType,
  localDate, needsActingAccount, personLabel, scopeLabel, visibleKeys,
  type ApiKeyForm, type ApiKeyPerson, type ApiKeyRecord, type ApiKeyScope, type KeyFilter,
} from '../utils/apiKeys';

type Account = NonNullable<ApiKeyPerson>;
type Issued = { name: string; value: string; scopes: string[]; rotated: boolean };

const props = defineProps<{ refreshKey: number }>();

const keys = ref<ApiKeyRecord[]>([]);
const scopes = ref<ApiKeyScope[]>([]);
const loading = ref(false);
const loadError = ref('');
const filter = ref<KeyFilter>('live');
const shown = computed(() => visibleKeys(keys.value, filter.value));

const formOpen = ref(false);
const editing = ref<ApiKeyRecord | null>(null);
const form = ref<ApiKeyForm>(emptyForm());
const problem = ref('');
const saving = ref(false);
const writing = computed(() => needsActingAccount(form.value.scopes, scopes.value));

const accounts = ref<Account[]>([]);
const searching = ref(false);
let searchToken = 0;

const secretOpen = ref(false);
const secret = ref<Issued | null>(null);
const pending = ref<Issued | null>(null);
const origin = window.location.origin;

function notifyError(error: unknown) {
  if (isSessionExpiredError(error)) return;
  ElMessage.error(toUserMessage(error));
}

function isWrite(name: string) {
  return scopes.value.some(scope => scope.name === name && scope.write);
}

function beforeToday(day: Date) {
  return localDate(day) < localDate(new Date());
}

async function load() {
  loading.value = true;
  loadError.value = '';
  try {
    const result = await getData<{ items: ApiKeyRecord[]; scopes: ApiKeyScope[] }>('/user/admin/api-keys');
    keys.value = result.items;
    scopes.value = result.scopes;
  } catch (error) {
    loadError.value = toUserMessage(error);
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  editing.value = null;
  form.value = emptyForm();
  accounts.value = [];
  problem.value = '';
  formOpen.value = true;
}

function openEdit(key: ApiKeyRecord) {
  editing.value = key;
  form.value = formFor(key);
  // The select can only show the current account's name if it is among its options.
  accounts.value = key.actingUser ? [key.actingUser] : [];
  problem.value = '';
  formOpen.value = true;
}

async function searchAccounts(keyword: string) {
  const query = keyword.trim();
  if (!query) return;
  const token = ++searchToken;
  searching.value = true;
  try {
    // Only ordinary, active accounts can back a key; the server checks this again.
    const params = new URLSearchParams({ keyword: query, role: 'USER', status: 'ACTIVE', limit: '20' });
    const page = await getData<{ items: Account[] }>(`/user/admin/users/page?${params.toString()}`);
    if (token === searchToken) accounts.value = page.items;
  } catch (error) {
    if (token === searchToken) notifyError(error);
  } finally {
    if (token === searchToken) searching.value = false;
  }
}

async function submit() {
  const today = new Date();
  problem.value = formProblem(form.value, scopes.value, today) || '';
  if (problem.value) return;
  saving.value = true;
  try {
    const request = keyRequest(form.value, today);
    if (editing.value) {
      await postData('/user/admin/api-keys/update', { ...request, keyId: editing.value.id });
      formOpen.value = false;
      ElMessage.success('接口密钥已更新，下一次请求起生效');
    } else {
      const created = await postData<ApiKeyRecord & { secret: string }>('/user/admin/api-keys', request);
      // Shown once the form has finished closing, so the secret is the only thing on screen.
      pending.value = issuedFrom(created, false);
      formOpen.value = false;
    }
    await load();
  } catch (error) {
    // The server's reason belongs in the form, where it can be corrected.
    problem.value = toUserMessage(error);
  } finally {
    saving.value = false;
  }
}

async function rotate(key: ApiKeyRecord) {
  try {
    await ElMessageBox.confirm(`轮换后「${key.name}」的旧密钥立即失效，正在使用它的系统都需要换上新密钥。权限和有效期保持不变。`,
      '轮换接口密钥', { type: 'warning', confirmButtonText: '轮换', cancelButtonText: '取消' });
  } catch {
    return;
  }
  try {
    const rotated = await postData<ApiKeyRecord & { secret: string }>('/user/admin/api-keys/rotate', { keyId: key.id });
    show(rotated, true);
    await load();
  } catch (error) {
    notifyError(error);
  }
}

async function revoke(key: ApiKeyRecord) {
  let reason = '';
  try {
    const answer = await ElMessageBox.prompt(`撤销后「${key.name}」立即失效，且不能恢复。可以填写原因（选填），会记入操作记录。`,
      '撤销接口密钥', {
        type: 'warning', confirmButtonText: '撤销', cancelButtonText: '取消', inputPlaceholder: '例如：合作已结束',
        inputValidator: (value: string) => (value || '').length <= 200 || '原因不能超过 200 个字符',
      });
    reason = (answer.value || '').trim();
  } catch {
    return;
  }
  try {
    await postData('/user/admin/api-keys/revoke', { keyId: key.id, reason });
    ElMessage.success('接口密钥已撤销');
    await load();
  } catch (error) {
    notifyError(error);
  }
}

function issuedFrom(issued: ApiKeyRecord & { secret: string }, rotated: boolean): Issued {
  return { name: issued.name, value: issued.secret, scopes: issued.scopes, rotated };
}

function show(issued: ApiKeyRecord & { secret: string }, rotated: boolean) {
  secret.value = issuedFrom(issued, rotated);
  secretOpen.value = true;
}

/** Runs when the form's closing animation has ended. */
function revealPending() {
  if (!pending.value) return;
  secret.value = pending.value;
  pending.value = null;
  secretOpen.value = true;
}

async function copySecret() {
  if (!secret.value) return;
  try {
    await navigator.clipboard.writeText(secret.value.value);
    ElMessage.success('密钥已复制');
  } catch {
    ElMessage.warning('无法访问剪贴板，请手动选中密钥复制');
  }
}

onMounted(() => void load());
// The page asks for a refresh by changing this number, which keeps the chosen filter as it was.
watch(() => props.refreshKey, () => void load());
</script>

<style scoped>
.api-key-filters {
  justify-content: space-between;
}

.api-key-prefix {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}

.api-key-actions {
  display: inline-flex;
  flex-wrap: nowrap;
  gap: 2px;
}

.api-key-actions .el-button {
  margin-left: 0;
  padding-left: 6px;
  padding-right: 6px;
}

.api-key-scopes {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

.api-key-scope-choices {
  display: grid;
  gap: 6px;
}

.api-key-scope-choices code {
  font-size: 12px;
  opacity: 0.7;
}

.api-key-account {
  width: 100%;
}

.api-key-secret {
  word-break: break-all;
  font-size: 15px;
  letter-spacing: 0;
}

.api-key-example {
  display: block;
  padding: 8px 10px;
  border-radius: 6px;
  background: rgba(127, 127, 127, 0.12);
  font-size: 12px;
  word-break: break-all;
}

.api-key-problem {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
