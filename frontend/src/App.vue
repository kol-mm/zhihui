<template>
  <main class="shell">
    <section class="hero">
      <div>
        <p class="eyebrow">Local Usable Version</p>
        <h1>AI 知识社区平台</h1>
        <p class="summary">用户端和管理端已拆分为可操作页面：支持注册登录、知识提交、发帖评论、反馈工单，以及审核、账号状态和工单回复。</p>
      </div>
      <div class="hero-actions">
        <el-tag>{{ currentUser }}</el-tag>
        <el-button type="primary" @click="refreshClient">刷新用户端数据</el-button>
        <el-button type="success" @click="refreshAdmin">刷新管理端数据</el-button>
      </div>
    </section>

    <section class="portal-switch">
      <el-segmented v-model="activePortal" :options="portalOptions" size="large" />
    </section>

    <section v-if="activePortal === 'client'" class="portal">
      <div class="portal-head">
        <div>
          <p class="eyebrow">Client Portal</p>
          <h2>用户端</h2>
        </div>
        <el-tag type="primary">普通用户操作</el-tag>
      </div>

      <section class="form-grid">
        <article class="card">
          <h3>注册 / 登录</h3>
          <el-form label-position="top">
            <el-form-item label="用户名"><el-input v-model="authForm.username" /></el-form-item>
            <el-form-item label="密码"><el-input v-model="authForm.password" type="password" show-password /></el-form-item>
            <el-form-item label="昵称"><el-input v-model="authForm.nickname" /></el-form-item>
            <div class="actions">
              <el-button type="primary" @click="registerUser">注册</el-button>
              <el-button @click="loginUser">登录</el-button>
              <el-button @click="loginAdmin">管理员登录</el-button>
            </div>
          </el-form>
        </article>

        <article class="card">
          <h3>知识提交</h3>
          <el-form label-position="top">
            <el-form-item label="标题"><el-input v-model="knowledgeForm.title" /></el-form-item>
            <el-form-item label="文件类型"><el-input v-model="knowledgeForm.fileType" /></el-form-item>
            <el-form-item label="文件地址"><el-input v-model="knowledgeForm.fileUrl" /></el-form-item>
            <el-form-item label="操作文件 ID"><el-input-number v-model="knowledgeAction.fileId" :min="1" /></el-form-item>
            <el-form-item label="举报原因"><el-input v-model="knowledgeAction.reason" /></el-form-item>
            <div class="actions">
              <el-button type="primary" @click="uploadKnowledge">提交知识</el-button>
              <el-button @click="collectKnowledge">收藏</el-button>
              <el-button @click="reportKnowledge">举报</el-button>
              <el-button @click="loadKnowledgeCollects">我的收藏</el-button>
              <el-button @click="loadKnowledge">刷新列表</el-button>
            </div>
          </el-form>
        </article>

        <article class="card">
          <h3>论坛发帖 / 评论</h3>
          <el-form label-position="top">
            <el-form-item label="标题"><el-input v-model="postForm.title" /></el-form-item>
            <el-form-item label="内容"><el-input v-model="postForm.content" type="textarea" :rows="3" /></el-form-item>
            <el-form-item label="评论 Post ID"><el-input-number v-model="commentForm.postId" :min="1" /></el-form-item>
            <el-form-item label="评论内容"><el-input v-model="commentForm.content" /></el-form-item>
            <el-form-item label="详情 / 评论列表 Post ID"><el-input-number v-model="detailPostId" :min="1" /></el-form-item>
            <div class="actions">
              <el-button type="primary" @click="createPost">发布帖子</el-button>
              <el-button @click="createComment">发表评论</el-button>
              <el-button @click="loadFeed">刷新广场</el-button>
              <el-button @click="loadPostDetail">查看详情</el-button>
              <el-button @click="loadComments">评论列表</el-button>
            </div>
          </el-form>
        </article>

        <article class="card">
          <h3>用户关注</h3>
          <el-form label-position="top">
            <el-form-item label="当前用户 ID"><el-input-number v-model="followForm.userId" :min="1" /></el-form-item>
            <el-form-item label="关注目标用户 ID"><el-input-number v-model="followForm.targetUserId" :min="1" /></el-form-item>
            <div class="actions">
              <el-button type="primary" @click="followUser">关注</el-button>
              <el-button @click="unfollowUser">取消关注</el-button>
              <el-button @click="loadFollows">关注列表</el-button>
            </div>
          </el-form>
        </article>

        <article class="card">
          <h3>反馈工单 / AI</h3>
          <el-form label-position="top">
            <el-form-item label="反馈类型"><el-input v-model="ticketForm.type" /></el-form-item>
            <el-form-item label="反馈内容"><el-input v-model="ticketForm.content" type="textarea" :rows="3" /></el-form-item>
            <el-form-item label="AI 问题"><el-input v-model="aiQuestion" /></el-form-item>
            <div class="actions">
              <el-button type="primary" @click="createTicket">提交工单</el-button>
              <el-button @click="askAi">AI 问答</el-button>
              <el-button @click="loadFeedback">刷新反馈</el-button>
            </div>
          </el-form>
        </article>
      </section>

      <section class="result-grid">
        <article class="card wide">
          <div class="card-head">
            <h3>知识列表</h3>
            <el-tag>{{ knowledgeFiles.length }} 条</el-tag>
          </div>
          <el-empty v-if="knowledgeFiles.length === 0" description="暂无知识数据，点击刷新列表加载" />
          <div v-else class="item-list">
            <div v-for="file in knowledgeFiles" :key="file.id" class="list-item">
              <div>
                <strong>{{ file.title }}</strong>
                <p>{{ file.fileType || 'unknown' }} · 上传人 {{ file.userId }} · {{ file.auditStatus }}</p>
              </div>
              <el-tag :type="file.auditStatus === 'APPROVED' ? 'success' : 'warning'">{{ file.auditStatus }}</el-tag>
            </div>
          </div>
        </article>

        <article class="card wide">
          <div class="card-head">
            <h3>社区广场</h3>
            <el-tag>{{ feedPosts.length }} 条</el-tag>
          </div>
          <el-empty v-if="feedPosts.length === 0" description="暂无帖子数据，点击刷新广场加载" />
          <div v-else class="item-list">
            <div v-for="post in feedPosts" :key="post.id" class="list-item">
              <div>
                <strong>#{{ post.id }} {{ post.title }}</strong>
                <p>{{ post.content }}</p>
              </div>
              <el-tag :type="post.status === 'PUBLISHED' ? 'success' : 'danger'">{{ post.status }}</el-tag>
            </div>
          </div>
        </article>

        <article class="card wide">
          <div class="card-head">
            <h3>我的工单</h3>
            <el-tag>{{ tickets.length }} 条</el-tag>
          </div>
          <el-empty v-if="tickets.length === 0" description="暂无工单，提交或刷新反馈后显示" />
          <div v-else class="item-list">
            <div v-for="ticket in tickets" :key="ticket.id" class="list-item">
              <div>
                <strong>#{{ ticket.id }} {{ ticket.type }}</strong>
                <p>{{ ticket.content }}</p>
                <small v-if="ticket.reply">回复：{{ ticket.reply }}</small>
              </div>
              <el-tag>{{ ticket.status }}</el-tag>
            </div>
          </div>
        </article>

        <article class="card wide">
          <h3>用户端数据</h3>
          <pre>{{ clientOutput }}</pre>
        </article>
      </section>
    </section>

    <section v-else class="portal">
      <div class="portal-head admin-head">
        <div>
          <p class="eyebrow">Admin Portal</p>
          <h2>管理端</h2>
        </div>
        <el-tag type="success">管理员操作</el-tag>
      </div>

      <section class="form-grid">
        <article class="card">
          <h3>知识审核</h3>
          <el-form label-position="top">
            <el-form-item label="文件 ID"><el-input-number v-model="knowledgeAudit.fileId" :min="1" /></el-form-item>
            <el-form-item label="审核状态"><el-select v-model="knowledgeAudit.auditStatus"><el-option label="通过" value="APPROVED" /><el-option label="拒绝" value="REJECTED" /></el-select></el-form-item>
            <el-form-item label="原因"><el-input v-model="knowledgeAudit.reason" /></el-form-item>
            <el-button type="success" @click="auditKnowledge">提交审核</el-button>
          </el-form>
        </article>

        <article class="card">
          <h3>帖子审核</h3>
          <el-form label-position="top">
            <el-form-item label="帖子 ID"><el-input-number v-model="postAudit.postId" :min="1" /></el-form-item>
            <el-form-item label="状态"><el-select v-model="postAudit.status"><el-option label="发布" value="PUBLISHED" /><el-option label="隐藏" value="HIDDEN" /></el-select></el-form-item>
            <el-form-item label="原因"><el-input v-model="postAudit.reason" /></el-form-item>
            <el-button type="success" @click="auditPost">提交审核</el-button>
          </el-form>
        </article>

        <article class="card">
          <h3>账号状态</h3>
          <el-form label-position="top">
            <el-form-item label="用户 ID"><el-input-number v-model="userStatus.userId" :min="1" /></el-form-item>
            <el-form-item label="状态"><el-select v-model="userStatus.status"><el-option label="正常" value="ACTIVE" /><el-option label="禁用" value="DISABLED" /></el-select></el-form-item>
            <el-button type="success" @click="updateUserStatus">更新状态</el-button>
          </el-form>
        </article>

        <article class="card">
          <h3>工单回复</h3>
          <el-form label-position="top">
            <el-form-item label="工单 ID"><el-input-number v-model="ticketReply.ticketId" :min="1" /></el-form-item>
            <el-form-item label="状态"><el-select v-model="ticketReply.status"><el-option label="处理中" value="PROCESSING" /><el-option label="已解决" value="RESOLVED" /></el-select></el-form-item>
            <el-form-item label="回复"><el-input v-model="ticketReply.reply" type="textarea" :rows="3" /></el-form-item>
            <el-button type="success" @click="replyTicket">回复工单</el-button>
          </el-form>
        </article>
      </section>

      <section class="result-grid">
        <article class="card wide">
          <div class="card-head">
            <h3>管理概览</h3>
            <el-tag type="success">Admin</el-tag>
          </div>
          <div class="metric-grid">
            <div v-for="metric in adminMetrics" :key="metric.label" class="metric">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
            </div>
          </div>
        </article>

        <article class="card wide">
          <div class="card-head">
            <h3>待处理队列</h3>
            <el-tag>{{ adminQueueCount }} 条</el-tag>
          </div>
          <el-tabs>
            <el-tab-pane label="知识">
              <el-empty v-if="adminKnowledgeFiles.length === 0" description="暂无知识数据" />
              <div v-else class="item-list">
                <div v-for="file in adminKnowledgeFiles" :key="file.id" class="list-item">
                  <div>
                    <strong>#{{ file.id }} {{ file.title }}</strong>
                    <p>上传人 {{ file.userId }} · {{ file.fileType }}</p>
                  </div>
                  <el-tag :type="file.auditStatus === 'APPROVED' ? 'success' : 'warning'">{{ file.auditStatus }}</el-tag>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="举报">
              <el-empty v-if="knowledgeReports.length === 0" description="暂无举报" />
              <div v-else class="item-list">
                <div v-for="report in knowledgeReports" :key="report.id || `${report.userId}-${report.fileId}-${report.createdAt}`" class="list-item">
                  <div>
                    <strong>文件 #{{ report.fileId }}</strong>
                    <p>{{ report.reason }}</p>
                  </div>
                  <el-tag type="warning">{{ report.status }}</el-tag>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="工单">
              <el-empty v-if="adminTickets.length === 0" description="暂无工单" />
              <div v-else class="item-list">
                <div v-for="ticket in adminTickets" :key="ticket.id" class="list-item">
                  <div>
                    <strong>#{{ ticket.id }} {{ ticket.type }}</strong>
                    <p>{{ ticket.content }}</p>
                  </div>
                  <el-tag>{{ ticket.status }}</el-tag>
                </div>
              </div>
            </el-tab-pane>
          </el-tabs>
        </article>

        <article class="card wide">
          <div class="card-head">
            <h3>管理端数据</h3>
            <div class="actions">
              <el-button @click="loadAdmin">平台概览</el-button>
              <el-button @click="loadAdminQueues">待处理数据</el-button>
            </div>
          </div>
          <pre>{{ adminOutput }}</pre>
        </article>
      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { deleteData, getData, postData, setAuthToken } from './api/client';

type KnowledgeFile = {
  id: number;
  userId: number;
  title: string;
  fileType: string;
  auditStatus: string;
};

type Post = {
  id: number;
  userId: number;
  title: string;
  content: string;
  status: string;
};

type Ticket = {
  id: number;
  userId: number;
  type: string;
  content: string;
  status: string;
  reply?: string;
};

type KnowledgeReport = {
  id?: number;
  userId: number;
  fileId: number;
  reason: string;
  status: string;
  createdAt?: string;
};

const activePortal = ref<'client' | 'admin'>('client');
const clientOutput = ref('填写表单后提交，数据会通过本地后端保存。');
const adminOutput = ref('管理端操作会真实修改状态并落盘。');
const currentUser = ref('未登录');
const portalOptions = [{ label: '用户端', value: 'client' }, { label: '管理端', value: 'admin' }];
const knowledgeFiles = ref<KnowledgeFile[]>([]);
const feedPosts = ref<Post[]>([]);
const tickets = ref<Ticket[]>([]);
const adminKnowledgeFiles = ref<KnowledgeFile[]>([]);
const knowledgeReports = ref<KnowledgeReport[]>([]);
const adminTickets = ref<Ticket[]>([]);
const adminOverview = ref<Record<string, Record<string, unknown>>>({});

const authForm = ref({ username: 'demo', password: 'demo', nickname: 'Demo User' });
const knowledgeForm = ref({ userId: 1, title: '本地知识文档', fileType: 'txt', fileUrl: 'local://knowledge.txt' });
const knowledgeAction = ref({ userId: 1, fileId: 1, reason: '内容不准确，需要复核' });
const postForm = ref({ userId: 1, title: '本地可用版本进展', content: '现在支持真实提交和持久化。' });
const commentForm = ref({ postId: 1, userId: 1, content: '收到，继续完善。' });
const detailPostId = ref(1);
const followForm = ref({ userId: 1, targetUserId: 2 });
const ticketForm = ref({ userId: 1, type: 'BUG', content: '这里填写使用中遇到的问题。' });
const aiQuestion = ref('平台现在支持哪些核心功能？');

const knowledgeAudit = ref({ fileId: 1, auditStatus: 'APPROVED', reason: '内容符合规范' });
const postAudit = ref({ postId: 1, status: 'PUBLISHED', reason: '内容正常' });
const userStatus = ref({ userId: 1, status: 'ACTIVE' });
const ticketReply = ref({ ticketId: 3001, status: 'PROCESSING', reply: '已收到反馈，正在处理。' });

const adminMetrics = computed(() => [
  { label: '用户总数', value: String(adminOverview.value.userAdmin?.totalUsers ?? '-') },
  { label: '知识文件', value: String(adminOverview.value.knowledgeAdmin?.totalFiles ?? '-') },
  { label: '知识举报', value: String(adminOverview.value.knowledgeAdmin?.reports ?? '-') },
  { label: '帖子数量', value: String(adminOverview.value.forumAdmin?.publishedPosts ?? '-') },
  { label: '工单数量', value: String(adminOverview.value.feedbackAdmin?.tickets ?? '-') }
]);

const adminQueueCount = computed(() => adminKnowledgeFiles.value.length + knowledgeReports.value.length + adminTickets.value.length);

function formatData(data: unknown) {
  return JSON.stringify(data, null, 2);
}

function formatError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return `请求失败：${message}\n\n请确认 Nacos、后端、AI 服务和前端均已重新启动。`;
}

async function runClient(action: () => Promise<unknown>) {
  clientOutput.value = '请求中...';
  try { clientOutput.value = formatData(await action()); } catch (error) { clientOutput.value = formatError(error); }
}

async function runAdmin(action: () => Promise<unknown>) {
  adminOutput.value = '请求中...';
  try { adminOutput.value = formatData(await action()); } catch (error) { adminOutput.value = formatError(error); }
}

async function registerUser() {
  await runClient(() => postData('/user/register', authForm.value));
}

async function loginUser() {
  await runClient(async () => {
    const result = await postData<{ token: string; role: string; user: { id: number; username: string } }>('/user/login', {
      username: authForm.value.username,
      password: authForm.value.password
    });
    setAuthToken(result.token);
    currentUser.value = `${result.user.username} / ${result.role}`;
    return result;
  });
}

async function loginAdmin() {
  authForm.value.username = 'admin';
  authForm.value.password = 'admin123';
  authForm.value.nickname = 'Local Admin';
  await loginUser();
  activePortal.value = 'admin';
}

async function uploadKnowledge() {
  await runClient(async () => {
    const created = await postData('/knowledge/upload', knowledgeForm.value);
    await loadKnowledge();
    return created;
  });
}

async function collectKnowledge() {
  await runClient(() => postData('/knowledge/collect', knowledgeAction.value));
}

async function reportKnowledge() {
  await runClient(() => postData('/knowledge/report', knowledgeAction.value));
}

async function loadKnowledgeCollects() {
  await runClient(() => getData(`/knowledge/collects?userId=${knowledgeAction.value.userId}`));
}

async function loadKnowledge() {
  await runClient(async () => ({
    files: knowledgeFiles.value = await getData<KnowledgeFile[]>('/knowledge/list'),
    ranking: await getData('/knowledge/ranking'),
    collects: await getData(`/knowledge/collects?userId=${knowledgeAction.value.userId}`)
  }));
}

async function createPost() {
  await runClient(async () => {
    const created = await postData('/post/create', postForm.value);
    await loadFeed();
    return created;
  });
}

async function createComment() {
  await runClient(() => postData('/comment/create', commentForm.value));
}

async function loadPostDetail() {
  await runClient(() => getData(`/post/detail?id=${detailPostId.value}`));
}

async function loadComments() {
  await runClient(() => getData(`/comment/list?postId=${detailPostId.value}`));
}

async function loadFeed() {
  await runClient(async () => feedPosts.value = await getData<Post[]>('/square/feed'));
}

async function followUser() {
  await runClient(() => postData('/user/follow', followForm.value));
}

async function unfollowUser() {
  await runClient(() => deleteData('/user/follow', followForm.value));
}

async function loadFollows() {
  await runClient(() => getData(`/user/follows?userId=${followForm.value.userId}`));
}

async function createTicket() {
  await runClient(async () => {
    const created = await postData('/feedback/ticket', ticketForm.value);
    await loadFeedback();
    return created;
  });
}

async function loadFeedback() {
  await runClient(async () => ({
    faqs: await getData('/feedback/faqs'),
    tickets: tickets.value = await getData<Ticket[]>('/feedback/tickets?userId=1')
  }));
}

async function askAi() {
  await runClient(() => postData('/ai/chat', { question: aiQuestion.value, user_id: 1 }));
}

async function loadAdmin() {
  await runAdmin(async () => adminOverview.value = {
    userAdmin: await getData('/user/admin/overview'),
    knowledgeAdmin: await getData('/knowledge/admin/overview'),
    forumAdmin: await getData('/post/admin/overview'),
    messageAdmin: await getData('/message/admin/overview?userId=1'),
    feedbackAdmin: await getData('/feedback/admin/overview?userId=1')
  });
}

async function loadAdminQueues() {
  await runAdmin(async () => ({
    knowledgeFiles: adminKnowledgeFiles.value = await getData<KnowledgeFile[]>('/knowledge/list'),
    knowledgeReports: knowledgeReports.value = await getData<KnowledgeReport[]>('/knowledge/admin/reports'),
    posts: await getData('/square/feed'),
    tickets: adminTickets.value = await getData<Ticket[]>('/feedback/tickets')
  }));
}

async function auditKnowledge() {
  await runAdmin(() => postData('/knowledge/admin/audit', knowledgeAudit.value));
}

async function auditPost() {
  await runAdmin(() => postData('/post/admin/audit', postAudit.value));
}

async function updateUserStatus() {
  await runAdmin(() => postData('/user/admin/status', userStatus.value));
}

async function replyTicket() {
  await runAdmin(() => postData('/feedback/admin/reply', ticketReply.value));
}

async function refreshClient() {
  activePortal.value = 'client';
  await loadKnowledge();
  await loadFeed();
  await loadFeedback();
}

async function refreshAdmin() {
  activePortal.value = 'admin';
  await loadAdmin();
  await loadAdminQueues();
}
</script>

<style scoped>
.shell { min-height: 100vh; padding: 32px; background: #f5f7fb; color: #1f2937; }
.hero, .portal, .card { border: 1px solid #e5e7eb; border-radius: 8px; background: #fff; }
.hero { display: flex; justify-content: space-between; gap: 24px; padding: 28px; align-items: center; }
.hero h1, .portal-head h2, .card h3 { margin: 0; }
.summary { max-width: 880px; line-height: 1.7; color: #4b5563; }
.eyebrow { margin: 0 0 8px; color: #2563eb; font-size: 12px; font-weight: 700; letter-spacing: 0; text-transform: uppercase; }
.portal-switch { display: flex; justify-content: center; margin: 22px 0; }
.portal { padding: 24px; }
.portal-head, .card-head { display: flex; justify-content: space-between; gap: 16px; align-items: center; margin-bottom: 20px; }
.portal-head { padding-bottom: 16px; border-bottom: 1px solid #e5e7eb; }
.admin-head .eyebrow { color: #059669; }
.hero-actions, .actions { display: flex; flex-wrap: wrap; gap: 10px; }
.form-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 16px; }
.result-grid { margin-top: 16px; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 16px; }
.card { padding: 18px; }
.wide { min-width: 0; }
.item-list { display: grid; gap: 10px; }
.list-item { display: flex; justify-content: space-between; gap: 14px; align-items: flex-start; padding: 12px; border: 1px solid #e5e7eb; border-radius: 8px; background: #f9fafb; }
.list-item p { margin: 6px 0 0; color: #4b5563; line-height: 1.5; }
.list-item small { display: block; margin-top: 6px; color: #059669; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; }
.metric { padding: 14px; border: 1px solid #e5e7eb; border-radius: 8px; background: #f9fafb; }
.metric span { display: block; color: #6b7280; font-size: 13px; }
.metric strong { display: block; margin-top: 8px; font-size: 22px; }
pre { min-height: 260px; margin: 16px 0 0; padding: 16px; overflow: auto; border-radius: 8px; background: #111827; color: #d1fae5; font-size: 13px; line-height: 1.55; white-space: pre-wrap; }
:deep(.el-select) { width: 100%; }
@media (max-width: 900px) {
  .shell { padding: 18px; }
  .hero, .portal-head, .card-head { display: block; }
  .hero-actions, .actions { margin-top: 16px; }
}
</style>
