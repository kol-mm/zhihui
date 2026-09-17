import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useEmailBinding, type EmailApi, type EmailState } from './emailBinding';

const state = (overrides: Partial<EmailState> = {}): EmailState => ({
  email: null, verified: false, verificationAvailable: true, codeSent: false, resendAfterSeconds: 0, ...overrides,
});

function setup(initial: EmailState = state(), confirmed = true) {
  const api = {
    status: vi.fn<EmailApi['status']>().mockResolvedValue(initial),
    bind: vi.fn<EmailApi['bind']>(),
    unbind: vi.fn<EmailApi['unbind']>(),
    sendCode: vi.fn<EmailApi['sendCode']>(),
    verify: vi.fn<EmailApi['verify']>(),
  };
  const feedback = {
    success: vi.fn<(message: string) => void>(),
    error: vi.fn<(message: string) => void>(),
    confirm: vi.fn<(message: string, title: string, action: string) => Promise<boolean>>().mockResolvedValue(confirmed),
  };
  const binding = useEmailBinding(api, feedback);
  return { api, feedback, binding };
}

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { vi.useRealTimers(); });

describe('loading', () => {
  it('reports a failed load and can retry', async () => {
    const { api, binding } = setup();
    expect(binding.loadState.value).toBe('loading');
    api.status.mockRejectedValueOnce(new Error('network'));
    await binding.load();
    expect(binding.loadState.value).toBe('failed');
    await binding.load();
    expect(binding.loadState.value).toBe('ready');
    expect(binding.state.value.email).toBeNull();
  });

  it('restores a running cooldown after a reload', async () => {
    const { binding } = setup(state({ email: 'a@example.com', codeSent: true, resendAfterSeconds: 42 }));
    await binding.load();
    expect(binding.cooldown.value).toBe(42);
    binding.dispose();
  });
});

describe('binding', () => {
  it('shows address problems next to the field without calling the server', async () => {
    const { api, binding } = setup();
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'nope';
    expect(await binding.bind()).toBe(false);
    expect(binding.draftError.value).toBe('邮箱地址格式不正确');
    expect(api.bind).not.toHaveBeenCalled();
    expect(binding.editing.value).toBe(true);

    // Typing clears the message.
    binding.draft.value = 'nope@';
    await vi.waitFor(() => expect(binding.draftError.value).toBe(''));
  });

  it('sends the normalized address and closes the editor', async () => {
    const { api, feedback, binding } = setup();
    await binding.load();
    binding.startEditing();
    binding.draft.value = '  Reader@Example.COM ';
    api.bind.mockResolvedValue(state({ email: 'reader@example.com' }));
    await binding.bind();
    expect(api.bind).toHaveBeenCalledWith('reader@example.com');
    expect(binding.editing.value).toBe(false);
    expect(binding.state.value.email).toBe('reader@example.com');
    expect(feedback.success).toHaveBeenCalledWith('邮箱已绑定，可随时验证');
  });

  it('says only "bound" when the platform cannot send mail', async () => {
    const { api, feedback, binding } = setup(state({ verificationAvailable: false }));
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'reader@example.com';
    api.bind.mockResolvedValue(state({ email: 'reader@example.com', verificationAvailable: false }));
    await binding.bind();
    expect(feedback.success).toHaveBeenCalledWith('邮箱已绑定');
    expect(binding.canVerify.value).toBe(false);
  });

  it('closes without a request when the address is unchanged', async () => {
    const { api, binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    binding.startEditing();
    expect(binding.draft.value).toBe('reader@example.com');
    binding.draft.value = 'READER@example.com';
    expect(await binding.bind()).toBe(true);
    expect(api.bind).not.toHaveBeenCalled();
    expect(binding.editing.value).toBe(false);
  });

  it('asks before replacing a verified address', async () => {
    const { api, feedback, binding } = setup(state({ email: 'old@example.com', verified: true }), false);
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'new@example.com';
    await binding.bind();
    expect(feedback.confirm).toHaveBeenCalledOnce();
    expect(api.bind).not.toHaveBeenCalled();
    expect(binding.editing.value).toBe(true);

    feedback.confirm.mockResolvedValue(true);
    api.bind.mockResolvedValue(state({ email: 'new@example.com' }));
    await binding.bind();
    expect(api.bind).toHaveBeenCalledWith('new@example.com');
    expect(binding.state.value.verified).toBe(false);
  });

  it('does not ask when the current address is unverified', async () => {
    const { api, feedback, binding } = setup(state({ email: 'old@example.com' }));
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'new@example.com';
    api.bind.mockResolvedValue(state({ email: 'new@example.com' }));
    await binding.bind();
    expect(feedback.confirm).not.toHaveBeenCalled();
  });

  it('keeps the editor open with the server message when binding fails', async () => {
    const { api, binding } = setup();
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'reader@example.com';
    api.bind.mockRejectedValue(new Error('邮箱地址格式不正确'));
    await binding.bind();
    expect(binding.editing.value).toBe(true);
    expect(binding.draftError.value).toBe('邮箱地址格式不正确');
    expect(binding.saving.value).toBe(false);
  });

  it('ignores a second submit while the first is running', async () => {
    const { api, binding } = setup();
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'reader@example.com';
    let finish: (value: EmailState) => void = () => {};
    api.bind.mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const first = binding.bind();
    await binding.bind();
    finish(state({ email: 'reader@example.com' }));
    await first;
    expect(api.bind).toHaveBeenCalledOnce();
  });

  it('cancel keeps the saved address', async () => {
    const { binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    binding.startEditing();
    binding.draft.value = 'other@example.com';
    expect(binding.cancelEditing()).toBe(true);
    expect(binding.editing.value).toBe(false);
    expect(binding.state.value.email).toBe('reader@example.com');
  });
});

describe('unbinding', () => {
  it('only unbinds after confirmation', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com', codeSent: true }), false);
    await binding.load();
    binding.code.value = '123';
    await binding.unbind();
    expect(api.unbind).not.toHaveBeenCalled();

    feedback.confirm.mockResolvedValue(true);
    api.unbind.mockResolvedValue(state());
    await binding.unbind();
    expect(binding.state.value.email).toBeNull();
    expect(binding.code.value).toBe('');
    expect(feedback.success).toHaveBeenCalledWith('邮箱已解绑');
  });

  it('shows a toast when unbinding fails', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    api.unbind.mockRejectedValue(new Error('操作失败，请稍后重试'));
    await binding.unbind();
    expect(feedback.error).toHaveBeenCalledWith('操作失败，请稍后重试');
    expect(binding.state.value.email).toBe('reader@example.com');
  });
});

describe('sending a code', () => {
  it('counts the cooldown down to zero and then allows a resend', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    api.sendCode.mockResolvedValue(state({ email: 'reader@example.com', codeSent: true, resendAfterSeconds: 60 }));
    expect(await binding.sendCode()).toBe(true);
    expect(feedback.success).toHaveBeenCalledWith('验证码已发送');
    expect(binding.cooldown.value).toBe(60);

    // Refused locally while the cooldown runs.
    expect(await binding.sendCode()).toBe(false);
    expect(api.sendCode).toHaveBeenCalledOnce();

    vi.advanceTimersByTime(25_000);
    expect(binding.cooldown.value).toBe(35);
    vi.advanceTimersByTime(35_000);
    expect(binding.cooldown.value).toBe(0);
    expect(vi.getTimerCount()).toBe(0);
    expect(await binding.sendCode()).toBe(true);
  });

  it('follows the clock rather than the number of ticks', async () => {
    const { api, binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    api.sendCode.mockResolvedValue(state({ email: 'reader@example.com', codeSent: true, resendAfterSeconds: 60 }));
    await binding.sendCode();
    // A background tab may run the timer rarely; the next tick still shows the real remaining time.
    vi.setSystemTime(Date.now() + 50_000);
    vi.advanceTimersByTime(1_000);
    expect(binding.cooldown.value).toBe(9);
    binding.dispose();
  });

  it('shows the refusal and picks up the server cooldown', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    api.sendCode.mockRejectedValue(new Error('请 37 秒后再获取验证码'));
    api.status.mockResolvedValue(state({ email: 'reader@example.com', codeSent: true, resendAfterSeconds: 37 }));
    expect(await binding.sendCode()).toBe(false);
    expect(feedback.error).toHaveBeenCalledWith('请 37 秒后再获取验证码');
    expect(binding.cooldown.value).toBe(37);
    expect(binding.sending.value).toBe(false);
    binding.dispose();
  });

  it('does not send when verification is not possible', async () => {
    for (const current of [state(), state({ email: 'a@example.com', verified: true }), state({ email: 'a@example.com', verificationAvailable: false })]) {
      const { api, binding } = setup(current);
      await binding.load();
      expect(await binding.sendCode()).toBe(false);
      expect(api.sendCode).not.toHaveBeenCalled();
    }
  });

  it('does not repeat the notice for an ended session', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com' }));
    await binding.load();
    const expired = await captureSessionExpired();
    expect((await import('../api/client')).isSessionExpiredError(expired)).toBe(true);
    api.sendCode.mockRejectedValue(expired);
    await binding.sendCode();
    expect(feedback.error).not.toHaveBeenCalled();
  });
});

describe('verifying', () => {
  it('checks the code format in place and strips spaces', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com', codeSent: true }));
    await binding.load();
    binding.code.value = '12a456';
    await binding.verify();
    expect(binding.codeError.value).toBe('请输入 6 位数字验证码');
    expect(api.verify).not.toHaveBeenCalled();

    binding.code.value = '123 456';
    api.verify.mockResolvedValue(state({ email: 'reader@example.com', verified: true }));
    await binding.verify();
    expect(api.verify).toHaveBeenCalledWith('123456');
    expect(binding.state.value.verified).toBe(true);
    expect(binding.code.value).toBe('');
    expect(feedback.success).toHaveBeenCalledWith('邮箱验证成功');
  });

  it('shows a wrong code next to the field and resyncs, so a cancelled code asks for a new one', async () => {
    const { api, feedback, binding } = setup(state({ email: 'reader@example.com', codeSent: true }));
    await binding.load();
    binding.code.value = '000000';
    api.verify.mockRejectedValue(new Error('验证码错误或已过期'));
    api.status.mockResolvedValue(state({ email: 'reader@example.com', codeSent: false }));
    await binding.verify();
    expect(binding.codeError.value).toBe('验证码错误或已过期，请重新获取验证码');
    expect(feedback.error).not.toHaveBeenCalled();
    expect(binding.state.value.codeSent).toBe(false);
    expect(binding.code.value).toBe('000000');
    expect(binding.verifying.value).toBe(false);
  });

  it('keeps the plain message while the code is still usable', async () => {
    const { api, binding } = setup(state({ email: 'reader@example.com', codeSent: true }));
    await binding.load();
    binding.code.value = '000000';
    api.verify.mockRejectedValue(new Error('验证码错误或已过期'));
    await binding.verify();
    expect(binding.codeError.value).toBe('验证码错误或已过期');
  });

  it('explains when another account already verified the address', async () => {
    const { api, binding } = setup(state({ email: 'shared@example.com', codeSent: true }));
    await binding.load();
    binding.code.value = '123456';
    api.verify.mockRejectedValue(new Error('该邮箱已被其他账号验证，请更换邮箱'));
    await binding.verify();
    expect(binding.codeError.value).toBe('该邮箱已被其他账号验证，请更换邮箱');
  });

  it('keeps the page usable when the resync fails too', async () => {
    const { api, binding } = setup(state({ email: 'reader@example.com', codeSent: true }));
    await binding.load();
    binding.code.value = '000000';
    api.verify.mockRejectedValue(new Error('验证码错误或已过期'));
    api.status.mockRejectedValue(new Error('network'));
    await binding.verify();
    expect(binding.state.value.codeSent).toBe(true);
    expect(binding.loadState.value).toBe('ready');
  });
});

/** The client's session-expired error, produced the way the gateway triggers it. */
async function captureSessionExpired(): Promise<unknown> {
  const client = await import('../api/client');
  const original = client.api.defaults.adapter;
  client.api.defaults.adapter = async (config) => {
    throw Object.assign(new Error('Request failed with status code 401'), {
      isAxiosError: true, config, response: { status: 401, statusText: 'Unauthorized', headers: {}, config, data: { code: 401, message: 'valid user authorization is required' } },
    });
  };
  try {
    await client.postData('/user/email/code', {});
    return null;
  } catch (error) {
    return error;
  } finally {
    client.api.defaults.adapter = original;
  }
}
