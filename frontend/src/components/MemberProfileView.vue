<template>
  <section class="page-stack">
ge-stack">
            <div class="page-toolbar"><div><h3>个人中心</h3><p>管理资料、关注关系和反馈工单</p></div><el-button type="primary" @click="saveProfile">保存资料</el-button></div>
            <div class="profile-layout"><section class="surface profile-card"><div class="profile-avatar"><img v-if="avatarUrl" :src="resolveApiUrl(avatarUrl)" alt="头像" /><span v-else>{{ displayName.slice(0, 1).toUpperCase() }}</span></div><h3>{{ displayName }}</h3><p>@{{ username }}</p><el-tag>{{ roleLabel(role) }}</el-tag><el-button v-if="avatarUrl" class="remove-avatar" text type="danger" @click="removeAvatar">删除头像</el-button><el-button class="onboarding-replay" text type="primary" @click="openOnboarding">查看新手引导</el-button><div class="profile-counts"><span><strong>{{ followData.followedUserIds?.length || 0 }}</strong>关注</span><span><strong>{{ followData.followerUserIds?.length || 0 }}</strong>粉丝</span><span><strong>{{ drafts.length }}</strong>草稿</span></div></section><section class="surface profile-form"><h3>基础资料</h3><el-alert v-if="profileAudit?.status==='PENDING'" class="profile-audit-notice" type="warning" :closable="false" show-icon title="资料修改待管理员审核"><ul class="profile-diff"><li v-for="change in profileAuditPending" :key="change.field">{{ change.description }}</li></ul><small>审核通过前，其他成员看到的仍是原来的资料。再次保存可以修改提交的内容。</small></el-alert><el-alert v-else-if="profileAudit?.status==='REJECTED'" class="profile-audit-notice" type="error" :closable="false" show-icon title="资料修改未通过审核"><p>{{ profileAudit.reason || '资料未通过审核' }}</p><small>可以修改后重新提交。</small></el-alert><el-form label-position="top"><el-form-item label="昵称"><el-input v-model="profileForm.nickname" /></el-form-item><el-form-item label="个性签名"><el-input v-model="profileForm.signature" type="textarea" :rows="3" /></el-form-item></el-form><el-divider>邮箱</el-divider><EmailBinding /><el-divider>修改密码</el-divider><el-form label-position="top"><el-form-item label="当前密码"><el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item><el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" maxlength="128" placeholder="8-128 位，需包含字母和数字" /></el-form-item><el-button type="primary" plain @click="changePassword">更新密码</el-button></el-form></section><section class="surface feedback-card"><div class="surface-head"><div><h3>我的反馈</h3><p>问题进度与官方回复</p></div><el-button v-if="platformConfig.feedback_enabled" :icon="Plus" circle @click="feedbackDialog = true" /></div><article v-for="ticket in tickets" :key="ticket.id" :data-ticket-id="ticket.id" :class="{ 'is-linked': linkedTicketId === ticket.id }"><div><strong>{{ ticketTypeLabel(ticket.type) }}</strong><p>{{ ticket.content }}</p><small v-if="ticket.reply">官方回复：{{ ticket.reply }}</small></div><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag></article><el-empty v-if="!tickets.length" description="暂无反馈工单" /></section></div>
            <section class="surface"><div class="surface-head"><div><h3>我的知识资源</h3><p>统一查看上传、收藏、点赞、下载和转发记录</p></div><el-tag type="info">{{ myKnowledgeTotal }} 条</el-tag><el-radio-group v-model="knowledgeActivityType" size="small" @change="loadMyKnowledge"><el-radio-button value="UPLOADED">上传</el-radio-button><el-radio-button value="COLLECTED">收藏</el-radio-button><el-radio-button value="LIKED">点赞</el-radio-button><el-radio-button value="DOWNLOADED">下载</el-radio-button><el-radio-button value="FORWARDED">转发</el-radio-button></el-radio-group></div><div class="activity-list"><article v-for="file in myKnowledge" :key="file.id"><span class="file-type">{{ file.fileType?.toUpperCase() }}</span><div><strong>{{ file.title }}</strong><p>资源 #{{ file.id }} · {{ auditLabel(file.auditStatus) }}</p></div><el-button text type="primary" @click="openKnowledge(file)">阅读</el-button></article><div v-if="myKnowledgeHasMore || myKnowledgeLoading || myKnowledgeError" class="knowledge-load-more"><el-button text type="primary" :loading="myKnowledgeLoading" @click="loadMoreMyKnowledge">{{ myKnowledgeLoading ? '正在加载记录' : myKnowledgeError ? `${myKnowledgeError}，点击重试` : '加载更多记录' }}</el-button></div><el-empty v-if="!myKnowledge.length" description="暂无对应资源记录" /></div></section>
            <section class="surface"><div class="surface-head"><div><h3>收藏的社区帖子</h3><p>集中查看收藏内容，进入帖子后可继续参与讨论</p></div><el-tag type="info">{{ collectedPosts.length }} 篇</el-tag></div><div class="activity-list"><article v-for="post in collectedPosts" :key="post.id"><span class="file-type">帖子</span><div><strong>{{ post.title }}</strong><p>帖子 #{{ post.id }} · {{ post.likes || 0 }} 赞</p></div><div class="collection-actions"><el-button text type="primary" @click="openPostDetail(post)">查看</el-button><el-button text type="danger" @click="removeCollectedPost(post)">取消收藏</el-button></div></article><el-empty v-if="!collectedPosts.length" description="暂无收藏的社区帖子" /></div></section>
            <div class="two-column account-tools">
              <section class="surface"><div class="surface-head"><div><h3>关系与安全</h3><p>输入完整用户名后管理关注、拉黑和举报</p></div></div><el-form label-position="top"><el-form-item label="对方完整用户名"><div class="exact-user-search"><el-input v-model="relationForm.username" placeholder="输入完整用户名" clearable @clear="relationTargetUser = undefined; relationForm.targetUserId = 0" @keyup.enter="resolveRelationUser" /><el-button :icon="Search" @click="resolveRelationUser">查找</el-button></div></el-form-item></el-form><div v-if="relationTargetUser" class="exact-user-result"><strong>{{ relationTargetUser.nickname }}</strong><span>@{{ relationTargetUser.username }}</span></div><div class="relation-actions"><el-button type="primary" @click="followUser">关注</el-button><el-button @click="unfollowUser">取消关注</el-button><el-button type="warning" @click="blockUser">拉黑</el-button><el-button @click="unblockUser">解除拉黑</el-button><el-button type="danger" text @click="reportUser">举报用户</el-button></div><div class="relation-summary"><span>已关注 <strong>{{ followedUsers.length }} 人</strong></span><span>粉丝 <strong>{{ followerUsers.length }} 人</strong></span><span>黑名单 <strong>{{ blockedUserIds.length }} 人</strong></span></div><el-tabs class="relation-lists"><el-tab-pane :label="`关注 ${followedUsers.length}`"><div class="compact-user-list"><article v-for="user in followedUsers" :key="user.id"><div class="mini-avatar">{{ user.nickname.slice(0,1) }}</div><span><strong>{{ user.nickname }}</strong><small>@{{ user.username }}</small></span><el-button text type="primary" @click="loadAuthorPostsFromProfile(user.id)">查看动态</el-button></article><el-empty v-if="!followedUsers.length" description="还没有关注用户" /></div></el-tab-pane><el-tab-pane :label="`粉丝 ${followerUsers.length}`"><div class="compact-user-list"><article v-for="user in followerUsers" :key="user.id"><div class="mini-avatar">{{ user.nickname.slice(0,1) }}</div><span><strong>{{ user.nickname }}</strong><small>@{{ user.username }}</small></span><el-button text type="primary" @click="loadAuthorPostsFromProfile(user.id)">查看动态</el-button></article><el-empty v-if="!followerUsers.length" description="暂无粉丝" /></div></el-tab-pane></el-tabs></section>
              <section class="surface"><el-tabs v-model="profileToolTab"><el-tab-pane label="行为足迹" name="activity"><div class="activity-list behavior-list"><article v-for="item in behaviors" :key="item.id" :class="{ 'activity-clickable': behaviorTargetCanOpen(item) }" :role="behaviorTargetCanOpen(item) ? 'link' : undefined" :tabindex="behaviorTargetCanOpen(item) ? 0 : undefined" @click="openBehaviorTarget(item)" @keydown.enter="openBehaviorTarget(item)"><span class="event-dot"></span><div><strong>{{ behaviorActionLabel(item.action) }} · {{ behaviorTargetLabel(item.targetType) }}</strong><p>{{ behaviorTargetLabel(item.targetType) }} #{{ item.targetId }}</p><small>{{ formatDate(item.createdAt) }}</small></div><ArrowRight v-if="behaviorTargetCanOpen(item)" class="activity-arrow" /></article><el-empty v-if="!behaviors.length" description="暂无行为记录" /></div></el-tab-pane><el-tab-pane :label="`我的草稿 ${drafts.length}`" name="drafts"><article v-for="draft in drafts" :key="draft.id" class="draft-row"><div><strong>{{ draft.title }}</strong><p>{{ draft.content }}</p><small>{{ draft.imageUrls?.length || 0 }} 张图片 · {{ formatDate(draft.updatedAt || '') }}</small></div><div class="draft-actions"><el-button text type="primary" @click="editDraft(draft)">编辑</el-button><el-button text type="success" @click="publishDraft(draft)">发布</el-button><el-button text type="danger" @click="deleteDraft(draft)">删除</el-button></div></article><el-empty v-if="!drafts.length" description="暂无草稿" /></el-tab-pane></el-tabs></section>
            </div>
            <section class="surface faq-help"><div class="surface-head"><div><h3>使用帮助</h3><p>常见问题与平台使用说明</p></div><el-tag type="info">{{ faqs.length }} 条</el-tag></div><el-collapse><el-collapse-item v-for="faq in faqs" :key="faq.id" :title="faq.question" :name="faq.id"><p>{{ faq.answer }}</p></el-collapse-item></el-collapse><el-empty v-if="!faqs.length" description="暂无使用帮助" /></section>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, type Ref } from 'vue';
import EmailBinding from './EmailBinding.vue';
import { deleteData, getData, postData, resolveApiUrl, toUserMessage } from '../api/client';
import { ProfileAuditState, pendingChanges } from '../utils/profileAudit';
import { auditLabel, formatDate, roleLabel } from '../utils/statusLabels';
import { ArrowRight, Plus, Search } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { UploadRawFile, UploadUserFile } from 'element-plus';

type BehaviorRecord = { id:number; action:string; targetType:string; targetId:number; createdAt:string };
type Draft = { id:number; title:string; content:string; userId:number; imageUrls?:string[]; updatedAt?:string };
type Faq = { id:number; question:string; answer:string; sortNo:number };
type KnowledgeContentBlock = { type:'image'|'heading'|'list'|'paragraph'|'table'; text?:string; url?:string };
type KnowledgeFile = { id:number; userId:number; categoryId?:number; title:string; fileType:string; auditStatus:string; parseStatus?:string; auditSource?:string; auditReason?:string; fileUrl?:string; content?:string; contentBlocks?:KnowledgeContentBlock[]; imageUrls?:string[]; coverUrl?:string; views?:number; downloads?:number; likes?:number; liked?:boolean; collected?:boolean; createdAt?:string };
type KnowledgePage = { items:KnowledgeFile[]; nextCursor:number|null; hasMore:boolean; total:number };
type Post = { id:number; userId:number; title:string; content:string; status:string; auditSource?:string; auditReason?:string; imageUrls?:string[]; likes?:number; liked?:boolean; collected?:boolean; commentCount?:number; createdAt?:string };
type Ticket = { id:number; userId:number; type:string; content:string; status:string; reply?:string; assigneeUserId?:number; assignedAt?:string; closedAt?:string; createdAt?:string; updatedAt?:string };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string; signature?:string; status:string; role:string; publishPolicy?:string; messagingEnabled?:boolean; email?:string|null; emailVerified?:boolean; profileAudit?:ProfileAuditState|null };

/** What this screen shows belongs to the page that loads it; it arrives together. */
type MemberProfileViewPage = {
  profileToolTab:Ref<string>;
  activeView:Ref<any>;
  applyMyKnowledgePage:(page:KnowledgePage, reset:boolean)=>any;
  applyOwnProfile:(user:UserRecord)=>any;
  avatarUrl:Ref<any>;
  behaviorActionLabel:(action:string)=>any;
  behaviorTargetLabel:(target:string)=>any;
  behaviors:Ref<BehaviorRecord[]>;
  blockedUserIds:Ref<number[]>;
  collectPost:(post:Post)=>any;
  collectedPosts:Ref<Post[]>;
  communityUser:(userId:number)=>any;
  currentUserId:Ref<any>;
  displayName:Ref<any>;
  drafts:Ref<Draft[]>;
  editingDraftId:Ref<number>;
  editingPostId:Ref<number>;
  existingImageFiles:(imageUrls?:string[])=>any;
  faqs:Ref<Faq[]>;
  feedbackDialog:Ref<boolean>;
  followData:Ref<{ followedUserIds?:number[]; followerUserIds?:number[] }>;
  knowledgeActivityType:Ref<string>;
  linkedTicketId:Ref<number>;
  loadAuthorPosts:(userId:number)=>any;
  loadDrafts:()=>any;
  loadMyKnowledge:()=>any;
  loadProfile:()=>any;
  myKnowledge:Ref<KnowledgeFile[]>;
  myKnowledgeCursor:Ref<number|null>;
  myKnowledgeError:Ref<string>;
  myKnowledgeHasMore:Ref<boolean>;
  myKnowledgeLoading:Ref<boolean>;
  myKnowledgeToken:any;
  myKnowledgeTotal:Ref<number>;
  myKnowledgeUrl:(cursor?:number|null)=>any;
  nicknameImpersonatesStaff:(nickname:string)=>any;
  notifyError:(error:unknown)=>any;
  onboardingVisible:Ref<boolean>;
  openKnowledge:(file:KnowledgeFile)=>any;
  openPostDetail:(post:Post)=>any;
  passwordForm:Ref<any>;
  passwordRuleMessage:(password:string, accountName:string)=>any;
  platformConfig:Ref<any>;
  postDialog:Ref<boolean>;
  postForm:Ref<any>;
  postImageFiles:Ref<UploadUserFile[]>;
  profileAudit:Ref<ProfileAuditState|null>;
  profileForm:Ref<any>;
  relationForm:Ref<any>;
  relationTarget:()=>any;
  relationTargetUser:Ref<UserRecord|undefined>;
  role:Ref<any>;
  selectedPostImages:Ref<UploadRawFile[]>;
  ticketStatusLabel:(status:string)=>any;
  ticketTypeLabel:(type:string)=>any;
  tickets:Ref<Ticket[]>;
  username:Ref<any>;
};

const props = defineProps<{ page: MemberProfileViewPage }>();

const { activeView, applyMyKnowledgePage, applyOwnProfile, avatarUrl, behaviorActionLabel, behaviorTargetLabel, behaviors, blockedUserIds, collectPost, collectedPosts, communityUser, currentUserId, displayName, drafts, editingDraftId, editingPostId, existingImageFiles, faqs, feedbackDialog, followData, knowledgeActivityType, linkedTicketId, loadAuthorPosts, loadDrafts, loadMyKnowledge, loadProfile, myKnowledge, myKnowledgeCursor, myKnowledgeError, myKnowledgeHasMore, myKnowledgeLoading, myKnowledgeToken, myKnowledgeTotal, myKnowledgeUrl, nicknameImpersonatesStaff, notifyError, onboardingVisible, openKnowledge, openPostDetail, passwordForm, passwordRuleMessage, platformConfig, postDialog, postForm, postImageFiles, profileAudit, profileForm, profileToolTab, relationForm, relationTarget, relationTargetUser, role, selectedPostImages, ticketStatusLabel, ticketTypeLabel, tickets, username } = props.page;

function behaviorTargetCanOpen(item:BehaviorRecord){return item.targetId>0&&['KNOWLEDGE','POST'].includes(item.targetType);}
async function blockUser(){if(!relationTarget())return;await postData('/user/block',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已加入黑名单');}
async function changePassword(){if(!passwordForm.value.currentPassword){ElMessage.warning('请输入当前密码');return;}const passwordRule=passwordRuleMessage(passwordForm.value.newPassword,username.value);if(passwordRule){ElMessage.warning(passwordRule);return;}if(passwordForm.value.newPassword===passwordForm.value.currentPassword){ElMessage.warning('新密码不能与当前密码相同');return;}try{await postData('/user/password',passwordForm.value);passwordForm.value={currentPassword:'',newPassword:''};ElMessage.success('密码已更新，其他设备上的登录已退出');}catch(error){notifyError(error);}}
async function deleteDraft(draft:Draft){await ElMessageBox.confirm(`确认删除草稿“${draft.title}”？`,'删除草稿',{type:'warning'});await deleteData('/post/draft',{draftId:draft.id});await loadDrafts();ElMessage.success('草稿已删除');}
function editDraft(draft:Draft){editingDraftId.value=draft.id;editingPostId.value=0;postForm.value={userId:currentUserId.value,title:draft.title,content:draft.content};selectedPostImages.value=[];postImageFiles.value=existingImageFiles(draft.imageUrls);postDialog.value=true;}
async function followUser(){if(!relationTarget())return;await postData('/user/follow',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已关注用户');}
const followedUsers = computed(() => (followData.value.followedUserIds || []).map(communityUser));
const followerUsers = computed(() => (followData.value.followerUserIds || []).map(communityUser));
async function loadAuthorPostsFromProfile(userId:number){activeView.value='forum';await loadAuthorPosts(userId);}
async function loadMoreMyKnowledge(){
  if(!myKnowledgeHasMore.value||myKnowledgeLoading.value)return;
  const token=myKnowledgeToken;
  myKnowledgeLoading.value=true;
  myKnowledgeError.value='';
  try{
    const page=await getData<KnowledgePage>(myKnowledgeUrl(myKnowledgeCursor.value));
    if(token!==myKnowledgeToken)return;
    applyMyKnowledgePage(page,false);
  }catch(error){if(token===myKnowledgeToken)myKnowledgeError.value=toUserMessage(error,'记录加载失败');}
  finally{myKnowledgeLoading.value=false;}
}
function openBehaviorTarget(item:BehaviorRecord){if(!behaviorTargetCanOpen(item))return;if(item.targetType==='KNOWLEDGE')openKnowledge({id:item.targetId} as KnowledgeFile);else openPostDetail({id:item.targetId} as Post);}
function openOnboarding(){onboardingVisible.value=true;}
const profileAuditPending = computed(()=>profileAudit.value?.status==='PENDING'?pendingChanges({nickname:displayName.value,signature:profileForm.value.signature},profileAudit.value):[]);
async function publishDraft(draft:Draft){await ElMessageBox.confirm(`确认发布草稿“${draft.title}”？`,'发布草稿',{type:'info'});await postData('/post/draft/publish',{id:draft.id});await loadDrafts();ElMessage.success('帖子已提交审核');}
async function removeAvatar(){await ElMessageBox.confirm('确认删除当前头像？','删除头像',{type:'warning'});const user=await postData<UserRecord>('/user/profile',{...profileForm.value,avatarUrl:''});profileForm.value.avatarUrl='';applyOwnProfile(user);ElMessage.success('头像已删除');}
async function removeCollectedPost(post:Post){if(post.collected)await collectPost(post);}
async function reportUser(){if(!relationTarget())return;const {value}=await ElMessageBox.prompt('请填写举报原因','举报用户',{inputValue:'发布不当内容'});await postData('/user/report',{targetUserId:relationForm.value.targetUserId,reason:value});ElMessage.success('用户举报已提交');}
async function resolveRelationUser(){const query=relationForm.value.username.trim();if(!query){relationTargetUser.value=undefined;relationForm.value.targetUserId=0;return;}try{const user=await getData<UserRecord>(`/user/info?username=${encodeURIComponent(query)}`);if(user.id===currentUserId.value)throw new Error('不能对自己执行社交操作');relationTargetUser.value=user;relationForm.value.targetUserId=user.id;}catch(error){relationTargetUser.value=undefined;relationForm.value.targetUserId=0;notifyError(error);}}
async function saveProfile(){const nickname=profileForm.value.nickname.trim();if(role.value!=='ADMIN'&&nickname!==displayName.value&&nicknameImpersonatesStaff(nickname)){ElMessage.warning('昵称不能冒充平台管理员、官方或客服，请更换');return;}const user=await postData<UserRecord>('/user/profile',profileForm.value);applyOwnProfile(user);
  ElMessage.success(user.profileAudit?.status==='PENDING'?'资料已提交，等待管理员审核':'资料已保存');}
async function unblockUser(){if(!relationTarget())return;await deleteData('/user/block',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已解除拉黑');}
async function unfollowUser(){if(!relationTarget())return;await deleteData('/user/follow',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已取消关注');}
</script>
