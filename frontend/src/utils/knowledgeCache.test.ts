import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createKnowledgeCache, KNOWLEDGE_CACHE_TTL_MS, type StorageLike } from './knowledgeCache';

function fakeStorage():StorageLike & { map:Map<string, string> } {
  const map = new Map<string, string>();
  return {
    map,
    getItem: (key:string) => map.get(key) ?? null,
    setItem: (key:string, value:string) => { map.set(key, value); },
    removeItem: (key:string) => { map.delete(key); },
  };
}

const detail = (id:number, title = '标题') => ({ id, title, updatedAt: '2026-09-21T08:00:00Z' });

describe('knowledge detail cache', () => {
  let clock = 1_000;
  beforeEach(() => { clock = 1_000; });
  const cache = () => createKnowledgeCache({ storage: fakeStorage(), now: () => clock });

  it('returns what was stored for the same viewer', () => {
    const store = cache();
    store.write(7, 1, detail(7));
    expect(store.read(7, 1)).toEqual(detail(7));
    expect(store.read(7, 2)).toBeNull();
    expect(store.read(8, 1)).toBeNull();
  });

  it('expires entries after the time to live', () => {
    const store = cache();
    store.write(7, 1, detail(7));
    clock += KNOWLEDGE_CACHE_TTL_MS - 1;
    expect(store.read(7, 1)).not.toBeNull();
    clock += 2;
    expect(store.read(7, 1)).toBeNull();
    expect(store.size()).toBe(0);
  });

  it('drops one document or all of them', () => {
    const store = cache();
    store.write(1, 1, detail(1));
    store.write(2, 1, detail(2));
    store.invalidate(1);
    expect(store.read(1, 1)).toBeNull();
    expect(store.read(2, 1)).not.toBeNull();
    store.invalidate();
    expect(store.size()).toBe(0);
  });

  it('notices when the server copy differs', () => {
    const store = cache();
    store.write(7, 1, detail(7, '旧标题'));
    expect(store.changed(7, 1, detail(7, '旧标题'))).toBe(false);
    expect(store.changed(7, 1, detail(7, '新标题'))).toBe(true);
    expect(store.changed(9, 1, detail(9))).toBe(true, );
    expect(store.changed(7, 1, null)).toBe(false);
  });

  it('survives a reload through storage, and keeps only the newest entries', () => {
    const storage = fakeStorage();
    const first = createKnowledgeCache({ storage, now: () => clock });
    for (let id = 1; id <= 35; id++) {
      clock += 1;
      first.write(id, 1, detail(id));
    }
    expect(first.size()).toBe(30);
    expect(first.read(1, 1)).toBeNull();
    expect(first.read(35, 1)).not.toBeNull();

    const reopened = createKnowledgeCache({ storage, now: () => clock });
    expect(reopened.read(35, 1)).toEqual(detail(35));
  });

  it('works without any storage at all', () => {
    const store = createKnowledgeCache({ now: () => clock });
    store.write(7, 1, detail(7));
    expect(store.read(7, 1)).toEqual(detail(7));
  });

  it('ignores unreadable stored data', () => {
    const storage = fakeStorage();
    storage.map.set('ai-knowledge-detail-cache-v1', 'not json');
    const store = createKnowledgeCache({ storage, now: () => clock });
    expect(store.size()).toBe(0);
    store.write(7, 1, detail(7));
    expect(store.read(7, 1)).not.toBeNull();
  });

  it('keeps working when storage refuses to save', () => {
    const storage = { ...fakeStorage(), setItem: vi.fn(() => { throw new Error('quota'); }) };
    const store = createKnowledgeCache({ storage, now: () => clock });
    store.write(7, 1, detail(7));
    expect(store.read(7, 1)).toEqual(detail(7));
  });
});
