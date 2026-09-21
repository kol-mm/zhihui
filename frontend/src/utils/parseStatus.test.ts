import { describe, expect, it } from 'vitest';
import { isFullTextIndexed, parseStatusHint, parseStatusLabel, parseStatusTone } from './parseStatus';

describe('knowledge parse status', () => {
  it('names each status a reader would see', () => {
    expect(parseStatusLabel('INDEXED')).toBe('全文可搜');
    expect(parseStatusLabel('TOO_LARGE')).toBe('仅标题检索');
    expect(parseStatusLabel('EMPTY')).toBe('未提取到正文');
    expect(parseStatusLabel('PENDING')).toBe('待解析');
  });

  it('says nothing when the file carries no status, so no tag is shown', () => {
    expect(parseStatusLabel(undefined)).toBe('');
    expect(parseStatusLabel('')).toBe('');
    expect(parseStatusLabel('   ')).toBe('');
    expect(parseStatusHint(undefined)).toBe('');
  });

  it('shows an unfamiliar status as it came, rather than hiding it', () => {
    expect(parseStatusLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW');
    expect(parseStatusTone('SOMETHING_NEW')).toBe('info');
    expect(parseStatusHint('SOMETHING_NEW')).toContain('只能按标题');
  });

  it('marks only indexed files as searchable by their text', () => {
    expect(isFullTextIndexed('INDEXED')).toBe(true);
    expect(isFullTextIndexed('indexed')).toBe(true);
    expect(isFullTextIndexed('TOO_LARGE')).toBe(false);
    expect(isFullTextIndexed('')).toBe(false);
  });

  it('warns about a large file and stays quiet about an indexed one', () => {
    expect(parseStatusTone('TOO_LARGE')).toBe('warning');
    expect(parseStatusHint('TOO_LARGE')).toContain('文件较大');
    expect(parseStatusTone('INDEXED')).toBe('success');
    expect(parseStatusHint('INDEXED')).toBe('');
  });

  it('explains an empty extraction differently from one that never ran', () => {
    expect(parseStatusHint('EMPTY')).toContain('没有从文件中提取到文本');
    expect(parseStatusHint('PENDING')).toContain('尚未建立索引');
  });
});
