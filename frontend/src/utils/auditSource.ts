/** Who decided a piece of content's fate, and what they said about it. */

const LABELS: Record<string, string> = {
  AI: 'AI 判定',
  MANUAL: '人工审核',
};

function key(source?: string | null): string {
  return (source ?? '').trim().toUpperCase();
}

/** Empty when nobody has decided yet, so the caller can leave the tag out rather than invent one. */
export function auditSourceLabel(source?: string | null): string {
  const value = key(source);
  if (!value) return '';
  return LABELS[value] ?? value;
}

export function auditSourceTone(source?: string | null): 'info' | 'success' {
  return key(source) === 'AI' ? 'info' : 'success';
}

export function decidedByAi(source?: string | null): boolean {
  return key(source) === 'AI';
}

/** One line for a reviewer: who decided and why, or '' when there is nothing to show. */
export function auditDecisionNote(source?: string | null, reason?: string | null): string {
  const who = auditSourceLabel(source);
  const why = (reason ?? '').trim();
  if (!who && !why) return '';
  if (!who) return why;
  return why ? `${who}：${why}` : who;
}
