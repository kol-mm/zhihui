/** Turning the review counters into something an administrator can act on. */

export type ReviewHealth = {
  enabled?: boolean;
  model_configured?: boolean;
  idle?: boolean;
  requested?: number;
  approved?: number;
  rejected?: number;
  escalated?: number;
  sampled?: number;
  model_failures?: number;
  last_decision?: string | null;
  last_decision_at?: string | null;
  last_failure?: string | null;
  last_failure_at?: string | null;
};

export type ReviewVerdict = {
  tone: 'success' | 'info' | 'warning' | 'danger';
  headline: string;
  detail: string;
};

function count(value: number | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}

/**
 * Review that is quietly doing nothing looks exactly like review that is working: everything lands in the
 * human queue either way. These are the states worth telling apart, most alarming first.
 */
export function reviewVerdict(health: ReviewHealth | null | undefined): ReviewVerdict {
  const data = health ?? {};
  if (!data.enabled) {
    return { tone: 'info', headline: '未开启', detail: 'AI 审核关闭，所有内容按原流程等待人工审核。' };
  }

  const requested = count(data.requested);
  const failures = count(data.model_failures);
  const automatic = count(data.approved) + count(data.rejected);

  if (data.idle || requested === 0) {
    return {
      tone: 'warning',
      headline: '已开启但没有收到请求',
      detail: '开关已打开，却没有任何内容送来审核。请确认知识与社区服务能访问 AI 服务。',
    };
  }
  if (failures > 0 && automatic === 0) {
    return {
      tone: 'danger',
      headline: '模型始终失败',
      detail: `${failures} 次调用失败，没有任何内容被自动判定，全部转入人工队列。`,
    };
  }
  if (failures > 0) {
    return {
      tone: 'warning',
      headline: '部分调用失败',
      detail: `已自动判定 ${automatic} 条，另有 ${failures} 次调用失败转入人工。`,
    };
  }
  if (!data.model_configured) {
    return {
      tone: 'info',
      headline: '仅本地规则',
      detail: '未配置模型，只有敏感词与长度规则可用；高于本地规则置信度的门槛会让内容转人工。',
    };
  }
  return {
    tone: 'success',
    headline: '运行正常',
    detail: `已自动判定 ${automatic} 条，转人工 ${count(data.escalated)} 条（含抽样 ${count(data.sampled)} 条）。`,
  };
}

/** The counters worth showing, in a fixed order, skipping the ones that are zero and uninteresting. */
export function reviewCounters(health: ReviewHealth | null | undefined): { label: string; value: number }[] {
  const data = health ?? {};
  return [
    { label: '收到请求', value: count(data.requested) },
    { label: '自动通过', value: count(data.approved) },
    { label: '自动驳回', value: count(data.rejected) },
    { label: '转人工', value: count(data.escalated) },
    { label: '抽样复核', value: count(data.sampled) },
    { label: '调用失败', value: count(data.model_failures) },
  ];
}
