<template>
  <section class="detail-page community-detail-page">
    <button class="detail-back" type="button" @click="emit('back')"><ArrowLeft />返回社区广场</button>
    <div class="community-detail-layout">
      <article class="detail-post">
        <header class="post-detail-author">
          <div class="user-avatar"><img v-if="author.avatarUrl" loading="lazy" decoding="async" :src="resolveApiUrl(author.avatarUrl)" alt="作者头像" /><span v-else>{{ author.nickname.slice(0, 1).toUpperCase() }}</span></div>
          <div><strong>{{ author.nickname }}</strong><span>@{{ author.username }} · 帖子 #{{ post.id }}</span></div>
          <el-button v-if="post.userId !== currentUserId" text :type="following ? 'success' : 'primary'" @click="emit('follow', post.userId)">{{ following ? '取消关注' : '关注作者' }}</el-button>
          <el-tag v-if="post.status !== 'PUBLISHED'" type="warning">{{ postStatusLabel(post.status) }}</el-tag>
        </header>
        <h1>{{ post.title }}</h1>
        <p class="post-detail-content">{{ post.content }}</p>
        <div v-if="post.imageUrls?.length" class="post-images detail-post-images"><img v-for="image in post.imageUrls" :key="image" loading="lazy" decoding="async" :src="resolveApiUrl(image)" alt="帖子配图" /></div>
        <footer class="detail-actions post-detail-actions">
          <el-button :type="post.liked ? 'primary' : 'default'" :plain="post.liked" :icon="Star" @click="emit('like', post)">{{ post.liked ? '取消点赞' : '点赞' }} {{ post.likes || 0 }}</el-button>
          <el-button :type="post.collected ? 'primary' : 'default'" :plain="post.collected" :icon="CollectionTag" @click="emit('collect', post)">{{ post.collected ? '取消收藏' : '收藏' }}</el-button>
          <el-button v-if="post.userId === currentUserId" type="primary" plain :icon="Edit" @click="emit('edit', post)">编辑帖子</el-button>
          <el-button v-if="post.userId === currentUserId" type="danger" plain :icon="Delete" @click="emit('delete-post', post)">删除帖子</el-button>
        </footer>
      </article>
      <aside class="detail-discussion">
        <div class="discussion-head"><div><p>公开讨论</p><h2>{{ commentTotal }} 条评论</h2></div><ChatDotRound /></div>
        <div class="detail-comment-list" @scroll.passive="handleCommentScroll">
          <section v-for="comment in rootComments" :key="comment.id" class="detail-comment-thread">
            <article class="detail-comment-item" :class="{ 'is-reply-target': replyTarget?.id === comment.id, 'is-placeholder': comment.placeholder }">
              <div class="mini-avatar"><img v-if="!comment.placeholder && userFor(comment.userId).avatarUrl" loading="lazy" decoding="async" :src="resolveApiUrl(userFor(comment.userId).avatarUrl || '')" alt="评论者头像" /><span v-else>{{ comment.placeholder ? '·' : userFor(comment.userId).nickname.slice(0, 1) }}</span></div>
              <div class="detail-comment-body"><div class="detail-comment-meta"><div class="detail-comment-author"><strong>{{ commentAuthorName(comment) }}</strong></div><div v-if="!comment.placeholder"><el-button v-if="commentsEnabled" text type="primary" size="small" @click="startReply(comment)">回复</el-button><el-button v-if="comment.userId === currentUserId" text type="danger" size="small" @click="emit('delete-comment', comment)">删除</el-button></div></div><p>{{ comment.placeholder ? '该评论已被隐藏' : comment.content }}</p></div>
            </article>
            <div v-if="threadReplies(comment.id).length" class="detail-comment-replies">
              <article v-for="reply in threadReplies(comment.id)" :key="reply.id" class="detail-comment-item" :class="{ 'is-reply-target': replyTarget?.id === reply.id, 'is-placeholder': reply.placeholder }">
                <div class="mini-avatar"><img v-if="!reply.placeholder && userFor(reply.userId).avatarUrl" loading="lazy" decoding="async" :src="resolveApiUrl(userFor(reply.userId).avatarUrl || '')" alt="回复者头像" /><span v-else>{{ reply.placeholder ? '·' : userFor(reply.userId).nickname.slice(0, 1) }}</span></div>
                <div class="detail-comment-body"><div class="detail-comment-meta"><div class="detail-comment-author"><strong>{{ commentAuthorName(reply) }}</strong><span v-if="parentFor(reply)">回复 {{ parentAuthorName(reply) }}</span></div><div v-if="!reply.placeholder"><el-button v-if="commentsEnabled" text type="primary" size="small" @click="startReply(reply)">回复</el-button><el-button v-if="reply.userId === currentUserId" text type="danger" size="small" @click="emit('delete-comment', reply)">删除</el-button></div></div><p>{{ reply.placeholder ? '该回复已被隐藏' : reply.content }}</p></div>
              </article>
            </div>
          </section>
          <div v-if="commentsHasMore || commentsLoading || commentsError" class="detail-comment-more">
            <el-button text type="primary" size="small" :loading="commentsLoading" @click="emit('load-more-comments')">{{ commentsLoading ? '正在加载评论' : commentsError ? `${commentsError}，点击重试` : '加载更多评论' }}</el-button>
          </div>
          <el-empty v-else-if="!comments.length" description="暂无评论，发表第一条讨论" />
        </div>
        <div v-if="commentsEnabled" ref="commentComposer" class="detail-comment-compose">
          <div v-if="replyTarget" class="detail-reply-context"><span>回复 {{ userFor(replyTarget.userId).nickname }}：{{ replyTarget.content }}</span><el-button text size="small" @click="cancelReply">取消回复</el-button></div>
          <el-input ref="commentInput" v-model="commentText" type="textarea" :rows="3" resize="none" :maxlength="maxCommentLength" show-word-limit :disabled="commentSubmitting" :placeholder="replyTarget ? `回复 ${userFor(replyTarget.userId).nickname}` : '发表公开评论'" />
          <el-button type="primary" :icon="Promotion" :loading="commentSubmitting" :disabled="!commentText.trim()" @click="submitComment">{{ replyTarget ? '发表回复' : '发表评论' }}</el-button>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue';
import { ArrowLeft, ChatDotRound, CollectionTag, Delete, Edit, Promotion, Star } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { resolveApiUrl, toUserMessage } from '../api/client';

type Post = { id:number; userId:number; title:string; content:string; status:string; imageUrls?:string[]; likes?:number; liked?:boolean; collected?:boolean };
type Comment = { id:number; userId:number; parentId?:number; rootId?:number; content:string; placeholder?:boolean };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string };

const props = defineProps<{ post:Post; comments:Comment[]; commentTotal:number; commentsHasMore:boolean; commentsLoading:boolean; commentsError:string; users:UserRecord[]; currentUserId:number; following:boolean; commentsEnabled:boolean; maxCommentLength:number; onSubmitComment:(payload:{content:string;parentId:number})=>Promise<void> }>();
const emit = defineEmits<{ back:[]; like:[post:Post]; collect:[post:Post]; edit:[post:Post]; 'delete-post':[post:Post]; 'delete-comment':[comment:Comment]; 'load-more-comments':[]; follow:[userId:number] }>();
const commentSubmitting = ref(false);
const commentText = ref('');
const replyTarget = ref<Comment>();
const commentInput = ref<{ focus:()=>void }>();
const commentComposer = ref<HTMLElement>();
const author = computed(() => userFor(props.post.userId));
const commentById = computed(() => new Map(props.comments.map(comment => [comment.id, comment])));
const rootComments = computed(() => props.comments.filter(comment => !comment.parentId || !commentById.value.has(comment.parentId)));
const repliesByParent = computed(() => {
  const replies = new Map<number, Comment[]>();
  for (const comment of props.comments) {
    if (!comment.parentId) continue;
    const siblings = replies.get(comment.parentId);
    if (siblings) siblings.push(comment);
    else replies.set(comment.parentId, [comment]);
  }
  return replies;
});

function userFor(userId:number):UserRecord {
  return props.users.find(user => user.id === userId) || { id:userId, username:'unknown', nickname:'已注销用户' };
}

function parentFor(comment:Comment):Comment|undefined {
  return comment.parentId ? commentById.value.get(comment.parentId) : undefined;
}

function parentAuthorName(comment:Comment):string {
  const parent = parentFor(comment);
  return parent ? commentAuthorName(parent) : '';
}

function commentAuthorName(comment:Comment):string {
  return comment.placeholder ? '已隐藏的评论' : userFor(comment.userId).nickname;
}

function handleCommentScroll(event:Event) {
  const list = event.currentTarget as HTMLElement;
  if (!props.commentsHasMore || props.commentsLoading || props.commentsError) return;
  if (list.scrollHeight - list.scrollTop - list.clientHeight < 120) emit('load-more-comments');
}

function threadReplies(rootId:number):Comment[] {
  const replies:Comment[] = [];
  const pending = [rootId];
  const visited = new Set<number>();
  while (pending.length) {
    const parentId = pending.shift()!;
    if (visited.has(parentId)) continue;
    visited.add(parentId);
    for (const comment of repliesByParent.value.get(parentId) || []) {
      replies.push(comment);
      pending.push(comment.id);
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

async function submitComment() {
  const content = commentText.value.trim();
  if (!content || commentSubmitting.value) return;
  commentSubmitting.value = true;
  try {
    await props.onSubmitComment({ content, parentId:replyTarget.value?.id || 0 });
    commentText.value = '';
    replyTarget.value = undefined;
  } catch (error) {
    ElMessage.error(toUserMessage(error, '评论发布失败，请重试'));
  } finally {
    commentSubmitting.value = false;
  }
}

function postStatusLabel(status:string):string {
  return ({ PUBLISHED:'已发布', PENDING:'待审核', HIDDEN:'已隐藏' } as Record<string,string>)[status] || status;
}
</script>
