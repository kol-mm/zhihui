/** Chinese wording for administrator action log entries. */

export type AuditChange = { field: string; label: string; before?: unknown; after?: unknown; hidden?: boolean };

export type AuditEntry = {
  id: number;
  createdAt: string;
  actorId: number;
  actorName: string | null;
  action: string;
  category: string;
  targetType: string | null;
  targetId: string | null;
  targetLabel: string | null;
  subjectUserId: number | null;
  summary: string | null;
  detail: Record<string, unknown>;
  source: string | null;
  clientIp: string | null;
};

export const AUDIT_CATEGORIES: { value: string; label: string }[] = [
  { value: 'ACCOUNT', label: '账号' },
  { value: 'KNOWLEDGE', label: '知识库' },
  { value: 'COMMUNITY', label: '社区' },
  { value: 'MESSAGE', label: '私信' },
  { value: 'SUPPORT', label: '工单与常见问题' },
  { value: 'SYSTEM', label: '平台设置' },
];

const ACTIONS: Record<string, string> = {
  USER_STATUS: '修改账号状态',
  USER_GOVERNANCE: '修改用户资料与权限',
  USER_REPORT_RESOLVE: '处理用户举报',
  USER_PROFILE_AUDIT: '审核会员资料',
  PASSWORD_RESET_ISSUE: '签发密码重置码',
  PASSWORD_RESET_CLOSE: '关闭密码重置申请',
  KNOWLEDGE_AUDIT: '审核知识资源',
  KNOWLEDGE_EDIT: '编辑知识资源',
  KNOWLEDGE_DELETE: '删除知识资源',
  KNOWLEDGE_REPORT_RESOLVE: '处理知识举报',
  KNOWLEDGE_CATEGORY_SAVE: '保存知识分类',
  KNOWLEDGE_CATEGORY_DELETE: '删除知识分类',
  POST_AUDIT: '审核帖子',
  POST_EDIT: '编辑帖子',
  POST_DELETE: '删除帖子',
  COMMENT_STATUS: '修改评论状态',
  COMMENT_DELETE: '删除评论',
  DRAFT_DELETE: '删除草稿',
  DRAFTS_PURGE: '清理过期草稿',
  MESSAGES_CLEAR: '清空会话记录',
  MESSAGES_CLEAR_ALL: '清空全部私信',
  MESSAGE_DELETE: '删除私信',
  SESSION_DELETE: '删除私信会话',
  SESSION_STATUS: '修改会话状态',
  FAQ_SAVE: '保存常见问题',
  FAQ_DELETE: '删除常见问题',
  TICKET_ASSIGN: '分配工单',
  TICKET_REPLY: '回复工单',
  AI_CONFIG_SAVE: '修改平台设置',
  AI_INDEX_REBUILD: '重建 AI 索引',
};

/** Actions that remove something or take a member's access away. */
const SEVERE = new Set(['USER_STATUS', 'KNOWLEDGE_DELETE', 'POST_DELETE', 'COMMENT_DELETE', 'MESSAGES_CLEAR', 'MESSAGES_CLEAR_ALL',
  'MESSAGE_DELETE', 'SESSION_DELETE', 'DRAFTS_PURGE', 'KNOWLEDGE_CATEGORY_DELETE', 'FAQ_DELETE']);

const VALUES: Record<string, string> = {
  ACTIVE: '正常', DISABLED: '已停用', DELETED: '已删除',
  USER: '社区用户', ADMIN: '平台管理员',
  STANDARD: '标准审核', PRE_REVIEW: '强制预审', BLOCKED: '禁止发布',
  PENDING: '待处理', APPROVED: '已通过', REJECTED: '已驳回', HIDDEN: '已隐藏', PUBLISHED: '已发布', VISIBLE: '显示',
  PROCESSING: '处理中', RESOLVED: '已解决', RESTRICTED: '已限制', ARCHIVED: '已归档',
  local: '本地模型', 'openai-compatible': 'OpenAI 兼容接口',
  'all-approved': '全部已审核知识', 'admin-selected': '管理员指定知识',
};

/** Statuses whose meaning depends on the thing they describe. */
const CONTEXT_VALUES: Record<string, Record<string, string>> = {
  auditStatus: { PENDING: '待审核' },
  status: {},
};

const DETAIL_LABELS: Record<string, string> = {
  reason: '原因',
  result: '处理结果',
  note: '备注',
  status: '处理状态',
  removed: '删除数量',
  retentionDays: '保留天数',
  participants: '会话双方',
  postId: '所属帖子',
  fileId: '知识资源',
  sessionId: '所属会话',
  firstBatchDocuments: '首批文档数',
};

export function actionLabel(action: string): string {
  return ACTIONS[action] ?? action;
}

export function categoryLabel(category: string): string {
  return AUDIT_CATEGORIES.find(item => item.value === category)?.label ?? category;
}

export function isSevere(action: string): boolean {
  return SEVERE.has(action);
}

export function valueLabel(field: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '空';
  if (typeof value === 'boolean') return value ? '开启' : '关闭';
  if (Array.isArray(value)) return value.map(item => valueLabel(field, item)).join('、');
  if (typeof value === 'object') return JSON.stringify(value);
  const text = String(value);
  if (field === 'categoryId' || field === 'assigneeUserId' || field === 'parentId') return `#${text}`;
  return CONTEXT_VALUES[field]?.[text] ?? VALUES[text] ?? text;
}

export function describeChange(change: AuditChange): string {
  if (change.hidden) return `${change.label}：已修改（内容不记录）`;
  return `${change.label}：${valueLabel(change.field, change.before)} → ${valueLabel(change.field, change.after)}`;
}

export function changesOf(entry: Pick<AuditEntry, 'detail'>): AuditChange[] {
  const changes = entry.detail?.changes;
  return Array.isArray(changes) ? changes as AuditChange[] : [];
}

/** The other facts of an entry, as label and text, in a stable order. */
export function detailFacts(entry: Pick<AuditEntry, 'detail'>): { label: string; text: string }[] {
  return Object.entries(entry.detail ?? {})
    .filter(([key, value]) => key !== 'changes' && value !== null && value !== undefined && value !== '')
    .map(([key, value]) => {
      const text = key === 'participants' && Array.isArray(value) ? value.map(id => `#${id}`).join(' 与 ')
        : key === 'postId' || key === 'fileId' || key === 'sessionId' ? `#${value}`
          : valueLabel(key, value);
      return { label: DETAIL_LABELS[key] ?? key, text };
    });
}

const SOURCES: Record<string, string> = {
  'user-service': '用户服务', 'knowledge-service': '知识服务', 'community-service': '社区服务',
  'message-service': '消息服务', 'ai-service': 'AI 服务',
};

export function sourceLabel(source: string | null): string {
  return source ? SOURCES[source] ?? source : '';
}
