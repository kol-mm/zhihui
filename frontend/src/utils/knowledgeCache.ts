/**
 * A small cache for knowledge detail payloads, so reopening a document is instant.
 *
 * Entries are kept per viewer (liked/collected differ per member), expire after a few minutes, and are dropped
 * whenever the document is edited, reviewed or deleted. A cached entry is shown at once and then refreshed in the
 * background, so a stale title or body corrects itself without the reader waiting.
 */

export type CachedKnowledge = { id:number; updatedAt?:string|null; [key:string]:unknown };

type Entry = { viewerId:number; storedAt:number; detail:CachedKnowledge };

export type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export const KNOWLEDGE_CACHE_TTL_MS = 5 * 60 * 1000;
const STORAGE_KEY = 'ai-knowledge-detail-cache-v1';
const MAX_ENTRIES = 30;

function safeStorage():StorageLike|undefined {
  try {
    const probe = '__zh_probe__';
    localStorage.setItem(probe, '1');
    localStorage.removeItem(probe);
    return localStorage;
  } catch {
    // Private windows and blocked site data: the cache stays in memory for this page only.
    return undefined;
  }
}

export function createKnowledgeCache(options:{ storage?:StorageLike; now?:()=>number; ttlMs?:number } = {}) {
  const now = options.now ?? (() => Date.now());
  const ttl = options.ttlMs ?? KNOWLEDGE_CACHE_TTL_MS;
  const storage = options.storage;
  const entries = new Map<number, Entry>();
  let loaded = false;

  function load() {
    if (loaded) return;
    loaded = true;
    if (!storage) return;
    try {
      const saved = JSON.parse(storage.getItem(STORAGE_KEY) || '[]') as [number, Entry][];
      if (Array.isArray(saved)) saved.forEach(([id, entry]) => { if (entry?.detail) entries.set(id, entry); });
    } catch {
      // Unreadable cache: start empty rather than fail the page.
    }
  }

  function persist() {
    if (!storage) return;
    try {
      storage.setItem(STORAGE_KEY, JSON.stringify([...entries.entries()]));
    } catch {
      // Out of quota: the in-memory cache still works for this page.
    }
  }

  function prune() {
    if (entries.size <= MAX_ENTRIES) return;
    [...entries.entries()]
      .sort((left, right) => left[1].storedAt - right[1].storedAt)
      .slice(0, entries.size - MAX_ENTRIES)
      .forEach(([id]) => entries.delete(id));
  }

  return {
    /** The cached detail for this viewer, or null when there is none or it has expired. */
    read(id:number, viewerId:number):CachedKnowledge|null {
      load();
      const entry = entries.get(id);
      if (!entry || entry.viewerId !== viewerId) return null;
      if (now() - entry.storedAt > ttl) {
        entries.delete(id);
        persist();
        return null;
      }
      return entry.detail;
    },

    write(id:number, viewerId:number, detail:CachedKnowledge|null|undefined) {
      load();
      if (!detail || !id) return;
      entries.set(id, { viewerId, storedAt: now(), detail });
      prune();
      persist();
    },

    /** Drops one document, or everything when no id is given (sign-out, a bulk change). */
    invalidate(id?:number) {
      load();
      if (id === undefined) entries.clear();
      else entries.delete(id);
      persist();
    },

    /** True when the server's copy differs from what was cached, so the page needs redrawing. */
    changed(id:number, viewerId:number, detail:CachedKnowledge|null|undefined):boolean {
      if (!detail) return false;
      const cached = this.read(id, viewerId);
      return !cached || JSON.stringify(cached) !== JSON.stringify(detail);
    },

    size():number {
      load();
      return entries.size;
    },
  };
}

export const knowledgeCache = createKnowledgeCache({ storage: safeStorage() });
