<template>
  <div v-if="!authenticated" class="auth-screen">
    <section class="login-panel">
      <div class="brand-mark"><Reading /></div>
      <p class="product-label">AI KNOWLEDGE COMMUNITY</p>
      <h1>AI 知识社区平台</h1>
      <p class="login-copy">访问知识库、参与社区讨论，并使用知识增强的 AI 问答。</p>
      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="用户名"><el-input v-model="loginForm.username" size="large" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="loginForm.password" size="large" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-button class="login-button" type="primary" size="large" :loading="busy" native-type="submit">登录</el-button>
        <el-button class="register-entry" text type="primary" @click="registerDialog = true">没有账号？立即注册</el-button>
      </el-form>
      <div class="demo-accounts">
        <button type="button" @click="useAccount('demo', 'demo')"><span>用户体验账号</span><strong>demo / demo</strong></button>
        <button type="button" @click="useAccount('admin', 'admin123')"><span>管理体验账号</span><strong>admin / admin123</strong></button>
      </div>
    </section>
    <el-dialog v-model="registerDialog" title="注册社区账号" width="min(460px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="registerForm.username" autocomplete="username" /></el-form-item>
        <el-form-item label="昵称"><el-input v-model="registerForm.nickname" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="registerForm.password" type="password" show-password autocomplete="new-password" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="registerDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="registerAccount">注册并登录</el-button></template>
    </el-dialog>
  </div>

  <div v-else class="app-layout" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <aside class="sidebar" :class="{ open: mobileMenuOpen }">
      <div class="sidebar-brand">
        <el-button class="sidebar-collapse" :icon="sidebarCollapsed ? ArrowRight : Menu" circle text :title="sidebarCollapsed ? '展开导航' : '收起导航'" @click="sidebarCollapsed = !sidebarCollapsed" />
        <div class="brand-mark small"><Reading /></div>
        <div><strong>知汇</strong><span>AI Knowledge</span></div>
      </div>

      <div v-if="role === 'ADMIN'" class="portal-toggle">
        <button :class="{ active: portal === 'client' }" @click="switchPortal('client')">用户端</button>
        <button :class="{ active: portal === 'admin' }" @click="switchPortal('admin')">管理端</button>
      </div>

      <nav class="main-nav" aria-label="主导航">
        <button v-for="item in currentNavigation" :key="item.key" :class="{ active: activeView === item.key }" @click="selectView(item.key)">
          <component :is="item.icon" /><span>{{ item.label }}</span>
          <el-badge v-if="item.badge" :value="item.badge" />
        </button>
      </nav>

      <div class="sidebar-footer">
        <div class="user-avatar"><img v-if="avatarUrl" :src="resolveApiUrl(avatarUrl)" alt="avatar" /><span v-else>{{ displayName.slice(0, 1).toUpperCase() }}</span></div>
        <div><strong>{{ displayName }}</strong><span>{{ role === 'ADMIN' ? '平台管理员' : '社区用户' }}</span></div>
        <el-button :icon="SwitchButton" circle text title="退出登录" @click="logout" />
      </div>
    </aside>
    <div v-if="mobileMenuOpen" class="mobile-mask" @click="mobileMenuOpen = false" />

    <section class="workspace-shell">
      <header class="topbar">
        <el-button class="menu-button" :icon="Menu" circle text title="打开导航" @click="mobileMenuOpen = true" />
        <div><p>{{ portal === 'admin' ? '运营管理' : '知识社区' }}</p><h2>{{ currentTitle }}</h2></div>
        <div class="topbar-actions">
          <el-upload v-if="portal === 'client' && activeView === 'profile'" :show-file-list="false" :auto-upload="false" accept="image/jpeg,image/png,image/gif,image/webp" :on-change="handleAvatarFile"><el-button :icon="Upload">上传头像</el-button></el-upload>
          <el-input v-if="portal === 'client'" v-model="globalSearch" class="global-search" placeholder="搜索知识与内容" :prefix-icon="Search" clearable @keyup.enter="runGlobalSearch" />
          <el-button v-if="portal === 'admin' && activeView === 'moderation'" :icon="Setting" circle title="管理知识分类" @click="openCategoryManager" />
          <el-button :icon="Refresh" circle title="刷新当前数据" @click="refreshCurrentView" />
        </div>
      </header>

      <main class="content-area">
        <div v-if="viewLoading" class="view-loading" role="status"><el-icon class="is-loading"><Loading /></el-icon><span>正在加载当前页面...</span></div>
        <div v-else-if="viewError" class="view-error"><el-icon><Warning /></el-icon><span>{{ viewError }}</span><el-button size="small" type="primary" @click="refreshCurrentView">重试</el-button></div>
        <template v-if="!viewLoading && !viewError && portal === 'client'">
          <section v-if="activeView === 'home'" class="page-stack">
            <div class="welcome-row">
              <div><p class="section-kicker">今日概览</p><h3>{{ greeting }}，{{ displayName }}</h3><p>继续探索知识、社区动态和你的 AI 对话。</p></div>
              <el-button type="primary" :icon="Upload" @click="knowledgeDialog = true">上传知识</el-button>
            </div>
            <div class="metrics-grid">
              <div class="metric-tile"><span class="metric-icon green"><Files /></span><div><strong>{{ knowledgeFiles.length }}</strong><span>知识资源</span></div></div>
              <div class="metric-tile"><span class="metric-icon blue"><ChatDotRound /></span><div><strong>{{ feedPosts.length }}</strong><span>社区动态</span></div></div>
              <div class="metric-tile"><span class="metric-icon amber"><Bell /></span><div><strong>{{ notifications.length }}</strong><span>通知提醒</span></div></div>
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
                <div class="surface-head"><div><h3>社区动态</h3><p>关注内容与最新讨论</p></div><el-button text type="primary" @click="selectView('community')">进入广场</el-button></div>
                <div v-if="feedPosts.length" class="feed-mini">
                  <article v-for="post in feedPosts.slice(0, 3)" :key="post.id"><div class="mini-avatar">{{ post.userId }}</div><div><strong>{{ post.title }}</strong><p>{{ post.content }}</p><small>用户 {{ post.userId }} · {{ post.likes || 0 }} 赞</small></div></article>
                </div><el-empty v-else description="暂无社区动态" />
              </section>
            </div>
          </section>

          <section v-else-if="activeView === 'knowledge'" class="page-stack">
            <div class="page-toolbar"><div><h3>知识库</h3><p>检索、阅读并管理社区知识资源</p></div><el-button type="primary" :icon="Upload" @click="knowledgeDialog = true">上传资料</el-button></div>
            <section class="surface filter-bar"><el-input v-model="knowledgeKeyword" placeholder="输入标题或正文关键词" :prefix-icon="Search" clearable @keyup.enter="searchKnowledge" /><el-select v-model="knowledgeType" placeholder="全部格式" clearable><el-option label="Word" value="docx" /><el-option label="PDF" value="pdf" /><el-option label="TXT" value="txt" /><el-option label="Markdown" value="md" /></el-select><el-button :icon="Search" @click="searchKnowledge">搜索</el-button></section>
            <section class="surface">
              <div class="category-filter">
                <el-radio-group v-model="knowledgeCategoryId" size="small">
                  <el-radio-button :value="0">全部 {{ knowledgeFiles.length }}</el-radio-button>
                  <el-radio-button v-for="category in knowledgeCategories" :key="category.id" :value="category.id">
                    {{ category.name }} {{ categoryCount(category.id) }}
                  </el-radio-button>
                </el-radio-group>
              </div>
              <div v-if="filteredKnowledge.length" class="knowledge-grid">
                <article v-for="file in filteredKnowledge" :key="file.id" class="knowledge-card">
                  <img v-if="file.coverUrl" class="knowledge-cover" :src="resolveApiUrl(file.coverUrl)" :alt="`${file.title}封面`" />
                  <div class="knowledge-card-top"><span class="file-type large">{{ file.fileType?.toUpperCase() || 'DOC' }}</span><el-tag :type="file.auditStatus === 'APPROVED' ? 'success' : 'warning'" effect="plain">{{ auditLabel(file.auditStatus) }}</el-tag></div>
                  <h4>{{ file.title }}</h4><p>上传者 #{{ file.userId }} · 资源编号 {{ file.id }}</p>
                  <div class="card-stats"><span><View />{{ file.views || 0 }}</span><span><Download />{{ file.downloads || 0 }}</span><span><Star />{{ file.likes || 0 }}</span></div>
                  <div class="card-actions"><el-button text type="primary" @click="viewKnowledge(file)">阅读</el-button><el-button v-if="file.fileUrl" text @click="downloadKnowledge(file)">下载</el-button><el-dropdown trigger="click"><el-button text :icon="MoreFilled" /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="likeKnowledge(file)">点赞</el-dropdown-item><el-dropdown-item @click="collectKnowledge(file)">收藏</el-dropdown-item><el-dropdown-item @click="forwardKnowledge(file)">转发</el-dropdown-item><el-dropdown-item divided @click="reportKnowledge(file)">举报</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
                </article>
              </div><el-empty v-else description="没有匹配的知识资源" />
            </section>
          </section>

          <section v-else-if="activeView === 'community'" class="page-stack">
            <div class="page-toolbar"><div><h3>社区广场</h3><p>发现关注用户的动态并参与讨论</p></div><el-button type="primary" :icon="EditPen" @click="postDialog = true">发布帖子</el-button></div>
            <div class="feed-layout">
              <section class="feed-column">
                <div class="feed-tabs"><button :class="{ active: feedMode === 'all' }" @click="loadFeed('all')">最新</button><button :class="{ active: feedMode === 'following' }" @click="loadFeed('following')">关注</button></div>
                <article v-for="post in feedPosts" :key="post.id" class="post-card">
                  <div class="post-author"><div class="user-avatar"><img v-if="communityUser(post.userId).avatarUrl" :src="resolveApiUrl(communityUser(post.userId).avatarUrl || '')" alt="头像" /><span v-else>{{ communityUser(post.userId).nickname.slice(0, 1).toUpperCase() }}</span></div><div><strong>{{ communityUser(post.userId).nickname }}</strong><span>@{{ communityUser(post.userId).username }} · 帖子 #{{ post.id }}</span></div><el-tag v-if="post.status !== 'PUBLISHED'" type="warning">{{ post.status }}</el-tag></div>
                  <h3>{{ post.title }}</h3><p>{{ post.content }}</p>
                  <div v-if="post.imageUrls?.length" class="post-images"><img v-for="image in post.imageUrls" :key="image" :src="resolveApiUrl(image)" alt="帖子配图" /></div>
                  <div class="post-actions"><el-button text :icon="Star" @click="likePost(post)">{{ post.likes || 0 }} 点赞</el-button><el-button text :icon="ChatDotRound" @click="openComments(post)">评论</el-button><el-button text :icon="CollectionTag" @click="collectPost(post)">收藏</el-button></div>
                </article>
                <el-empty v-if="!feedPosts.length" description="还没有动态，发布第一条帖子吧" />
              </section>
              <aside class="surface community-side"><h3>我的创作</h3><button @click="openDrafts"><Document />草稿箱<span>{{ drafts.length }}</span></button><button @click="loadMyPosts"><EditPen />我的帖子<ArrowRight /></button><h3>社区提示</h3><p>尊重原创，理性交流。发现不当内容可通过举报交由管理员处理。</p></aside>
            </div>
          </section>

          <section v-else-if="activeView === 'messages'" class="message-page">
            <section class="surface session-panel"><div class="surface-head"><div><h3>消息中心</h3><p>{{ sessions.length }} 个联系人</p></div><el-button :icon="Plus" circle title="发起私信" @click="newConversation" /></div><div class="session-list"><button v-for="session in sessions" :key="session.id" :class="{ active: messageForm.sessionId === session.id }" @click="openSession(session)"><div class="mini-avatar">{{ sessionPartner(session).nickname.slice(0,1).toUpperCase() }}</div><span><strong>{{ sessionPartner(session).nickname }}</strong><small>{{ session.lastMessage || `@${sessionPartner(session).username}` }}</small></span></button></div><el-empty v-if="!sessions.length" description="暂无私信会话" /></section>
            <section class="surface conversation-panel"><div class="conversation-head"><div><strong>{{ currentMessagePartner?.nickname || '选择联系人开始私信' }}</strong><span v-if="currentMessagePartner">@{{ currentMessagePartner.username }} · 私密会话</span></div><el-dropdown v-if="messageForm.sessionId"><el-button :icon="MoreFilled" circle text /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="clearCurrentSession">清空当前会话</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div><div class="message-list"><div v-for="message in messages" :key="message.id" :class="['message-bubble', message.senderId === currentUserId ? 'mine' : '']"><p>{{ message.content }}</p><div class="message-meta"><small>{{ formatDate(message.createdAt) }}</small><el-button :icon="Delete" circle text type="danger" title="删除消息" @click="deleteMessage(message)" /></div></div><el-empty v-if="!messages.length" description="选择联系人并发送第一条消息" /></div><div class="message-compose"><el-input v-model="messageForm.content" :disabled="!messageForm.sessionId" placeholder="输入消息" @keyup.enter="sendMessage" /><el-button type="primary" :icon="Promotion" :disabled="!messageForm.sessionId" @click="sendMessage">发送</el-button></div></section>
          </section>

          <section v-else-if="activeView === 'ai'" class="ai-page">
            <section class="surface ai-chat"><div class="ai-heading"><span class="ai-symbol"><MagicStick /></span><div><h3>知识库 AI</h3><p>回答将优先引用平台已收录的知识片段</p></div></div><div class="ai-messages"><div v-if="!aiMessages.length" class="ai-empty"><MagicStick /><h3>今天想了解什么？</h3><p>试试询问平台知识、文档内容或社区使用方式。</p><div><button v-for="prompt in aiPrompts" :key="prompt" @click="aiQuestion = prompt">{{ prompt }}</button></div></div><div v-for="(message, index) in aiMessages" :key="index" :class="['ai-message', message.role]"><span>{{ message.role === 'assistant' ? 'AI' : displayName.slice(0, 1) }}</span><p>{{ message.content }}</p></div></div><div class="ai-compose"><el-input v-model="aiQuestion" type="textarea" :rows="2" resize="none" placeholder="向知识库提问..." @keydown.ctrl.enter="askAi" /><el-button type="primary" :icon="Promotion" :loading="aiBusy" @click="askAi">发送</el-button></div></section>
            <aside class="surface ai-history"><div class="surface-head"><div><h3>对话历史</h3><p>最近的 AI 会话</p></div><el-button :icon="Refresh" circle text @click="loadAiHistory" /></div><button v-for="session in aiSessions" :key="session.id" @click="loadAiSession(session.id)"><ChatLineRound /><span><strong>{{ session.title }}</strong><small>{{ formatDate(session.created_at) }}</small></span></button><el-empty v-if="!aiSessions.length" description="暂无历史对话" /></aside>
          </section>

          <section v-else class="page-stack">
            <div class="page-toolbar"><div><h3>个人中心</h3><p>管理资料、关注关系和反馈工单</p></div><el-button type="primary" @click="saveProfile">保存资料</el-button></div>
            <div class="profile-layout"><section class="surface profile-card"><div class="profile-avatar">{{ displayName.slice(0, 1).toUpperCase() }}</div><h3>{{ displayName }}</h3><p>@{{ username }}</p><el-tag>{{ role }}</el-tag><div class="profile-counts"><span><strong>{{ followData.followedUserIds?.length || 0 }}</strong>关注</span><span><strong>{{ followData.followerUserIds?.length || 0 }}</strong>粉丝</span><span><strong>{{ drafts.length }}</strong>草稿</span></div></section><section class="surface profile-form"><h3>基础资料</h3><el-form label-position="top"><el-form-item label="昵称"><el-input v-model="profileForm.nickname" /></el-form-item><el-form-item label="头像地址"><el-input v-model="profileForm.avatarUrl" /></el-form-item><el-form-item label="个性签名"><el-input v-model="profileForm.signature" type="textarea" :rows="3" /></el-form-item></el-form><el-divider>修改密码</el-divider><el-form label-position="top"><el-form-item label="当前密码"><el-input v-model="passwordForm.currentPassword" type="password" show-password /></el-form-item><el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" show-password /></el-form-item><el-button type="primary" plain @click="changePassword">更新密码</el-button></el-form></section><section class="surface feedback-card"><div class="surface-head"><div><h3>我的反馈</h3><p>问题进度与官方回复</p></div><el-button :icon="Plus" circle @click="feedbackDialog = true" /></div><article v-for="ticket in tickets" :key="ticket.id"><div><strong>{{ ticket.type }}</strong><p>{{ ticket.content }}</p><small v-if="ticket.reply">官方回复：{{ ticket.reply }}</small></div><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag></article><el-empty v-if="!tickets.length" description="暂无反馈工单" /></section></div>
            <section class="surface"><div class="surface-head"><div><h3>我的知识资源</h3><p>统一查看上传、收藏、点赞、下载和转发记录</p></div><el-radio-group v-model="knowledgeActivityType" size="small" @change="loadMyKnowledge"><el-radio-button value="UPLOADED">上传</el-radio-button><el-radio-button value="COLLECTED">收藏</el-radio-button><el-radio-button value="LIKED">点赞</el-radio-button><el-radio-button value="DOWNLOADED">下载</el-radio-button><el-radio-button value="FORWARDED">转发</el-radio-button></el-radio-group></div><div class="activity-list"><article v-for="file in myKnowledge" :key="file.id"><span class="file-type">{{ file.fileType?.toUpperCase() }}</span><div><strong>{{ file.title }}</strong><p>资源 #{{ file.id }} · {{ auditLabel(file.auditStatus) }}</p></div><el-button text type="primary" @click="openKnowledge(file)">阅读</el-button></article><el-empty v-if="!myKnowledge.length" description="暂无对应资源记录" /></div></section>
            <div class="two-column account-tools">
              <section class="surface"><div class="surface-head"><div><h3>关系与安全</h3><p>搜索用户后管理关注、拉黑和举报</p></div></div><el-form label-position="top"><el-form-item label="选择用户"><el-select v-model="relationForm.targetUserId" filterable placeholder="输入昵称或用户名"><el-option v-for="user in directoryUsers" :key="user.id" :label="`${user.nickname} (@${user.username})`" :value="user.id" /></el-select></el-form-item></el-form><div class="relation-actions"><el-button type="primary" @click="followUser">关注</el-button><el-button @click="unfollowUser">取消关注</el-button><el-button type="warning" @click="blockUser">拉黑</el-button><el-button @click="unblockUser">解除拉黑</el-button><el-button type="danger" text @click="reportUser">举报用户</el-button></div><div class="relation-summary"><span>已关注 <strong>{{ followData.followedUserIds?.map(id=>directoryUsers.find(user=>user.id===id)?.nickname||`#${id}`).join('、') || '无' }}</strong></span><span>黑名单 <strong>{{ blockedUserIds.map(id=>directoryUsers.find(user=>user.id===id)?.nickname||`#${id}`).join('、') || '无' }}</strong></span></div></section>
              <section class="surface"><el-tabs><el-tab-pane label="行为足迹"><div class="activity-list"><article v-for="item in behaviors" :key="item.id"><span class="event-dot"></span><div><strong>{{ behaviorActionLabel(item.action) }} · {{ behaviorTargetLabel(item.targetType) }}</strong><p>{{ behaviorTargetLabel(item.targetType) }} #{{ item.targetId }}</p><small>{{ formatDate(item.createdAt) }}</small></div></article><el-empty v-if="!behaviors.length" description="暂无行为记录" /></div></el-tab-pane><el-tab-pane :label="`我的草稿 ${drafts.length}`"><article v-for="draft in drafts" :key="draft.id" class="draft-row"><div><strong>{{ draft.title }}</strong><p>{{ draft.content }}</p></div><el-tag size="small">草稿</el-tag></article><el-empty v-if="!drafts.length" description="暂无草稿" /></el-tab-pane></el-tabs></section>
            </div>
          </section>
        </template>

        <template v-else-if="!viewLoading && !viewError">
          <section v-if="activeView === 'dashboard'" class="page-stack">
            <div class="page-toolbar"><div><h3>平台运营概览</h3><p>内容、用户、互动和待办事项的实时摘要</p></div><el-button :icon="Refresh" @click="loadAdminDashboard">刷新数据</el-button></div>
            <div class="metrics-grid admin-metrics"><div v-for="metric in adminMetrics" :key="metric.label" class="metric-tile"><span :class="['metric-icon', metric.color]"><component :is="metric.icon" /></span><div><strong>{{ metric.value }}</strong><span>{{ metric.label }}</span><small>{{ metric.hint }}</small></div></div></div>
            <div class="two-column"><section class="surface"><div class="surface-head"><div><h3>待处理事项</h3><p>优先处理积压内容</p></div></div><div class="task-list"><button @click="selectView('moderation')"><span class="task-dot amber"></span><span><strong>知识审核</strong><small>用户上传资源审核</small></span><b>{{ metricValue('knowledgeAdmin', 'pendingAudit') }}</b></button><button @click="selectView('moderation')"><span class="task-dot red"></span><span><strong>内容举报</strong><small>知识与用户举报</small></span><b>{{ knowledgeReports.length + userReports.length }}</b></button><button @click="selectView('tickets')"><span class="task-dot blue"></span><span><strong>反馈工单</strong><small>待回复用户问题</small></span><b>{{ metricValue('feedbackAdmin', 'pendingTickets') }}</b></button></div></section><section class="surface"><div class="surface-head"><div><h3>系统状态</h3><p>来自各服务健康接口的实时结果</p></div><el-tag :type="healthItems.every(item=>item.ok)?'success':'danger'">{{ healthItems.every(item=>item.ok)?'运行正常':'存在异常' }}</el-tag></div><div class="status-list"><span v-for="item in healthItems" :key="item.label"><i :class="{offline:!item.ok}"></i>{{ item.label }}<strong :class="{offline:!item.ok}">{{ item.ok?'正常':'异常' }}</strong></span></div></section></div>
          </section>

          <section v-else-if="activeView === 'moderation'" class="page-stack"><div class="page-toolbar"><div><h3>内容审核</h3><p>先查看完整内容，再处理知识、帖子和举报</p></div><el-button :icon="Refresh" @click="loadModeration">刷新队列</el-button></div><section class="surface"><el-tabs v-model="moderationTab"><el-tab-pane label="知识资源" name="knowledge"><el-table :data="knowledgeFiles"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="fileType" label="格式" width="90" /><el-table-column prop="auditStatus" label="状态" width="110" /><el-table-column label="操作" width="250"><template #default="scope"><el-button text type="primary" @click="reviewKnowledge(scope.row)">查看内容</el-button><el-button text type="success" @click="auditKnowledge(scope.row, 'APPROVED')">通过</el-button><el-button text type="danger" @click="auditKnowledge(scope.row, 'REJECTED')">驳回</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane :label="`知识举报 ${knowledgeReports.length}`" name="reports"><el-table :data="knowledgeReports"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="fileId" label="资源" width="90" /><el-table-column prop="reason" label="举报原因" min-width="240" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="120"><template #default="scope"><el-button text type="primary" :disabled="scope.row.status === 'RESOLVED'" @click="resolveKnowledgeReport(scope.row)">结案</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane :label="`用户举报 ${userReports.length}`" name="users"><el-table :data="userReports"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="targetUserId" label="被举报用户" width="120" /><el-table-column prop="reason" label="原因" min-width="240" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="120"><template #default="scope"><el-button text type="primary" :disabled="scope.row.status === 'RESOLVED'" @click="resolveUserReport(scope.row)">结案</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane label="帖子" name="posts"><el-table :data="feedPosts"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="250"><template #default="scope"><el-button text type="primary" @click="reviewPost(scope.row)">查看内容</el-button><el-button text type="success" @click="auditPost(scope.row, 'PUBLISHED')">发布</el-button><el-button text type="danger" @click="auditPost(scope.row, 'HIDDEN')">隐藏</el-button></template></el-table-column></el-table></el-tab-pane></el-tabs></section></section>

          <section v-else-if="activeView === 'users'" class="page-stack"><div class="page-toolbar"><div><h3>用户管理</h3><p>账号资料、状态和权限维护</p></div><el-input v-model="adminUserKeyword" class="table-search" placeholder="搜索用户" :prefix-icon="Search" clearable /></div><section class="surface"><el-table :data="filteredAdminUsers"><el-table-column label="用户" min-width="230"><template #default="scope"><div class="table-user"><div class="mini-avatar">{{ scope.row.nickname.slice(0, 1) }}</div><span><strong>{{ scope.row.nickname }}</strong><small>@{{ scope.row.username }}</small></span></div></template></el-table-column><el-table-column prop="role" label="角色" width="110" /><el-table-column prop="status" label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'danger'">{{ scope.row.status === 'ACTIVE' ? '正常' : '已禁用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="160"><template #default="scope"><el-button text :type="scope.row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggleUserStatus(scope.row)">{{ scope.row.status === 'ACTIVE' ? '禁用账号' : '恢复账号' }}</el-button></template></el-table-column></el-table></section></section>

          <section v-else-if="activeView === 'tickets'" class="page-stack"><div class="page-toolbar"><div><h3>工单与 FAQ</h3><p>跟进用户问题并维护自助答疑</p></div><el-button type="primary" :icon="Plus" @click="faqDialog = true">新增 FAQ</el-button></div><div class="two-column"><section class="surface"><div class="surface-head"><div><h3>反馈工单</h3><p>{{ adminTickets.length }} 条记录</p></div></div><article v-for="ticket in adminTickets" :key="ticket.id" class="ticket-row"><div><strong>#{{ ticket.id }} · {{ ticket.type }}</strong><p>{{ ticket.content }}</p><small v-if="ticket.reply">当前回复：{{ ticket.reply }}</small></div><div><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag><el-button text type="primary" @click="openTicketReply(ticket)">处理</el-button></div></article><el-empty v-if="!adminTickets.length" description="暂无工单" /></section><section class="surface"><div class="surface-head"><div><h3>常见问题</h3><p>用户端自助答疑内容</p></div></div><article v-for="faq in faqs" :key="faq.id" class="faq-row"><div><strong>{{ faq.question }}</strong><p>{{ faq.answer }}</p></div><div><el-button :icon="Edit" circle text @click="editFaq(faq)" /><el-button :icon="Delete" circle text type="danger" @click="deleteFaq(faq)" /></div></article><el-empty v-if="!faqs.length" description="暂无 FAQ" /></section></div></section>

          <section v-else class="page-stack"><div class="page-toolbar"><div><h3>AI 与系统配置</h3><p>控制知识检索范围、匹配规则并查看事件日志</p></div><el-button type="primary" @click="saveAiConfig">保存配置</el-button></div><div class="two-column"><section class="surface settings-form"><h3>AI 检索配置</h3><el-form label-position="top"><el-form-item label="AI 提供商"><el-select v-model="aiConfig.provider"><el-option label="本地知识检索" value="local" /><el-option label="OpenAI 兼容接口" value="openai-compatible" /></el-select></el-form-item><el-form-item label="模型名称"><el-input v-model="aiConfig.model" /></el-form-item><el-form-item label="完整请求地址"><el-input v-model="aiConfig.request_url" :disabled="aiConfig.provider === 'local'" placeholder="例如 https://api.example.com/v1/chat/completions" /></el-form-item><el-form-item label="兼容接口基础地址（旧配置）"><el-input v-model="aiConfig.base_url" :disabled="aiConfig.provider === 'local'" placeholder="留空即可；仅用于兼容旧配置" /></el-form-item><el-form-item label="生成温度"><el-slider v-model="aiConfig.temperature" :min="0" :max="2" :step="0.1" show-input /></el-form-item><el-form-item label="知识数据源范围"><el-select v-model="aiConfig.data_source_scope"><el-option label="全部已审核知识" value="all-approved" /><el-option label="仅管理员指定" value="admin-selected" /></el-select></el-form-item><el-form-item label="单次匹配片段数"><el-input-number v-model="aiConfig.match_limit" :min="1" :max="20" /></el-form-item><el-form-item label="回复合规规则"><el-input v-model="aiConfig.compliance_rule" /></el-form-item><el-form-item label="单个上传文件上限（MB）"><el-input-number v-model="aiConfig.max_upload_mb" :min="1" :max="200" /></el-form-item><el-form-item label="通知功能"><el-switch v-model="aiConfig.notifications_enabled" /></el-form-item><el-form-item label="社区功能"><el-switch v-model="aiConfig.community_enabled" /></el-form-item></el-form><div class="config-status"><span>已索引知识片段<strong>{{ aiOverview.chunk_count || 0 }}</strong></span><span>AI 对话会话<strong>{{ aiOverview.session_count || 0 }}</strong></span></div></section><section class="surface"><div class="surface-head"><div><h3>业务事件日志</h3><p>最近 50 条本地事件</p></div><el-tag type="info">{{ adminEvents.length }}</el-tag></div><div class="event-list"><article v-for="event in adminEvents" :key="event.id"><span class="event-dot"></span><div><strong>{{ event.type }}</strong><p>对象 {{ event.aggregateId }}</p><small>{{ formatDate(event.createdAt) }}</small></div><el-tag size="small" effect="plain">{{ event.status }}</el-tag></article></div><el-empty v-if="!adminEvents.length" description="暂无事件" /></section></div></section>
        </template>
      </main>
    </section>

    <el-dialog v-model="knowledgeDialog" title="上传知识资料" width="min(640px, 94vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标题"><el-input v-model="knowledgeForm.title" placeholder="留空时使用文件名或首张图片名" /></el-form-item>
        <el-form-item label="知识分类"><el-select v-model="knowledgeForm.categoryId" clearable placeholder="选择分类"><el-option v-for="category in knowledgeCategories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item>
        <el-form-item label="资料文件">
          <el-upload drag :auto-upload="false" :limit="1" accept=".txt,.md,.pdf,.docx" :on-change="handleKnowledgeFile" :on-remove="clearKnowledgeFile"><UploadFilled /><div class="el-upload__text">拖放文件到这里，或<em>点击选择</em></div><template #tip><div class="el-upload__tip">支持 TXT、Markdown、PDF、DOCX，单个文件不超过 25 MB</div></template></el-upload>
        </el-form-item>
        <el-divider>或直接录入文本</el-divider>
        <div class="form-pair"><el-form-item label="文件名"><el-input v-model="knowledgeForm.filename" /></el-form-item><el-form-item label="格式"><el-select v-model="knowledgeForm.fileType"><el-option label="TXT" value="txt" /><el-option label="Markdown" value="md" /></el-select></el-form-item></div>
        <el-form-item label="文本正文"><el-input v-model="knowledgeForm.content" type="textarea" :rows="6" placeholder="可直接录入纯文本；正文图片只能包含在上传的原始文件中" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="knowledgeDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="uploadKnowledge">上传并索引</el-button></template>
    </el-dialog>
    <el-dialog v-model="readerDialog" class="reader-dialog" width="min(900px, 96vw)" top="3vh" destroy-on-close>
      <template #header><div class="reader-header"><span class="file-type large">{{ selectedKnowledge?.fileType?.toUpperCase() }}</span><div><h3>{{ selectedKnowledge?.title }}</h3><p>资源 #{{ selectedKnowledge?.id }} · {{ reviewingKnowledge ? auditLabel(selectedKnowledge?.auditStatus || '') : `浏览 ${selectedKnowledge?.views || 0} 次` }}</p></div></div></template>
      <iframe v-if="selectedKnowledge?.fileType === 'pdf' && pdfPreviewUrl" class="knowledge-pdf-preview" :src="pdfPreviewUrl" title="PDF 预览" />
      <article v-else class="knowledge-body rich-knowledge-body">
        <template v-for="(block, index) in knowledgeContentBlocks" :key="`${block.type}-${index}`">
          <img v-if="block.type === 'image'" class="knowledge-inline-image" :src="resolveApiUrl(block.url || '')" :alt="block.text || '知识插图'" />
          <h3 v-else-if="block.type === 'heading'">{{ block.text }}</h3>
          <p v-else :class="{ 'knowledge-list-item': block.type === 'list' }">{{ block.text }}</p>
        </template>
        <el-empty v-if="!knowledgeContentBlocks.length" description="暂无可预览正文" />
      </article>
      <template #footer><el-button @click="readerDialog = false">关闭</el-button><template v-if="reviewingKnowledge && selectedKnowledge"><el-button type="danger" @click="auditKnowledge(selectedKnowledge, 'REJECTED')">驳回</el-button><el-button type="success" @click="auditKnowledge(selectedKnowledge, 'APPROVED')">通过审核</el-button></template><el-button v-else-if="selectedKnowledge?.fileUrl" type="primary" :icon="Download" @click="selectedKnowledge && downloadKnowledge(selectedKnowledge)">下载资料</el-button></template>
    </el-dialog>
    <el-dialog v-model="postReviewDialog" width="min(760px, 94vw)" top="5vh" destroy-on-close><template #header><div class="reader-header"><span class="file-type large">POST</span><div><h3>{{ selectedReviewPost?.title }}</h3><p>帖子 #{{ selectedReviewPost?.id }} · 用户 #{{ selectedReviewPost?.userId }} · {{ selectedReviewPost?.status }}</p></div></div></template><article class="knowledge-body">{{ selectedReviewPost?.content }}</article><div v-if="selectedReviewPost?.imageUrls?.length" class="post-images"><img v-for="image in selectedReviewPost.imageUrls" :key="image" :src="resolveApiUrl(image)" alt="待审核帖子配图" /></div><template #footer><el-button @click="postReviewDialog = false">关闭</el-button><el-button v-if="selectedReviewPost" type="danger" @click="auditPost(selectedReviewPost, 'HIDDEN')">隐藏</el-button><el-button v-if="selectedReviewPost" type="success" @click="auditPost(selectedReviewPost, 'PUBLISHED')">发布</el-button></template></el-dialog>
    <el-dialog v-model="postDialog" title="发布社区帖子" width="min(560px, 92vw)" destroy-on-close><el-form label-position="top"><el-form-item label="标题"><el-input v-model="postForm.title" /></el-form-item><el-form-item label="正文"><el-input v-model="postForm.content" type="textarea" :rows="6" /></el-form-item><el-form-item label="帖子配图"><el-upload list-type="picture-card" :auto-upload="false" :limit="9" accept="image/jpeg,image/png,image/gif,image/webp" :on-change="handlePostImages" :on-remove="handlePostImages"><Plus /></el-upload><div class="el-upload__tip">最多 9 张，支持 JPEG、PNG、GIF、WebP，单张不超过 10 MB</div></el-form-item></el-form><template #footer><el-button @click="saveDraft">存为草稿</el-button><el-button type="primary" :loading="busy" @click="createPost">发布</el-button></template></el-dialog>
    <el-drawer v-model="commentDrawer" title="帖子讨论" size="min(460px, 92vw)"><div class="comment-post"><strong>{{ selectedPost?.title }}</strong><p>{{ selectedPost?.content }}</p></div><div class="comment-list"><article v-for="comment in comments" :key="comment.id"><div class="mini-avatar"><img v-if="communityUser(comment.userId).avatarUrl" :src="resolveApiUrl(communityUser(comment.userId).avatarUrl || '')" alt="头像" /><span v-else>{{ communityUser(comment.userId).nickname.slice(0, 1) }}</span></div><div><strong>{{ communityUser(comment.userId).nickname }}</strong><p>{{ comment.content }}</p></div></article><el-empty v-if="!comments.length" description="暂无评论" /></div><div class="drawer-compose"><el-input v-model="commentText" placeholder="发表公开评论" /><el-button type="primary" @click="createComment">发送</el-button></div></el-drawer>
    <el-dialog v-model="feedbackDialog" title="提交反馈" width="min(480px, 92vw)"><el-form label-position="top"><el-form-item label="反馈类型"><el-select v-model="feedbackForm.type"><el-option label="系统问题" value="BUG" /><el-option label="产品建议" value="SUGGESTION" /><el-option label="客服咨询" value="SUPPORT" /></el-select></el-form-item><el-form-item label="问题描述"><el-input v-model="feedbackForm.content" type="textarea" :rows="5" /></el-form-item></el-form><template #footer><el-button @click="feedbackDialog = false">取消</el-button><el-button type="primary" @click="createTicket">提交</el-button></template></el-dialog>
    <el-dialog v-model="conversationDialog" title="发起私信" width="min(460px, 92vw)"><el-form label-position="top"><el-form-item label="选择联系人"><el-select v-model="conversationTargetId" filterable placeholder="输入昵称或用户名"><el-option v-for="user in directoryUsers" :key="user.id" :label="`${user.nickname} (@${user.username})`" :value="user.id" /></el-select></el-form-item></el-form><template #footer><el-button @click="conversationDialog = false">取消</el-button><el-button type="primary" :disabled="!conversationTargetId" @click="createConversation">开始聊天</el-button></template></el-dialog>
    <el-dialog v-model="ticketDialog" title="处理反馈工单" width="min(500px, 92vw)"><el-form label-position="top"><el-form-item label="处理状态"><el-select v-model="ticketReply.status"><el-option label="处理中" value="PROCESSING" /><el-option label="已解决" value="RESOLVED" /></el-select></el-form-item><el-form-item label="官方回复"><el-input v-model="ticketReply.reply" type="textarea" :rows="5" /></el-form-item></el-form><template #footer><el-button @click="ticketDialog = false">取消</el-button><el-button type="primary" @click="replyTicket">保存回复</el-button></template></el-dialog>
    <el-dialog v-model="faqDialog" :title="faqForm.id ? '编辑 FAQ' : '新增 FAQ'" width="min(500px, 92vw)"><el-form label-position="top"><el-form-item label="问题"><el-input v-model="faqForm.question" /></el-form-item><el-form-item label="答案"><el-input v-model="faqForm.answer" type="textarea" :rows="5" /></el-form-item><el-form-item label="排序"><el-input-number v-model="faqForm.sortNo" :min="0" /></el-form-item></el-form><template #footer><el-button @click="faqDialog = false">取消</el-button><el-button type="primary" @click="saveFaq">保存</el-button></template></el-dialog>
    <el-dialog v-model="governanceDialog" class="governance-dialog" title="深度内容审查" width="min(1000px, 94vw)" top="5vh">
      <el-tabs v-model="governanceTab">
        <el-tab-pane label="私信监管" name="messages"><el-table :data="adminChatSessions" max-height="520" @row-click="loadAdminChatMessages"><el-table-column prop="id" label="会话" width="80" /><el-table-column prop="userAId" label="用户 A" width="100" /><el-table-column prop="userBId" label="用户 B" width="100" /><el-table-column prop="messageCount" label="消息数" width="100" /><el-table-column prop="updatedAt" label="更新时间" min-width="180" /></el-table><div v-if="adminChatMessages.length" class="admin-message-preview"><article v-for="message in adminChatMessages" :key="message.id"><strong>用户 {{ message.senderId }}</strong><span>{{ message.content }}</span><small>{{ formatDate(message.createdAt) }}</small></article></div><el-empty v-else description="选择会话查看消息" /></el-tab-pane>
        <el-tab-pane :label="`评论 ${adminComments.length}`" name="comments"><el-table :data="adminComments" max-height="520"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="postId" label="帖子" width="90" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column prop="content" label="评论内容" min-width="280" /><el-table-column label="操作" width="100"><template #default="scope"><el-button text type="danger" @click="deleteAdminComment(scope.row)">删除</el-button></template></el-table-column></el-table></el-tab-pane>
        <el-tab-pane :label="`草稿 ${adminDrafts.length}`" name="drafts"><el-table :data="adminDrafts" max-height="520"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column prop="title" label="标题" min-width="200" /><el-table-column prop="content" label="内容" min-width="280" /><el-table-column label="操作" width="100"><template #default="scope"><el-button text type="danger" @click="deleteAdminDraft(scope.row)">删除</el-button></template></el-table-column></el-table></el-tab-pane>
        <el-tab-pane :label="`AI 会话 ${aiAuditSessions.length}`" name="sessions"><el-table :data="aiAuditSessions" max-height="520"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="user_id" label="用户" width="100" /><el-table-column prop="title" label="会话标题" min-width="280" /><el-table-column prop="created_at" label="创建时间" min-width="180" /></el-table></el-tab-pane>
        <el-tab-pane :label="`知识切片 ${aiChunks.length}`" name="chunks"><el-table :data="aiChunks" max-height="520"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="file_id" label="文件" width="90" /><el-table-column prop="title" label="标题" min-width="180" /><el-table-column prop="content" label="切片内容" min-width="360" show-overflow-tooltip /></el-table></el-tab-pane>
      </el-tabs>
      <template #footer><el-button :icon="Refresh" @click="loadGovernance">刷新数据</el-button><el-button type="primary" @click="governanceDialog = false">完成</el-button></template>
    </el-dialog>
    <el-dialog v-model="notificationsDialog" title="通知中心" width="min(620px, 92vw)"><div class="notification-list"><article v-for="notice in notifications" :key="notice.id"><span class="notification-symbol"><Bell /></span><div><strong>{{ notice.title }}</strong><p>{{ notice.content }}</p><small>{{ notice.type || 'SYSTEM' }} · {{ formatDate(notice.createdAt || '') }}</small></div><el-button v-if="!notice.read" text type="primary" @click="markNotificationRead(notice)">标为已读</el-button><el-tag v-else type="info" size="small">已读</el-tag></article><el-empty v-if="!notifications.length" description="暂无通知" /></div><template #footer><el-button :icon="Refresh" @click="openNotifications">刷新</el-button><el-button :disabled="!notifications.some(notice => !notice.read)" @click="markAllNotificationsRead">全部已读</el-button><el-button type="primary" @click="notificationsDialog = false">关闭</el-button></template></el-dialog>
    <el-dialog v-model="categoryDialog" title="知识分类管理" width="min(520px, 92vw)"><el-form inline @submit.prevent="saveCategory"><el-form-item><el-input v-model="categoryForm.name" placeholder="分类名称" /></el-form-item><el-form-item><el-input-number v-model="categoryForm.sortNo" :min="0" /></el-form-item><el-button type="primary" @click="saveCategory">{{ categoryForm.id ? '保存修改' : '新增分类' }}</el-button></el-form><div class="category-admin-list"><article v-for="category in knowledgeCategories" :key="category.id"><span>{{ category.name }}</span><small>排序 {{ category.sortNo || 0 }}</small><div><el-button text @click="editCategory(category)">编辑</el-button><el-button text type="danger" @click="removeCategory(category)">删除</el-button></div></article></div><template #footer><el-button @click="categoryDialog=false">关闭</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue';
import type { UploadFile, UploadRawFile } from 'element-plus';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { ArrowRight, Bell, ChatDotRound, ChatLineRound, CollectionTag, Delete, Document, Download, Edit, EditPen, Files, House, Loading, MagicStick, Menu, Message, MoreFilled, Plus, Promotion, Reading, Refresh, Search, Setting, Star, SwitchButton, Tickets, Upload, UploadFilled, User, UserFilled, View, Warning } from '@element-plus/icons-vue';
import { deleteData, downloadData, getAuthToken, getData, postData, postFormData, putData, resolveApiUrl, setAuthToken } from './api/client';

type KnowledgeFile = { id:number; userId:number; categoryId?:number; title:string; fileType:string; auditStatus:string; fileUrl?:string; content?:string; imageUrls?:string[]; coverUrl?:string; views?:number; downloads?:number; likes?:number };
type KnowledgeContentBlock = { type:'image'|'heading'|'list'|'paragraph'; text?:string; url?:string };
type Post = { id:number; userId:number; title:string; content:string; status:string; imageUrls?:string[]; likes?:number };
type Ticket = { id:number; userId:number; type:string; content:string; status:string; reply?:string };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string; signature?:string; status:string; role:string };
type ChatMessage = { id:number; sessionId:number; senderId:number; content:string; status:string; createdAt:string };
type ChatSession = { id:number; userAId:number; userBId:number; otherUserId:number; status:string; updatedAt:string; lastMessage:string };
type Notice = { id:number; type?:string; title:string; content:string; read:boolean; createdAt?:string };
type Draft = { id:number; title:string; content:string; userId:number };
type Report = { id:number; fileId?:number; targetUserId?:number; reason:string; status:string };
type Faq = { id:number; question:string; answer:string; sortNo:number };
type EventRecord = { id:number; type:string; aggregateId:string; status:string; createdAt:string };
type AiSession = { id:number; user_id?:number; title:string; created_at:string };
type Comment = { id:number; postId?:number; userId:number; content:string };
type BehaviorRecord = { id:number; action:string; targetType:string; targetId:number; createdAt:string };
type AiChunk = { id:number; file_id:number; title:string; content:string; created_at:string };
type AiReference = { id:number; file_id:number; title:string; content:string; score?:number };
type AiMessageView = { role:'user'|'assistant'; content:string; references?:AiReference[] };
type NavigationItem = { key:string; label:string; icon:ReturnType<typeof markRaw>; badge?:number };

const authenticated = ref(Boolean(getAuthToken()));
const busy = ref(false); const aiBusy = ref(false); const mobileMenuOpen = ref(false); const sidebarCollapsed = ref(false);
const username = ref(localStorage.getItem('ai-knowledge-username') || '');
const displayName = ref(localStorage.getItem('ai-knowledge-name') || username.value || '用户');
const avatarUrl = ref(localStorage.getItem('ai-knowledge-avatar') || '');
const role = ref(localStorage.getItem('ai-knowledge-role') || 'USER');
const currentUserId = ref(Number(localStorage.getItem('ai-knowledge-user-id') || 1));
const portal = ref<'client'|'admin'>(role.value === 'ADMIN' ? 'admin' : 'client');
const activeView = ref(portal.value === 'admin' ? 'dashboard' : 'home');
const globalSearch = ref(''); const knowledgeKeyword = ref(''); const knowledgeType = ref(''); const knowledgeCategoryId = ref(0); const adminUserKeyword = ref('');
const viewLoading = ref(false); const viewError = ref('');
const feedMode = ref<'all'|'following'>('all'); const moderationTab = ref('knowledge'); const governanceTab = ref('comments');
const knowledgeDialog = ref(false); const readerDialog = ref(false); const postDialog = ref(false); const postReviewDialog = ref(false); const feedbackDialog = ref(false); const ticketDialog = ref(false); const faqDialog = ref(false); const categoryDialog = ref(false); const commentDrawer = ref(false); const registerDialog = ref(false); const governanceDialog = ref(false); const notificationsDialog = ref(false); const conversationDialog = ref(false);

const loginForm = ref({ username:'demo', password:'demo' });
const registerForm = ref({ username:'', nickname:'', password:'' });
const profileForm = ref({ userId:currentUserId.value, nickname:displayName.value, avatarUrl:'', signature:'' });
const passwordForm = ref({ currentPassword:'', newPassword:'' });
const knowledgeForm = ref({ userId:currentUserId.value, title:'', filename:'knowledge.txt', fileType:'txt', content:'', fileUrl:'', categoryId:0 });
type KnowledgeCategory = { id:number; name:string; parentId?:number; sortNo?:number };
const selectedKnowledgeFile = ref<File>();
const postForm = ref({ userId:currentUserId.value, title:'', content:'' });
const selectedPostImages = ref<UploadRawFile[]>([]);
const messageForm = ref({ sessionId:0, senderId:currentUserId.value, content:'' });
const conversationTargetId = ref(0);
const feedbackForm = ref({ userId:currentUserId.value, type:'BUG', content:'' });
const ticketReply = ref({ ticketId:0, status:'PROCESSING', reply:'' });
const faqForm = ref({ id:0, question:'', answer:'', sortNo:10, enabled:1 });
const aiConfig = ref({ data_source_scope:'all-approved', match_limit:5, compliance_rule:'answer-with-references', provider:'local', model:'local-rag', base_url:'', request_url:'', temperature:0.2, max_upload_mb:25, notifications_enabled:true, community_enabled:true });
const platformConfig = ref({ max_upload_mb:25, notifications_enabled:true, community_enabled:true });
const relationForm = ref({ targetUserId:2 });
const categoryForm = ref({ id:0, name:'', sortNo:10 });

const knowledgeFiles = ref<KnowledgeFile[]>([]); const feedPosts = ref<Post[]>([]); const tickets = ref<Ticket[]>([]); const adminTickets = ref<Ticket[]>([]);
const messages = ref<ChatMessage[]>([]); const notifications = ref<Notice[]>([]); const drafts = ref<Draft[]>([]); const sessions = ref<ChatSession[]>([]); const adminChatSessions = ref<ChatSession[]>([]); const adminChatMessages = ref<ChatMessage[]>([]);
const adminUsers = ref<UserRecord[]>([]); const knowledgeReports = ref<Report[]>([]); const userReports = ref<Report[]>([]); const faqs = ref<Faq[]>([]); const adminEvents = ref<EventRecord[]>([]);
const blockedUserIds = ref<number[]>([]); const behaviors = ref<BehaviorRecord[]>([]); const adminComments = ref<Comment[]>([]); const adminDrafts = ref<Draft[]>([]); const aiChunks = ref<AiChunk[]>([]); const aiAuditSessions = ref<AiSession[]>([]);
const myKnowledge = ref<KnowledgeFile[]>([]); const knowledgeActivityType = ref('UPLOADED'); const knowledgeCategories = ref<KnowledgeCategory[]>([]);
const adminOverview = ref<Record<string, Record<string, unknown>>>({}); const aiOverview = ref<Record<string, unknown>>({});
const followData = ref<{ followedUserIds?:number[]; followerUserIds?:number[] }>({});
const directoryUsers = ref<UserRecord[]>([]); const systemHealth = ref<Record<string,boolean>>({});
const aiQuestion = ref(''); const aiMessages = ref<AiMessageView[]>([]); const aiSessions = ref<AiSession[]>([]); const aiSessionId = ref<number>();
const selectedPost = ref<Post>(); const comments = ref<Comment[]>([]); const commentText = ref('');
const selectedKnowledge = ref<KnowledgeFile>();
const pdfPreviewUrl = ref('');
const selectedReviewPost = ref<Post>(); const reviewingKnowledge = ref(false);
const aiPrompts = ['平台支持哪些知识格式？','如何使用全文搜索？','社区有哪些核心功能？'];

const clientNavigation: NavigationItem[] = [{key:'home',label:'工作台',icon:markRaw(House)},{key:'knowledge',label:'知识库',icon:markRaw(Files)},{key:'community',label:'社区广场',icon:markRaw(ChatDotRound)},{key:'messages',label:'消息中心',icon:markRaw(Message)},{key:'notifications',label:'通知中心',icon:markRaw(Bell),badge:0},{key:'ai',label:'AI 问答',icon:markRaw(MagicStick)},{key:'profile',label:'个人中心',icon:markRaw(User)}];
const adminNavigation: NavigationItem[] = [{key:'dashboard',label:'运营概览',icon:markRaw(House)},{key:'moderation',label:'内容审核',icon:markRaw(CollectionTag)},{key:'governance',label:'深度审查',icon:markRaw(View)},{key:'users',label:'用户管理',icon:markRaw(UserFilled)},{key:'tickets',label:'工单与 FAQ',icon:markRaw(Tickets)},{key:'system',label:'AI 与系统',icon:markRaw(Setting)}];
const currentNavigation = computed(() => portal.value === 'admin' ? adminNavigation : clientNavigation
  .filter(item => platformConfig.value.community_enabled || item.key !== 'community')
  .filter(item => platformConfig.value.notifications_enabled || item.key !== 'notifications')
  .map(item => item.key === 'notifications' ? {...item,badge:notifications.value.filter(notice=>!notice.read).length || 0} : item));
const currentTitle = computed(() => currentNavigation.value.find(item => item.key === activeView.value)?.label || '工作台');
const greeting = computed(() => { const hour = new Date().getHours(); return hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'; });
const filteredKnowledge = computed(() => knowledgeFiles.value.filter(file => (!knowledgeType.value || file.fileType === knowledgeType.value) && (!knowledgeCategoryId.value || file.categoryId === knowledgeCategoryId.value) && (!knowledgeKeyword.value || file.title.toLowerCase().includes(knowledgeKeyword.value.toLowerCase()))));
const filteredAdminUsers = computed(() => adminUsers.value.filter(user => `${user.username}${user.nickname}`.toLowerCase().includes(adminUserKeyword.value.toLowerCase())));
const adminMetrics = computed(() => [{label:'注册用户',value:metricValue('userAdmin','totalUsers'),hint:`${metricValue('userAdmin','activeUsers')} 个正常账号`,color:'green',icon:markRaw(UserFilled)},{label:'知识资源',value:metricValue('knowledgeAdmin','totalFiles'),hint:`${metricValue('knowledgeAdmin','pendingAudit')} 个待审核`,color:'blue',icon:markRaw(Files)},{label:'社区帖子',value:metricValue('forumAdmin','publishedPosts'),hint:'全站内容产出',color:'amber',icon:markRaw(ChatDotRound)},{label:'反馈工单',value:metricValue('feedbackAdmin','tickets'),hint:`${metricValue('feedbackAdmin','pendingTickets')} 个待处理`,color:'red',icon:markRaw(Tickets)}]);
const healthItems = computed(() => [{label:'网关与用户服务',ok:systemHealth.value.user},{label:'知识与全文检索',ok:systemHealth.value.knowledge},{label:'社区与消息服务',ok:systemHealth.value.community&&systemHealth.value.message},{label:'AI 向量检索',ok:systemHealth.value.ai}]);
const currentMessageSession = computed(() => sessions.value.find(session => session.id === messageForm.value.sessionId));
const currentMessagePartner = computed(() => currentMessageSession.value ? sessionPartner(currentMessageSession.value) : undefined);
const knowledgeContentBlocks = computed<KnowledgeContentBlock[]>(() => {
  const content = selectedKnowledge.value?.content || '';
  if (!content.trim()) return [];
  const blocks: KnowledgeContentBlock[] = [];
  let paragraph: string[] = [];
  const flush = () => { const text = paragraph.join(' ').trim(); if (text) blocks.push({ type: 'paragraph', text }); paragraph = []; };
  for (const line of content.replace(/\r/g, '').split('\n')) {
    const image = line.trim().match(/^!\[([^\]]*)\]\(((?:\/knowledge\/media\/[A-Za-z0-9_-]+)|(?:https?:\/\/[^\s)]+))\)$/i);
    if (image) { flush(); blocks.push({ type: 'image', text: image[1], url: image[2] }); continue; }
    if (!line.trim()) { flush(); continue; }
    const heading = line.trim().match(/^#{1,3}\s+(.+)$/);
    if (heading) { flush(); blocks.push({ type: 'heading', text: heading[1] }); continue; }
    if (/^\s*[-*]\s+/.test(line)) { flush(); blocks.push({ type: 'list', text: line.trim().replace(/^[-*]\s+/, '') }); continue; }
    paragraph.push(line.trim());
  }
  flush();
  return blocks;
});

function metricValue(section:string,key:string){ const value=adminOverview.value[section]?.[key]; return typeof value==='number'?value:0; }
function categoryCount(categoryId:number){ return knowledgeFiles.value.filter(file=>file.categoryId===categoryId).length; }
function auditLabel(status:string){ return ({APPROVED:'已通过',PENDING:'待审核',REJECTED:'已驳回'} as Record<string,string>)[status] || status; }
function ticketStatusLabel(status:string){ return ({PENDING:'待处理',PROCESSING:'处理中',RESOLVED:'已解决'} as Record<string,string>)[status] || status; }
function behaviorActionLabel(action:string){return ({VIEW:'浏览',LIKE:'点赞',COLLECT:'收藏',COMMENT:'评论',UPLOAD:'上传'} as Record<string,string>)[action]||action;}
function behaviorTargetLabel(target:string){return ({KNOWLEDGE:'知识',POST:'帖子',COMMENT:'评论',USER:'用户'} as Record<string,string>)[target]||target;}
function formatDate(value:string){ return value ? new Date(value).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) : ''; }
function notifyError(error:unknown){ ElMessage.error(error instanceof Error?error.message:String(error)); }
function useAccount(user:string,password:string){ loginForm.value={username:user,password}; }

async function registerAccount(){
  if(!registerForm.value.username.trim() || registerForm.value.password.length < 6){ ElMessage.warning('请输入用户名，密码至少 6 位'); return; }
  busy.value=true;
  try{
    await postData('/user/register',registerForm.value);
    loginForm.value={username:registerForm.value.username,password:registerForm.value.password};
    registerDialog.value=false;
    await login();
  }catch(error){ notifyError(error); }
  finally{ busy.value=false; }
}

async function login(){ busy.value=true; try { const result=await postData<{token:string;role:string;user:UserRecord}>('/user/login',loginForm.value); setAuthToken(result.token); username.value=result.user.username; displayName.value=result.user.nickname; avatarUrl.value=result.user.avatarUrl||''; role.value=result.role; currentUserId.value=result.user.id; localStorage.setItem('ai-knowledge-username',username.value); localStorage.setItem('ai-knowledge-name',displayName.value); localStorage.setItem('ai-knowledge-avatar',avatarUrl.value); localStorage.setItem('ai-knowledge-role',role.value); localStorage.setItem('ai-knowledge-user-id',String(currentUserId.value)); authenticated.value=true; portal.value=result.role==='ADMIN'?'admin':'client'; activeView.value=result.role==='ADMIN'?'dashboard':'home'; syncUserForms(); await loadPublicConfig(); await refreshCurrentView(); ElMessage.success('登录成功'); } catch(error){ notifyError(error); } finally{busy.value=false;} }
function logout(){ localStorage.removeItem('ai-knowledge-local-token'); ['ai-knowledge-username','ai-knowledge-name','ai-knowledge-role','ai-knowledge-user-id'].forEach(key=>localStorage.removeItem(key)); authenticated.value=false; }
async function restoreSession(){try{const session=await getData<{userId:number;username:string;role:string}>('/user/session');currentUserId.value=session.userId;username.value=session.username;role.value=session.role;localStorage.setItem('ai-knowledge-user-id',String(session.userId));syncUserForms();await loadPublicConfig();await refreshCurrentView();}catch{logout();}}
async function loadPublicConfig(){try{platformConfig.value=await getData('/ai/config/public');}catch{platformConfig.value={max_upload_mb:25,notifications_enabled:true,community_enabled:true};}}
function syncUserForms(){ profileForm.value.userId=currentUserId.value; knowledgeForm.value.userId=currentUserId.value; postForm.value.userId=currentUserId.value; messageForm.value.senderId=currentUserId.value; feedbackForm.value.userId=currentUserId.value; }
function switchPortal(value:'client'|'admin'){ portal.value=value; activeView.value=value==='admin'?'dashboard':'home'; mobileMenuOpen.value=false; refreshCurrentView(); }
function selectView(key:string){ if(key==='governance'){governanceDialog.value=true;mobileMenuOpen.value=false;loadGovernance();return;} if(key==='notifications'){mobileMenuOpen.value=false;openNotifications();return;} activeView.value=key; mobileMenuOpen.value=false; refreshCurrentView(); }
async function refreshCurrentView(){ viewLoading.value=true; viewError.value=''; try { if(portal.value==='admin'){ if(activeView.value==='dashboard') await loadAdminDashboard(); else if(activeView.value==='moderation') await loadModeration(); else if(activeView.value==='users') adminUsers.value=await getData('/user/admin/users'); else if(activeView.value==='tickets') await loadTicketsAdmin(); else await loadSystemAdmin(); } else { if(['home','knowledge'].includes(activeView.value)) await loadKnowledge(); if(['home','community'].includes(activeView.value)) await loadFeed(feedMode.value); if(['home','messages'].includes(activeView.value)) await loadMessageData(); if(activeView.value==='ai') await loadAiHistory(); if(activeView.value==='profile') await loadProfile(); if(activeView.value==='home') await loadFeedback(); } } catch(error){ viewError.value=error instanceof Error?error.message:'页面加载失败，请重试'; notifyError(error); } finally { viewLoading.value=false; } }
async function runGlobalSearch(){ activeView.value='knowledge'; knowledgeKeyword.value=globalSearch.value; await searchKnowledge(); }

async function loadKnowledge(){ const [files,categories]=await Promise.all([getData<KnowledgeFile[]>('/knowledge/list'),getData<KnowledgeCategory[]>('/knowledge/categories')]); knowledgeFiles.value=files; knowledgeCategories.value=categories; }
async function searchKnowledge(){ const results=knowledgeKeyword.value?await getData<KnowledgeFile[]>(`/knowledge/search/fulltext?keyword=${encodeURIComponent(knowledgeKeyword.value)}`):await getData<KnowledgeFile[]>('/knowledge/list'); knowledgeFiles.value=results; }
function handleKnowledgeFile(file:UploadFile){if(file.size&&file.size>platformConfig.value.max_upload_mb*1024*1024){selectedKnowledgeFile.value=undefined;ElMessage.error(`文件不能超过 ${platformConfig.value.max_upload_mb} MB`);return;}selectedKnowledgeFile.value=file.raw;if(file.raw&&!knowledgeForm.value.title)knowledgeForm.value.title=file.name.replace(/\.[^.]+$/,'');}
function clearKnowledgeFile(){selectedKnowledgeFile.value=undefined;}
async function uploadKnowledge(){if(!selectedKnowledgeFile.value&&!knowledgeForm.value.content.trim()){ElMessage.warning('请选择文件或填写文本正文');return;}busy.value=true;try{if(selectedKnowledgeFile.value){const form=new FormData();form.append('file',selectedKnowledgeFile.value);form.append('title',knowledgeForm.value.title);if(knowledgeForm.value.categoryId)form.append('categoryId',String(knowledgeForm.value.categoryId));await postFormData('/knowledge/file/upload',form);}else{const stored=await postData<{fileUrl:string}>('/knowledge/storage/upload',{filename:knowledgeForm.value.filename,content:knowledgeForm.value.content,fileType:knowledgeForm.value.fileType,title:knowledgeForm.value.title});await postData('/knowledge/upload',{title:knowledgeForm.value.title||knowledgeForm.value.filename,fileType:knowledgeForm.value.fileType,fileUrl:stored.fileUrl,content:knowledgeForm.value.content,categoryId:knowledgeForm.value.categoryId||null});}knowledgeDialog.value=false;selectedKnowledgeFile.value=undefined;knowledgeForm.value.title='';knowledgeForm.value.content='';knowledgeForm.value.categoryId=0;await loadKnowledge();ElMessage.success('资料已保存并完成正文索引，等待管理员审核');}catch(error){notifyError(error);}finally{busy.value=false;}}
function openKnowledge(file:KnowledgeFile){ activeView.value='knowledge'; viewKnowledge(file); }
async function prepareKnowledgePreview(file:KnowledgeFile){if(pdfPreviewUrl.value){URL.revokeObjectURL(pdfPreviewUrl.value);pdfPreviewUrl.value='';}if(file.fileType?.toLowerCase()==='pdf'&&file.fileUrl){const blob=await downloadData(`/knowledge/file/${file.id}`);pdfPreviewUrl.value=URL.createObjectURL(blob);}}
async function viewKnowledge(file:KnowledgeFile){ reviewingKnowledge.value=false;const detail=await postData<KnowledgeFile>('/knowledge/view',{fileId:file.id});selectedKnowledge.value=detail;await prepareKnowledgePreview(detail);readerDialog.value=true;await recordBehavior('VIEW','KNOWLEDGE',file.id);await loadKnowledge(); }
async function reviewKnowledge(file:KnowledgeFile){const detail=await getData<KnowledgeFile>(`/knowledge/admin/preview?fileId=${file.id}`);selectedKnowledge.value=detail;await prepareKnowledgePreview(detail);reviewingKnowledge.value=true;readerDialog.value=true;}
function reviewPost(post:Post){selectedReviewPost.value=post;postReviewDialog.value=true;}
async function downloadKnowledge(file:KnowledgeFile){try{const blob=await downloadData(`/knowledge/file/${file.id}`);const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download=`${file.title}.${file.fileType||'bin'}`;anchor.click();URL.revokeObjectURL(url);await recordBehavior('DOWNLOAD','KNOWLEDGE',file.id);await loadKnowledge();}catch(error){notifyError(error);}}
async function likeKnowledge(file:KnowledgeFile){ await postData('/knowledge/like',{userId:currentUserId.value,fileId:file.id}); await recordBehavior('LIKE','KNOWLEDGE',file.id); ElMessage.success('已点赞'); await loadKnowledge(); }
async function collectKnowledge(file:KnowledgeFile){ await postData('/knowledge/collect',{userId:currentUserId.value,fileId:file.id}); ElMessage.success('已收藏'); }
async function forwardKnowledge(file:KnowledgeFile){await postData('/knowledge/forward',{fileId:file.id});ElMessage.success('已记录转发');await loadMyKnowledge();}
async function reportKnowledge(file:KnowledgeFile){ const {value}=await ElMessageBox.prompt('请填写举报原因','举报知识资源',{inputValue:'内容不准确'}); await postData('/knowledge/report',{userId:currentUserId.value,fileId:file.id,reason:value}); ElMessage.success('举报已提交'); }

async function loadFeed(mode:'all'|'following'=feedMode.value){ feedMode.value=mode; const directoryPromise=getData<UserRecord[]>('/user/directory'); if(mode==='following'){const relations=await getData<{followedUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`); feedPosts.value=await getData(`/square/following-feed?followedUserIds=${relations.followedUserIds.join(',')}`);}else feedPosts.value=await getData('/square/feed');directoryUsers.value=(await directoryPromise).filter(user=>user.id!==currentUserId.value); }
function communityUser(userId:number):UserRecord{return directoryUsers.value.find(user=>user.id===userId)||(userId===currentUserId.value?{id:userId,username:username.value,nickname:displayName.value,avatarUrl:avatarUrl.value,status:'ACTIVE',role:role.value}:{id:userId,username:`user${userId}`,nickname:`用户 ${userId}`,status:'ACTIVE',role:'USER'});}
function handlePostImages(_file:UploadFile, files:UploadFile[]){selectedPostImages.value=files.map(file=>file.raw).filter((file):file is UploadRawFile=>Boolean(file));}
async function createPost(){ if(!postForm.value.title.trim()||!postForm.value.content.trim()){ElMessage.warning('请填写标题和正文');return;} busy.value=true;try{let imageUrls:string[]=[];if(selectedPostImages.value.length){const form=new FormData();selectedPostImages.value.forEach(file=>form.append('files',file));const uploaded=await postFormData<{imageUrls:string[]}>('/post/media/upload',form);imageUrls=uploaded.imageUrls;}await postData('/post/create',{...postForm.value,imageUrls});postDialog.value=false;postForm.value.title='';postForm.value.content='';selectedPostImages.value=[];await loadFeed();ElMessage.success('帖子已发布');}catch(error){notifyError(error);}finally{busy.value=false;} }
async function saveDraft(){ await postData('/post/draft',{userId:currentUserId.value,title:postForm.value.title||'未命名草稿',content:postForm.value.content});postDialog.value=false;await loadDrafts();ElMessage.success('草稿已保存'); }
async function loadDrafts(){ drafts.value=await getData(`/post/drafts?userId=${currentUserId.value}`); }
async function loadMyKnowledge(){myKnowledge.value=await getData<KnowledgeFile[]>(`/knowledge/mine?type=${knowledgeActivityType.value}`);}
async function openDrafts(){activeView.value='profile';await loadProfile();}
async function loadMyPosts(){ feedPosts.value=await getData(`/square/feed?authorUserId=${currentUserId.value}`); }
async function likePost(post:Post){ await postData('/post/like',{userId:currentUserId.value,postId:post.id});await recordBehavior('LIKE','POST',post.id);await loadFeed(); }
async function collectPost(post:Post){ await postData('/square/collect',{userId:currentUserId.value,postId:post.id});ElMessage.success('已收藏帖子'); }
async function openComments(post:Post){ selectedPost.value=post;comments.value=await getData(`/comment/list?postId=${post.id}`);commentDrawer.value=true; }
async function createComment(){ if(!commentText.value.trim()||!selectedPost.value)return;await postData('/comment/create',{postId:selectedPost.value.id,userId:currentUserId.value,content:commentText.value});commentText.value='';await openComments(selectedPost.value);ElMessage.success('评论已发布'); }

async function loadMessageData(){const [chatSessions,notices,directory]=await Promise.all([getData<ChatSession[]>(`/message/sessions?userId=${currentUserId.value}`),getData<Notice[]>(`/notification/list?userId=${currentUserId.value}`),getData<UserRecord[]>('/user/directory')]);sessions.value=chatSessions;notifications.value=notices;directoryUsers.value=directory.filter(user=>user.id!==currentUserId.value);if(sessions.value.length&&!sessions.value.some(session=>session.id===messageForm.value.sessionId))messageForm.value.sessionId=sessions.value[0].id;if(!sessions.value.length)messageForm.value.sessionId=0;await loadMessages();}
async function openNotifications(){notifications.value=await getData(`/notification/list?userId=${currentUserId.value}`);notificationsDialog.value=true;}
async function markNotificationRead(notice:Notice){await postData('/notification/read',{notificationId:notice.id});notice.read=true;ElMessage.success('已标记为已读');}
async function markAllNotificationsRead(){await postData('/notification/read-all',{});notifications.value=notifications.value.map(notice=>({...notice,read:true}));ElMessage.success('全部通知已读');}
function sessionPartner(session:ChatSession){return directoryUsers.value.find(user=>user.id===session.otherUserId)||{id:session.otherUserId,username:`user${session.otherUserId}`,nickname:`用户 ${session.otherUserId}`,status:'ACTIVE',role:'USER'};}
async function openSession(session:ChatSession){messageForm.value.sessionId=session.id;await loadMessages();}
async function loadMessages(){messages.value=messageForm.value.sessionId?await getData(`/message/list?sessionId=${messageForm.value.sessionId}&userId=${currentUserId.value}`):[];}
async function newConversation(){if(!directoryUsers.value.length)directoryUsers.value=(await getData<UserRecord[]>('/user/directory')).filter(user=>user.id!==currentUserId.value);conversationTargetId.value=directoryUsers.value[0]?.id||0;conversationDialog.value=true;}
async function createConversation(){const session=await postData<ChatSession>('/message/session',{userId:currentUserId.value,targetUserId:conversationTargetId.value});conversationDialog.value=false;await loadMessageData();messageForm.value.sessionId=session.id;await loadMessages();}
async function sendMessage(){if(!messageForm.value.content.trim())return;await postData('/message/send',messageForm.value);messageForm.value.content='';await loadMessageData();}
async function clearCurrentSession(){await ElMessageBox.confirm('确认清空当前会话记录？','清空会话',{type:'warning'});await postData('/message/clear',{sessionId:messageForm.value.sessionId,userId:currentUserId.value});await loadMessageData();}
async function deleteMessage(message:ChatMessage){await deleteData('/message',{messageId:message.id,userId:currentUserId.value});await loadMessageData();ElMessage.success('消息已删除');}

async function askAi(){if(!aiQuestion.value.trim())return;const question=aiQuestion.value;aiMessages.value.push({role:'user',content:question});aiQuestion.value='';aiBusy.value=true;try{const result=await postData<{session_id:number;answer:string;references:AiReference[]}>('/ai/chat',{question,user_id:currentUserId.value,session_id:aiSessionId.value});aiSessionId.value=result.session_id;const citations=(result.references||[]).slice(0,3).map((reference,index)=>`[${index+1}] ${reference.title}（知识 #${reference.file_id}）`).join('\n');aiMessages.value.push({role:'assistant',content:citations?`${result.answer}\n\n引用来源：\n${citations}`:result.answer,references:result.references});await loadAiHistory();}catch(error){notifyError(error);}finally{aiBusy.value=false;}}
async function loadAiHistory(){const history=await getData<{sessions:AiSession[]}> (`/ai/history?user_id=${currentUserId.value}`);aiSessions.value=history.sessions;}
async function loadAiSession(id:number){aiSessionId.value=id;const history=await getData<{messages:{role:'user'|'assistant';content:string}[]}>(`/ai/history?user_id=${currentUserId.value}&session_id=${id}`);aiMessages.value=history.messages;}

async function loadProfile(){const [user,follows,blocks,history,directory]=await Promise.all([getData<UserRecord>(`/user/info?username=${username.value}`),getData<{followedUserIds:number[];followerUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`),getData<{blockedUserIds:number[]}>(`/user/blocks?userId=${currentUserId.value}`),getData<BehaviorRecord[]>(`/user/behaviors?userId=${currentUserId.value}`),getData<UserRecord[]>('/user/directory')]);profileForm.value={userId:user.id,nickname:user.nickname,avatarUrl:user.avatarUrl||'',signature:user.signature||''};displayName.value=user.nickname;followData.value=follows;blockedUserIds.value=blocks.blockedUserIds;behaviors.value=history.slice(-30).reverse();directoryUsers.value=directory.filter(item=>item.id!==currentUserId.value);if(!directoryUsers.value.some(item=>item.id===relationForm.value.targetUserId))relationForm.value.targetUserId=directoryUsers.value[0]?.id||0;await loadDrafts();await loadFeedback();await loadMyKnowledge();}
async function saveProfile(){const user=await postData<UserRecord>('/user/profile',profileForm.value);displayName.value=user.nickname;avatarUrl.value=user.avatarUrl||'';localStorage.setItem('ai-knowledge-name',user.nickname);localStorage.setItem('ai-knowledge-avatar',avatarUrl.value);ElMessage.success('资料已保存');}
async function changePassword(){if(!passwordForm.value.currentPassword||passwordForm.value.newPassword.length<8){ElMessage.warning('请输入当前密码和至少 8 位新密码');return;}try{await postData('/user/password',passwordForm.value);passwordForm.value={currentPassword:'',newPassword:''};ElMessage.success('密码已更新');}catch(error){notifyError(error);}}
async function handleAvatarFile(file: UploadFile){const raw=file.raw as UploadRawFile|undefined;if(!raw)return;const form=new FormData();form.append('file',raw);try{const user=await postFormData<UserRecord>('/user/avatar/upload',form);profileForm.value.avatarUrl=user.avatarUrl||'';avatarUrl.value=user.avatarUrl||'';localStorage.setItem('ai-knowledge-avatar',avatarUrl.value);ElMessage.success('头像已更新');}catch(error){notifyError(error);}}
async function recordBehavior(action:string,targetType:string,targetId:number){await postData('/user/behavior',{userId:currentUserId.value,action,targetType,targetId});}
async function followUser(){await postData('/user/follow',{userId:currentUserId.value,targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已关注用户');}
async function unfollowUser(){await deleteData('/user/follow',{userId:currentUserId.value,targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已取消关注');}
async function blockUser(){await postData('/user/block',{userId:currentUserId.value,targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已加入黑名单');}
async function unblockUser(){await deleteData('/user/block',{userId:currentUserId.value,targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已解除拉黑');}
async function reportUser(){const {value}=await ElMessageBox.prompt('请填写举报原因','举报用户',{inputValue:'发布不当内容'});await postData('/user/report',{reporterId:currentUserId.value,targetUserId:relationForm.value.targetUserId,reason:value});ElMessage.success('用户举报已提交');}
async function loadFeedback(){tickets.value=await getData(`/feedback/tickets?userId=${currentUserId.value}`);}
async function createTicket(){if(!feedbackForm.value.content.trim())return;await postData('/feedback/ticket',feedbackForm.value);feedbackDialog.value=false;feedbackForm.value.content='';await loadFeedback();ElMessage.success('反馈已提交');}

async function loadAdminDashboard(){const [overview]=await Promise.all([{userAdmin:await getData<Record<string,unknown>>('/user/admin/overview'),knowledgeAdmin:await getData<Record<string,unknown>>('/knowledge/admin/overview'),forumAdmin:await getData<Record<string,unknown>>('/post/admin/overview'),messageAdmin:await getData<Record<string,unknown>>('/message/admin/overview?userId=1'),feedbackAdmin:await getData<Record<string,unknown>>('/feedback/admin/overview')}]);adminOverview.value=overview;const checks=await Promise.allSettled(['/user/health','/knowledge/health','/post/health','/message/health','/ai/health'].map(url=>getData(url)));systemHealth.value={user:checks[0].status==='fulfilled',knowledge:checks[1].status==='fulfilled',community:checks[2].status==='fulfilled',message:checks[3].status==='fulfilled',ai:checks[4].status==='fulfilled'};await loadModeration();}
async function loadModeration(){[knowledgeFiles.value,knowledgeReports.value,userReports.value,feedPosts.value]=await Promise.all([getData<KnowledgeFile[]>('/knowledge/list?includeAll=true'),getData<Report[]>('/knowledge/admin/reports'),getData<Report[]>('/user/admin/reports'),getData<Post[]>('/square/feed')]);}
async function openCategoryManager(){knowledgeCategories.value=await getData<KnowledgeCategory[]>('/knowledge/categories');categoryForm.value={id:0,name:'',sortNo:10};categoryDialog.value=true;}
function editCategory(category:KnowledgeCategory){categoryForm.value={id:category.id,name:category.name,sortNo:category.sortNo||0};}
async function saveCategory(){if(!categoryForm.value.name.trim())return;await postData('/knowledge/admin/category',{...categoryForm.value});categoryForm.value={id:0,name:'',sortNo:10};knowledgeCategories.value=await getData('/knowledge/categories');ElMessage.success('分类已保存');}
async function removeCategory(category:KnowledgeCategory){await ElMessageBox.confirm(`确认删除分类“${category.name}”？`,'删除分类',{type:'warning'});await deleteData('/knowledge/admin/category',{categoryId:category.id});knowledgeCategories.value=await getData('/knowledge/categories');}
async function auditKnowledge(file:KnowledgeFile,status:string){await postData('/knowledge/admin/audit',{fileId:file.id,auditStatus:status,reason:'管理员审核'});if(reviewingKnowledge.value&&selectedKnowledge.value?.id===file.id){readerDialog.value=false;reviewingKnowledge.value=false;}await loadModeration();ElMessage.success('审核状态已更新');}
async function auditPost(post:Post,status:string){await postData('/post/admin/audit',{postId:post.id,status,reason:'管理员审核'});if(selectedReviewPost.value?.id===post.id)postReviewDialog.value=false;await loadModeration();ElMessage.success('帖子状态已更新');}
async function resolveKnowledgeReport(report:Report){await postData('/knowledge/admin/report/resolve',{reportId:report.id,status:'RESOLVED',result:'管理员已处理'});await loadModeration();}
async function resolveUserReport(report:Report){await postData('/user/admin/report/resolve',{reportId:report.id,status:'RESOLVED',result:'管理员已处理'});await loadModeration();}
async function loadGovernance(){const [commentsResult,draftsResult,chunksResult,historyResult,chatResult]=await Promise.all([getData<Comment[]>('/comment/list'),getData<Draft[]>('/post/drafts'),getData<{chunks:AiChunk[]}>('/ai/admin/chunks'),getData<{sessions:AiSession[]}>('/ai/history'),getData<ChatSession[]>('/message/admin/sessions')]);adminComments.value=commentsResult;adminDrafts.value=draftsResult;aiChunks.value=chunksResult.chunks;aiAuditSessions.value=historyResult.sessions;adminChatSessions.value=chatResult;adminChatMessages.value=[];}
async function loadAdminChatMessages(session:ChatSession){adminChatMessages.value=await getData<ChatMessage[]>(`/message/admin/list?sessionId=${session.id}`);}
async function deleteAdminComment(comment:Comment){await ElMessageBox.confirm('确认删除这条违规评论？','删除评论',{type:'warning'});await deleteData('/comment/admin',{commentId:comment.id});await loadGovernance();ElMessage.success('评论已删除');}
async function deleteAdminDraft(draft:Draft){await ElMessageBox.confirm(`确认删除草稿“${draft.title}”？`,'删除草稿',{type:'warning'});await deleteData('/post/admin/draft',{draftId:draft.id});await loadGovernance();ElMessage.success('草稿已删除');}
async function toggleUserStatus(user:UserRecord){await postData('/user/admin/status',{userId:user.id,status:user.status==='ACTIVE'?'DISABLED':'ACTIVE'});adminUsers.value=await getData('/user/admin/users');ElMessage.success('账号状态已更新');}
async function loadTicketsAdmin(){[adminTickets.value,faqs.value]=await Promise.all([getData<Ticket[]>('/feedback/tickets'),getData<Faq[]>('/feedback/faqs')]);}
function openTicketReply(ticket:Ticket){ticketReply.value={ticketId:ticket.id,status:ticket.status==='RESOLVED'?'RESOLVED':'PROCESSING',reply:ticket.reply||''};ticketDialog.value=true;}
async function replyTicket(){await postData('/feedback/admin/reply',ticketReply.value);ticketDialog.value=false;await loadTicketsAdmin();ElMessage.success('工单已更新');}
function editFaq(faq:Faq){faqForm.value={...faq,enabled:1};faqDialog.value=true;}
async function saveFaq(){await postData('/feedback/admin/faq',{...faqForm.value,id:faqForm.value.id||undefined});faqDialog.value=false;faqForm.value={id:0,question:'',answer:'',sortNo:10,enabled:1};await loadTicketsAdmin();ElMessage.success('FAQ 已保存');}
async function deleteFaq(faq:Faq){await ElMessageBox.confirm(`确认删除“${faq.question}”？`,'删除 FAQ',{type:'warning'});await deleteData('/feedback/admin/faq',{faqId:faq.id});await loadTicketsAdmin();}
async function loadSystemAdmin(){const [overview,events]=await Promise.all([getData<Record<string,unknown>>('/ai/admin/overview'),getData<EventRecord[]>('/event/list?limit=50')]);aiOverview.value=overview;adminEvents.value=events;const config=overview.configuration as typeof aiConfig.value|undefined;if(config)aiConfig.value={...config};}
async function saveAiConfig(){await postData('/ai/admin/config',aiConfig.value);ElMessage.success('AI 配置已保存');await loadSystemAdmin();}

onMounted(async()=>{syncUserForms();if(authenticated.value)await restoreSession();});
</script>
