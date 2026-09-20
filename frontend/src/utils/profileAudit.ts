/** Reading a member's pending profile change: what changed, and how far along it is. */

export type ProfileFields = { nickname?: string | null; signature?: string | null };

export type ProfileChange = {
  id: number;
  userId: number;
  username?: string | null;
  status: string;
  reason?: string | null;
  createdAt: string;
  updatedAt: string;
  stale?: boolean;
  before: ProfileFields;
  after: ProfileFields;
};

/** What the member submitted, as their own profile page sees it. */
export type ProfileAuditState = {
  id: number;
  status: string;
  nickname?: string | null;
  signature?: string | null;
  reason?: string | null;
  createdAt: string;
  updatedAt: string;
};

const FIELD_LABELS: { field: keyof ProfileFields; label: string }[] = [
  { field: 'nickname', label: '昵称' },
  { field: 'signature', label: '个性签名' },
];

const STATUS_LABELS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
};

export function profileAuditStatusLabel(status: string): string {
  return STATUS_LABELS[status] ?? status;
}

export function profileAuditStatusType(status: string): 'warning' | 'success' | 'danger' | 'info' {
  return status === 'PENDING' ? 'warning' : status === 'APPROVED' ? 'success' : status === 'REJECTED' ? 'danger' : 'info';
}

function text(value: unknown): string {
  return value === null || value === undefined || value === '' ? '' : String(value);
}

/** The fields that differ, in a fixed order; an empty value reads as 空 so a cleared signature is visible. */
export function profileAuditChanges(before: ProfileFields | null | undefined, after: ProfileFields | null | undefined) {
  return FIELD_LABELS
    .map(({ field, label }) => ({ field, label, before: text(before?.[field]), after: text(after?.[field]) }))
    .filter(change => change.before !== change.after)
    .map(change => ({ ...change, description: `${change.label}：${change.before || '空'} → ${change.after || '空'}` }));
}

/** The same diff for the member's own page, where "before" is the profile they are currently shown under. */
export function pendingChanges(current: ProfileFields | null | undefined, pending: ProfileAuditState | null | undefined) {
  if (!pending) return [];
  return profileAuditChanges(current, { nickname: pending.nickname, signature: pending.signature });
}
