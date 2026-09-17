import { describe, expect, it } from 'vitest';
import { detailPath, linkedCommentFrom, noticeDestination } from './noticeTargets';

describe('noticeDestination', () => {
  it('opens posts at the comment and knowledge files at the top', () => {
    expect(noticeDestination({ type:'POST', id:5, anchorId:9 })).toEqual({ kind:'detail', detail:'community', id:5, commentId:9 });
    expect(noticeDestination({ type:'POST', id:5, anchorId:null })).toEqual({ kind:'detail', detail:'community', id:5, commentId:undefined });
    expect(noticeDestination({ type:'KNOWLEDGE', id:3, anchorId:4 })).toEqual({ kind:'detail', detail:'knowledge', id:3 });
  });

  it('opens conversations and tickets', () => {
    expect(noticeDestination({ type:'CHAT', id:7, anchorId:70 })).toEqual({ kind:'chat', sessionId:7 });
    expect(noticeDestination({ type:'TICKET', id:2 })).toEqual({ kind:'ticket', ticketId:2 });
  });

  it('ignores anything it does not know', () => {
    expect(noticeDestination(undefined)).toBeUndefined();
    expect(noticeDestination(null)).toBeUndefined();
    expect(noticeDestination({ type:'URL', id:1 })).toBeUndefined();
    expect(noticeDestination({ type:'POST', id:0 })).toBeUndefined();
    expect(noticeDestination({ type:'POST', id:1.5 })).toBeUndefined();
    expect(noticeDestination({ type:'POST', id:1, anchorId:-2 })).toEqual({ kind:'detail', detail:'community', id:1, commentId:undefined });
  });
});

describe('detail links', () => {
  it('round-trips the comment through the address', () => {
    expect(detailPath('community', 5, 9)).toBe('/community/5?comment=9');
    expect(detailPath('knowledge', 3)).toBe('/knowledge/3');
    expect(linkedCommentFrom('?comment=9')).toBe(9);
    expect(linkedCommentFrom('?x=1&comment=12')).toBe(12);
  });

  it('reads nothing from a missing or bad value', () => {
    for (const search of ['', '?comment=', '?comment=abc', '?comment=-4', '?comment=1e400', '?comment=2.5']) {
      expect(linkedCommentFrom(search)).toBe(0);
    }
  });
});
