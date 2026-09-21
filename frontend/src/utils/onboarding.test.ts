import { describe, expect, it, vi } from 'vitest';
import { markOnboardingSeen, ONBOARDING_STEPS, shouldShowOnboarding, type StorageLike } from './onboarding';

function fakeStorage():StorageLike & { map:Map<string, string> } {
  const map = new Map<string, string>();
  return { map, getItem: (key:string) => map.get(key) ?? null, setItem: (key:string, value:string) => { map.set(key, value); } };
}

describe('first-visit guide', () => {
  it('covers knowledge, community and AI', () => {
    expect(ONBOARDING_STEPS.map(step => step.key)).toEqual(['knowledge', 'community', 'ai']);
    ONBOARDING_STEPS.forEach(step => {
      expect(step.title.length).toBeGreaterThan(0);
      expect(step.body.length).toBeGreaterThan(0);
    });
  });

  it('shows once per member and then stays away', () => {
    const storage = fakeStorage();
    expect(shouldShowOnboarding(7, storage)).toBe(true);
    markOnboardingSeen(7, storage);
    expect(shouldShowOnboarding(7, storage)).toBe(false);
    expect(shouldShowOnboarding(8, storage)).toBe(true, );
  });

  it('needs a member and a place to remember it', () => {
    const storage = fakeStorage();
    expect(shouldShowOnboarding(0, storage)).toBe(false);
    expect(shouldShowOnboarding(undefined, storage)).toBe(false);
    expect(shouldShowOnboarding(7, undefined)).toBe(false);
    markOnboardingSeen(undefined, storage);
    expect(storage.map.size).toBe(0);
  });

  it('stays quiet when storage is blocked', () => {
    const blocked:StorageLike = {
      getItem: vi.fn(() => { throw new Error('blocked'); }),
      setItem: vi.fn(() => { throw new Error('blocked'); }),
    };
    expect(shouldShowOnboarding(7, blocked)).toBe(false);
    expect(() => markOnboardingSeen(7, blocked)).not.toThrow();
  });
});
