/**
 * The rules behind the 模型密钥 screen. ai-service checks every one again; these let the form say what is wrong
 * before a key is sent anywhere.
 */

export type ProviderKey = {
  id: number;
  provider: string;
  name: string;
  hint: string;
  active: boolean;
  createdBy: number | null;
  createdAt: string;
  updatedAt: string;
  lastTestedAt: string | null;
  lastTestOk: boolean | null;
  lastTestMessage: string | null;
};

export type KeySource = 'stored' | 'environment' | 'undecryptable' | 'none';

export type InUse = { source: KeySource; keyId: number | null; name: string | null; hint: string | null };

export type ProviderInfo = { provider: string; label: string; environmentVariable: string; inUse: InUse };

export type AddKeyForm = { provider: string; name: string; secret: string; activate: boolean };

export const PROVIDER_LABELS: Record<string, string> = {
  local: '本地知识检索',
  anthropic: 'Anthropic Claude',
  'openai-compatible': 'OpenAI 兼容接口',
};

export const NAME_MAX = 64;

export function providerLabel(provider: string): string {
  return PROVIDER_LABELS[provider] || provider;
}

/** Where a provider's calls get their key right now, in a sentence. */
export function inUseText(info: ProviderInfo): string {
  const { inUse } = info;
  if (inUse.source === 'stored') return `平台中启用的「${inUse.name}」（${inUse.hint}）`;
  if (inUse.source === 'environment') return `环境变量 ${info.environmentVariable}（${inUse.hint}）`;
  if (inUse.source === 'undecryptable') return `启用的「${inUse.name}」无法解密，调用会失败`;
  return '未配置密钥';
}

export function inUseTone(source: KeySource): 'success' | 'info' | 'danger' | 'warning' {
  if (source === 'stored') return 'success';
  if (source === 'environment') return 'info';
  return source === 'undecryptable' ? 'danger' : 'warning';
}

export function testLabel(key: Pick<ProviderKey, 'lastTestOk' | 'lastTestedAt'>): string {
  if (key.lastTestOk === null || !key.lastTestedAt) return '未测试';
  return key.lastTestOk ? '测试通过' : '测试未通过';
}

/** The first key for a provider is switched on by default; later ones wait until someone chooses them. */
export function activateByDefault(provider: string, keys: ProviderKey[]): boolean {
  return !keys.some(key => key.provider === provider && key.active);
}

export function secretProblem(provider: string, secret: string): string | null {
  const value = secret.trim();
  if (!value) return '请填写密钥';
  if (/\s/.test(value)) return '密钥中不能包含空格或换行';
  if (value.length > 500) return '密钥过长';
  if (provider === 'anthropic') {
    if (value.startsWith('sk-ant-admin')) return '这是 Anthropic 的管理密钥，不能用来调用模型；请使用普通 API 密钥（sk-ant-api…）';
    if (!value.startsWith('sk-ant-')) return 'Anthropic 的 API 密钥以 sk-ant- 开头';
    if (value.length < 40) return '密钥长度不对，请确认复制完整';
  } else if (value.length < 8) {
    return '密钥过短，请确认复制完整';
  }
  return null;
}

export function nameProblem(name: string): string | null {
  const value = name.trim();
  if (!value) return '请填写密钥名称';
  if (value.length > NAME_MAX) return `名称不能超过 ${NAME_MAX} 个字符`;
  return null;
}

export function addProblem(form: AddKeyForm): string | null {
  return nameProblem(form.name) || secretProblem(form.provider, form.secret);
}

/** An edit may rename, replace the secret, or both; an empty secret means "keep the one it has". */
export function editProblem(provider: string, name: string, secret: string): string | null {
  return nameProblem(name) || (secret.trim() ? secretProblem(provider, secret) : null);
}

/** What happens to a provider's calls if this key is deleted — said before it is. */
export function deletionConsequence(key: ProviderKey, info: ProviderInfo | undefined): string {
  if (!key.active) return '这个密钥当前未启用，删除不影响正在进行的调用。';
  const variable = info?.environmentVariable ?? '环境变量';
  return `这是 ${providerLabel(key.provider)} 当前使用的密钥。删除后将改用环境变量 ${variable} 中的密钥；如果没有配置，调用会失败，AI 问答回到本地检索，内容审核转为人工。`;
}
