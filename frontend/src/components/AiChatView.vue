<template>
  <section class="ai-page" :class="{ 'history-open': mobileAiHistoryOpen }">
ryOpen }">
            <section class="surface ai-chat"><div class="ai-heading"><span class="ai-symbol"><MagicStick /></span><div><h3>知识库 AI</h3><p>回答将优先引用平台已收录的知识片段</p></div><el-button class="mobile-ai-history-toggle" :icon="ChatLineRound" circle text title="查看对话历史" @click="mobileAiHistoryOpen = !mobileAiHistoryOpen" /></div><div class="ai-messages"><div v-if="!aiMessages.length" class="ai-empty"><MagicStick /><h3>今天想了解什么？</h3><p>试试询问平台知识、文档内容或社区使用方式。</p><div><button v-for="prompt in aiPrompts" :key="prompt" @click="aiQuestion = prompt">{{ prompt }}</button></div></div><div v-for="(message, index) in aiMessages" :key="index" :class="['ai-message', message.role]"><span>{{ message.role === 'assistant' ? 'AI' : displayName.slice(0, 1) }}</span><div class="ai-message-content"><p>{{ message.content }}</p><div v-if="message.references?.length" class="ai-references"><strong>知识来源</strong><button v-for="reference in message.references" :key="reference.id" @click="openAiReference(reference)"><Document /><span>{{ reference.title }}</span><small>知识 #{{ reference.file_id }}</small><ArrowRight /></button></div></div></div></div><div class="ai-compose"><el-input v-model="aiQuestion" type="textarea" :rows="2" resize="none" placeholder="向知识库提问..." @keydown.ctrl.enter="askAi" /><el-button type="primary" :icon="Promotion" :loading="aiBusy" @click="askAi">发送</el-button></div></section>
            <aside class="surface ai-history"><div class="surface-head"><div><h3>对话历史</h3><p>最近的 AI 会话</p></div><div class="ai-history-head-actions"><el-button class="mobile-ai-history-toggle" :icon="Close" circle text title="返回问答" @click="mobileAiHistoryOpen = false" /><el-button :icon="Plus" circle title="新建会话" @click="newAiSession" /><el-button :icon="Refresh" circle text title="刷新会话" @click="loadAiHistory" /></div></div><el-input v-model="aiHistoryKeyword" class="ai-history-search" :prefix-icon="Search" clearable placeholder="搜索会话" /><div class="ai-history-list"><article v-for="session in filteredAiSessions" :key="session.id" :class="{ active: aiSessionId === session.id }"><button class="ai-session-main" @click="loadAiSession(session.id)"><ChatLineRound /><span><strong>{{ session.title }}</strong><small>{{ formatDate(session.created_at) }}</small></span></button><div class="ai-session-actions"><el-button :icon="Edit" circle text title="重命名会话" @click.stop="renameAiSession(session)" /><el-button :icon="Delete" circle text type="danger" title="删除会话" @click.stop="deleteAiSession(session)" /></div></article></div><el-empty v-if="!filteredAiSessions.length" :description="aiHistoryKeyword ? '没有匹配的历史会话' : '暂无历史对话'" /></aside>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, type Ref } from 'vue';
import { ChatLineRound, Delete, EditPen, MagicStick, Plus, Search } from '@element-plus/icons-vue';
import { deleteData, getData, postData, putData } from '../api/client';
import { formatDate } from '../utils/statusLabels';
import { ArrowRight, Close, Document, Edit, Promotion, Refresh } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';

type AiMessageView = { role:'user'|'assistant'; content:string; references?:AiReference[] };
type AiReference = { id:number; file_id:number; title:string; content:string; score?:number };
type AiSession = { id:number; user_id?:number; title:string; created_at:string };
type ChatMessage = { id:number; sessionId:number; senderId:number; content:string; status:string; createdAt:string };
type DetailRoute = { kind:'knowledge'|'community'; id:number };

/** What this screen shows belongs to the page that loads it; it arrives together. */
type AiChatViewPage = {
  aiBusy:Ref<boolean>;
  aiMessages:Ref<AiMessageView[]>;
  aiQuestion:Ref<string>;
  aiSessionId:Ref<number|undefined>;
  aiSessions:Ref<AiSession[]>;
  currentUserId:Ref<any>;
  displayName:Ref<any>;
  loadAiHistory:()=>any;
  messages:Ref<ChatMessage[]>;
  mobileAiHistoryOpen:Ref<boolean>;
  navigateToDetail:(kind:DetailRoute['kind'], id:number, commentId?:number)=>any;
  notifyError:(error:unknown)=>any;
  role:Ref<any>;
};

const props = defineProps<{ page: AiChatViewPage }>();

const { aiBusy, aiMessages, aiQuestion, aiSessionId, aiSessions, currentUserId, displayName, loadAiHistory, messages, mobileAiHistoryOpen, navigateToDetail, notifyError, role } = props.page;

const aiHistoryKeyword = ref('');
const aiPrompts = ['平台支持哪些知识格式？','如何使用全文搜索？','社区有哪些核心功能？'];
async function askAi(){if(!aiQuestion.value.trim())return;const question=aiQuestion.value;aiMessages.value.push({role:'user',content:question});aiQuestion.value='';aiBusy.value=true;try{const result=await postData<{session_id:number;answer:string;references:AiReference[]}>('/ai/chat',{question,user_id:currentUserId.value,session_id:aiSessionId.value});aiSessionId.value=result.session_id;const references=[...new Map((result.references||[]).map(reference=>[reference.file_id,reference])).values()].slice(0,3);aiMessages.value.push({role:'assistant',content:result.answer,references});await loadAiHistory();}catch(error){notifyError(error);}finally{aiBusy.value=false;}}
async function deleteAiSession(session:AiSession){await ElMessageBox.confirm(`确认删除会话“${session.title}”及其全部消息？`,'删除 AI 会话',{type:'warning',confirmButtonText:'确认删除'});await deleteData(`/ai/session/${session.id}`);aiSessions.value=aiSessions.value.filter(item=>item.id!==session.id);if(aiSessionId.value===session.id)newAiSession();ElMessage.success('AI 会话已删除');}
const filteredAiSessions = computed(() => {const keyword=aiHistoryKeyword.value.trim().toLowerCase();return keyword?aiSessions.value.filter(session=>session.title.toLowerCase().includes(keyword)):aiSessions.value;});
async function loadAiSession(id:number){aiSessionId.value=id;const history=await getData<{messages:{role:'user'|'assistant';content:string}[]}>(`/ai/history?user_id=${currentUserId.value}&session_id=${id}`);aiMessages.value=history.messages;mobileAiHistoryOpen.value=false;}
function newAiSession(){aiSessionId.value=undefined;aiMessages.value=[];aiQuestion.value='';mobileAiHistoryOpen.value=false;}
function openAiReference(reference:AiReference){if(!reference.file_id){ElMessage.warning('该知识来源当前不可访问');return;}navigateToDetail('knowledge',reference.file_id);}
async function renameAiSession(session:AiSession){const {value}=await ElMessageBox.prompt('请输入新的会话名称','重命名 AI 会话',{inputValue:session.title,inputPattern:/\S+/,inputErrorMessage:'会话名称不能为空',confirmButtonText:'保存'});const result=await putData<{id:number;title:string}>(`/ai/session/${session.id}`,{title:value});session.title=result.title;ElMessage.success('会话名称已更新');}
</script>
