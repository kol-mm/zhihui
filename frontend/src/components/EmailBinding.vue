<template>
  <section class="email-binding" aria-label="邮箱绑定" :aria-busy="loadState === 'loading'">
    <p v-if="loadState === 'loading'" class="email-binding-hint" role="status">正在读取邮箱信息…</p>
    <div v-else-if="loadState === 'failed'" class="email-binding-status" role="alert">
      <span class="email-binding-empty">邮箱信息加载失败</span>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <template v-else>
      <div class="email-binding-status">
        <div class="email-binding-current">
          <strong v-if="state.email">{{ state.email }}</strong>
          <span v-else class="email-binding-empty">尚未绑定邮箱</span>
          <el-tag v-if="state.email" size="small" :type="state.verified ? 'success' : 'info'" effect="plain">{{ state.verified ? '已验证' : '未验证' }}</el-tag>
        </div>
        <div v-if="!editing" ref="actions" class="email-binding-actions">
          <el-button size="small" :disabled="busy" @click="openEditor">{{ state.email ? '更换' : '绑定邮箱' }}</el-button>
          <el-button v-if="state.email" size="small" text type="danger" :disabled="busy" @click="remove">解绑</el-button>
        </div>
      </div>

      <form v-if="editing" class="email-binding-form" novalidate @submit.prevent="save" @keydown.esc.prevent="closeEditor">
        <div class="email-binding-row">
          <el-input ref="emailInput" v-model="draft" type="email" inputmode="email" autocomplete="email" maxlength="254"
                    placeholder="name@example.com" aria-label="邮箱地址" :aria-invalid="!!draftError"
                    aria-describedby="email-binding-draft-error" :disabled="saving" />
          <el-button type="primary" native-type="submit" :loading="saving">保存</el-button>
          <el-button :disabled="saving" @click="closeEditor">取消</el-button>
        </div>
        <p id="email-binding-draft-error" class="email-binding-error" role="alert">{{ draftError }}</p>
      </form>

      <template v-if="state.email && !state.verified && !editing">
        <form v-if="state.verificationAvailable" class="email-binding-form" novalidate @submit.prevent="check">
          <div class="email-binding-row">
            <el-input ref="codeInput" v-model="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7"
                      placeholder="6 位验证码" aria-label="邮箱验证码" :aria-invalid="!!codeError"
                      aria-describedby="email-binding-code-error" :disabled="!state.codeSent || verifying" />
            <el-button native-type="submit" type="primary" plain :loading="verifying" :disabled="!state.codeSent || sending">验证</el-button>
            <el-button :loading="sending" :disabled="cooldown > 0 || verifying" @click="send">{{ sendLabel }}</el-button>
          </div>
          <p id="email-binding-code-error" class="email-binding-error" role="alert">{{ codeError }}</p>
        </form>
        <p class="email-binding-hint" aria-live="polite">
          <template v-if="!state.verificationAvailable">平台暂未开通邮件发送，邮箱暂时无法验证；绑定不受影响。</template>
          <template v-else-if="state.codeSent">验证码已发送至 {{ maskEmail(state.email) }}，15 分钟内有效。收不到请检查垃圾邮件。</template>
          <template v-else>验证是可选的，不验证也可以正常使用。</template>
        </p>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { deleteData, getData, postData } from '../api/client';
import { useEmailBinding, type EmailState } from '../composables/emailBinding';
import { maskEmail } from '../utils/emailAddress';

const {
  state, loadState, editing, draft, draftError, code, codeError, saving, sending, verifying, busy, cooldown,
  load, startEditing, cancelEditing, bind, unbind, sendCode, verify, dispose,
} = useEmailBinding({
  status: () => getData<EmailState>('/user/email'),
  bind: (email) => postData<EmailState>('/user/email', { email }),
  unbind: () => deleteData<EmailState>('/user/email'),
  sendCode: () => postData<EmailState>('/user/email/code', {}),
  verify: (value) => postData<EmailState>('/user/email/verify', { code: value }),
}, {
  success: (message) => ElMessage.success(message),
  error: (message) => ElMessage.error(message),
  confirm: (message, title, action) => ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' })
    .then(() => true, () => false),
});

const emailInput = ref<{ focus(): void }>();
const codeInput = ref<{ focus(): void }>();
const actions = ref<HTMLElement>();
const sendLabel = computed(() => cooldown.value > 0 ? `${cooldown.value} 秒后重发` : state.value.codeSent ? '重新发送' : '发送验证码');

async function openEditor() {
  startEditing();
  await nextTick();
  emailInput.value?.focus();
}

/** Keyboard users land back on the section's first button instead of the top of the page. */
async function focusActions() {
  await nextTick();
  actions.value?.querySelector('button')?.focus();
}

async function save() {
  if (await bind()) await focusActions();
}

async function closeEditor() {
  if (cancelEditing()) await focusActions();
}

async function remove() {
  await unbind();
  if (!state.value.email) await focusActions();
}

async function check() {
  await verify();
  if (state.value.verified) await focusActions();
}

async function send() {
  if (!await sendCode()) return;
  await nextTick();
  codeInput.value?.focus();
}

onMounted(load);
onBeforeUnmount(dispose);
</script>

<style scoped>
.email-binding { display: grid; gap: 10px; }
.email-binding-status { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; }
.email-binding-current { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; min-width: 0; }
.email-binding-current strong { overflow-wrap: anywhere; }
.email-binding-empty { color: var(--el-text-color-secondary); }
.email-binding-actions { display: flex; align-items: center; gap: 4px; }
.email-binding-form { display: grid; gap: 4px; }
.email-binding-row { display: flex; flex-wrap: wrap; gap: 8px; }
.email-binding-row .el-input { flex: 1 1 220px; }
.email-binding-row .el-button + .el-button { margin-left: 0; }
.email-binding-error { min-height: 0; margin: 0; color: var(--el-color-danger); font-size: 12px; line-height: 1.5; }
.email-binding-error:empty { display: none; }
.email-binding-hint { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.5; }
@media (min-width: 521px) {
  .email-binding-form:has(input[inputmode="numeric"]) .el-input { flex: 0 1 160px; }
}
</style>
