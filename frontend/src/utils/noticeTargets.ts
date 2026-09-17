// Where a notification leads. The server stores a target type, an id and optionally an item inside it.

export type NoticeTarget = { type:string; id:number; anchorId?:number|null };

export type NoticeDestination =
  | { kind:'detail'; detail:'community'|'knowledge'; id:number; commentId?:number }
  | { kind:'chat'; sessionId:number }
  | { kind:'ticket'; ticketId:number };

export function noticeDestination(target:NoticeTarget|null|undefined):NoticeDestination|undefined {
  if (!target || !Number.isSafeInteger(target.id) || target.id <= 0) return undefined;
  const anchorId = target.anchorId && Number.isSafeInteger(target.anchorId) && target.anchorId > 0 ? target.anchorId : undefined;
  switch (target.type) {
    case 'POST': return { kind:'detail', detail:'community', id:target.id, commentId:anchorId };
    case 'KNOWLEDGE': return { kind:'detail', detail:'knowledge', id:target.id };
    case 'CHAT': return { kind:'chat', sessionId:target.id };
    case 'TICKET': return { kind:'ticket', ticketId:target.id };
    default: return undefined;
  }
}

/** The detail page path, with the comment to show when there is one. */
export function detailPath(detail:'community'|'knowledge', id:number, commentId?:number):string {
  return `/${detail}/${id}${commentId ? `?comment=${commentId}` : ''}`;
}

/** The comment named in the address bar, or 0. */
export function linkedCommentFrom(search:string):number {
  const value = Number(new URLSearchParams(search).get('comment'));
  return Number.isSafeInteger(value) && value > 0 ? value : 0;
}
