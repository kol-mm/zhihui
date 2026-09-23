<template>
  <section class="page-stack">
            <div class="page-toolbar"><div><h3>知识库</h3><p>检索、阅读并管理社区知识资源</p></div><el-button v-if="platformConfig.knowledge_upload_enabled" type="primary" :icon="Upload" @click="knowledgeDialog = true">上传资料</el-button></div>
            <section class="surface filter-bar"><el-input v-model="knowledgeKeyword" placeholder="输入标题或正文关键词" :prefix-icon="Search" clearable @keyup.enter="searchKnowledge" /><el-select v-model="knowledgeType" placeholder="全部格式" clearable><el-option label="Word" value="docx" /><el-option label="PDF" value="pdf" /><el-option label="TXT" value="txt" /><el-option label="Markdown" value="md" /></el-select><el-button :icon="Search" @click="searchKnowledge">搜索</el-button></section>
            <section class="surface">
              <div class="category-filter">
                <el-radio-group v-model="knowledgeCategoryId" size="small">
                  <el-radio-button :value="0">全部 {{ knowledgeSearchMode ? knowledgeFiles.length : knowledgeTotal }}</el-radio-button>
                  <el-radio-button v-for="category in knowledgeCategories" :key="category.id" :value="category.id">
                    {{ category.name }} {{ categoryCount(category.id) }}
                  </el-radio-button>
                </el-radio-group>
              </div>
              <div v-if="filteredKnowledge.length" class="knowledge-grid">
                <article v-for="file in filteredKnowledge" :key="file.id" class="knowledge-card">
                  <el-tag v-if="file.collected" class="knowledge-collected-badge" type="success" effect="plain">已收藏</el-tag>
                  <img v-if="file.coverUrl" class="knowledge-cover" loading="lazy" decoding="async" :src="resolveApiUrl(file.coverUrl)" :alt="`${file.title}封面`" />
                  <div class="knowledge-card-top"><span class="file-type large">{{ file.fileType?.toUpperCase() || '文档' }}</span><el-tag :type="file.auditStatus === 'APPROVED' ? 'success' : 'warning'" effect="plain">{{ auditLabel(file.auditStatus) }}</el-tag></div>
                  <h4>{{ file.title }}</h4><p>{{ communityUser(file.userId).nickname }} · @{{ communityUser(file.userId).username }}</p>
                  <div class="card-stats"><span><View />{{ file.views || 0 }}</span><span><Download />{{ file.downloads || 0 }}</span><span><Star />{{ file.likes || 0 }}</span></div>
                  <div class="card-actions"><el-button text type="primary" @click="openKnowledge(file)">阅读</el-button><el-button v-if="file.userId !== currentUserId" text :type="isFollowing(file.userId) ? 'success' : 'default'" @click="toggleFollowAuthor(file.userId)">{{ isFollowing(file.userId) ? '取消关注' : '关注作者' }}</el-button><el-button v-if="file.fileUrl" text @click="downloadKnowledge(file)">下载</el-button><el-dropdown trigger="click"><el-button text :icon="MoreFilled" /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="likeKnowledge(file)">{{ file.liked ? '取消点赞' : '点赞' }}</el-dropdown-item><el-dropdown-item @click="collectKnowledge(file)">{{ file.collected ? '取消收藏' : '收藏' }}</el-dropdown-item><el-dropdown-item @click="forwardKnowledge(file)">转发</el-dropdown-item><el-dropdown-item v-if="file.userId === currentUserId" divided @click="deleteKnowledge(file)">删除资源</el-dropdown-item><el-dropdown-item v-else divided @click="reportKnowledge(file)">举报</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
                </article>
              </div>              <div v-if="!knowledgeSearchMode && (knowledgeHasMore || knowledgeLoadingMore || knowledgeLoadError)" ref="knowledgeSentinelRef" class="knowledge-load-more"><el-button :loading="knowledgeLoadingMore" @click="loadMoreKnowledge">{{ knowledgeLoadingMore ? '正在加载资源' : knowledgeLoadError ? `${knowledgeLoadError}，点击重试` : '加载更多资源' }}</el-button></div>
              <el-empty v-if="!filteredKnowledge.length" description="没有匹配的知识资源" />
            </section>
            <section v-if="platformConfig.user_ranking_enabled" class="surface"><div class="surface-head"><div><h3>知识贡献榜</h3><p>综合上传量、浏览量、下载量和违规频次排序</p></div><el-tag type="info">前 {{ knowledgeRanking.length }} 名</el-tag></div><div class="ranking-list"><article v-for="item in knowledgeRanking" :key="item.userId"><strong>{{ item.rank }}</strong><div class="mini-avatar">{{ communityUser(item.userId).nickname.slice(0,1) }}</div><span><b>{{ communityUser(item.userId).nickname }}</b><small>上传 {{ item.uploads }} · 浏览 {{ item.views }} · 下载 {{ item.downloads }} · 违规 {{ item.violations }}</small></span><em>{{ item.score }} 分</em></article></div><el-empty v-if="!knowledgeRanking.length" description="暂无榜单数据" /></section>
  </section>
</template>

<script setup lang="ts">
import { computed, type Ref } from 'vue';
import { ref } from 'vue';
import { postData, resolveApiUrl } from '../api/client';
import { knowledgeCache } from '../utils/knowledgeCache';
import { auditLabel } from '../utils/statusLabels';
import { Download, MoreFilled, Search, Star, Upload, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';

type KnowledgeCategory = { id:number; name:string; parentId?:number; sortNo?:number };
type KnowledgeContentBlock = { type:'image'|'heading'|'list'|'paragraph'|'table'; text?:string; url?:string };
type KnowledgeFile = { id:number; userId:number; categoryId?:number; title:string; fileType:string; auditStatus:string; parseStatus?:string; auditSource?:string; auditReason?:string; fileUrl?:string; content?:string; contentBlocks?:KnowledgeContentBlock[]; imageUrls?:string[]; coverUrl?:string; views?:number; downloads?:number; likes?:number; liked?:boolean; collected?:boolean; createdAt?:string };
type KnowledgeLikeResult = { fileId:number; liked:boolean; likes:number };
type KnowledgeRanking = { rank:number; userId:number; uploads:number; views:number; downloads:number; violations:number; score:number };


/** What this screen shows belongs to the page that loads it; it arrives together. */
type KnowledgeLibraryViewPage = {
  activeView:Ref<any>;
  collectKnowledge:(file:KnowledgeFile)=>any;
  communityUser:(userId:number)=>any;
  currentUserId:Ref<any>;
  deleteKnowledge:(file:KnowledgeFile)=>any;
  downloadKnowledge:(file:KnowledgeFile)=>any;
  forwardKnowledge:(file:KnowledgeFile)=>any;
  isFollowing:(userId:number)=>any;
  knowledgeActivityType:Ref<string>;
  knowledgeCategories:Ref<KnowledgeCategory[]>;
  knowledgeCategoryCounts:Ref<Record<number, number>>;
  knowledgeCategoryId:Ref<number>;
  knowledgeDialog:Ref<boolean>;
  knowledgeFiles:Ref<KnowledgeFile[]>;
  knowledgeHasMore:Ref<boolean>;
  knowledgeKeyword:Ref<string>;
  knowledgeLoadError:Ref<string>;
  knowledgeLoadingMore:Ref<boolean>;
  knowledgeRanking:Ref<KnowledgeRanking[]>;
  knowledgeSearchMode:Ref<boolean>;
  knowledgeSentinelRef:Ref<HTMLElement|undefined>;
  knowledgeTotal:Ref<number>;
  knowledgeType:Ref<string>;
  loadMoreKnowledge:()=>any;
  myKnowledge:Ref<KnowledgeFile[]>;
  openKnowledge:(file:KnowledgeFile)=>any;
  platformConfig:Ref<any>;
  recordBehavior:(action:string, targetType:string, targetId:number)=>any;
  reportKnowledge:(file:KnowledgeFile)=>any;
  searchKnowledge:()=>any;
  toggleFollowAuthor:(userId:number)=>any;
  username:Ref<any>;
};

const props = defineProps<{ page: KnowledgeLibraryViewPage }>();

const { activeView, collectKnowledge, communityUser, currentUserId, deleteKnowledge, downloadKnowledge, forwardKnowledge, isFollowing, knowledgeActivityType, knowledgeCategories, knowledgeCategoryCounts, knowledgeCategoryId, knowledgeDialog, knowledgeFiles, knowledgeHasMore, knowledgeKeyword, knowledgeLoadError, knowledgeLoadingMore, knowledgeRanking, knowledgeSearchMode, knowledgeSentinelRef, knowledgeTotal, knowledgeType, loadMoreKnowledge, myKnowledge, openKnowledge, platformConfig, recordBehavior, reportKnowledge, searchKnowledge, toggleFollowAuthor, username } = props.page;

function categoryCount(categoryId:number){ return knowledgeSearchMode.value ? knowledgeFiles.value.filter(file=>file.categoryId===categoryId).length : (knowledgeCategoryCounts.value[categoryId] || 0); }
const filteredKnowledge = computed(() => knowledgeSearchMode.value
  ? knowledgeFiles.value.filter(file => (!knowledgeType.value || file.fileType === knowledgeType.value) && (!knowledgeCategoryId.value || file.categoryId === knowledgeCategoryId.value))
  : knowledgeFiles.value);
async function likeKnowledge(file:KnowledgeFile){const result=await postData<KnowledgeLikeResult>('/knowledge/like',{fileId:file.id});file.likes=result.likes;file.liked=result.liked;knowledgeCache.invalidate(file.id);if(result.liked){await recordBehavior('LIKE','KNOWLEDGE',file.id);ElMessage.success('已点赞');}else{if(knowledgeActivityType.value==='LIKED')myKnowledge.value=myKnowledge.value.filter(item=>item.id!==file.id);ElMessage.success('已取消点赞');}}
</script>
