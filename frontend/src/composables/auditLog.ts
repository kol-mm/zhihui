import { ref, watch } from 'vue';
import { isSessionExpiredError, toUserMessage } from '../api/client';
import type { AuditEntry } from '../utils/auditLabels';

export type AuditPage = { items: AuditEntry[]; hasMore: boolean; nextCursor: number | null };
export type AuditFilters = { category: string; actor: string; keyword: string; from: string; to: string; subjectUserId: number | null };

export const emptyFilters = (): AuditFilters => ({ category: '', actor: '', keyword: '', from: '', to: '', subjectUserId: null });

export function auditQuery(filters: AuditFilters, cursor: number | null, limit: number): string {
  const params = new URLSearchParams();
  if (filters.category) params.set('category', filters.category);
  if (filters.actor.trim()) params.set('actor', filters.actor.trim());
  if (filters.keyword.trim()) params.set('keyword', filters.keyword.trim());
  if (filters.from) params.set('from', filters.from);
  if (filters.to) params.set('to', filters.to);
  if (filters.subjectUserId) params.set('subjectUserId', String(filters.subjectUserId));
  if (cursor) params.set('cursor', String(cursor));
  params.set('limit', String(limit));
  return `/user/admin/audit?${params.toString()}`;
}

/**
 * The action log page: filters, newest-first pages and "load more". Typing in the text filters waits briefly
 * before asking; an answer to an older question is dropped when a newer one was already sent.
 */
export function useAuditLog(fetchPage: (url: string) => Promise<AuditPage>, onError: (message: string) => void,
                            options: { pageSize?: number; debounceMs?: number; initial?: Partial<AuditFilters> } = {}) {
  const pageSize = options.pageSize ?? 30;
  const debounceMs = options.debounceMs ?? 350;
  const filters = ref<AuditFilters>({ ...emptyFilters(), ...options.initial });
  const items = ref<AuditEntry[]>([]);
  const hasMore = ref(false);
  const loading = ref(false);
  const failed = ref(false);
  let cursor: number | null = null;
  let request = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function load(reset = true) {
    if (!reset && (loading.value || !hasMore.value)) return;
    const token = ++request;
    loading.value = true;
    try {
      const page = await fetchPage(auditQuery(filters.value, reset ? null : cursor, pageSize));
      if (token !== request) return;
      const known = new Set(reset ? [] : items.value.map(item => item.id));
      items.value = reset ? page.items : [...items.value, ...page.items.filter(item => !known.has(item.id))];
      hasMore.value = page.hasMore;
      cursor = page.nextCursor;
      failed.value = false;
    } catch (error) {
      if (token !== request) return;
      if (reset) failed.value = true;
      if (!isSessionExpiredError(error)) onError(toUserMessage(error, '操作记录加载失败，请重试'));
    } finally {
      if (token === request) loading.value = false;
    }
  }

  function scheduleLoad() {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => { timer = undefined; void load(true); }, debounceMs);
  }

  // Choices apply at once; typed text waits for a pause. One watcher, so a change to both loads once.
  watch(() => ({ ...filters.value }), (now, before) => {
    const onlyText = now.category === before.category && now.from === before.from && now.to === before.to
      && now.subjectUserId === before.subjectUserId;
    if (onlyText) {
      scheduleLoad();
      return;
    }
    if (timer) { clearTimeout(timer); timer = undefined; }
    void load(true);
  });

  function setDays(range: [string, string] | null) {
    filters.value.from = range?.[0] ?? '';
    filters.value.to = range?.[1] ?? '';
  }

  function reset() {
    filters.value = emptyFilters();
  }

  function dispose() {
    if (timer) clearTimeout(timer);
    request++;
  }

  return { filters, items, hasMore, loading, failed, load, loadMore: () => load(false), setDays, reset, dispose };
}
