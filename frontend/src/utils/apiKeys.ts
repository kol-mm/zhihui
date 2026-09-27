/**
 * The rules behind the 接口密钥 screen, kept out of the component so they can be read and tested on their own.
 * The server checks every one of them again; these exist so the form says what is wrong before it is sent.
 */

export type ApiKeyScope = { name: string; label: string; write: boolean };

export type ApiKeyPerson = { id: number; username: string; nickname: string; status: string; role: string } | null;

export type ApiKeyRecord = {
  id: number;
  name: string;
  prefix: string;
  scopes: string[];
  status: string;
  actingUser: ApiKeyPerson;
  createdBy: ApiKeyPerson;
  revokedBy: ApiKeyPerson;
  createdAt: string;
  updatedAt: string;
  expiresAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
};

export type ApiKeyForm = {
  name: string;
  scopes: string[];
  actingUserId: number | null;
  expiry: string;
  customDate: string;
};

export const NAME_MAX = 64;

export const EXPIRY_CHOICES = [
  { value: 'never', label: '永久有效' },
  { value: '30', label: '30 天' },
  { value: '90', label: '90 天' },
  { value: '365', label: '一年' },
  { value: 'custom', label: '指定日期' },
];

export type KeyFilter = 'live' | 'revoked' | 'all';

export function emptyForm(): ApiKeyForm {
  return { name: '', scopes: [], actingUserId: null, expiry: 'never', customDate: '' };
}

export function keyStatusLabel(status: string): string {
  return ({ ACTIVE: '有效', EXPIRED: '已过期', REVOKED: '已撤销' } as Record<string, string>)[status] || status;
}

export function keyStatusType(status: string): 'success' | 'warning' | 'info' {
  if (status === 'ACTIVE') return 'success';
  return status === 'EXPIRED' ? 'warning' : 'info';
}

export function scopeLabel(name: string, catalogue: ApiKeyScope[]): string {
  return catalogue.find(scope => scope.name === name)?.label || name;
}

/** A write scope changes content, so the key has to act as the account that content belongs to. */
export function needsActingAccount(scopes: string[], catalogue: ApiKeyScope[]): boolean {
  return catalogue.some(scope => scope.write && scopes.includes(scope.name));
}

export function localDate(day: Date): string {
  return `${day.getFullYear()}-${String(day.getMonth() + 1).padStart(2, '0')}-${String(day.getDate()).padStart(2, '0')}`;
}

/** The last day the key is valid, as the server wants it, or '' for a key that does not expire. */
export function expiresOn(choice: string, customDate: string, today: Date): string {
  if (choice === 'never') return '';
  if (choice === 'custom') return customDate;
  const days = Number(choice);
  if (!Number.isInteger(days) || days <= 0) return '';
  const last = new Date(today.getFullYear(), today.getMonth(), today.getDate() + days);
  return localDate(last);
}

/** What is wrong with the form, in the words the page shows; null when it can be sent. */
export function formProblem(form: ApiKeyForm, catalogue: ApiKeyScope[], today: Date): string | null {
  const name = form.name.trim();
  if (!name) return '请填写接口密钥的名称';
  if (name.length > NAME_MAX) return `名称不能超过 ${NAME_MAX} 个字符`;
  if (!form.scopes.length) return '请至少选择一项权限';
  if (needsActingAccount(form.scopes, catalogue) && !form.actingUserId) {
    return '带写权限的接口密钥必须指定一个代为操作的账号，写入的内容归属该账号';
  }
  if (form.expiry === 'custom') {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.customDate)) return '请选择有效期';
    if (form.customDate < localDate(today)) return '有效期不能早于今天';
  }
  return null;
}

export function keyRequest(form: ApiKeyForm, today: Date): Record<string, unknown> {
  return {
    name: form.name.trim(),
    scopes: form.scopes,
    actingUserId: form.actingUserId,
    expiresOn: expiresOn(form.expiry, form.customDate, today),
  };
}

/** The form for editing a key, as it stands now. */
export function formFor(key: ApiKeyRecord): ApiKeyForm {
  return {
    name: key.name,
    scopes: [...key.scopes],
    actingUserId: key.actingUser?.id ?? null,
    expiry: key.expiresAt ? 'custom' : 'never',
    customDate: key.expiresAt ? key.expiresAt.slice(0, 10) : '',
  };
}

/** Revoked keys are kept for their history, but out of the way unless asked for. */
export function visibleKeys(keys: ApiKeyRecord[], filter: KeyFilter): ApiKeyRecord[] {
  if (filter === 'all') return keys;
  return keys.filter(key => (filter === 'revoked') === (key.status === 'REVOKED'));
}

export function personLabel(person: ApiKeyPerson): string {
  if (!person) return '—';
  return person.nickname ? `${person.nickname}（@${person.username}）` : `@${person.username}`;
}

/** A first request to try with a new key, reading something its scopes allow. */
export function curlExample(origin: string, secret: string, scopes: string[]): string {
  const path = scopes.includes('knowledge:read') ? '/api/knowledge/list'
    : scopes.includes('community:read') ? '/api/square/feed' : '/api/knowledge/list';
  return `curl -H "Authorization: Bearer ${secret}" ${origin}${path}`;
}
