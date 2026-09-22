import { nextTick, ref } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  createListLoader,
  emptyModerationList,
  moderationPageUrl,
  moderationStatusChoices,
  useModeration,
  type ModerationList,
  type ModerationPage,
} from './moderation';

type Row = { id: number; title?: string };

const page = (items: Row[], extra: Partial<ModerationPage<Row>> = {}): ModerationPage<Row> => ({
  items, nextCursor: null, hasMore: false, total: items.length, ...extra,
});

const query = (url: string) => Object.fromEntries(new URLSearchParams(url.split('?')[1]));
const path = (url: string) => url.split('?')[0];

describe('which queue a tab reads', () => {
  it('sends each tab to its own endpoint', () => {
    expect(path(moderationPageUrl('knowledge', null))).toBe('/knowledge/admin/files/page');
    expect(path(moderationPageUrl('reports', null))).toBe('/knowledge/admin/reports/page');
    expect(path(moderationPageUrl('users', null))).toBe('/user/admin/reports/page');
    expect(path(moderationPageUrl('profiles', null))).toBe('/user/admin/profile-changes/page');
    expect(path(moderationPageUrl('posts', null))).toBe('/post/admin/posts/page');
    expect(path(moderationPageUrl('post-management', null))).toBe('/post/admin/posts/page');
  });

  it('asks for a page of twenty and carries the cursor only when there is one', () => {
    expect(query(moderationPageUrl('knowledge', null)).limit).toBe('20');
    expect(query(moderationPageUrl('knowledge', null)).cursor).toBeUndefined();
    expect(query(moderationPageUrl('knowledge', 42)).cursor).toBe('42');
    expect(query(moderationPageUrl('knowledge', 'abc')).cursor).toBe('abc');
  });

  it('passes a keyword trimmed, and not at all when it is blank', () => {
    expect(query(moderationPageUrl('knowledge', null, '  报告  ')).keyword).toBe('报告');
    expect(query(moderationPageUrl('knowledge', null, '   ')).keyword).toBeUndefined();
    expect(query(moderationPageUrl('knowledge', null, '')).keyword).toBeUndefined();
  });

  it('leaves the status off the tabs that accept any of them', () => {
    expect(query(moderationPageUrl('knowledge', null, '', '')).status).toBeUndefined();
    expect(query(moderationPageUrl('reports', null, '', '')).status).toBeUndefined();
    expect(query(moderationPageUrl('knowledge', null, '', 'APPROVED')).status).toBe('APPROVED');
  });

  it('insists on a default for the two tabs that mean something narrower', () => {
    // 资料审核 is a queue of what is waiting, not a list of every change ever made.
    expect(query(moderationPageUrl('profiles', null, '', '')).status).toBe('PENDING');
    expect(query(moderationPageUrl('profiles', null, '', 'APPROVED')).status).toBe('APPROVED');
    // 帖子管理 shows what is live or hidden; 待审帖子 is only ever the waiting ones.
    expect(query(moderationPageUrl('post-management', null, '', '')).status).toBe('PUBLISHED,HIDDEN');
    expect(query(moderationPageUrl('post-management', null, '', 'HIDDEN')).status).toBe('HIDDEN');
    expect(query(moderationPageUrl('posts', null, '', 'HIDDEN')).status).toBe('PENDING');
  });
});

describe('the statuses a tab offers', () => {
  it('speaks each tab’s own vocabulary', () => {
    expect(moderationStatusChoices('reports').map(o => o.value)).toEqual(['PENDING', 'PROCESSING', 'RESOLVED']);
    expect(moderationStatusChoices('users').map(o => o.value)).toEqual(['PENDING', 'PROCESSING', 'RESOLVED']);
    expect(moderationStatusChoices('post-management').map(o => o.value)).toEqual(['PUBLISHED', 'HIDDEN']);
    expect(moderationStatusChoices('posts').map(o => o.value)).toEqual(['PENDING']);
    expect(moderationStatusChoices('profiles').map(o => o.value)).toEqual(['PENDING', 'APPROVED', 'REJECTED']);
    expect(moderationStatusChoices('knowledge').map(o => o.value)).toEqual(['PENDING', 'APPROVED', 'REJECTED', 'HIDDEN']);
  });

  it('never offers a status the endpoint would reject for that tab', () => {
    // Every offered status must survive the round trip to the URL unchanged.
    for (const tab of ['knowledge', 'reports', 'users', 'profiles', 'post-management']) {
      for (const choice of moderationStatusChoices(tab)) {
        expect(query(moderationPageUrl(tab, null, '', choice.value)).status).toBe(choice.value);
      }
    }
  });
});

describe('loading one list', () => {
  it('replaces on a reset and appends on the next page', async () => {
    const answers = [page([{ id: 1 }, { id: 2 }], { nextCursor: 2, hasMore: true, total: 5 }),
                     page([{ id: 3 }], { nextCursor: null, hasMore: false, total: null })];
    const load = createListLoader(async () => answers.shift() as any);
    const list = ref<ModerationList<Row>>(emptyModerationList());

    await load('knowledge', list, true, () => '/first');
    expect(list.value.items.map(i => i.id)).toEqual([1, 2]);
    expect(list.value).toMatchObject({ cursor: 2, hasMore: true, total: 5, loading: false });

    await load('knowledge', list, false, () => '/second');
    expect(list.value.items.map(i => i.id)).toEqual([1, 2, 3]);
    // The later page sends no total; the figure from the first page is what the reader was shown.
    expect(list.value.total).toBe(5);
  });

  it('does not repeat a row that arrives on two pages', async () => {
    const answers = [page([{ id: 1 }, { id: 2 }], { hasMore: true }), page([{ id: 2 }, { id: 3 }])];
    const load = createListLoader(async () => answers.shift() as any);
    const list = ref<ModerationList<Row>>(emptyModerationList());

    await load('knowledge', list, true, () => '/first');
    await load('knowledge', list, false, () => '/second');

    expect(list.value.items.map(i => i.id)).toEqual([1, 2, 3]);
  });

  it('drops an answer to a question that has since been asked again', async () => {
    let release: ((value: ModerationPage<Row>) => void) | undefined;
    const slow = new Promise<ModerationPage<Row>>(resolve => { release = resolve; });
    const answers: Promise<ModerationPage<Row>>[] = [slow, Promise.resolve(page([{ id: 99, title: 'newest' }]))];
    const load = createListLoader(() => answers.shift() as any);
    const list = ref<ModerationList<Row>>(emptyModerationList());

    const first = load('knowledge', list, true, () => '/slow');
    const second = load('knowledge', list, true, () => '/fast');
    await second;
    release!(page([{ id: 1, title: 'stale' }]));
    await first;

    // The stale answer came back last and must not have won.
    expect(list.value.items.map(i => i.title)).toEqual(['newest']);
    expect(list.value.loading).toBe(false);
  });

  it('keeps two lists from cancelling each other', async () => {
    const load = createListLoader(async (url: string) => page([{ id: url.length }]) as any);
    const knowledge = ref<ModerationList<Row>>(emptyModerationList());
    const reports = ref<ModerationList<Row>>(emptyModerationList());

    await Promise.all([load('knowledge', knowledge, true, () => '/k'),
                       load('reports', reports, true, () => '/reports')]);

    expect(knowledge.value.items).toHaveLength(1);
    expect(reports.value.items).toHaveLength(1);
  });

  it('stops showing itself as loading even when the request fails', async () => {
    const load = createListLoader(async () => { throw new Error('gateway said no'); });
    const list = ref<ModerationList<Row>>(emptyModerationList());

    await expect(load('knowledge', list, true, () => '/boom')).rejects.toThrow('gateway said no');
    expect(list.value.loading).toBe(false);
  });
});

describe('the moderation screen', () => {
  let urls: string[];
  let active: boolean;

  function build(overrides: Partial<Parameters<typeof useModeration>[0]> = {}) {
    urls = [];
    active = true;
    const load = createListLoader(async (url: string) => { urls.push(url); return page([{ id: 1 }]) as any; });
    return useModeration<Row, Row, Row, Row>({
      loadList: load,
      fetchOverview: async (url: string) => ({ from: url }),
      onOverview: () => {},
      onError: () => {},
      isActive: () => active,
      debounceMs: 5,
      ...overrides,
    });
  }

  beforeEach(() => { vi.useFakeTimers(); });
  afterEach(() => { vi.useRealTimers(); });

  it('opens on the knowledge queue', () => {
    const screen = build();
    expect(screen.moderationTab.value).toBe('knowledge');
    expect(screen.moderationStatusOptions.value.map(o => o.value)).toContain('HIDDEN');
  });

  it('fetches only the tab that is open', async () => {
    const screen = build();
    await screen.loadModerationTab('reports', true);

    expect(urls).toHaveLength(1);
    expect(path(urls[0])).toBe('/knowledge/admin/reports/page');
    expect(screen.moderationKnowledgeReports.value.items).toHaveLength(1);
    expect(screen.moderationKnowledge.value.items).toHaveLength(0);
  });

  it('counts the results of whichever tab is open', async () => {
    const screen = build();
    await screen.loadModerationTab('knowledge', true);
    expect(screen.moderationResultCount.value).toBe(1);

    screen.moderationTab.value = 'posts';
    expect(screen.moderationResultCount.value).toBe(0);
  });

  it('clears a status that the newly opened tab would not understand', async () => {
    const screen = build();
    screen.adminModerationStatus.value = 'RESOLVED';
    screen.moderationTab.value = 'post-management';
    await nextTick();

    // RESOLVED belongs to a report, not to a post, and must not be sent with the new tab's request.
    expect(screen.adminModerationStatus.value).toBe('');
  });

  it('asks at once when the tab changes, and waits while a keyword is typed', async () => {
    const screen = build();

    screen.moderationTab.value = 'users';
    await nextTick();
    vi.advanceTimersByTime(0);
    await nextTick();
    expect(urls).toHaveLength(1);

    screen.adminModerationKeyword.value = '张';
    await nextTick();
    expect(urls).toHaveLength(1);
    vi.advanceTimersByTime(5);
    await nextTick();
    expect(urls).toHaveLength(2);
    expect(query(urls[1]).keyword).toBe('张');
  });

  it('does not fetch for a screen the reader is not looking at', async () => {
    const screen = build();
    active = false;

    screen.adminModerationKeyword.value = '张';
    await nextTick();
    vi.advanceTimersByTime(50);
    await nextTick();

    expect(urls).toHaveLength(0);
  });

  it('hands the three overviews back to the page with the open tab', async () => {
    const seen: Record<string, unknown>[] = [];
    const screen = build({ onOverview: (sections: any) => seen.push(sections) });

    await screen.loadModeration();

    expect(seen).toHaveLength(1);
    expect(Object.keys(seen[0])).toEqual(['knowledgeAdmin', 'forumAdmin', 'userAdmin']);
    expect(urls.map(path)).toEqual(['/knowledge/admin/files/page']);
  });

  it('stops a pending request when the screen goes away', async () => {
    const screen = build();
    screen.adminModerationKeyword.value = '张';
    await nextTick();

    screen.dispose();
    vi.advanceTimersByTime(50);
    await nextTick();

    expect(urls).toHaveLength(0);
  });
});
