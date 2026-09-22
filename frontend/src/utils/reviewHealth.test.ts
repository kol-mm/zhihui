import { describe, expect, it } from 'vitest';
import { reviewCounters, reviewVerdict } from './reviewHealth';

describe('AI review health', () => {
  it('says so plainly when review is off', () => {
    const verdict = reviewVerdict({ enabled: false, requested: 0 });

    expect(verdict.tone).toBe('info');
    expect(verdict.headline).toBe('未开启');
  });

  it('warns when it is on but nothing has been asked of it', () => {
    // The failure that hid a broken feature twice: enabled, reachable, and never called.
    const verdict = reviewVerdict({ enabled: true, requested: 0, idle: true });

    expect(verdict.tone).toBe('warning');
    expect(verdict.headline).toContain('没有收到请求');
    expect(verdict.detail).toContain('AI 服务');
  });

  it('treats a model that always fails as the most serious state', () => {
    const verdict = reviewVerdict({ enabled: true, requested: 8, escalated: 8, model_failures: 8 });

    expect(verdict.tone).toBe('danger');
    expect(verdict.headline).toBe('模型始终失败');
    expect(verdict.detail).toContain('8');
  });

  it('flags partial failures without crying wolf', () => {
    const verdict = reviewVerdict({ enabled: true, requested: 10, approved: 6, escalated: 4, model_failures: 2 });

    expect(verdict.tone).toBe('warning');
    expect(verdict.headline).toBe('部分调用失败');
  });

  it('explains a platform running on local rules only', () => {
    const verdict = reviewVerdict({ enabled: true, requested: 5, escalated: 5, model_configured: false });

    expect(verdict.tone).toBe('info');
    expect(verdict.headline).toBe('仅本地规则');
  });

  it('reports a healthy reviewer with its numbers', () => {
    const verdict = reviewVerdict({
      enabled: true, model_configured: true, requested: 20,
      approved: 14, rejected: 3, escalated: 3, sampled: 2,
    });

    expect(verdict.tone).toBe('success');
    expect(verdict.detail).toContain('17');
    expect(verdict.detail).toContain('抽样 2');
  });

  it('copes with nothing at all', () => {
    expect(reviewVerdict(undefined).headline).toBe('未开启');
    expect(reviewVerdict(null).tone).toBe('info');
  });

  it('lists the counters in a fixed order and never shows rubbish', () => {
    const counters = reviewCounters({ requested: 4, approved: 2, model_failures: undefined, sampled: -1 });

    expect(counters.map(item => item.label)).toEqual(
      ['收到请求', '自动通过', '自动驳回', '转人工', '抽样复核', '调用失败']);
    expect(counters[0].value).toBe(4);
    expect(counters[4].value).toBe(0);
    expect(counters[5].value).toBe(0);
  });
});
