import { describe, expect, it } from 'vitest';
import { anchorShift, pickScrollAnchor, type AnchorItem } from './scrollAnchor';

// A list whose viewport spans y=100..500; each item is 80px tall.
const view = { top:100, bottom:500, scrollTop:640 };
const items = (ids:string[], firstTop:number):AnchorItem[] =>
  ids.map((id, index) => ({ id, top:firstTop + index * 80, bottom:firstTop + index * 80 + 80 }));

describe('pickScrollAnchor', () => {
  it('holds the first item still showing at the top', () => {
    // 9 spans y=10..90, above the view; 10 spans 90..170 and is cut at the top.
    expect(pickScrollAnchor(view, items(['9', '10', '11', '12'], 10), undefined)).toEqual({ id:'10', offset:-10 });
  });

  it('prefers the linked comment while it is on screen', () => {
    expect(pickScrollAnchor(view, items(['9', '10', '11', '12'], 10), '12')).toEqual({ id:'12', offset:150 });
    // 16 starts at y=570, below the view, so it is no help: fall back to the top item.
    expect(pickScrollAnchor(view, items(['9', '10', '11', '12', '13', '14', '15', '16'], 10), '16')).toEqual({ id:'10', offset:-10 });
  });

  it('does nothing at the top of the list or without items', () => {
    expect(pickScrollAnchor({ ...view, scrollTop:0 }, items(['1'], 100), undefined)).toBeUndefined();
    expect(pickScrollAnchor(view, [], '1')).toBeUndefined();
  });
});

describe('anchorShift', () => {
  it('scrolls by exactly the height of what was inserted above', () => {
    const anchor = pickScrollAnchor(view, items(['10', '12'], 90), '12')!;
    // Loading comment 11 puts an 80px item between 10 and 12.
    const after = items(['10', '11', '12'], 90).find(item => item.id === '12')!;
    expect(anchorShift(anchor, view.top, after.top)).toBe(80);
  });

  it('ignores sub-pixel moves and missing anchors', () => {
    expect(anchorShift({ id:'1', offset:20 }, 100, 120.4)).toBe(0);
    expect(anchorShift({ id:'1', offset:20 }, 100, undefined)).toBe(0);
    expect(anchorShift({ id:'1', offset:20 }, 100, 60)).toBe(-60);
  });
});
