/** The words the admin screens put on a status, and the way they show a moment in time. */

export function auditLabel(status:string){ return ({APPROVED:'已通过',PENDING:'待审核',REJECTED:'已驳回',HIDDEN:'已下架'} as Record<string,string>)[status] || status; }
export function postStatusLabel(status:string){ return ({PUBLISHED:'已发布',PENDING:'待审核',HIDDEN:'已隐藏'} as Record<string,string>)[status] || status; }
export function reportStatusLabel(status:string){ return ({PENDING:'待处理',PROCESSING:'处理中',RESOLVED:'已结案',REJECTED:'已驳回'} as Record<string,string>)[status] || status; }
export function roleLabel(value:string){ return ({ADMIN:'平台管理员',USER:'社区用户'} as Record<string,string>)[value] || value; }
export function publishPolicyLabel(value?:string){ return ({STANDARD:'标准审核',PRE_REVIEW:'强制预审',BLOCKED:'禁止发布'} as Record<string,string>)[value || 'STANDARD'] || value || '标准审核'; }
export function sessionStatusLabel(value:string){ return ({ACTIVE:'正常',RESTRICTED:'已限制',ARCHIVED:'已封存'} as Record<string,string>)[value] || value; }
export function formatDate(value:string){ if(!value)return ''; const time=new Date(value); return Number.isNaN(time.getTime())?value:time.toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}); }
