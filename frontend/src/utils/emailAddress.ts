/** The same checks the user service applies, so obvious mistakes are caught before a request is sent. */
export const MAX_EMAIL_LENGTH = 254;

export function normalizeEmail(value: string): string {
  return value.trim().toLowerCase();
}

/** A Chinese problem description, or null when the address looks usable. */
export function emailProblem(value: string): string | null {
  const email = normalizeEmail(value);
  if (!email) return '请输入邮箱地址';
  if (email.length > MAX_EMAIL_LENGTH || !/^[^\s@]{1,64}@[^\s@]+\.[^\s@.]{2,}$/.test(email)) return '邮箱地址格式不正确';
  return null;
}

/** r***@example.com: enough to recognise an address without showing all of it. */
export function maskEmail(value: string | null | undefined): string {
  if (!value) return '';
  const at = value.lastIndexOf('@');
  if (at <= 0) return value;
  const local = value.slice(0, at);
  return `${local.slice(0, Math.max(1, Math.min(2, local.length - 1)))}***${value.slice(at)}`;
}

/** Six digits, ignoring spaces a member may paste along with the code. */
export function normalizeCode(value: string): string {
  return value.replace(/\s/g, '');
}
