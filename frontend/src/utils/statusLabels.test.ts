import { describe, expect, it } from 'vitest';
import {
  auditLabel,
  formatDate,
  postStatusLabel,
  publishPolicyLabel,
  reportStatusLabel,
  roleLabel,
  sessionStatusLabel,
} from './statusLabels';

describe('the words put on a status', () => {
  it('names each review state of a knowledge file', () => {
    expect(auditLabel('APPROVED')).toBe('已通过');
    expect(auditLabel('PENDING')).toBe('待审核');
    expect(auditLabel('REJECTED')).toBe('已驳回');
    expect(auditLabel('HIDDEN')).toBe('已下架');
  });

  it('names each state of a post, which differ from a file’s', () => {
    expect(postStatusLabel('PUBLISHED')).toBe('已发布');
    expect(postStatusLabel('HIDDEN')).toBe('已隐藏');
  });

  it('names report, role, policy and conversation states', () => {
    expect(reportStatusLabel('PROCESSING')).toBe('处理中');
    expect(reportStatusLabel('RESOLVED')).toBe('已结案');
    expect(roleLabel('ADMIN')).toBe('平台管理员');
    expect(roleLabel('USER')).toBe('社区用户');
    expect(publishPolicyLabel('PRE_REVIEW')).toBe('强制预审');
    expect(sessionStatusLabel('ARCHIVED')).toBe('已封存');
  });

  it('falls back to what it was given rather than showing nothing', () => {
    expect(auditLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(postStatusLabel('')).toBe('');
    expect(roleLabel('MODERATOR')).toBe('MODERATOR');
  });

  it('treats a missing publish policy as the standard one', () => {
    expect(publishPolicyLabel(undefined)).toBe('标准审核');
    expect(publishPolicyLabel('')).toBe('标准审核');
  });

  it('shows a moment in time, and leaves unparseable input alone', () => {
    expect(formatDate('')).toBe('');
    expect(formatDate('not a date')).toBe('not a date');
    // The exact wording depends on the runtime's locale data; what matters is that it rendered something else.
    const rendered = formatDate('2026-09-22T08:30:00Z');
    expect(rendered).not.toBe('2026-09-22T08:30:00Z');
    expect(rendered.length).toBeGreaterThan(0);
  });
});
