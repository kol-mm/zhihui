import { describe, expect, it } from 'vitest';
import { auditDecisionNote, auditSourceLabel, auditSourceTone, decidedByAi } from './auditSource';

describe('who decided a review', () => {
  it('names the two kinds of reviewer', () => {
    expect(auditSourceLabel('AI')).toBe('AI 判定');
    expect(auditSourceLabel('MANUAL')).toBe('人工审核');
    expect(auditSourceLabel('manual')).toBe('人工审核');
  });

  it('says nothing when nobody has decided yet', () => {
    expect(auditSourceLabel(undefined)).toBe('');
    expect(auditSourceLabel('')).toBe('');
    expect(auditSourceLabel('  ')).toBe('');
    expect(auditDecisionNote(null, null)).toBe('');
  });

  it('shows an unfamiliar source as it came rather than hiding it', () => {
    expect(auditSourceLabel('IMPORTED')).toBe('IMPORTED');
  });

  it('tells the AI apart from a person', () => {
    expect(decidedByAi('AI')).toBe(true);
    expect(decidedByAi('ai')).toBe(true);
    expect(decidedByAi('MANUAL')).toBe(false);
    expect(decidedByAi('')).toBe(false);
    expect(auditSourceTone('AI')).toBe('info');
    expect(auditSourceTone('MANUAL')).toBe('success');
  });

  it('writes one line saying who decided and why', () => {
    expect(auditDecisionNote('AI', '命中平台敏感词规则')).toBe('AI 判定：命中平台敏感词规则');
    expect(auditDecisionNote('MANUAL', '与知识库主题无关')).toBe('人工审核：与知识库主题无关');
  });

  it('copes when only one half is known', () => {
    expect(auditDecisionNote('AI', '')).toBe('AI 判定');
    expect(auditDecisionNote('AI', '   ')).toBe('AI 判定');
    expect(auditDecisionNote('', '历史记录没有来源')).toBe('历史记录没有来源');
  });
});
