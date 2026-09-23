<template>
  <section class="page-stack community-page">
            <div class="page-toolbar"><div><h3>{{ activeView === 'forum' ? '社区论坛' : '关注广场' }}</h3><p>{{ activeView === 'forum' ? '浏览全站已审核帖子并参与讨论' : '查看已关注用户的最新动态' }}</p></div><el-button type="primary" :icon="EditPen" @click="postDialog = true">发布帖子</el-button></div>
            <div class="feed-layout">
              <section class="feed-column">
                <div class="feed-tabs"><button v-if="activeView === 'forum'" :class="{ active: feedMode === 'all' }" @click="loadFeed('all')">全部帖子</button><button v-if="activeView === 'square'" :class="{ active: feedMode === 'following' }" @click="loadFeed('following')">关注动态</button><button :class="{ active: feedMode === 'mine' }" @click="loadMyPosts">我的帖子</button><button v-if="feedMode === 'author'" class="active" @click="loadFeed(activeView === 'square' ? 'following' : 'all')">{{ communityUser(authorFilterUserId).nickname }} 的帖子</button></div>
                <article v-for="post in feedPosts" :key="post.id" class="post-card">
                  <div class="post-author"><button class="post-author-link" @click="loadAuthorPosts(post.userId)"><div class="user-avatar"><img v-if="communityUser(post.userId).avatarUrl" loading="lazy" decoding="async" :src="resolveApiUrl(communityUser(post.userId).avatarUrl || '')" alt="头像" /><span v-else>{{ communityUser(post.userId).nickname.slice(0, 1).toUpperCase() }}</span></div><div><strong>{{ communityUser(post.userId).nickname }}</strong><span>@{{ communityUser(post.userId).username }} · 帖子 #{{ post.id }}</span></div></button><el-tag v-if="post.status !== 'PUBLISHED'" type="warning">{{ postStatusLabel(post.status) }}</el-tag></div>
                  <button class="post-title-button" @click="openPostDetail(post)"><h3>{{ post.title }}</h3></button><p>{{ post.content }}</p>
                  <div v-if="post.imageUrls?.length" class="post-images"><img v-for="image in post.imageUrls" :key="image" loading="lazy" decoding="async" :src="resolveApiUrl(image)" alt="帖子配图" /></div>
                  <div class="post-actions"><el-button text :icon="View" @click="openPostDetail(post)">查看详情</el-button><el-button v-if="post.userId !== currentUserId" text :type="isFollowing(post.userId) ? 'success' : 'default'" @click="toggleFollowAuthor(post.userId)">{{ isFollowing(post.userId) ? '取消关注' : '关注作者' }}</el-button><el-button text :type="post.liked ? 'primary' : 'default'" :icon="Star" @click="likePost(post)">{{ post.liked ? '取消点赞' : '点赞' }} {{ post.likes || 0 }}</el-button><el-button v-if="platformConfig.comments_enabled" text :icon="ChatDotRound" @click="quickComment(post)">快捷评论</el-button><el-button text :type="post.collected ? 'primary' : 'default'" :icon="CollectionTag" @click="collectPost(post)">{{ post.collected ? '取消收藏' : '收藏' }}</el-button><el-button v-if="post.userId === currentUserId" text :icon="Edit" @click="editPost(post)">编辑</el-button><el-button v-if="post.userId === currentUserId" text type="danger" :icon="Delete" @click="deletePost(post)">删除</el-button></div>
                </article>
                <div v-if="feedPosts.length && (feedHasMore || feedLoadingMore || feedLoadError)" ref="feedSentinelRef" class="feed-load-more"><el-button :loading="feedLoadingMore" @click="loadMoreFeed">{{ feedLoadingMore ? '正在加载帖子' : feedLoadError ? `${feedLoadError}，点击重试` : '加载更多帖子' }}</el-button></div>
                <el-empty v-if="!feedPosts.length" :description="activeView === 'square' ? '暂时没有关注动态' : '还没有已发布的帖子'" />
              </section>
              <aside class="surface community-side"><h3>我的创作</h3><button @click="openDrafts"><Document />草稿箱<span>{{ drafts.length }}</span></button><button :class="{ active: feedMode === 'mine' }" @click="loadMyPosts"><EditPen />我的帖子<ArrowRight /></button><h3>社区提示</h3><p>尊重原创，理性交流。发现不当内容可通过举报交由管理员处理。</p></aside>
            </div>
  </section>
</template>

<script setup lang="ts">
import { type Ref } from 'vue';
import { ref } from 'vue';
import { postData, resolveApiUrl } from '../api/client';
import { postStatusLabel } from '../utils/statusLabels';
import { ArrowRight, ChatDotRound, CollectionTag, Delete, Document, Edit, EditPen, Star, View } from '@element-plus/icons-vue';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';

type Draft = { id:number; title:string; content:string; userId:number; imageUrls?:string[]; updatedAt?:string };
type FeedMode = 'all'|'following'|'mine'|'author';
type Post = { id:number; userId:number; title:string; content:string; status:string; auditSource?:string; auditReason?:string; imageUrls?:string[]; likes?:number; liked?:boolean; collected?:boolean; commentCount?:number; createdAt?:string };
type PostLikeResult = { postId:number; liked:boolean; created:boolean; likes:number };


/** What this screen shows belongs to the page that loads it; it arrives together. */
type CommunityFeedViewPage = {
  activeView:Ref<any>;
  authorFilterUserId:Ref<number>;
  avatarUrl:Ref<any>;
  collectPost:(post:Post)=>any;
  communityUser:(userId:number)=>any;
  currentUserId:Ref<any>;
  deletePost:(post:Post)=>any;
  drafts:Ref<Draft[]>;
  editPost:(post:Post)=>any;
  feedHasMore:Ref<boolean>;
  feedLoadError:Ref<string>;
  feedLoadingMore:Ref<boolean>;
  feedMode:Ref<'all'|'following'|'mine'|'author'>;
  feedPosts:Ref<Post[]>;
  feedSentinelRef:Ref<HTMLElement|undefined>;
  isFollowing:(userId:number)=>any;
  loadAuthorPosts:(userId:number)=>any;
  loadFeed:(mode?:FeedMode)=>any;
  loadMoreFeed:()=>any;
  loadMyPosts:()=>any;
  loadProfile:()=>any;
  openPostDetail:(post:Post)=>any;
  platformConfig:Ref<any>;
  postDialog:Ref<boolean>;
  profileToolTab:Ref<string>;
  recordBehavior:(action:string, targetType:string, targetId:number)=>any;
  toggleFollowAuthor:(userId:number)=>any;
  username:Ref<any>;
};

const props = defineProps<{ page: CommunityFeedViewPage }>();

const { activeView, authorFilterUserId, avatarUrl, collectPost, communityUser, currentUserId, deletePost, drafts, editPost, feedHasMore, feedLoadError, feedLoadingMore, feedMode, feedPosts, feedSentinelRef, isFollowing, loadAuthorPosts, loadFeed, loadMoreFeed, loadMyPosts, loadProfile, openPostDetail, platformConfig, postDialog, profileToolTab, recordBehavior, toggleFollowAuthor, username } = props.page;

async function likePost(post:Post){const result=await postData<PostLikeResult>('/post/like',{postId:post.id});post.likes=result.likes;post.liked=result.liked;if(result.liked){await recordBehavior('LIKE','POST',post.id);ElMessage.success('已点赞');}else ElMessage.success('已取消点赞');}
async function openDrafts(){activeView.value='profile';profileToolTab.value='drafts';await loadProfile();}
async function quickComment(post:Post){const {value}=await ElMessageBox.prompt('输入公开评论内容','快捷评论',{inputPattern:/\S+/,inputErrorMessage:'评论不能为空',confirmButtonText:'发布'});if(value.length>platformConfig.value.max_comment_length){ElMessage.warning(`评论不能超过 ${platformConfig.value.max_comment_length} 个字符`);return;}await postData('/square/quick-comment',{postId:post.id,content:value});await recordBehavior('COMMENT','POST',post.id);ElMessage.success('评论已发布');}
</script>
