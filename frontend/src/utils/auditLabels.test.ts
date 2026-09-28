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

describe('API key entries', () => {
  it('are named, and the ones that cut off a secret are marked', () => {
    expect(actionLabel('API_KEY_CREATE')).toBe('创建接口密钥');
    expect(actionLabel('API_KEY_UPDATE')).toBe('修改接口密钥');
    expect(actionLabel('API_KEY_ROTATE')).toBe('轮换接口密钥');
    expect(actionLabel('API_KEY_REVOKE')).toBe('撤销接口密钥');
    expect(isSevere('API_KEY_REVOKE')).toBe(true);
    expect(isSevere('API_KEY_ROTATE')).toBe(true);
    expect(isSevere('API_KEY_CREATE')).toBe(false);
  });

  it('read a key\'s scopes in words, and only within its scopes', () => {
    expect(detailFacts({ detail: { scopes: ['knowledge:read', 'community:write'] } }))
      .toEqual([{ label: '权限', text: '读取知识库、发帖与评论' }]);
    expect(detailFacts({ detail: { previousPrefix: 'zk_abcdefgh' } }))
      .toEqual([{ label: '原密钥前缀', text: 'zk_abcdefgh' }]);
    expect(valueLabel('title', 'knowledge:read')).toBe('knowledge:read');
  });
});

describe('model provider key entries', () => {
  it('are named, and the ones that take the model away are marked', () => {
    expect(actionLabel('AI_PROVIDER_KEY_ADD')).toBe('添加模型密钥');
    expect(actionLabel('AI_PROVIDER_KEY_ACTIVATE')).toBe('启用模型密钥');
    expect(isSevere('AI_PROVIDER_KEY_DELETE')).toBe(true);
    expect(isSevere('AI_PROVIDER_KEY_DEACTIVATE')).toBe(true);
    expect(isSevere('AI_PROVIDER_KEY_ADD')).toBe(false);
  });

  it('read the provider and a replaced secret in words', () => {
    expect(detailFacts({ detail: { provider: 'anthropic' } })).toEqual([{ label: '模型服务', text: 'Anthropic Claude' }]);
    expect(describeChange({ field: 'secret', label: '密钥', hidden: true })).toBe('密钥：已修改（内容不记录）');
  });
});
