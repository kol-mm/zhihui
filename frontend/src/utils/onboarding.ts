/** The first-visit guide: which steps it shows, and remembering that a member has seen it. */

export type OnboardingStep = { key:string; title:string; body:string; hint:string };

export const ONBOARDING_STEPS:OnboardingStep[] = [
  {
    key: 'knowledge',
    title: '在知识库里查找和上传资料',
    body: '知识库收录平台上的文档与资料，可以按分类浏览、全文搜索，打开后在线阅读 PDF 或正文。',
    hint: '自己的资料也可以上传，经管理员审核后对所有成员可见。',
  },
  {
    key: 'community',
    title: '在社区论坛交流',
    body: '社区论坛用来提问、分享经验和讨论。发帖、评论和点赞都在这里，关注广场只显示你关注的人。',
    hint: '被回复时，通知中心会提醒你。',
  },
  {
    key: 'ai',
    title: '用 AI 问答快速找答案',
    body: 'AI 问答会基于平台上已审核的知识资料回答问题，并给出参考来源，适合快速了解一份资料的内容。',
    hint: '回答来自平台资料，请自行核对关键信息。',
  },
];

const STORAGE_PREFIX = 'ai-knowledge-onboarding-v1-';

export type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

function storageKey(userId:number|string){
  return `${STORAGE_PREFIX}${userId}`;
}

/** True for a member who has not finished or dismissed the guide on this device. */
export function shouldShowOnboarding(userId:number|string|undefined|null, storage:StorageLike|undefined):boolean {
  if (!userId || !storage) return false;
  try {
    return storage.getItem(storageKey(userId)) !== 'done';
  } catch {
    // Blocked site data: a guide that cannot be remembered is not shown, rather than shown on every visit.
    return false;
  }
}

export function markOnboardingSeen(userId:number|string|undefined|null, storage:StorageLike|undefined):void {
  if (!userId || !storage) return;
  try {
    storage.setItem(storageKey(userId), 'done');
  } catch {
    // Nothing to do: the guide simply appears again next time.
  }
}
