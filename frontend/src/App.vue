<template>
  <main class="shell">
    <section class="hero">
      <div>
        <p class="eyebrow">Local MVP</p>
        <h1>AI 知识社区平台</h1>
        <p class="summary">
          当前本地版已拆成用户端和管理端两个入口：用户端负责知识、论坛、消息、广场、个人中心、反馈；管理端负责审核、统计、账号和工单处理。
        </p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" @click="refreshClient">刷新用户端</el-button>
        <el-button type="success" @click="refreshAdmin">刷新管理端</el-button>
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
        <el-tag type="primary">面向普通用户</el-tag>
      </div>

      <section class="grid">
        <article v-for="module in clientModules" :key="module.title" class="panel">
          <div class="panel-head">
            <h3>{{ module.title }}</h3>
            <el-tag>{{ module.owner }}</el-tag>
          </div>
          <p>{{ module.desc }}</p>
          <ul>
            <li v-for="item in module.items" :key="item">{{ item }}</li>
          </ul>
        </article>
      </section>

      <section class="workspace single">
        <div class="console">
          <h2>用户端接口联调</h2>
          <div class="actions">
            <el-button @click="login">登录</el-button>
            <el-button @click="loadKnowledge">知识库</el-button>
            <el-button @click="loadFeed">广场</el-button>
            <el-button @click="loadMessages">消息</el-button>
            <el-button @click="askAi">AI 问答</el-button>
            <el-button @click="loadFeedback">反馈/FAQ</el-button>
          </div>
          <pre>{{ clientOutput }}</pre>
        </div>
      </section>
    </section>

    <section v-else class="portal">
      <div class="portal-head admin-head">
        <div>
          <p class="eyebrow">Admin Portal</p>
          <h2>管理端</h2>
        </div>
        <el-tag type="success">面向平台管理员</el-tag>
      </div>

      <section class="workspace">
        <div class="admin">
          <h2>管理端六大模块</h2>
          <el-table :data="adminModules" size="small">
            <el-table-column prop="name" label="模块" width="150" />
            <el-table-column prop="scope" label="管理范围" />
            <el-table-column prop="status" label="本地 MVP 状态" width="130" />
          </el-table>
        </div>

        <div class="console">
          <h2>管理端接口联调</h2>
          <div class="actions">
            <el-button type="success" @click="loadAdmin">平台概览</el-button>
            <el-button @click="loadAdminAudits">审核队列</el-button>
            <el-button @click="loadFeedbackAdmin">工单处理</el-button>
          </div>
          <pre>{{ adminOutput }}</pre>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { getData, postData } from './api/client';

const activePortal = ref<'client' | 'admin'>('client');
const clientOutput = ref('用户端：点击按钮联调本地接口。请先启动 Nacos、后端服务、AI 服务，再启动前端。');
const adminOutput = ref('管理端：点击按钮查看审核、统计、账号和工单管理接口。');

const portalOptions = [
  { label: '用户端', value: 'client' },
  { label: '管理端', value: 'admin' }
];

const clientModules = [
  { title: '知识库', owner: 'knowledge-service + ai-service', desc: '支持知识上传、检索、收藏、举报、转发和 AI 问答引用。', items: ['AI 问答', '知识存储', '检索浏览', '用户榜单'] },
  { title: '论坛', owner: 'community-service', desc: '支持发帖、修改、评论、草稿暂存和帖子详情。', items: ['发帖', '草稿暂存', '评论互动', '帖子修改'] },
  { title: '消息', owner: 'message-service + user-service', desc: '支持私信、通知、消息清理和用户关系动作。', items: ['私信聊天', '互动提醒', '关注关系', '清除记录'] },
  { title: '广场', owner: 'community-service', desc: '只展示已关注用户动态，支持快捷评论和收藏。', items: ['关注信息流', '作者筛选', '内容跳转', '快捷互动'] },
  { title: '个人中心', owner: 'user-service', desc: '提供个人资料、登录态、关注动作和后续个人内容入口。', items: ['个人信息', '登录注册', '关注操作', '账号状态'] },
  { title: '用户反馈', owner: 'message-service', desc: '支持 FAQ、工单提交、工单列表和客服回复管理入口。', items: ['FAQ', '提交反馈', '工单记录', '处理进度'] }
];

const adminModules = [
  { name: '知识库管理', scope: '资源审核、分类维护、AI 解析状态、违规举报处理', status: '已接入接口' },
  { name: '论坛管理', scope: '帖子审核、评论管理、草稿追踪、广场互动管理', status: '已接入接口' },
  { name: '消息互动管理', scope: '私信监管、互动提醒、聊天归档、消息清理', status: '已接入接口' },
  { name: '平台数据统计', scope: '用户、知识、论坛、消息、反馈的本地统计汇总', status: '前端聚合' },
  { name: '用户账号管理', scope: '资料审核、账号状态、社交关系、个人内容追踪', status: '已接入接口' },
  { name: '工单反馈管理', scope: '客服配置、工单处理、进度跟踪、FAQ 维护', status: '已接入接口' }
];

function formatData(data: unknown) {
  return JSON.stringify(data, null, 2);
}

function formatError(error: unknown) {
  const message = error instanceof Error ? error.message : String(error);
  return `请求失败：${message}\n\n请确认 start-nacos.bat 和 start-local.bat 已重新启动，旧服务窗口需要先关闭。`;
}

async function runClient(action: () => Promise<unknown>) {
  clientOutput.value = '请求中...';
  try {
    clientOutput.value = formatData(await action());
  } catch (error) {
    clientOutput.value = formatError(error);
  }
}

async function runAdmin(action: () => Promise<unknown>) {
  adminOutput.value = '请求中...';
  try {
    adminOutput.value = formatData(await action());
  } catch (error) {
    adminOutput.value = formatError(error);
  }
}

async function login() {
  await runClient(() => postData('/user/login', { username: 'demo', password: 'demo' }));
}

async function loadKnowledge() {
  await runClient(async () => ({ files: await getData('/knowledge/list'), ranking: await getData('/knowledge/ranking') }));
}

async function loadFeed() {
  await runClient(() => getData('/square/feed'));
}

async function loadMessages() {
  await runClient(async () => ({ notifications: await getData('/notification/list?userId=1'), messages: await getData('/message/list?sessionId=1') }));
}

async function askAi() {
  await runClient(() => postData('/ai/chat', { question: '平台有哪些核心模块？', user_id: 1 }));
}

async function loadFeedback() {
  await runClient(async () => ({ faqs: await getData('/feedback/faqs'), tickets: await getData('/feedback/tickets?userId=1') }));
}

async function loadAdmin() {
  await runAdmin(async () => {
    const [users, knowledge, forum, messages, feedback] = await Promise.all([
      getData('/user/admin/overview'),
      getData('/knowledge/admin/overview'),
      getData('/post/admin/overview'),
      getData('/message/admin/overview?userId=1'),
      getData('/feedback/admin/overview?userId=1')
    ]);

    return {
      userAdmin: users,
      knowledgeAdmin: knowledge,
      forumAdmin: forum,
      messageAdmin: messages,
      feedbackAdmin: feedback,
      statistics: { modules: 6, status: '本地 MVP 聚合统计已接入' }
    };
  });
}

async function loadAdminAudits() {
  await runAdmin(async () => ({
    knowledgeAudit: await getData('/knowledge/admin/audit'),
    forumAudit: await getData('/post/admin/audit'),
    userStatus: await getData('/user/admin/status')
  }));
}

async function loadFeedbackAdmin() {
  await runAdmin(() => getData('/feedback/admin/overview?userId=1'));
}

async function refreshClient() {
  activePortal.value = 'client';
  await loadKnowledge();
}

async function refreshAdmin() {
  activePortal.value = 'admin';
  await loadAdmin();
}
</script>

<style scoped>
.shell {
  min-height: 100vh;
  padding: 32px;
  background: #f5f7fb;
  color: #1f2937;
}

.hero,
.portal,
.workspace,
.panel,
.console,
.admin {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #ffffff;
}

.hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 28px;
  align-items: center;
}

.hero h1,
.portal-head h2 {
  margin: 0;
}

.hero-actions,
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.summary {
  max-width: 820px;
  line-height: 1.7;
  color: #4b5563;
}

.eyebrow {
  margin: 0 0 8px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0;
  text-transform: uppercase;
}

.portal-switch {
  display: flex;
  justify-content: center;
  margin: 22px 0;
}

.portal {
  padding: 24px;
}

.portal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid #e5e7eb;
}

.admin-head .eyebrow {
  color: #059669;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 16px;
}

.panel,
.console,
.admin {
  padding: 18px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.panel h3,
.console h2,
.admin h2 {
  margin: 0 0 12px;
}

.panel p {
  color: #4b5563;
  line-height: 1.6;
}

.panel ul {
  margin: 12px 0 0;
  padding-left: 18px;
  color: #374151;
}

.workspace {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(360px, 0.9fr);
  gap: 18px;
  margin-top: 18px;
  padding: 18px;
  background: #f9fafb;
}

.workspace.single {
  grid-template-columns: 1fr;
}

pre {
  min-height: 280px;
  margin: 16px 0 0;
  padding: 16px;
  overflow: auto;
  border-radius: 8px;
  background: #111827;
  color: #d1fae5;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}

@media (max-width: 900px) {
  .shell {
    padding: 18px;
  }

  .hero,
  .portal-head,
  .workspace {
    display: block;
  }

  .hero-actions {
    margin-top: 16px;
  }

  .console {
    margin-top: 16px;
  }
}
</style>
