import { describe, expect, it } from 'vitest';
import { emailProblem, maskEmail, normalizeCode, normalizeEmail } from './emailAddress';

describe('email address helpers', () => {
  it('normalizes like the server does', () => {
    expect(normalizeEmail('  Reader@Example.COM ')).toBe('reader@example.com');
  });

  it('accepts ordinary addresses and rejects obvious mistakes', () => {
    expect(emailProblem('reader@example.com')).toBeNull();
    expect(emailProblem('first.last+tag@mail.example.cn')).toBeNull();
    expect(emailProblem(' ')).toBe('请输入邮箱地址');
    expect(emailProblem('reader@example')).toBe('邮箱地址格式不正确');
    expect(emailProblem('reader example@example.com')).toBe('邮箱地址格式不正确');
    expect(emailProblem('a@b@example.com')).toBe('邮箱地址格式不正确');
    expect(emailProblem(`x@${'a'.repeat(250)}.com`)).toBe('邮箱地址格式不正确');
  });

  it('masks the local part', () => {
    expect(maskEmail('reader@example.com')).toBe('re***@example.com');
    expect(maskEmail('ab@example.com')).toBe('a***@example.com');
    expect(maskEmail('a@example.com')).toBe('a***@example.com');
    expect(maskEmail(null)).toBe('');
  });

  it('ignores spaces in pasted codes', () => {
    expect(normalizeCode(' 123 456 ')).toBe('123456');
  });
});
