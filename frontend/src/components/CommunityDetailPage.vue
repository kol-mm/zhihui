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
          <el-button :type="post.liked ? 'primary' : 'default'" :plain="post.liked" :icon="Star" @click="emit('like', post)">{{ post.liked ? '取消点赞' : '点赞' }} {{ post.likes || 0 }}</el-button>
          <el-button :type="post.collected ? 'primary' : 'default'" :plain="post.collected" :icon="CollectionTag" @click="emit('collect', post)">{{ post.collected ? '取消收藏' : '收藏' }}</el-button>
          <el-button v-if="post.userId === currentUserId" type="primary" plain :icon="Edit" @click="emit('edit', post)">编辑帖子</el-button>
        </footer>
      </article>
      <aside class="detail-discussion">
        <div class="discussion-head"><div><p>公开讨论</p><h2>{{ comments.length }} 条评论</h2></div><ChatDotRound /></div>
        <div class="detail-comment-list">
          <section v-for="comment in rootComments" :key="comment.id" class="detail-comment-thread">
            <article class="detail-comment-item" :class="{ 'is-reply-target': replyTarget?.id === comment.id }">
              <div class="mini-avatar"><img v-if="userFor(comment.userId).avatarUrl" :src="resolveApiUrl(userFor(comment.userId).avatarUrl || '')" alt="评论者头像" /><span v-else>{{ userFor(comment.userId).nickname.slice(0, 1) }}</span></div>
              <div class="detail-comment-body"><div class="detail-comment-meta"><div class="detail-comment-author"><strong>{{ userFor(comment.userId).nickname }}</strong></div><el-button text type="primary" size="small" @click="startReply(comment)">回复</el-button></div><p>{{ comment.content }}</p></div>
            </article>
            <div v-if="threadReplies(comment.id).length" class="detail-comment-replies">
              <article v-for="reply in threadReplies(comment.id)" :key="reply.id" class="detail-comment-item" :class="{ 'is-reply-target': replyTarget?.id === reply.id }">
                <div class="mini-avatar"><img v-if="userFor(reply.userId).avatarUrl" :src="resolveApiUrl(userFor(reply.userId).avatarUrl || '')" alt="回复者头像" /><span v-else>{{ userFor(reply.userId).nickname.slice(0, 1) }}</span></div>
                <div class="detail-comment-body"><div class="detail-comment-meta"><div class="detail-comment-author"><strong>{{ userFor(reply.userId).nickname }}</strong><span v-if="parentFor(reply)">回复 {{ parentAuthorName(reply) }}</span></div><el-button text type="primary" size="small" @click="startReply(reply)">回复</el-button></div><p>{{ reply.content }}</p></div>
              </article>
            </div>
          </section>
          <el-empty v-if="!comments.length" description="暂无评论，发表第一条讨论" />
        </div>
        <div ref="commentComposer" class="detail-comment-compose">
          <div v-if="replyTarget" class="detail-reply-context"><span>回复 {{ userFor(replyTarget.userId).nickname }}：{{ replyTarget.content }}</span><el-button text size="small" @click="cancelReply">取消回复</el-button></div>
          <el-input ref="commentInput" v-model="commentText" type="textarea" :rows="3" resize="none" :placeholder="replyTarget ? `回复 ${userFor(replyTarget.userId).nickname}` : '发表公开评论'" />
          <el-button type="primary" :icon="Promotion" :disabled="!commentText.trim()" @click="submitComment">{{ replyTarget ? '发表回复' : '发表评论' }}</el-button>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { ArrowLeft, ChatDotRound, CollectionTag, Edit, Promotion, Star } from '@element-plus/icons-vue';
import { resolveApiUrl } from '../api/client';

type Post = { id:number; userId:number; title:string; content:string; status:string; imageUrls?:string[]; likes?:number; liked?:boolean; collected?:boolean };
type Comment = { id:number; userId:number; parentId?:number; content:string };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string };

const props = defineProps<{ post:Post; comments:Comment[]; users:UserRecord[]; currentUserId:number }>();
const emit = defineEmits<{ back:[]; like:[post:Post]; collect:[post:Post]; edit:[post:Post]; comment:[payload:{content:string;parentId:number}] }>();
const commentText = ref('');
const replyTarget = ref<Comment>();
const commentInput = ref<{ focus:()=>void }>();
const commentComposer = ref<HTMLElement>();
const author = computed(() => userFor(props.post.userId));
const commentById = computed(() => new Map(props.comments.map(comment => [comment.id, comment])));
const rootComments = computed(() => props.comments.filter(comment => !comment.parentId || !commentById.value.has(comment.parentId)));

function userFor(userId:number):UserRecord {
  return props.users.find(user => user.id === userId) || { id:userId, username:`user${userId}`, nickname:`用户 ${userId}` };
}

function parentFor(comment:Comment):Comment|undefined {
  return comment.parentId ? commentById.value.get(comment.parentId) : undefined;
}

function parentAuthorName(comment:Comment):string {
  const parent = parentFor(comment);
  return parent ? userFor(parent.userId).nickname : '';
}

function threadReplies(rootId:number):Comment[] {
  const replies:Comment[] = [];
  const pending = [rootId];
  const visited = new Set<number>();
  while (pending.length) {
    const parentId = pending.shift()!;
    if (visited.has(parentId)) continue;
    visited.add(parentId);
    for (const comment of props.comments) {
      if (comment.parentId === parentId) {
        replies.push(comment);
        pending.push(comment.id);
      }
    }
  }
  return replies;
}

function startReply(comment:Comment) {
  replyTarget.value = comment;
  nextTick(() => {
    commentComposer.value?.scrollIntoView({ block:'nearest', behavior:'smooth' });
    commentInput.value?.focus();
  });
}

function cancelReply() {
  replyTarget.value = undefined;
}

function submitComment() {
  const content = commentText.value.trim();
  if (!content) return;
  emit('comment', { content, parentId:replyTarget.value?.id || 0 });
  commentText.value = '';
  replyTarget.value = undefined;
}
</script>
