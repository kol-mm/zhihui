import { afterEach, describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios';
import { api, getData, postData, putData, REVIEWED_WRITE_TIMEOUT_MS, TIMEOUT_MESSAGE, toUserMessage } from './client';
import { isDismissal, messageForUnhandled } from '../utils/unhandledErrors';

const realAdapter = api.defaults.adapter;
afterEach(() => {
  api.defaults.adapter = realAdapter;
});

/** Answers every request with `respond`, recording the config each one was sent with. */
function answerWith(respond: (config: InternalAxiosRequestConfig) => Promise<AxiosResponse>) {
  const sent: InternalAxiosRequestConfig[] = [];
  api.defaults.adapter = async config => {
    sent.push(config);
    return respond(config);
  };
  return sent;
}

function failWith(status: number, data: unknown) {
  return answerWith(async config => {
    const response = { status, statusText: '', headers: {}, config, data } as AxiosResponse;
    throw new AxiosError('failed', AxiosError.ERR_BAD_REQUEST, config, null, response);
  });
}

async function messageOf(request: Promise<unknown>): Promise<string> {
  try {
    await request;
  } catch (error) {
    return toUserMessage(error);
  }
  throw new Error('the request succeeded');
}

describe('errors from ai-service reach the page', () => {
  it('shows a refusal ai-service wrote in Chinese, which used to become 操作失败', async () => {
    failWith(403, { detail: 'AI 问答功能当前未开放' });
    expect(await messageOf(postData('/ai/chat', {}))).toBe('AI 问答功能当前未开放');
  });

  it('translates one it wrote in English', async () => {
    failWith(404, { detail: 'AI session not found' });
    expect(await messageOf(getData('/ai/session/9'))).toBe('AI 会话不存在或已被删除');
  });

  it('explains a validation failure instead of a generic error', async () => {
    failWith(422, { detail: [{ loc: ['body', 'question'], type: 'string_too_long', ctx: { max_length: 4000 } }] });
    expect(await messageOf(postData('/ai/chat', {}))).toBe('问题不能超过 4000 个字符');
  });

  it('still reads the Java services\' message', async () => {
    failWith(400, { code: 500, message: '帖子正文不能为空' });
    expect(await messageOf(postData('/post/create', {}))).toBe('帖子正文不能为空');
  });
});

describe('timeouts', () => {
  it('says a timed-out request may have gone through, not that the service is unreachable', async () => {
    answerWith(async config => {
      throw new AxiosError('timeout of 8000ms exceeded', AxiosError.ECONNABORTED, config);
    });
    expect(await messageOf(postData('/post/create', {}))).toBe(TIMEOUT_MESSAGE);
  });

  it('gives a write that may wait for AI review longer than the services wait for it', async () => {
    const sent = answerWith(async config => ({ status: 200, statusText: '', headers: {}, config, data: { code: 0, data: {} } }));

    await postData('/post/create', {}, { timeout: REVIEWED_WRITE_TIMEOUT_MS });
    await putData('/post/update', {}, { timeout: REVIEWED_WRITE_TIMEOUT_MS });
    await postData('/user/profile', {});

    // ai-service allows the model 8 s by default and the Java services wait 2 s more; the page must outlast both,
    // with room for an operator to raise the model's allowance.
    expect(REVIEWED_WRITE_TIMEOUT_MS).toBeGreaterThanOrEqual(8_000 + 2_000 + 15_000);
    expect(sent.map(config => config.timeout)).toEqual([REVIEWED_WRITE_TIMEOUT_MS, REVIEWED_WRITE_TIMEOUT_MS, 8000]);
  });
});

describe('errors no handler caught', () => {
  it('are shown in the same words a handler would have used', async () => {
    failWith(400, { code: 500, message: '帖子标题不能为空' });
    let caught: unknown;
    try {
      await postData('/post/draft/publish', { id: 1 });
    } catch (error) {
      caught = error;
    }
    expect(messageForUnhandled(caught)).toBe('帖子标题不能为空');
    expect(messageForUnhandled(new TypeError('x is undefined'))).toBe('操作失败，请稍后重试');
  });

  it('say nothing when the visitor closed a confirmation', () => {
    expect(isDismissal('cancel')).toBe(true);
    expect(isDismissal('close')).toBe(true);
    expect(messageForUnhandled('cancel')).toBeNull();
    expect(messageForUnhandled('close')).toBeNull();
  });

  it('say nothing about an ended session, which the page announces itself', async () => {
    failWith(401, { message: 'valid user authorization is required' });
    let caught: unknown;
    try {
      await getData('/user/info');
    } catch (error) {
      caught = error;
    }
    expect(messageForUnhandled(caught)).toBeNull();
  });
});
