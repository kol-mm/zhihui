<template>
  <section class="detail-page knowledge-detail-page">
    <button class="detail-back" type="button" @click="emit('back')"><ArrowLeft />返回知识库</button>
    <article class="detail-document">
      <header class="detail-header">
        <div class="detail-heading">
          <span class="file-type large">{{ file.fileType?.toUpperCase() || '文档' }}</span>
          <div><p>知识资源 #{{ file.id }}</p><h1>{{ file.title }}</h1><span>上传者 #{{ file.userId }} · 浏览 {{ file.views || 0 }} · 下载 {{ file.downloads || 0 }}</span></div>
        </div>
        <div class="detail-actions">
          <el-button v-if="file.userId !== currentUserId" :type="following ? 'success' : 'default'" :plain="following" @click="emit('follow', file.userId)">{{ following ? '取消关注' : '关注作者' }}</el-button>
          <el-button :type="file.liked ? 'primary' : 'default'" :plain="file.liked" :icon="Star" @click="emit('like', file)">{{ file.liked ? '取消点赞' : '点赞' }} {{ file.likes || 0 }}</el-button>
          <el-button :type="file.collected ? 'primary' : 'default'" :plain="file.collected" :icon="CollectionTag" @click="emit('collect', file)">{{ file.collected ? '取消收藏' : '收藏' }}</el-button>
          <el-button :icon="Share" @click="emit('forward', file)">转发</el-button>
          <el-button v-if="file.fileUrl" type="primary" :icon="Download" @click="emit('download', file)">下载</el-button>
          <el-button v-if="file.userId === currentUserId" type="danger" plain :icon="Delete" @click="emit('delete', file)">删除资源</el-button>
          <el-button type="danger" text :icon="Warning" @click="emit('report', file)">举报</el-button>
        </div>
      </header>
      <iframe v-if="file.fileType?.toLowerCase() === 'pdf' && pdfPreviewUrl" class="knowledge-pdf-preview detail-pdf" :src="pdfPreviewUrl" title="PDF 预览" />
      <div v-else class="detail-knowledge-body rich-knowledge-body">
        <template v-for="(block, index) in blocks" :key="`${block.type}-${index}`">
          <img v-if="block.type === 'image'" class="knowledge-inline-image" :src="resolveApiUrl(block.url || '')" :alt="block.text || '知识插图'" />
          <h2 v-else-if="block.type === 'heading'">{{ block.text }}</h2>
          <p v-else :class="{ 'knowledge-list-item': block.type === 'list' }">{{ block.text }}</p>
        </template>
        <el-empty v-if="!blocks.length" description="暂无可预览正文" />
      </div>
    </article>
  </section>
</template>

<script setup lang="ts">
import { ArrowLeft, CollectionTag, Delete, Download, Share, Star, Warning } from '@element-plus/icons-vue';
import { resolveApiUrl } from '../api/client';

type KnowledgeFile = { id:number; userId:number; title:string; fileType:string; auditStatus:string; fileUrl?:string; views?:number; downloads?:number; likes?:number; liked?:boolean; collected?:boolean };
type ContentBlock = { type:'image'|'heading'|'list'|'paragraph'; text?:string; url?:string };

defineProps<{ file:KnowledgeFile; blocks:ContentBlock[]; pdfPreviewUrl:string; currentUserId:number; following:boolean }>();
const emit = defineEmits<{
  back:[];
  download:[file:KnowledgeFile];
  like:[file:KnowledgeFile];
  collect:[file:KnowledgeFile];
  forward:[file:KnowledgeFile];
  report:[file:KnowledgeFile];
  delete:[file:KnowledgeFile];
  follow:[userId:number];
}>();
</script>
