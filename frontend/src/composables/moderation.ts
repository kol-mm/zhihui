import { computed, ref, watch, type Ref } from 'vue';

/**
 * 内容审核: six queues of things waiting for an administrator, and the paging they share.
 *
 * Every admin list here — the six moderation tabs and the five in the governance dialog — reads a keyset page,
 * appends without repeating what it already holds, and keeps the first page's total while later pages send
 * null. A list asked for again while a request is still out drops the older answer rather than letting it
 * arrive last and win.
 *
 * The page keeps what this cannot: the dialogs that act on a row, and the admin overview these counts feed.
 * Those arrive as the callbacks below, so this module never reaches back into the page.
 */

export type ModerationList<T> = { items:T[]; cursor:number|string|null; hasMore:boolean; total:number; loading:boolean };
export type ModerationPage<T> = { items:T[]; nextCursor:number|string|null; hasMore:boolean; total:number|null };
export const emptyModerationList = <T,>():ModerationList<T> => ({items:[],cursor:null,hasMore:false,total:0,loading:false});
export const MODERATION_PAGE_SIZE = 20;

type Row = { id:number };
export type OverviewSections = { knowledgeAdmin:Record<string,unknown>; forumAdmin:Record<string,unknown>; userAdmin:Record<string,unknown> };

/**
 * One loader for every admin list. `key` is what keeps two lists from cancelling each other's requests: each
 * key counts its own, and an answer to a question that has since been asked again is dropped.
 */
export function createListLoader(fetchPage:<T>(url:string)=>Promise<ModerationPage<T>>){
  const tokens:Record<string,number> = {};
  return async function loadList<T extends Row>(key:string,list:{value:ModerationList<T>},reset:boolean,url:(cursor:number|string|null)=>string){
    const token=(tokens[key]||0)+1;tokens[key]=token;
    list.value={...list.value,loading:true};
    try{
      const page=await fetchPage<T>(url(reset?null:list.value.cursor));
      if(token!==tokens[key])return;
      const known=new Set(reset?[]:list.value.items.map(item=>item.id));
      list.value={items:reset?page.items:[...list.value.items,...page.items.filter(item=>!known.has(item.id))],cursor:page.nextCursor,hasMore:page.hasMore,total:page.total??list.value.total,loading:false};
    } finally { if(token===tokens[key]&&list.value.loading)list.value={...list.value,loading:false}; }
  };
}

/**
 * Which endpoint a tab reads and which status it may ask for. The tabs do not share a status vocabulary — a
 * report is PENDING/PROCESSING/RESOLVED, a post is PUBLISHED/HIDDEN — so a status carried over from another
 * tab must not be sent, and two tabs insist on a default rather than asking for everything.
 */
export function moderationPageUrl(tab:string,cursor:number|string|null,keyword='',status=''){
  const params=new URLSearchParams({limit:String(MODERATION_PAGE_SIZE)});
  if(cursor)params.set('cursor',String(cursor));
  const trimmed=keyword.trim();
  if(trimmed)params.set('keyword',trimmed);
  if(tab==='knowledge'||tab==='reports'||tab==='users'){
    if(status)params.set('status',status);
    const base=({knowledge:'/knowledge/admin/files/page',reports:'/knowledge/admin/reports/page',users:'/user/admin/reports/page'} as Record<string,string>)[tab];
    return `${base}?${params.toString()}`;
  }
  if(tab==='profiles'){
    params.set('status',status||'PENDING');
    return `/user/admin/profile-changes/page?${params.toString()}`;
  }
  params.set('status',tab==='posts'?'PENDING':status||'PUBLISHED,HIDDEN');
  return `/post/admin/posts/page?${params.toString()}`;
}

/** The statuses a tab offers, in the words the tab uses for them. */
export function moderationStatusChoices(tab:string){
  if(['reports','users'].includes(tab))return [{label:'待处理',value:'PENDING'},{label:'处理中',value:'PROCESSING'},{label:'已结案',value:'RESOLVED'}];
  if(tab==='post-management')return [{label:'已发布',value:'PUBLISHED'},{label:'已隐藏',value:'HIDDEN'}];
  if(tab==='posts')return [{label:'待审核',value:'PENDING'}];
  if(tab==='profiles')return [{label:'待审核',value:'PENDING'},{label:'已通过',value:'APPROVED'},{label:'已驳回',value:'REJECTED'}];
  return [{label:'待审核',value:'PENDING'},{label:'已通过',value:'APPROVED'},{label:'已驳回',value:'REJECTED'},{label:'已下架',value:'HIDDEN'}];
}

export function useModeration<K extends Row,P extends Row,R extends Row,C extends Row>(deps:{
  loadList:<T extends Row>(key:string,list:{value:ModerationList<T>},reset:boolean,url:(cursor:number|string|null)=>string)=>Promise<void>;
  fetchOverview:(url:string)=>Promise<Record<string,unknown>>;
  onOverview:(sections:OverviewSections)=>void;
  onError:(error:unknown)=>void;
  /** Whether 内容审核 is the screen in front of the reader; the filters do not fetch when it is not. */
  isActive:()=>boolean;
  debounceMs?:number;
}){
  const debounceMs = deps.debounceMs ?? 250;
  const moderationTab = ref('knowledge');
  const adminModerationKeyword = ref('');
  const adminModerationStatus = ref('');

  const moderationKnowledge = ref(emptyModerationList<K>()) as Ref<ModerationList<K>>;
  const moderationPendingPosts = ref(emptyModerationList<P>()) as Ref<ModerationList<P>>;
  const moderationManagedPosts = ref(emptyModerationList<P>()) as Ref<ModerationList<P>>;
  const moderationKnowledgeReports = ref(emptyModerationList<R>()) as Ref<ModerationList<R>>;
  const moderationUserReports = ref(emptyModerationList<R>()) as Ref<ModerationList<R>>;
  const moderationProfileChanges = ref(emptyModerationList<C>()) as Ref<ModerationList<C>>;

  const moderationStatusOptions = computed(()=>moderationStatusChoices(moderationTab.value));
  const moderationResultCount = computed(()=>({
    knowledge:moderationKnowledge.value.total,
    reports:moderationKnowledgeReports.value.total,
    users:moderationUserReports.value.total,
    posts:moderationPendingPosts.value.total,
    profiles:moderationProfileChanges.value.total,
    'post-management':moderationManagedPosts.value.total,
  }[moderationTab.value]||0));

  const pageUrl = (tab:string) => (cursor:number|string|null) =>
    moderationPageUrl(tab,cursor,adminModerationKeyword.value,adminModerationStatus.value);

  // Only the open tab is fetched; the report tabs keep filtering their short lists in the browser.
  async function loadModerationTab(tab=moderationTab.value,reset=true){
    if(tab==='knowledge')await deps.loadList(tab,moderationKnowledge,reset,pageUrl(tab));
    else if(tab==='posts')await deps.loadList(tab,moderationPendingPosts,reset,pageUrl(tab));
    else if(tab==='profiles')await deps.loadList(tab,moderationProfileChanges,reset,pageUrl(tab));
    else if(tab==='post-management')await deps.loadList(tab,moderationManagedPosts,reset,pageUrl(tab));
    else if(tab==='reports')await deps.loadList(tab,moderationKnowledgeReports,reset,pageUrl(tab));
    else if(tab==='users')await deps.loadList(tab,moderationUserReports,reset,pageUrl(tab));
  }

  async function loadMoreModeration(){ await loadModerationTab(moderationTab.value,false); }

  async function loadModeration(){
    const [knowledgeAdmin,forumAdmin,userAdmin]=await Promise.all([
      deps.fetchOverview('/knowledge/admin/overview'),
      deps.fetchOverview('/post/admin/overview'),
      deps.fetchOverview('/user/admin/overview'),
      loadModerationTab(moderationTab.value,true)]);
    deps.onOverview({knowledgeAdmin,forumAdmin,userAdmin});
  }

  let moderationFilterTimer:ReturnType<typeof setTimeout>|undefined;
  // Switching tab asks at once; typing waits, so a keyword is not sent letter by letter.
  watch([moderationTab,adminModerationKeyword,adminModerationStatus],([tab],[previousTab])=>{
    if(!deps.isActive())return;
    clearTimeout(moderationFilterTimer);
    moderationFilterTimer=setTimeout(()=>void loadModerationTab(moderationTab.value,true).catch(deps.onError),tab!==previousTab?0:debounceMs);
  });
  // Declared after the one above so it runs second: a status belonging to the tab just left would otherwise be
  // sent to the tab just opened, which does not know that word.
  watch(moderationTab,()=>adminModerationStatus.value='');

  function dispose(){ clearTimeout(moderationFilterTimer); }

  return {
    moderationTab, adminModerationKeyword, adminModerationStatus,
    moderationKnowledge, moderationPendingPosts, moderationManagedPosts,
    moderationKnowledgeReports, moderationUserReports, moderationProfileChanges,
    moderationStatusOptions, moderationResultCount,
    loadModerationTab, loadMoreModeration, loadModeration, dispose,
  };
}
