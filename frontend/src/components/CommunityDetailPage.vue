<template>
  <section class="detail-page community-detail-page">
    <button class="detail-back" type="button" @click="emit('back')"><ArrowLeft />返回社区广场</button>
    <div class="community-detail-layout">
      <article class="detail-post">
        <header class="post-detail-author">
          <div class="user-avatar"><img v-if="author.avatarUrl" :src="resolveApiUrl(author.avatarUrl)" alt="作者头像" /><span v-else>{{ author.nickname.slice(0, 1).toUpperCase() }}</span></div>
          <div><strong>{{ author.nickname }}</strong><span>@{{ author.username }} · 帖子 #{{ post.id }}</span></div>
          <el-tag v-if="post.status !== 'PUBLISHED'" type="warning">{{ post.status }}</el-tag>
        </header>
        <h1>{{ post.title }}</h1>
        <p class="post-detail-content">{{ post.content }}</p>
        <div v-if="post.imageUrls?.length" class="post-images detail-post-images"><img v-for="image in post.imageUrls" :key="image" :src="resolveApiUrl(image)" alt="帖子配图" /></div>
        <footer class="detail-actions post-detail-actions">
          <el-button :icon="Star" @click="emit('like', post)">{{ post.likes || 0 }} 点赞</el-button>
          <el-button :icon="CollectionTag" @click="emit('collect', post)">收藏</el-button>
          <el-button v-if="post.userId === currentUserId" type="primary" plain :icon="Edit" @click="emit('edit', post)">编辑帖子</el-button>
        </footer>
      </article>
      <aside class="detail-discussion">
        <div class="discussion-head"><div><p>公开讨论</p><h2>{{ comments.length }} 条评论</h2></div><ChatDotRound /></div>
        <div class="detail-comment-list">
          <article v-for="comment in comments" :key="comment.id">
            <div class="mini-avatar"><img v-if="userFor(comment.userId).avatarUrl" :src="resolveApiUrl(userFor(comment.userId).avatarUrl || '')" alt="评论者头像" /><span v-else>{{ userFor(comment.userId).nickname.slice(0, 1) }}</span></div>
            <div><strong>{{ userFor(comment.userId).nickname }}</strong><p>{{ comment.content }}</p></div>
          </article>
          <el-empty v-if="!comments.length" description="暂无评论，发表第一条讨论" />
        </div>
        <div class="detail-comment-compose"><el-input v-model="commentText" type="textarea" :rows="3" resize="none" placeholder="发表公开评论" /><el-button type="primary" :icon="Promotion" :disabled="!commentText.trim()" @click="submitComment">发表评论</el-button></div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ArrowLeft, ChatDotRound, CollectionTag, Edit, Promotion, Star } from '@element-plus/icons-vue';
import { resolveApiUrl } from '../api/client';

type Post = { id:number; userId:number; title:string; content:string; status:string; imageUrls?:string[]; likes?:number };
type Comment = { id:number; userId:number; content:string };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string };

const props = defineProps<{ post:Post; comments:Comment[]; users:UserRecord[]; currentUserId:number }>();
const emit = defineEmits<{ back:[]; like:[post:Post]; collect:[post:Post]; edit:[post:Post]; comment:[text:string] }>();
const commentText = ref('');
const author = computed(() => userFor(props.post.userId));

function userFor(userId:number):UserRecord {
  return props.users.find(user => user.id === userId) || { id:userId, username:`user${userId}`, nickname:`用户 ${userId}` };
}

function submitComment() {
  const content = commentText.value.trim();
  if (!content) return;
  emit('comment', content);
  commentText.value = '';
}
</script>
