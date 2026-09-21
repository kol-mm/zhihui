/** How a knowledge file's text was indexed, and what that means for finding the file again. */

const LABELS: Record<string, string> = {
  INDEXED: '全文可搜',
  TOO_LARGE: '仅标题检索',
  EMPTY: '未提取到正文',
  PENDING: '待解析',
};

const TONES: Record<string, 'success' | 'warning' | 'info'> = {
  INDEXED: 'success',
  TOO_LARGE: 'warning',
  EMPTY: 'info',
  PENDING: 'info',
};

const HINTS: Record<string, string> = {
  TOO_LARGE: '文件较大，正文未参与全文检索，只能按标题搜索到这份资料。',
  EMPTY: '没有从文件中提取到文本，只能按标题搜索到这份资料。',
  PENDING: '正文尚未建立索引，暂时只能按标题搜索到这份资料。',
};

function key(status?: string | null): string {
  return (status ?? '').trim().toUpperCase();
}

/** Empty when the file carries no status, so the caller can leave the tag out rather than guess. */
export function parseStatusLabel(status?: string | null): string {
  const value = key(status);
  if (!value) return '';
  return LABELS[value] ?? value;
}

export function parseStatusTone(status?: string | null): 'success' | 'warning' | 'info' {
  return TONES[key(status)] ?? 'info';
}

/** True only when the text itself is searchable; anything else means the title alone is. */
export function isFullTextIndexed(status?: string | null): boolean {
  return key(status) === 'INDEXED';
}

/** A sentence for the member reading the file, or '' when there is nothing worth saying. */
export function parseStatusHint(status?: string | null): string {
  const value = key(status);
  if (!value || value === 'INDEXED') return '';
  return HINTS[value] ?? '正文未参与全文检索，只能按标题搜索到这份资料。';
}
