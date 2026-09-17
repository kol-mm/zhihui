// Keeps a scrolling list steady while items are inserted above what the reader is looking at.
// The page lists comments by id, so a thread opened from a notification can sit before comments that are
// loaded later; without an anchor those arrive above it and push it out of view.

export type AnchorItem = { id:string; top:number; bottom:number };
export type ScrollAnchor = { id:string; offset:number };

/**
 * Chooses the item to hold in place: the preferred one (the linked comment) while it is on screen, otherwise the
 * first item still showing at the top. Positions are viewport coordinates, as getBoundingClientRect reports them.
 */
export function pickScrollAnchor(
  view:{ top:number; bottom:number; scrollTop:number },
  items:AnchorItem[],
  preferredId?:string
):ScrollAnchor|undefined {
  if (view.scrollTop <= 0) return undefined;
  const visible = (item:AnchorItem) => item.bottom > view.top && item.top < view.bottom;
  const preferred = preferredId ? items.find(item => item.id === preferredId && visible(item)) : undefined;
  const anchor = preferred || items.find(item => item.bottom > view.top);
  return anchor ? { id:anchor.id, offset:anchor.top - view.top } : undefined;
}

/** How far to scroll so the anchor is back where it was; 0 when it has not moved or is gone. */
export function anchorShift(anchor:ScrollAnchor, viewTop:number, itemTop:number|undefined):number {
  if (itemTop === undefined) return 0;
  const shift = itemTop - viewTop - anchor.offset;
  return Math.abs(shift) < 1 ? 0 : shift;
}

function itemsIn(list:HTMLElement):AnchorItem[] {
  return [...list.querySelectorAll<HTMLElement>('[data-comment-id]')].map(element => {
    const rect = element.getBoundingClientRect();
    return { id:element.dataset.commentId || '', top:rect.top, bottom:rect.bottom };
  });
}

export function captureScrollAnchor(list:HTMLElement|undefined, preferredId?:number):ScrollAnchor|undefined {
  if (!list) return undefined;
  const rect = list.getBoundingClientRect();
  return pickScrollAnchor({ top:rect.top, bottom:rect.bottom, scrollTop:list.scrollTop }, itemsIn(list), preferredId ? String(preferredId) : undefined);
}

export function restoreScrollAnchor(list:HTMLElement|undefined, anchor:ScrollAnchor|undefined):void {
  if (!list || !anchor) return;
  const element = list.querySelector<HTMLElement>(`[data-comment-id="${anchor.id}"]`);
  const shift = anchorShift(anchor, list.getBoundingClientRect().top, element?.getBoundingClientRect().top);
  if (shift) list.scrollTop += shift;
}
