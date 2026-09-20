import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';
import { auditQuery, emptyFilters, useAuditLog, type AuditPage } from './auditLog';
import type { AuditEntry } from '../utils/auditLabels';

const entry = (id: number): AuditEntry => ({
  id, createdAt: '2026-09-17T08:00:00Z', actorId: 2, actorName: 'admin', action: 'USER_STATUS', category: 'ACCOUNT',
  targetType: 'USER', targetId: String(id), targetLabel: `成员${id}`, subjectUserId: id, summary: null, detail: {},
  source: 'user-service', clientIp: null,
});
const page = (ids: number[], hasMore = false): AuditPage => ({ items: ids.map(entry), hasMore, nextCursor: hasMore ? ids[ids.length - 1] : null });

function setup(options: Parameters<typeof useAuditLog>[2] = {}) {
  const fetchPage = vi.fn<(url: string) => Promise<AuditPage>>().mockResolvedValue(page([]));
  const onError = vi.fn<(message: string) => void>();
  const log = useAuditLog(fetchPage, onError, { debounceMs: 300, ...options });
  return { fetchPage, onError, log };
}

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { vi.useRealTimers(); });

describe('audit query', () => {
  it('only sends the filters that are set', () => {
    expect(auditQuery(emptyFilters(), null, 30)).toBe('/user/admin/audit?limit=30');
    const url = auditQuery({ category: 'ACCOUNT', actor: ' ops ', keyword: '张 三', from: '2026-09-01', to: '2026-09-17', subjectUserId: 7 }, 55, 20);
    const params = new URL(url, 'http://x').searchParams;
    expect(Object.fromEntries(params)).toEqual({
      category: 'ACCOUNT', actor: 'ops', keyword: '张 三', from: '2026-09-01', to: '2026-09-17', subjectUserId: '7', cursor: '55', limit: '20',
    });
  });
});

describe('audit log page', () => {
  it('loads pages newest first and appends without duplicates', async () => {
    const { fetchPage, log } = setup({ pageSize: 2 });
    fetchPage.mockResolvedValueOnce(page([9, 8], true)).mockResolvedValueOnce(page([8, 7], false));
    await log.load();
    expect(log.items.value.map(item => item.id)).toEqual([9, 8]);
    expect(log.hasMore.value).toBe(true);
    await log.loadMore();
    expect(fetchPage).toHaveBeenLastCalledWith('/user/admin/audit?cursor=8&limit=2');
    expect(log.items.value.map(item => item.id)).toEqual([9, 8, 7]);
    expect(log.hasMore.value).toBe(false);
    await log.loadMore();
    expect(fetchPage).toHaveBeenCalledTimes(2);
  });

  it('applies choices at once and waits for a pause in typed text', async () => {
    const { fetchPage, log } = setup();
    log.filters.value.category = 'KNOWLEDGE';
    await nextTick();
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(fetchPage.mock.calls[0][0]).toContain('category=KNOWLEDGE');

    log.filters.value.keyword = '帖';
    await nextTick();
    log.filters.value.keyword = '帖子';
    await nextTick();
    expect(fetchPage).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(300);
    expect(fetchPage).toHaveBeenCalledTimes(2);
    expect(fetchPage.mock.calls[1][0]).toContain('keyword=%E5%B8%96%E5%AD%90');
  });

  it('clearing every filter loads once', async () => {
    const { fetchPage, log } = setup();
    log.filters.value = { category: 'SYSTEM', actor: 'ops', keyword: 'x', from: '2026-09-01', to: '', subjectUserId: 3 };
    await nextTick();
    await vi.runAllTimersAsync();
    fetchPage.mockClear();
    log.reset();
    await nextTick();
    await vi.runAllTimersAsync();
    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(fetchPage).toHaveBeenCalledWith('/user/admin/audit?limit=30');
  });

  it('starts from the member it was opened for', async () => {
    const { fetchPage, log } = setup({ initial: { subjectUserId: 12 } });
    await nextTick();
    expect(fetchPage).not.toHaveBeenCalled();
    await log.load();
    expect(fetchPage).toHaveBeenCalledWith('/user/admin/audit?subjectUserId=12&limit=30');
  });

  it('ignores an answer that arrives after a newer question', async () => {
    const { fetchPage, log } = setup();
    let finishOld: (value: AuditPage) => void = () => {};
    fetchPage.mockReturnValueOnce(new Promise(resolve => { finishOld = resolve; }))
      .mockResolvedValueOnce(page([2]));
    const old = log.load();
    await log.load();
    finishOld(page([1]));
    await old;
    expect(log.items.value.map(item => item.id)).toEqual([2]);
    expect(log.loading.value).toBe(false);
  });

  it('reports failures and marks a failed first page', async () => {
    const { fetchPage, onError, log } = setup();
    fetchPage.mockRejectedValueOnce(new Error('日期格式应为 2026-09-17'));
    await log.load();
    expect(log.failed.value).toBe(true);
    expect(onError).toHaveBeenCalledWith('日期格式应为 2026-09-17');
    fetchPage.mockRejectedValueOnce(new Error('network'));
    await log.load();
    expect(onError).toHaveBeenLastCalledWith('操作记录加载失败，请重试');
    await log.load();
    expect(log.failed.value).toBe(false);
  });

  it('stops pending work when the page closes', async () => {
    const { fetchPage, log } = setup();
    log.filters.value.actor = 'ops';
    await nextTick();
    log.dispose();
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetchPage).not.toHaveBeenCalled();
  });
});
