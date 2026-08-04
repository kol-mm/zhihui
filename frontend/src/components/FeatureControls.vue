<template>
  <section class="feature-band">
    <div v-if="portal === 'client'" class="feature-grid">
      <article class="feature-card">
        <h3>用户关系与足迹</h3>
        <el-form label-position="top">
          <el-form-item label="当前用户 ID"><el-input-number v-model="relation.userId" :min="1" /></el-form-item>
          <el-form-item label="目标用户 ID"><el-input-number v-model="relation.targetUserId" :min="1" /></el-form-item>
          <el-form-item label="举报原因"><el-input v-model="relation.reason" /></el-form-item>
          <div class="actions">
            <el-button type="primary" @click="follow">关注</el-button>
            <el-button @click="unfollow">取消关注</el-button>
            <el-button @click="block">拉黑</el-button>
            <el-button @click="unblock">取消拉黑</el-button>
            <el-button @click="report">举报</el-button>
            <el-button @click="loadRelations">关系与足迹</el-button>
          </div>
        </el-form>
      </article>

      <article class="feature-card">
        <h3>帖子媒体与关注动态</h3>
        <el-form label-position="top">
          <el-form-item label="帖子 ID"><el-input-number v-model="post.id" :min="1" /></el-form-item>
          <el-form-item label="标题"><el-input v-model="post.title" /></el-form-item>
          <el-form-item label="内容"><el-input v-model="post.content" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="图片地址（逗号分隔，最多 9 张）"><el-input v-model="post.imageUrls" /></el-form-item>
          <div class="actions">
            <el-button type="primary" @click="createPost">图文发帖</el-button>
            <el-button @click="updatePost">修改帖子</el-button>
            <el-button @click="likePost">点赞帖子</el-button>
            <el-button @click="followingFeed">关注动态</el-button>
            <el-button @click="authorFeed">作者内容</el-button>
          </div>
        </el-form>
      </article>

      <article class="feature-card">
        <h3>会话与 AI 历史</h3>
        <el-form label-position="top">
          <el-form-item label="会话 ID"><el-input-number v-model="message.sessionId" :min="1" /></el-form-item>
          <el-form-item label="消息 ID"><el-input-number v-model="message.messageId" :min="1" /></el-form-item>
          <div class="actions">
            <el-button @click="deleteMessage">删除单条</el-button>
            <el-button @click="clearSession">清空会话</el-button>
            <el-button @click="clearAll">清空全部</el-button>
            <el-button @click="loadSessions">会话列表</el-button>
            <el-button @click="loadAiHistory">AI 问答历史</el-button>
          </div>
        </el-form>
      </article>
    </div>

    <div v-else class="feature-grid">
      <article class="feature-card">
        <h3>内容处置</h3>
        <el-form label-position="top">
          <el-form-item label="知识举报 ID"><el-input-number v-model="moderation.reportId" :min="1" /></el-form-item>
          <el-form-item label="评论 ID"><el-input-number v-model="moderation.commentId" :min="1" /></el-form-item>
          <el-form-item label="草稿 ID"><el-input-number v-model="moderation.draftId" :min="1" /></el-form-item>
          <div class="actions">
            <el-button type="success" @click="resolveReport">结案举报</el-button>
            <el-button @click="removeComment">删除评论</el-button>
            <el-button @click="removeDraft">删除草稿</el-button>
          </div>
        </el-form>
      </article>

      <article class="feature-card">
        <h3>FAQ 运维</h3>
        <el-form label-position="top">
          <el-form-item label="FAQ ID（新增填 0）"><el-input-number v-model="faq.id" :min="0" /></el-form-item>
          <el-form-item label="问题"><el-input v-model="faq.question" /></el-form-item>
          <el-form-item label="答案"><el-input v-model="faq.answer" type="textarea" :rows="2" /></el-form-item>
          <div class="actions"><el-button type="success" @click="saveFaq">保存</el-button><el-button @click="deleteFaq">删除</el-button></div>
        </el-form>
      </article>

      <article class="feature-card">
        <h3>AI 数据源与规则</h3>
        <el-form label-position="top">
          <el-form-item label="数据源范围"><el-input v-model="aiConfig.data_source_scope" /></el-form-item>
          <el-form-item label="匹配数量"><el-input-number v-model="aiConfig.match_limit" :min="1" :max="20" /></el-form-item>
          <el-form-item label="合规规则"><el-input v-model="aiConfig.compliance_rule" /></el-form-item>
          <div class="actions"><el-button type="success" @click="saveAiConfig">保存配置</el-button><el-button @click="loadAiAdmin">问答与切片</el-button></div>
        </el-form>
      </article>
    </div>

    <pre v-if="output">{{ output }}</pre>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { deleteData, getData, postData, putData } from '../api/client';

defineProps<{ portal: 'client' | 'admin' }>();

const output = ref('');
const relation = ref({ userId: 1, targetUserId: 2, reason: '骚扰或违规内容' });
const post = ref({ id: 1, userId: 1, title: '图文帖子', content: '支持多图与后续修改。', imageUrls: '' });
const message = ref({ sessionId: 1, messageId: 1 });
const moderation = ref({ reportId: 1, commentId: 1, draftId: 1 });
const faq = ref({ id: 0, question: '如何启动本地版本？', answer: '运行 start-local.bat。', sortNo: 10, enabled: 1 });
const aiConfig = ref({ data_source_scope: 'all-approved', match_limit: 5, compliance_rule: 'answer-with-references' });

async function run(action: () => Promise<unknown>) {
  try { output.value = JSON.stringify(await action(), null, 2); }
  catch (error) { output.value = error instanceof Error ? error.message : String(error); }
}
const imageUrls = () => post.value.imageUrls.split(',').map(value => value.trim()).filter(Boolean).slice(0, 9);
const follow = () => run(() => postData('/user/follow', relation.value));
const unfollow = () => run(() => deleteData('/user/follow', relation.value));
const block = () => run(() => postData('/user/block', relation.value));
const unblock = () => run(() => deleteData('/user/block', relation.value));
const report = () => run(() => postData('/user/report', { reporterId: relation.value.userId, targetUserId: relation.value.targetUserId, reason: relation.value.reason }));
const loadRelations = () => run(async () => ({
  follows: await getData(`/user/follows?userId=${relation.value.userId}`),
  blocks: await getData(`/user/blocks?userId=${relation.value.userId}`),
  behaviors: await getData(`/user/behaviors?userId=${relation.value.userId}`)
}));
const createPost = () => run(() => postData('/post/create', { ...post.value, imageUrls: imageUrls() }));
const updatePost = () => run(() => putData('/post/update', { ...post.value, imageUrls: imageUrls() }));
const likePost = () => run(() => postData('/post/like', { userId: post.value.userId, postId: post.value.id }));
const followingFeed = () => run(async () => {
  const relations = await getData<{ followedUserIds: number[] }>(`/user/follows?userId=${relation.value.userId}`);
  return getData(`/square/following-feed?followedUserIds=${relations.followedUserIds.join(',')}`);
});
const authorFeed = () => run(() => getData(`/square/feed?authorUserId=${relation.value.targetUserId}`));
const deleteMessage = () => run(() => deleteData('/message', { messageId: message.value.messageId }));
const clearSession = () => run(() => postData('/message/clear', { sessionId: message.value.sessionId }));
const clearAll = () => run(() => postData('/message/clear', {}));
const loadSessions = () => run(() => getData('/message/sessions'));
const loadAiHistory = () => run(() => getData(`/ai/history?user_id=${relation.value.userId}`));
const resolveReport = () => run(() => postData('/knowledge/admin/report/resolve', { reportId: moderation.value.reportId, status: 'RESOLVED', result: '管理员已处理' }));
const removeComment = () => run(() => deleteData('/comment/admin', { commentId: moderation.value.commentId }));
const removeDraft = () => run(() => deleteData('/post/admin/draft', { draftId: moderation.value.draftId }));
const saveFaq = () => run(() => postData('/feedback/admin/faq', { ...faq.value, id: faq.value.id || undefined }));
const deleteFaq = () => run(() => deleteData('/feedback/admin/faq', { faqId: faq.value.id }));
const saveAiConfig = () => run(() => postData('/ai/admin/config', aiConfig.value));
const loadAiAdmin = () => run(async () => ({ overview: await getData('/ai/admin/overview'), chunks: await getData('/ai/admin/chunks') }));
</script>

<style scoped>
.feature-band { margin-bottom: 18px; padding-bottom: 18px; border-bottom: 1px solid #e5e7eb; }
.feature-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; }
.feature-card { padding: 16px; border: 1px solid #e5e7eb; border-radius: 8px; background: #f9fafb; }
.feature-card h3 { margin: 0 0 14px; font-size: 17px; }
.actions { display: flex; flex-wrap: wrap; gap: 8px; }
pre { max-height: 260px; overflow: auto; margin: 16px 0 0; padding: 14px; background: #111827; color: #e5e7eb; border-radius: 6px; white-space: pre-wrap; }
</style>
