<template>
  <section class="message-page" :class="{ 'has-session': messageForm.sessionId }">
sionId }">
            <section class="surface session-panel"><div class="surface-head"><div><h3>消息中心</h3><p>{{ sessions.length }} 个联系人</p></div><div><el-button :icon="Delete" circle text type="danger" title="清空全部聊天记录" @click="clearAllMessages" /><el-button :icon="Plus" circle title="发起私信" @click="newConversation" /></div></div><div class="session-list"><button v-for="session in sessions" :key="session.id" :class="{ active: messageForm.sessionId === session.id }" @click="openSession(session)"><div class="mini-avatar">{{ sessionPartner(session).nickname.slice(0,1).toUpperCase() }}</div><span><strong>{{ sessionPartner(session).nickname }}</strong><small>{{ session.lastMessage || `@${sessionPartner(session).username}` }}</small></span></button></div><el-empty v-if="!sessions.length" description="暂无私信会话" /></section>
            <section class="surface conversation-panel"><div class="conversation-head"><el-button class="mobile-conversation-back" :icon="ArrowLeft" circle text title="返回联系人列表" @click="closeMobileConversation" /><div><strong>{{ currentMessagePartner?.nickname || '选择联系人开始私信' }}</strong><span v-if="currentMessagePartner">@{{ currentMessagePartner.username }} · 私密会话</span></div><el-button v-if="messageForm.sessionId" :icon="Refresh" :loading="messageRefreshing" circle text title="获取新消息" @click="refreshMessages" /><el-dropdown v-if="messageForm.sessionId"><el-button :icon="MoreFilled" circle text /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="clearCurrentSession">清空当前会话</el-dropdown-item><el-dropdown-item divided @click="deleteCurrentSession">删除整个会话</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div><div ref="messageListRef" class="message-list" @scroll.passive="handleMessageScroll"><div v-if="messageForm.sessionId && messages.length" class="message-history-state"><el-button v-if="messageHasOlder || messageHistoryLoading" text size="small" :loading="messageHistoryLoading" @click="loadOlderMessages">{{ messageHistoryLoading ? '正在加载更早的消息' : '查看更早的消息' }}</el-button><small v-else>已显示全部消息</small></div><div v-for="message in messages" :key="message.id" :class="['message-bubble', message.senderId === currentUserId ? 'mine' : '']"><p>{{ message.content }}</p><div class="message-meta"><small>{{ formatDate(message.createdAt) }}</small><el-button v-if="message.senderId === currentUserId" :icon="Delete" circle text type="danger" title="删除消息" @click="deleteMessage(message)" /></div></div><el-empty v-if="!messages.length" :description="messageForm.sessionId ? '发送第一条消息，开始对话' : '从左侧选择联系人'" /></div><button v-if="messageUnseenCount" type="button" class="message-new-indicator" @click="jumpToLatestMessages">{{ messageUnseenCount }} 条新消息</button><p v-if="openSessionClosed" class="message-compose-notice" role="status">该会话{{ sessionStatusLabel(openSessionStatus) }}，暂时不能发送消息</p><div class="message-compose"><el-input v-model="messageForm.content" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" resize="none" :maxlength="platformConfig.max_message_length" :disabled="!messageForm.sessionId || messageSending || openSessionClosed" placeholder="输入消息，Ctrl+Enter 发送" @keydown.ctrl.enter.prevent="sendMessage" /><el-button type="primary" :icon="Promotion" :loading="messageSending" :disabled="!messageForm.sessionId || !messageForm.content.trim() || openSessionClosed" @click="sendMessage">发送</el-button></div></section>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, type Ref, type ComputedRef } from 'vue';
import { ArrowLeft, Delete, Plus, Refresh } from '@element-plus/icons-vue';
import { formatDate, sessionStatusLabel } from '../utils/statusLabels';
import { ref } from 'vue';
import { deleteData, getData, postData } from '../api/client';
import { MoreFilled, Promotion } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ProfileAuditState } from '../utils/profileAudit';

type ChatMessage = { id:number; sessionId:number; senderId:number; content:string; status:string; createdAt:string };
type ChatSession = { id:number; userAId:number; userBId:number; otherUserId:number; status:string; updatedAt:string; lastMessage:string; messageCount?:number };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string; signature?:string; status:string; role:string; publishPolicy?:string; messagingEnabled?:boolean; email?:string|null; emailVerified?:boolean; profileAudit?:ProfileAuditState|null };

/** What this screen shows belongs to the page that loads it; it arrives together. */
type MessagesViewPage = {
  MESSAGE_PAGE_SIZE:any;
  communityUser:(userId:number)=>any;
  conversationDialog:Ref<boolean>;
  conversationTargetId:Ref<number>;
  conversationTargetUser:Ref<UserRecord|undefined>;
  conversationUsername:Ref<string>;
  currentMessageSession:ComputedRef<any>;
  currentUserId:Ref<any>;
  fetchNewMessages:(manual?:boolean)=>any;
  isMessageListNearBottom:()=>any;
  loadMessageData:(options?:{reloadMessages?:boolean})=>any;
  loadMessages:()=>any;
  loadedMessageSessionId:any;
  mergeMessages:(current:ChatMessage[], incoming:ChatMessage[])=>any;
  messageDrafts:any;
  messageForm:Ref<any>;
  messageHasOlder:Ref<boolean>;
  messageHistoryLoading:Ref<boolean>;
  messageListRef:Ref<HTMLElement|undefined>;
  messagePageUrl:(sessionId:number, cursor?:{beforeId?:number;afterId?:number}, limit?:any)=>any;
  messageRefreshing:Ref<boolean>;
  messageSending:Ref<boolean>;
  messageUnseenCount:Ref<number>;
  messages:Ref<ChatMessage[]>;
  notifyError:(error:unknown)=>any;
  platformConfig:Ref<any>;
  scrollMessagesToBottom:()=>any;
  selectMessageSession:(sessionId:number)=>any;
  sessions:Ref<ChatSession[]>;
  username:Ref<any>;
};

const props = defineProps<{ page: MessagesViewPage }>();

const { MESSAGE_PAGE_SIZE, communityUser, conversationDialog, conversationTargetId, conversationTargetUser, conversationUsername, currentMessageSession, currentUserId, fetchNewMessages, isMessageListNearBottom, loadMessageData, loadMessages, loadedMessageSessionId, mergeMessages, messageDrafts, messageForm, messageHasOlder, messageHistoryLoading, messageListRef, messagePageUrl, messageRefreshing, messageSending, messageUnseenCount, messages, notifyError, platformConfig, scrollMessagesToBottom, selectMessageSession, sessions, username } = props.page;

async function clearAllMessages(){await ElMessageBox.confirm('确认清空全部私信会话中的聊天记录？会话联系人仍会保留。','清空全部聊天记录',{type:'warning',confirmButtonText:'确认清空'});await postData('/message/clear-all',{});await loadMessageData();ElMessage.success('全部聊天记录已清空');}
async function clearCurrentSession(){await ElMessageBox.confirm('确认清空当前会话记录？','清空会话',{type:'warning'});await postData('/message/clear',{sessionId:messageForm.value.sessionId,userId:currentUserId.value});await loadMessageData();}
function closeMobileConversation(){selectMessageSession(0);}
const currentMessagePartner = computed(() => currentMessageSession.value ? sessionPartner(currentMessageSession.value) : undefined);
async function deleteCurrentSession(){if(!messageForm.value.sessionId)return;await ElMessageBox.confirm('删除会话后，该会话及其中消息都无法恢复。','删除会话',{type:'warning',confirmButtonText:'确认删除'});const sessionId=messageForm.value.sessionId;await deleteData('/message/session',{sessionId});selectMessageSession(0);messageDrafts.delete(sessionId);await loadMessageData();ElMessage.success('会话已删除');}
async function deleteMessage(message:ChatMessage){await deleteData('/message',{messageId:message.id,userId:currentUserId.value});messages.value=messages.value.filter(item=>item.id!==message.id);await loadMessageData({reloadMessages:false});ElMessage.success('消息已删除');}
function handleMessageScroll(){
  const list=messageListRef.value;
  if(!list)return;
  if(list.scrollTop<=60)void loadOlderMessages();
  if(isMessageListNearBottom())messageUnseenCount.value=0;
}
function jumpToLatestMessages(){const list=messageListRef.value;if(list)list.scrollTo({top:list.scrollHeight,behavior:'smooth'});messageUnseenCount.value=0;}
async function loadOlderMessages(){
  const sessionId=messageForm.value.sessionId;
  const oldest=messages.value[0];
  if(!sessionId||!oldest||loadedMessageSessionId!==sessionId||!messageHasOlder.value||messageHistoryLoading.value)return;
  messageHistoryLoading.value=true;
  try{
    const older=await getData<ChatMessage[]>(messagePageUrl(sessionId,{beforeId:oldest.id}));
    if(messageForm.value.sessionId!==sessionId||loadedMessageSessionId!==sessionId)return;
    const list=messageListRef.value;
    const distanceFromBottom=list?list.scrollHeight-list.scrollTop:0;
    messages.value=mergeMessages(older,messages.value);
    messageHasOlder.value=older.length>=MESSAGE_PAGE_SIZE;
    await nextTick();
    if(list)list.scrollTop=list.scrollHeight-distanceFromBottom;
  }catch(error){notifyError(error);}
  finally{messageHistoryLoading.value=false;}
}
async function newConversation(){conversationUsername.value='';conversationTargetUser.value=undefined;conversationTargetId.value=0;conversationDialog.value=true;}
async function openSession(session:ChatSession){selectMessageSession(session.id);try{await loadMessages();}catch(error){notifyError(error);}}
const openSessionClosed=computed(()=>Boolean(messageForm.value.sessionId)&&openSessionStatus.value!=='ACTIVE');
const openSessionStatus=computed(()=>sessions.value.find(session=>session.id===messageForm.value.sessionId)?.status||'ACTIVE');
async function refreshMessages(){
  if(messageRefreshing.value)return;
  messageRefreshing.value=true;
  try{const count=await fetchNewMessages(true);if(!count)ElMessage.info('暂无新消息');}
  catch(error){notifyError(error);}
  finally{messageRefreshing.value=false;}
}
async function sendMessage(){
  const sessionId=messageForm.value.sessionId;
  const content=messageForm.value.content.trim();
  if(!sessionId||!content||messageSending.value)return;
  messageSending.value=true;
  try{
    const saved=await postData<ChatMessage>('/message/send',{sessionId,content});
    messageDrafts.set(sessionId,'');
    if(messageForm.value.sessionId===sessionId){
      messageForm.value.content='';
      messages.value=mergeMessages(messages.value,[saved]);
      await nextTick();
      scrollMessagesToBottom();
    }
    const session=sessions.value.find(item=>item.id===sessionId);
    if(session){session.lastMessage=content;session.updatedAt=saved.createdAt;}
  }catch(error){notifyError(error);}
  finally{messageSending.value=false;}
}
function sessionPartner(session:ChatSession){return communityUser(session.otherUserId);}
</script>
