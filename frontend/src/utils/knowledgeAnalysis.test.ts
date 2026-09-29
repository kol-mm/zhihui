import { describe, expect, it } from 'vitest';
import { suggestedCategoryLabel } from './knowledgeAnalysis';

const names = new Map([[3, '人工智能'], [7, '数据库']]);

describe('the suggested category', () => {
  it('is named when it differs from the category the file has', () => {
    expect(suggestedCategoryLabel({ categoryId: 7, suggestedCategoryId: 3 }, names)).toBe('AI 建议分类：人工智能');
  });

  it('says nothing when it was applied, or when there is none', () => {
    expect(suggestedCategoryLabel({ categoryId: 3, suggestedCategoryId: 3 }, names)).toBe('');
    expect(suggestedCategoryLabel({ categoryId: 3, suggestedCategoryId: null }, names)).toBe('');
    expect(suggestedCategoryLabel({}, names)).toBe('');
  });

  it('falls back to the number when the name is not known', () => {
    expect(suggestedCategoryLabel({ categoryId: null, suggestedCategoryId: 42 }, new Map())).toBe('AI 建议分类：#42');
  });
});
