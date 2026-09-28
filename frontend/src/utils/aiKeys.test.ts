import { describe, expect, it } from 'vitest';
import {
  activateByDefault,
  addProblem,
  deletionConsequence,
  editProblem,
  inUseText,
  inUseTone,
  providerLabel,
  secretProblem,
  testLabel,
  type ProviderInfo,
  type ProviderKey,
} from './aiKeys';

const CLAUDE_KEY = 'sk-ant-api03-' + 'A'.repeat(80);

function key(overrides: Partial<ProviderKey> = {}): ProviderKey {
  return {
    id: 1, provider: 'anthropic', name: '主密钥', hint: 'sk-ant-api03-…AAAA', active: true, createdBy: 2,
    createdAt: '2026-09-27T00:00:00Z', updatedAt: '2026-09-27T00:00:00Z', lastTestedAt: null, lastTestOk: null,
    lastTestMessage: null, ...overrides,
  };
}

function info(source: ProviderInfo['inUse']['source'], overrides: Partial<ProviderInfo['inUse']> = {}): ProviderInfo {
  return {
    provider: 'anthropic', label: 'Anthropic Claude', environmentVariable: 'ANTHROPIC_API_KEY',
    inUse: { source, keyId: null, name: null, hint: null, ...overrides },
  };
}

describe('which key a provider is using', () => {
  it('says where the key comes from, never the key itself', () => {
    expect(inUseText(info('stored', { name: '主密钥', hint: 'sk-ant-api03-…Ab12', keyId: 1 })))
      .toBe('平台中启用的「主密钥」（sk-ant-api03-…Ab12）');
    expect(inUseText(info('environment', { name: 'ANTHROPIC_API_KEY', hint: 'sk-ant-api03-…Zz99' })))
      .toBe('环境变量 ANTHROPIC_API_KEY（sk-ant-api03-…Zz99）');
    expect(inUseText(info('undecryptable', { name: '旧密钥' }))).toContain('无法解密');
    expect(inUseText(info('none'))).toBe('未配置密钥');
  });

  it('marks a key that cannot be read as the thing to fix first', () => {
    expect(inUseTone('stored')).toBe('success');
    expect(inUseTone('environment')).toBe('info');
    expect(inUseTone('undecryptable')).toBe('danger');
    expect(inUseTone('none')).toBe('warning');
  });
});

describe('what a key must look like', () => {
  it('accepts an Anthropic API key and turns away an admin key', () => {
    expect(secretProblem('anthropic', CLAUDE_KEY)).toBeNull();
    expect(secretProblem('anthropic', 'sk-ant-admin01-' + 'a'.repeat(60))).toContain('管理密钥');
    expect(secretProblem('anthropic', 'sk-' + 'a'.repeat(60))).toContain('sk-ant-');
    expect(secretProblem('anthropic', 'sk-ant-api03-short')).toContain('长度');
  });

  it('refuses whitespace, which is always a copying mistake', () => {
    expect(secretProblem('anthropic', CLAUDE_KEY + '\n')).toBeNull();   // trailing newline trimmed
    expect(secretProblem('anthropic', 'sk-ant-api03-aa aa' + 'a'.repeat(40))).toContain('空格');
  });

  it('is less particular about OpenAI-compatible services, which use many formats', () => {
    expect(secretProblem('openai-compatible', 'local-token-123')).toBeNull();
    expect(secretProblem('openai-compatible', 'short')).toContain('过短');
    expect(secretProblem('openai-compatible', '   ')).toBe('请填写密钥');
  });

  it('checks the name first, then the key', () => {
    expect(addProblem({ provider: 'anthropic', name: ' ', secret: 'x', activate: true })).toBe('请填写密钥名称');
    expect(addProblem({ provider: 'anthropic', name: 'x'.repeat(65), secret: CLAUDE_KEY, activate: true })).toContain('64');
    expect(addProblem({ provider: 'anthropic', name: '主密钥', secret: CLAUDE_KEY, activate: true })).toBeNull();
  });

  it('lets an edit keep the stored secret by leaving it empty', () => {
    expect(editProblem('anthropic', '改名', '')).toBeNull();
    expect(editProblem('anthropic', '改名', 'not-a-claude-key-at-all-but-long-enough')).toContain('sk-ant-');
  });
});

describe('switching keys on', () => {
  it('switches the first key for a provider on, and leaves later ones waiting', () => {
    expect(activateByDefault('anthropic', [])).toBe(true);
    expect(activateByDefault('anthropic', [key({ provider: 'openai-compatible' })])).toBe(true);
    expect(activateByDefault('anthropic', [key()])).toBe(false);
    expect(activateByDefault('anthropic', [key({ active: false })])).toBe(true);
  });
});

describe('before deleting', () => {
  it('says what happens to the calls a key is serving', () => {
    expect(deletionConsequence(key({ active: false }), info('stored'))).toContain('不影响');
    const warning = deletionConsequence(key(), info('stored'));
    expect(warning).toContain('ANTHROPIC_API_KEY');
    expect(warning).toContain('内容审核转为人工');
  });
});

describe('labels', () => {
  it('names providers and test results', () => {
    expect(providerLabel('anthropic')).toBe('Anthropic Claude');
    expect(providerLabel('local')).toBe('本地知识检索');
    expect(providerLabel('other')).toBe('other');
    expect(testLabel({ lastTestOk: null, lastTestedAt: null })).toBe('未测试');
    expect(testLabel({ lastTestOk: true, lastTestedAt: '2026-09-27T00:00:00Z' })).toBe('测试通过');
    expect(testLabel({ lastTestOk: false, lastTestedAt: '2026-09-27T00:00:00Z' })).toBe('测试未通过');
  });
});
