import { describe, expect, it } from 'vitest';
import { pendingChanges, profileAuditChanges, profileAuditStatusLabel, profileAuditStatusType } from './profileAudit';

describe('profile review helpers', () => {
  it('lists only the fields that differ, in a fixed order', () => {
    const changes = profileAuditChanges(
      { nickname: '原昵称', signature: '原签名' },
      { nickname: '新昵称', signature: '新签名' },
    );
    expect(changes.map(change => change.field)).toEqual(['nickname', 'signature']);
    expect(changes[0].description).toBe('昵称：原昵称 → 新昵称');
    expect(profileAuditChanges({ nickname: '同一个', signature: 'x' }, { nickname: '同一个', signature: 'x' })).toEqual([]);
  });

  it('shows an emptied or newly filled field as 空', () => {
    expect(profileAuditChanges({ nickname: 'a', signature: '有签名' }, { nickname: 'a', signature: '' })[0].description)
      .toBe('个性签名：有签名 → 空');
    expect(profileAuditChanges({ nickname: 'a', signature: null }, { nickname: 'a', signature: '新签名' })[0].description)
      .toBe('个性签名：空 → 新签名');
    expect(profileAuditChanges({ nickname: 'a' }, { nickname: 'a', signature: undefined })).toEqual([]);
  });

  it('describes the state', () => {
    expect(profileAuditStatusLabel('PENDING')).toBe('待审核');
    expect(profileAuditStatusLabel('REJECTED')).toBe('已驳回');
    expect(profileAuditStatusLabel('SOMETHING')).toBe('SOMETHING');
    expect(profileAuditStatusType('PENDING')).toBe('warning');
    expect(profileAuditStatusType('APPROVED')).toBe('success');
    expect(profileAuditStatusType('REJECTED')).toBe('danger');
  });

  it('diffs a member page against what they submitted', () => {
    const pending = { id: 1, status: 'PENDING', nickname: '待审昵称', signature: '原签名', createdAt: '', updatedAt: '' };
    expect(pendingChanges({ nickname: '现昵称', signature: '原签名' }, pending).map(change => change.description))
      .toEqual(['昵称：现昵称 → 待审昵称']);
    expect(pendingChanges({ nickname: '现昵称' }, null)).toEqual([]);
  });
});
