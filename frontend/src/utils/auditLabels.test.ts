import { describe, expect, it } from 'vitest';
import { actionLabel, categoryLabel, changesOf, describeChange, detailFacts, isSevere, sourceLabel, valueLabel } from './auditLabels';

describe('audit labels', () => {
  it('names actions and categories, falling back to the code', () => {
    expect(actionLabel('USER_STATUS')).toBe('修改账号状态');
    expect(actionLabel('AI_CONFIG_SAVE')).toBe('修改平台设置');
    expect(actionLabel('USER_PROFILE_AUDIT')).toBe('审核会员资料');
    expect(actionLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(categoryLabel('SUPPORT')).toBe('工单与常见问题');
    expect(categoryLabel('OTHER')).toBe('OTHER');
    expect(sourceLabel('knowledge-service')).toBe('知识服务');
    expect(sourceLabel(null)).toBe('');
  });

  it('marks removals and suspensions', () => {
    expect(isSevere('POST_DELETE')).toBe(true);
    expect(isSevere('USER_STATUS')).toBe(true);
    expect(isSevere('FAQ_SAVE')).toBe(false);
  });

  it('describes field changes in Chinese', () => {
    expect(describeChange({ field: 'role', label: '角色', before: 'USER', after: 'ADMIN' })).toBe('角色：社区用户 → 平台管理员');
    expect(describeChange({ field: 'status', label: '账号状态', before: 'ACTIVE', after: 'DISABLED' })).toBe('账号状态：正常 → 已停用');
    expect(describeChange({ field: 'auditStatus', label: '审核状态', before: 'PENDING', after: 'APPROVED' })).toBe('审核状态：待审核 → 已通过');
    expect(describeChange({ field: 'registration_enabled', label: '开放注册', before: true, after: false })).toBe('开放注册：开启 → 关闭');
    expect(describeChange({ field: 'email', label: '绑定邮箱', before: 'a@example.com', after: null })).toBe('绑定邮箱：a@example.com → 空');
    expect(describeChange({ field: 'categoryId', label: '分类', before: 1, after: 2 })).toBe('分类：#1 → #2');
    expect(describeChange({ field: 'password', label: '登录密码', hidden: true })).toBe('登录密码：已修改（内容不记录）');
    expect(valueLabel('title', 'ACTIVE plan')).toBe('ACTIVE plan');
  });

  it('lists the remaining facts', () => {
    const entry = { detail: { changes: [{ field: 'status', label: '状态' }], reason: '广告', participants: [61, 62], postId: 9, removed: 3, empty: '' } };
    expect(changesOf(entry)).toHaveLength(1);
    expect(detailFacts(entry)).toEqual([
      { label: '原因', text: '广告' },
      { label: '会话双方', text: '#61 与 #62' },
      { label: '所属帖子', text: '#9' },
      { label: '删除数量', text: '3' },
    ]);
    expect(changesOf({ detail: {} })).toEqual([]);
    expect(detailFacts({ detail: { unknownKey: 'x' } })).toEqual([{ label: 'unknownKey', text: 'x' }]);
  });
});
