import { computed, ref, watch } from 'vue';
import { isSessionExpiredError, toUserMessage } from '../api/client';
import { emailProblem, normalizeCode, normalizeEmail } from '../utils/emailAddress';

export type EmailState = {
  email: string | null;
  verified: boolean;
  verifiedAt?: string | null;
  verificationAvailable: boolean;
  codeSent: boolean;
  resendAfterSeconds: number;
};

export type EmailApi = {
  status(): Promise<EmailState>;
  bind(email: string): Promise<EmailState>;
  unbind(): Promise<EmailState>;
  sendCode(): Promise<EmailState>;
  verify(code: string): Promise<EmailState>;
};

export type EmailFeedback = {
  success(message: string): void;
  error(message: string): void;
  /** Resolves false when the member backs out. */
  confirm(message: string, title: string, action: string): Promise<boolean>;
};

const EMPTY: EmailState = { email: null, verified: false, verificationAvailable: false, codeSent: false, resendAfterSeconds: 0 };

/**
 * State and actions behind the profile's email section. Mistakes the member can fix in place (a malformed address,
 * a wrong code) are shown next to the field; failures of the request itself go to a toast.
 */
export function useEmailBinding(api: EmailApi, feedback: EmailFeedback) {
  const state = ref<EmailState>({ ...EMPTY });
  const loadState = ref<'loading' | 'ready' | 'failed'>('loading');
  const editing = ref(false);
  const draft = ref('');
  const draftError = ref('');
  const code = ref('');
  const codeError = ref('');
  const saving = ref(false);
  const sending = ref(false);
  const verifying = ref(false);

  // The cooldown counts toward a deadline, so a throttled background tab still shows the right number.
  const resendAt = ref(0);
  const now = ref(Date.now());
  let ticker: ReturnType<typeof setInterval> | undefined;
  const cooldown = computed(() => Math.max(0, Math.ceil((resendAt.value - now.value) / 1000)));

  const busy = computed(() => saving.value || sending.value || verifying.value);
  const canVerify = computed(() => !!state.value.email && !state.value.verified && state.value.verificationAvailable);

  // Synchronous, so an edit clears the old message at once and never a message set later in the same tick.
  watch(draft, () => { draftError.value = ''; }, { flush: 'sync' });
  watch(code, () => { codeError.value = ''; }, { flush: 'sync' });

  /** The page already tells the member once when the session ends, so that failure is not repeated here. */
  function report(error: unknown) {
    if (!isSessionExpiredError(error)) feedback.error(toUserMessage(error));
  }

  function stopTicker() {
    if (ticker) clearInterval(ticker);
    ticker = undefined;
  }

  function apply(next: EmailState) {
    state.value = { ...EMPTY, ...next };
    now.value = Date.now();
    resendAt.value = now.value + Math.max(0, next.resendAfterSeconds || 0) * 1000;
    stopTicker();
    if (cooldown.value > 0) {
      ticker = setInterval(() => {
        now.value = Date.now();
        if (cooldown.value <= 0) stopTicker();
      }, 1000);
    }
  }

  async function load() {
    loadState.value = 'loading';
    try {
      apply(await api.status());
      loadState.value = 'ready';
    } catch {
      loadState.value = 'failed';
    }
  }

  /** Brings the page back in line with the server after a refused request; stays quiet if that fails too. */
  async function resync() {
    try { apply(await api.status()); } catch { /* the toast for the original failure is enough */ }
  }

  function startEditing() {
    draft.value = state.value.email || '';
    draftError.value = '';
    editing.value = true;
  }

  function cancelEditing(): boolean {
    if (saving.value) return false;
    editing.value = false;
    draftError.value = '';
    return true;
  }

  /** True when the editor closed, so the page can put focus back on the section. */
  async function bind(): Promise<boolean> {
    if (busy.value) return false;
    const problem = emailProblem(draft.value);
    if (problem) { draftError.value = problem; return false; }
    const email = normalizeEmail(draft.value);
    if (email === state.value.email) { editing.value = false; return true; }
    if (state.value.verified
        && !await feedback.confirm(`更换后，新邮箱 ${email} 需要重新验证，原邮箱的验证状态将失效。`, '更换邮箱', '更换')) {
      return false;
    }
    saving.value = true;
    try {
      apply(await api.bind(email));
      editing.value = false;
      code.value = '';
      codeError.value = '';
      feedback.success(state.value.verificationAvailable ? '邮箱已绑定，可随时验证' : '邮箱已绑定');
      return true;
    } catch (error) {
      draftError.value = toUserMessage(error);
      return false;
    } finally {
      saving.value = false;
    }
  }

  async function unbind() {
    if (busy.value || !state.value.email) return;
    if (!await feedback.confirm(`解绑后账号将不再关联 ${state.value.email}。`, '解绑邮箱', '解绑')) return;
    saving.value = true;
    try {
      apply(await api.unbind());
      editing.value = false;
      code.value = '';
      codeError.value = '';
      feedback.success('邮箱已解绑');
    } catch (error) {
      report(error);
    } finally {
      saving.value = false;
    }
  }

  /** True when a code went out, so the page can move focus to the code field. */
  async function sendCode(): Promise<boolean> {
    if (busy.value || cooldown.value > 0 || !canVerify.value) return false;
    sending.value = true;
    try {
      apply(await api.sendCode());
      codeError.value = '';
      feedback.success('验证码已发送');
      return true;
    } catch (error) {
      report(error);
      await resync();
      return false;
    } finally {
      sending.value = false;
    }
  }

  async function verify() {
    if (busy.value) return;
    const value = normalizeCode(code.value);
    if (!/^\d{6}$/.test(value)) { codeError.value = '请输入 6 位数字验证码'; return; }
    verifying.value = true;
    try {
      apply(await api.verify(value));
      code.value = '';
      codeError.value = '';
      feedback.success('邮箱验证成功');
    } catch (error) {
      const message = toUserMessage(error);
      // Too many mistakes cancel the code on the server; the page should then ask for a new one.
      await resync();
      codeError.value = state.value.codeSent ? message : `${message}，请重新获取验证码`;
    } finally {
      verifying.value = false;
    }
  }

  return {
    state, loadState, editing, draft, draftError, code, codeError, saving, sending, verifying, busy, cooldown, canVerify,
    load, startEditing, cancelEditing, bind, unbind, sendCode, verify, dispose: stopTicker,
  };
}
