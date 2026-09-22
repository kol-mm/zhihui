<template>
  <section class="page-stack">
<div class="page-toolbar"><div><h3>用户管理</h3><p>账号资料、状态、权限和个人内容治理</p></div><el-button :icon="Download" :loading="adminUserExporting" @click="exportAdminUsers">导出筛选结果</el-button></div><section class="surface admin-list-surface"><div class="admin-filter-bar admin-user-filters"><el-input v-model="adminUserKeyword" :prefix-icon="Search" clearable placeholder="搜索 ID、用户名、昵称或邮箱" /><el-select v-model="adminUserRoleFilter" clearable placeholder="全部角色"><el-option label="管理员" value="ADMIN" /><el-option label="普通用户" value="USER" /></el-select><el-select v-model="adminUserStatusFilter" clearable placeholder="全部状态"><el-option label="正常" value="ACTIVE" /><el-option label="已停用" value="DISABLED" /></el-select><span>找到 <strong>{{ adminUserTotal }}</strong> 位</span></div><div class="admin-summary-strip"><span>全部<strong>{{ metric('totalUsers') }}</strong></span><span>正常<strong>{{ metric('activeUsers') }}</strong></span><span>已停用<strong>{{ metric('riskUsers') }}</strong></span><span>管理员<strong>{{ metric('admins') }}</strong></span></div><el-table class="admin-desktop-table" :data="adminUsers"><el-table-column label="用户" min-width="190"><template #default="scope"><div class="table-user"><div class="mini-avatar">{{ scope.row.nickname.slice(0, 1) }}</div><span><strong>{{ scope.row.nickname }}</strong><small>@{{ scope.row.username }}</small></span></div></template></el-table-column><el-table-column label="邮箱" min-width="190"><template #default="scope"><span v-if="scope.row.email" class="admin-email-cell"><span>{{ scope.row.email }}</span><el-tag v-if="scope.row.emailVerified" size="small" type="success" effect="plain">已验证</el-tag></span><span v-else class="muted-text">未绑定</span></template></el-table-column><el-table-column label="角色" width="120"><template #default="scope"><span class="stacked-status">{{ roleLabel(scope.row.role) }}<el-tag v-if="scope.row.superAdmin" size="small" type="warning" effect="plain">超级管理员</el-tag></span></template></el-table-column><el-table-column label="发帖权限" width="112"><template #default="scope">{{ publishPolicyLabel(scope.row.publishPolicy) }}</template></el-table-column><el-table-column prop="status" label="状态" width="90"><template #default="scope"><el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'danger'">{{ scope.row.status === 'ACTIVE' ? '正常' : '已停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="200" fixed="right"><template #default="scope"><el-button text type="primary" @click="$emit('governance', scope.row)">资料与治理</el-button><el-button v-if="scope.row.id !== currentUserId" text :type="scope.row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggleUserStatus(scope.row)">{{ scope.row.status === 'ACTIVE' ? '禁用' : '恢复' }}</el-button></template></el-table-column></el-table><div class="admin-mobile-cards admin-user-cards"><article v-for="user in adminUsers" :key="user.id"><header><div class="table-user"><div class="mini-avatar">{{ user.nickname.slice(0,1) }}</div><span><strong>{{ user.nickname }}</strong><small>@{{ user.username }} · #{{ user.id }}</small></span></div><el-tag size="small" :type="user.status==='ACTIVE'?'success':'danger'">{{ user.status==='ACTIVE'?'正常':'已停用' }}</el-tag></header><p>{{ roleLabel(user.role) }} · {{ publishPolicyLabel(user.publishPolicy) }}</p><p v-if="user.email" class="admin-email-cell"><span>{{ user.email }}</span><el-tag v-if="user.emailVerified" size="small" type="success" effect="plain">已验证</el-tag></p><footer><el-button type="primary" plain @click="$emit('governance', user)">资料与治理</el-button><el-button v-if="user.id !== currentUserId" :type="user.status==='ACTIVE'?'danger':'success'" plain @click="toggleUserStatus(user)">{{ user.status==='ACTIVE'?'禁用账号':'恢复账号' }}</el-button></footer></article><el-empty v-if="!adminUsers.length" description="没有匹配的用户" /></div><div v-if="adminUserHasMore" class="knowledge-load-more"><el-button :loading="adminUserLoading" @click="loadMoreAdminUsers">加载更多用户</el-button></div></section><section class="surface admin-list-surface reset-request-surface"><div class="surface-head"><div><h3>密码重置申请</h3><p>核实申请人身份后签发一次性重置码；重置码 30 分钟内有效，只显示一次</p></div><div class="reset-request-tools"><el-tag v-if="resetRequestPending" type="warning" effect="plain">{{ resetRequestPending }} 条待处理</el-tag><el-select v-model="resetRequestStatus" class="reset-status-filter" aria-label="按状态筛选重置申请"><el-option label="待处理" value="PENDING" /><el-option label="已签发" value="ISSUED" /><el-option label="输错过多" value="EXPIRED" /><el-option label="已完成" value="COMPLETED" /><el-option label="已关闭" value="CLOSED" /><el-option label="全部状态" value="" /></el-select><el-button :icon="Refresh" circle title="刷新申请" :loading="resetRequestLoading" @click="loadResetRequests()" /></div></div><el-table class="admin-desktop-table" :data="resetRequests" empty-text="没有符合条件的重置申请"><el-table-column prop="id" label="编号" width="80" /><el-table-column label="账号" min-width="200"><template #default="scope"><div class="table-user"><div class="mini-avatar">{{ (scope.row.nickname || scope.row.username).slice(0, 1) }}</div><span><strong>{{ scope.row.nickname }}</strong><small>@{{ scope.row.username }}<template v-if="scope.row.accountStatus !== 'ACTIVE'"> · 账号已停用</template></small></span></div></template></el-table-column><el-table-column label="联系方式" min-width="200"><template #default="scope"><span class="reset-contact"><span>{{ scope.row.contact || '未填写' }}</span><small v-if="scope.row.email">绑定邮箱：{{ scope.row.email }}{{ scope.row.emailVerified ? '（已验证）' : '（未验证）' }}</small></span></template></el-table-column><el-table-column label="状态" width="130"><template #default="scope"><el-tag :type="resetStatusType(scope.row)" effect="plain">{{ resetStatusLabel(scope.row) }}</el-tag></template></el-table-column><el-table-column label="申请时间" width="130"><template #default="scope">{{ formatDate(scope.row.createdAt) }}</template></el-table-column><el-table-column label="操作" width="210" fixed="right"><template #default="scope"><el-button v-if="canIssueReset(scope.row)" text type="primary" @click="issueResetCode(scope.row)">{{ scope.row.status === 'PENDING' ? '签发重置码' : '重新签发' }}</el-button><el-button v-if="resetRequestOpen(scope.row)" text type="danger" @click="closeResetRequest(scope.row)">关闭</el-button><span v-if="scope.row.note" class="reset-request-note">{{ scope.row.note }}</span></template></el-table-column></el-table><div class="admin-mobile-cards admin-user-cards"><article v-for="item in resetRequests" :key="item.id"><header><div class="table-user"><div class="mini-avatar">{{ (item.nickname || item.username).slice(0, 1) }}</div><span><strong>{{ item.nickname }}</strong><small>@{{ item.username }} · #{{ item.id }}</small></span></div><el-tag size="small" :type="resetStatusType(item)">{{ resetStatusLabel(item) }}</el-tag></header><p>联系方式：{{ item.contact || '未填写' }}<template v-if="item.email"> · 绑定邮箱：{{ item.email }}{{ item.emailVerified ? '（已验证）' : '（未验证）' }}</template> · {{ formatDate(item.createdAt) }}<template v-if="item.accountStatus !== 'ACTIVE'"> · 账号已停用</template></p><p v-if="item.note">{{ item.note }}</p><footer v-if="resetRequestOpen(item)"><el-button v-if="canIssueReset(item)" type="primary" plain @click="issueResetCode(item)">{{ item.status === 'PENDING' ? '签发重置码' : '重新签发' }}</el-button><el-button type="danger" plain @click="closeResetRequest(item)">关闭申请</el-button></footer></article><el-empty v-if="!resetRequests.length" description="没有符合条件的重置申请" /></div><div v-if="resetRequestHasMore" class="knowledge-load-more"><el-button :loading="resetRequestLoading" @click="loadResetRequests(false)">加载更多申请</el-button></div></section>

<el-dialog v-model="resetCodeDialog" class="reset-code-dialog" title="重置码已签发" width="min(460px, 92vw)" :close-on-click-modal="false" @closed="issuedResetCode = null"><template v-if="issuedResetCode"><p>请把重置码私下转交给 <strong>{{ issuedResetCode.nickname }}</strong>（@{{ issuedResetCode.username }}）<template v-if="issuedResetCode.contact">，申请人留下的联系方式：{{ issuedResetCode.contact }}</template>。</p><div class="reset-code-value">{{ issuedResetCode.code }}</div><p class="reset-code-meta">{{ formatClock(issuedResetCode.expiresAt) }} 前有效 · 只能使用一次 · 输错 5 次失效</p><el-alert type="warning" :closable="false" show-icon title="关闭此窗口后无法再次查看，遗失请重新签发。不要在公开渠道发送重置码。" /></template><template #footer><el-button :icon="CopyDocument" @click="copyResetCode">复制重置码</el-button><el-button type="primary" @click="resetCodeDialog = false">完成</el-button></template></el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { CopyDocument, Download, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { getData, isSessionExpiredError, postData, toUserMessage } from '../api/client';
import { csvValue, localDateStamp } from '../utils/csvExport';
import { formatDate, publishPolicyLabel, roleLabel } from '../utils/statusLabels';
import type { ProfileAuditState } from '../utils/profileAudit';

type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string; signature?:string; status:string; role:string; publishPolicy?:string; messagingEnabled?:boolean; email?:string|null; emailVerified?:boolean; profileAudit?:ProfileAuditState|null };
// total is only sent with the first page; later pages send null and the table keeps the first figure.
type AdminPage<T> = { items:T[]; nextCursor:number|string|null; hasMore:boolean; total:number|null };

const props = defineProps<{ currentUserId:number; refreshKey:number; metrics?:Record<string,unknown> }>();
const emit = defineEmits<{ governance:[user:UserRecord]; overview:[metrics:Record<string,unknown>] }>();

// The strip above the table shows site-wide counts, which belong to the page that owns the admin overview; the
// filters narrow the table below it without changing them.
function metric(key:string){ const value=props.metrics?.[key]; return typeof value==='number'?value:0; }
function notifyError(error:unknown){ if(isSessionExpiredError(error))return; ElMessage.error(toUserMessage(error)); }

const adminUserKeyword = ref(''); const adminUserRoleFilter = ref(''); const adminUserStatusFilter = ref('');
const ADMIN_USER_PAGE_SIZE = 20; const ADMIN_EXPORT_PAGE_SIZE = 100; const ADMIN_EXPORT_MAX_PAGES = 50;
const adminUserTotal = ref(0); const adminUserCursor = ref<number|string|null>(null); const adminUserHasMore = ref(false); const adminUserLoading = ref(false); const adminUserExporting = ref(false); let adminUserToken = 0;
const adminUsers = ref<UserRecord[]>([]);

async function toggleUserStatus(user:UserRecord){if(user.status==='ACTIVE'){if(user.id===props.currentUserId){ElMessage.warning('不能停用当前登录的管理员账号');return;}try{await ElMessageBox.confirm(`停用“${user.nickname}”后，该用户将无法登录。确定继续吗？`,'确认停用账号',{type:'warning',confirmButtonText:'停用账号'});}catch{return;}}await postData('/user/admin/status',{userId:user.id,status:user.status==='ACTIVE'?'DISABLED':'ACTIVE'});await loadAdminUsers(true);ElMessage.success('账号状态已更新');}
async function exportAdminUsers(){
  if(adminUserExporting.value)return;
  adminUserExporting.value=true;
  const matching:UserRecord[]=[];
  try{
    let cursor:number|string|null=null;
    for(let round=0;round<ADMIN_EXPORT_MAX_PAGES;round++){
      const page:AdminPage<UserRecord>=await getData<AdminPage<UserRecord>>(adminUsersUrl(cursor,ADMIN_EXPORT_PAGE_SIZE));
      matching.push(...page.items);
      cursor=page.nextCursor;
      if(!page.hasMore)break;
    }
  }catch(error){notifyError(error);return;}finally{adminUserExporting.value=false;}
  if(!matching.length){ElMessage.warning('当前筛选条件下没有可导出的用户');return;}
  const rows=[['用户ID','用户名','昵称','角色','账号状态','发帖策略'],...matching.map(user=>[user.id,user.username,user.nickname,roleLabel(user.role),user.status==='ACTIVE'?'正常':'已停用',publishPolicyLabel(user.publishPolicy)])];const blob=new Blob([`\ufeff${rows.map(row=>row.map(csvValue).join(',')).join('\r\n')}`],{type:'text/csv;charset=utf-8'});const url=URL.createObjectURL(blob);const link=document.createElement('a');link.href=url;link.download=`zhihui-users-${localDateStamp()}.csv`;link.click();URL.revokeObjectURL(url);ElMessage.success(`已导出 ${matching.length} 位用户`);}
function adminUsersUrl(cursor:number|string|null,limit=ADMIN_USER_PAGE_SIZE){
  const params=new URLSearchParams({limit:String(limit)});
  if(cursor)params.set('cursor',String(cursor));
  if(adminUserKeyword.value.trim())params.set('keyword',adminUserKeyword.value.trim());
  if(adminUserStatusFilter.value)params.set('status',adminUserStatusFilter.value);
  if(adminUserRoleFilter.value)params.set('role',adminUserRoleFilter.value);
  return `/user/admin/users/page?${params.toString()}`;
}
// The filters and the cursor go to the server; the summary strip keeps showing site-wide counts.
async function loadAdminUsers(reset=true){
  if(reset)void loadResetRequests();
  const token=++adminUserToken;adminUserLoading.value=true;
  try{
    const [page,overview]=await Promise.all([
      getData<AdminPage<UserRecord>>(adminUsersUrl(reset?null:adminUserCursor.value)),
      reset?getData<Record<string,unknown>>('/user/admin/overview'):Promise.resolve(undefined)]);
    if(token!==adminUserToken)return;
    const known=new Set(reset?[]:adminUsers.value.map(user=>user.id));
    adminUsers.value=reset?page.items:[...adminUsers.value,...page.items.filter(user=>!known.has(user.id))];
    adminUserCursor.value=page.nextCursor;adminUserHasMore.value=page.hasMore;adminUserTotal.value=page.total??adminUserTotal.value;
    if(overview)emit('overview',overview);
  } finally { if(token===adminUserToken)adminUserLoading.value=false; }
}
type ResetRequestRecord = { id:number; userId:number; username:string; nickname:string; accountStatus:string; contact:string; email?:string|null; emailVerified?:boolean; status:string; codeExpired:boolean; failedAttempts:number; note:string; createdAt:string; updatedAt:string };
const resetRequests = ref<ResetRequestRecord[]>([]); const resetRequestStatus = ref('PENDING'); const resetRequestCursor = ref<number|string|null>(null); const resetRequestHasMore = ref(false); const resetRequestLoading = ref(false); const resetRequestPending = ref(0); let resetRequestToken = 0;
const resetCodeDialog = ref(false); const issuedResetCode = ref<{ code:string; expiresAt:number; username:string; nickname:string; contact:string }|null>(null);
async function loadResetRequests(reset=true){
  const token=++resetRequestToken;resetRequestLoading.value=true;
  try{
    const params=new URLSearchParams({limit:'20'});
    if(resetRequestStatus.value)params.set('status',resetRequestStatus.value);
    if(!reset&&resetRequestCursor.value)params.set('cursor',String(resetRequestCursor.value));
    const page=await getData<AdminPage<ResetRequestRecord>&{pending:number}>(`/user/admin/password-resets/page?${params.toString()}`);
    if(token!==resetRequestToken)return;
    const known=new Set(reset?[]:resetRequests.value.map(item=>item.id));
    resetRequests.value=reset?page.items:[...resetRequests.value,...page.items.filter(item=>!known.has(item.id))];
    resetRequestCursor.value=page.nextCursor;resetRequestHasMore.value=page.hasMore;resetRequestPending.value=page.pending;
  }catch(error){if(token===resetRequestToken)notifyError(error);}
  finally{if(token===resetRequestToken)resetRequestLoading.value=false;}
}
function resetRequestOpen(item:ResetRequestRecord){return ['PENDING','ISSUED','EXPIRED'].includes(item.status);}
function canIssueReset(item:ResetRequestRecord){return resetRequestOpen(item)&&item.accountStatus==='ACTIVE';}
function resetStatusLabel(item:ResetRequestRecord){
  if(item.status==='ISSUED')return item.codeExpired?'重置码已过期':'已签发';
  return ({PENDING:'待处理',EXPIRED:'输错过多',COMPLETED:'已完成',CLOSED:'已关闭'} as Record<string,string>)[item.status]||item.status;
}
function resetStatusType(item:ResetRequestRecord){
  if(item.status==='PENDING')return 'warning';
  if(item.status==='ISSUED')return item.codeExpired?'info':'primary';
  if(item.status==='COMPLETED')return 'success';
  return item.status==='EXPIRED'?'danger':'info';
}
async function issueResetCode(item:ResetRequestRecord){
  const again=item.status!=='PENDING';
  try{await ElMessageBox.confirm(`${again?'重新签发后，之前的重置码立即失效。':''}请先通过其他渠道确认申请人确实是 @${item.username} 的主人，再签发重置码。`,again?'重新签发重置码':'签发重置码',{type:'warning',confirmButtonText:'签发',cancelButtonText:'取消'});}catch{return;}
  try{
    const result=await postData<{code:string;expiresInSeconds:number}>('/user/admin/password-reset/issue',{requestId:item.id});
    issuedResetCode.value={code:result.code,expiresAt:Date.now()+result.expiresInSeconds*1000,username:item.username,nickname:item.nickname,contact:item.contact};
    resetCodeDialog.value=true;
    await loadResetRequests();
  }catch(error){notifyError(error);}
}
async function closeResetRequest(item:ResetRequestRecord){
  let note='';
  try{const {value}=await ElMessageBox.prompt('可填写处理说明（选填）。关闭后该申请不能再签发重置码。','关闭重置申请',{confirmButtonText:'关闭申请',cancelButtonText:'取消',inputPlaceholder:'如：无法核实身份',inputValidator:(value:string)=>(value||'').length<=255||'说明不能超过 255 个字符'});note=(value||'').trim();}catch{return;}
  try{await postData('/user/admin/password-reset/close',{requestId:item.id,note});ElMessage.success('申请已关闭');await loadResetRequests();}catch(error){notifyError(error);}
}
async function copyResetCode(){
  if(!issuedResetCode.value)return;
  try{await navigator.clipboard.writeText(issuedResetCode.value.code);ElMessage.success('重置码已复制');}catch{ElMessage.warning('无法访问剪贴板，请手动选中重置码复制');}
}
function formatClock(timestamp:number){return new Date(timestamp).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'});}
watch(resetRequestStatus,()=>{void loadResetRequests();});
async function loadMoreAdminUsers(){ if(adminUserHasMore.value&&!adminUserLoading.value)await loadAdminUsers(false); }
let adminUserFilterTimer:ReturnType<typeof setTimeout>|undefined;
watch([adminUserKeyword,adminUserStatusFilter,adminUserRoleFilter],()=>{
  clearTimeout(adminUserFilterTimer);
  adminUserFilterTimer=setTimeout(()=>void loadAdminUsers(true).catch(notifyError),250);
});
onBeforeUnmount(()=>clearTimeout(adminUserFilterTimer));

onMounted(()=>void loadAdminUsers(true).catch(notifyError));
// The page asks for a refresh by changing this number, which leaves the filters as the administrator set them.
watch(()=>props.refreshKey,()=>void loadAdminUsers(true).catch(notifyError));
</script>
