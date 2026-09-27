import { describe, expect, it } from 'vitest';
import {
  curlExample,
  emptyForm,
  expiresOn,
  formFor,
  formProblem,
  keyRequest,
  keyStatusLabel,
  keyStatusType,
  needsActingAccount,
  personLabel,
  visibleKeys,
  type ApiKeyRecord,
  type ApiKeyScope,
} from './apiKeys';

const CATALOGUE: ApiKeyScope[] = [
  { name: 'knowledge:read', label: '读取知识库', write: false },
  { name: 'knowledge:write', label: '上传与修改知识', write: true },
  { name: 'community:read', label: '读取社区内容', write: false },
  { name: 'community:write', label: '发帖与评论', write: true },
];
const TODAY = new Date(2026, 8, 27);   // 27 September 2026, local time

function key(overrides: Partial<ApiKeyRecord> = {}): ApiKeyRecord {
  return {
    id: 1, name: 'sync', prefix: 'zk_abcdefgh', scopes: ['knowledge:read'], status: 'ACTIVE',
    actingUser: null, createdBy: null, revokedBy: null, createdAt: '2026-09-01T00:00:00', updatedAt: '2026-09-01T00:00:00',
    expiresAt: null, lastUsedAt: null, revokedAt: null, ...overrides,
  };
}

describe('the status of a key', () => {
  it('is named and coloured', () => {
    expect(keyStatusLabel('ACTIVE')).toBe('有效');
    expect(keyStatusLabel('EXPIRED')).toBe('已过期');
    expect(keyStatusLabel('REVOKED')).toBe('已撤销');
    expect(keyStatusType('ACTIVE')).toBe('success');
    expect(keyStatusType('EXPIRED')).toBe('warning');
    expect(keyStatusType('REVOKED')).toBe('info');
  });
});

describe('when a key needs an account to act for', () => {
  it('is whenever it can write', () => {
    expect(needsActingAccount(['knowledge:read'], CATALOGUE)).toBe(false);
    expect(needsActingAccount(['knowledge:read', 'community:read'], CATALOGUE)).toBe(false);
    expect(needsActingAccount(['community:write'], CATALOGUE)).toBe(true);
    expect(needsActingAccount(['knowledge:read', 'knowledge:write'], CATALOGUE)).toBe(true);
  });
});

describe('the last day a key is valid', () => {
  it('counts days from today in the reader’s own calendar', () => {
    expect(expiresOn('30', '', TODAY)).toBe('2026-10-27');
    expect(expiresOn('90', '', TODAY)).toBe('2026-12-26');
    expect(expiresOn('365', '', TODAY)).toBe('2027-09-27');
  });

  it('is empty for a key that does not expire, and the chosen date when one is picked', () => {
    expect(expiresOn('never', '2026-12-31', TODAY)).toBe('');
    expect(expiresOn('custom', '2026-12-31', TODAY)).toBe('2026-12-31');
    expect(expiresOn('nonsense', '', TODAY)).toBe('');
  });
});

describe('what is wrong with the form', () => {
  const valid = { ...emptyForm(), name: 'sync', scopes: ['knowledge:read'] };

  it('accepts a complete read-only key', () => {
    expect(formProblem(valid, CATALOGUE, TODAY)).toBeNull();
  });

  it('asks for a name and at least one scope', () => {
    expect(formProblem({ ...valid, name: '   ' }, CATALOGUE, TODAY)).toBe('请填写接口密钥的名称');
    expect(formProblem({ ...valid, name: 'x'.repeat(65) }, CATALOGUE, TODAY)).toBe('名称不能超过 64 个字符');
    expect(formProblem({ ...valid, scopes: [] }, CATALOGUE, TODAY)).toBe('请至少选择一项权限');
  });

  it('asks for an account when the key can write, in the server’s words', () => {
    const writing = { ...valid, scopes: ['community:write'] };
    expect(formProblem(writing, CATALOGUE, TODAY)).toBe('带写权限的接口密钥必须指定一个代为操作的账号，写入的内容归属该账号');
    expect(formProblem({ ...writing, actingUserId: 1 }, CATALOGUE, TODAY)).toBeNull();
  });

  it('checks a picked date: present, and not in the past — today is fine', () => {
    expect(formProblem({ ...valid, expiry: 'custom', customDate: '' }, CATALOGUE, TODAY)).toBe('请选择有效期');
    expect(formProblem({ ...valid, expiry: 'custom', customDate: '2026-09-26' }, CATALOGUE, TODAY)).toBe('有效期不能早于今天');
    expect(formProblem({ ...valid, expiry: 'custom', customDate: '2026-09-27' }, CATALOGUE, TODAY)).toBeNull();
  });
});

describe('what the form sends', () => {
  it('trims the name and turns the expiry choice into a date', () => {
    const request = keyRequest({ name: '  报表同步 ', scopes: ['knowledge:read'], actingUserId: null, expiry: '30', customDate: '' }, TODAY);
    expect(request).toEqual({ name: '报表同步', scopes: ['knowledge:read'], actingUserId: null, expiresOn: '2026-10-27' });
  });
});

describe('editing a key', () => {
  it('starts from how the key stands now', () => {
    const form = formFor(key({ scopes: ['community:write'], actingUser: { id: 5, username: 'bot', nickname: '机器人', status: 'ACTIVE', role: 'USER' },
      expiresAt: '2026-12-31T23:59:59' }));
    expect(form).toEqual({ name: 'sync', scopes: ['community:write'], actingUserId: 5, expiry: 'custom', customDate: '2026-12-31' });
    expect(formFor(key()).expiry).toBe('never');
  });

  it('does not share the scope list with the record it came from', () => {
    const record = key();
    formFor(record).scopes.push('community:read');
    expect(record.scopes).toEqual(['knowledge:read']);
  });
});

describe('which keys are shown', () => {
  const keys = [key({ id: 1, status: 'ACTIVE' }), key({ id: 2, status: 'EXPIRED' }), key({ id: 3, status: 'REVOKED' })];

  it('keeps revoked keys out of the way unless asked for', () => {
    expect(visibleKeys(keys, 'live').map(k => k.id)).toEqual([1, 2]);
    expect(visibleKeys(keys, 'revoked').map(k => k.id)).toEqual([3]);
    expect(visibleKeys(keys, 'all').map(k => k.id)).toEqual([1, 2, 3]);
  });
});

describe('describing a key', () => {
  it('names the account it acts for', () => {
    expect(personLabel(null)).toBe('—');
    expect(personLabel({ id: 1, username: 'demo', nickname: 'Demo User', status: 'ACTIVE', role: 'USER' })).toBe('Demo User（@demo）');
    expect(personLabel({ id: 1, username: 'demo', nickname: '', status: 'ACTIVE', role: 'USER' })).toBe('@demo');
  });

  it('offers a first request the key is allowed to make', () => {
    expect(curlExample('https://zh.example', 'zk_x', ['knowledge:read'])).toBe(
      'curl -H "Authorization: Bearer zk_x" https://zh.example/api/knowledge/list');
    expect(curlExample('https://zh.example', 'zk_x', ['community:read'])).toContain('/api/square/feed');
  });
});
