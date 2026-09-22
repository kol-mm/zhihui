<template>
  <section class="page-stack">
ge-stack">
            <el-alert v-if="platformConfig.platform_notice" :title="platformConfig.platform_notice" type="info" :closable="false" show-icon />
            <div class="welcome-row">
              <div><p class="section-kicker">今日概览</p><h3>{{ greeting }}，{{ displayName }}</h3><p>继续探索知识、社区动态和你的 AI 对话。</p></div>
              <el-button v-if="platformConfig.knowledge_upload_enabled" type="primary" :icon="Upload" @click="knowledgeDialog = true">上传知识</el-button>
            </div>
            <div class="metrics-grid">
              <div class="metric-tile"><span class="metric-icon green"><Files /></span><div><strong>{{ knowledgeTotal }}</strong><span>知识资源</span></div></div>
              <div class="metric-tile"><span class="metric-icon blue"><ChatDotRound /></span><div><strong>{{ communityPostCount }}</strong><span>社区动态</span></div></div>
              <div class="metric-tile"><span class="metric-icon amber"><Bell /></span><div><strong>{{ notificationUnread }}</strong><span>未读通知</span></div></div>
              <div class="metric-tile"><span class="metric-icon red"><Tickets /></span><div><strong>{{ tickets.length }}</strong><span>反馈工单</span></div></div>
            </div>
            <div class="two-column">
              <section class="surface">
                <div class="surface-head"><div><h3>最新知识</h3><p>最近收录和更新的资源</p></div><el-button text type="primary" @click="selectView('knowledge')">查看全部</el-button></div>
                <div v-if="knowledgeFiles.length" class="resource-list compact">
                  <button v-for="file in knowledgeFiles.slice(0, 4)" :key="file.id" @click="openKnowledge(file)">
                    <span class="file-type">{{ file.fileType?.toUpperCase() || 'DOC' }}</span>
                    <span><strong>{{ file.title }}</strong><small>浏览 {{ file.views || 0 }} · 下载 {{ file.downloads || 0 }}</small></span>
                    <ArrowRight />
                  </button>
                </div><el-empty v-else description="暂无知识资源" />
              </section>
              <section class="surface">
                <div class="surface-head"><div><h3>关注动态</h3><p>查看已关注作者的最新内容</p></div><el-button text type="primary" @click="selectView('square')">进入广场</el-button></div>
                <div v-if="feedPosts.length" class="feed-mini">
                  <article v-for="post in feedPosts.slice(0, 3)" :key="post.id" class="feed-mini-link" @click="openPostDetail(post)"><div class="mini-avatar">{{ communityUser(post.userId).nickname.slice(0, 1) }}</div><div><strong>{{ post.title }}</strong><p>{{ post.content }}</p><small>{{ communityUser(post.userId).nickname }} · {{ post.likes || 0 }} 赞</small></div></article>
                </div><el-empty v-else description="暂无社区动态" />
              </section>
            </div>
  </section>
</template>

<script setup lang="ts">
import { computed, type Ref } from 'vue';
import { ArrowRight, Bell, ChatDotRound, Files, Tickets, Upload } from '@element-plus/icons-vue';

type KnowledgeContentBlock = { type:'image'|'heading'|'list'|'paragraph'|'table'; text?:string; url?:string };
type KnowledgeFile = { id:number; userId:number; categoryId?:number; title:string; fileType:string; auditStatus:string; parseStatus?:string; auditSource?:string; auditReason?:string; fileUrl?:string; content?:string; contentBlocks?:KnowledgeContentBlock[]; imageUrls?:string[]; coverUrl?:string; views?:number; downloads?:number; likes?:number; liked?:boolean; collected?:boolean; createdAt?:string };
type Post = { id:number; userId:number; title:string; content:string; status:string; auditSource?:string; auditReason?:string; imageUrls?:string[]; likes?:number; liked?:boolean; collected?:boolean; commentCount?:number; createdAt?:string };
type Ticket = { id:number; userId:number; type:string; content:string; status:string; reply?:string; assigneeUserId?:number; assignedAt?:string; closedAt?:string; createdAt?:string; updatedAt?:string };


/** What this screen shows belongs to the page that loads it; it arrives together. */
type WorkbenchViewPage = {
  activeView:Ref<any>;
  communityPostCount:Ref<number>;
  communityUser:(userId:number)=>any;
  displayName:Ref<any>;
  feedPosts:Ref<Post[]>;
  knowledgeDialog:Ref<boolean>;
  knowledgeFiles:Ref<KnowledgeFile[]>;
  knowledgeTotal:Ref<number>;
  notificationUnread:Ref<number>;
  openKnowledge:(file:KnowledgeFile)=>any;
  openPostDetail:(post:Post)=>any;
  platformConfig:Ref<any>;
  selectView:(key:string)=>any;
  tickets:Ref<Ticket[]>;
};

const props = defineProps<{ page: WorkbenchViewPage }>();

const { activeView, communityPostCount, communityUser, displayName, feedPosts, knowledgeDialog, knowledgeFiles, knowledgeTotal, notificationUnread, openKnowledge, openPostDetail, platformConfig, selectView, tickets } = props.page;

const greeting = computed(() => { const hour = new Date().getHours(); return hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'; });
</script>
