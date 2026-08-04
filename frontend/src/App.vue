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
      </el-form>
      <div class="demo-accounts">
        <button type="button" @click="useAccount('demo', 'demo')"><span>用户体验账号</span><strong>demo / demo</strong></button>
        <button type="button" @click="useAccount('admin', 'admin123')"><span>管理体验账号</span><strong>admin / admin123</strong></button>
      </div>
    </section>
  </div>

  <div v-else class="app-layout">
    <aside class="sidebar" :class="{ open: mobileMenuOpen }">
      <div class="sidebar-brand">
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
        <div class="user-avatar">{{ displayName.slice(0, 1).toUpperCase() }}</div>
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
          <el-input v-if="portal === 'client'" v-model="globalSearch" class="global-search" placeholder="搜索知识与内容" :prefix-icon="Search" clearable @keyup.enter="runGlobalSearch" />
          <el-button :icon="Refresh" circle title="刷新当前数据" @click="refreshCurrentView" />
        </div>
      </header>

      <main class="content-area">
        <template v-if="portal === 'client'">
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
              <div v-if="filteredKnowledge.length" class="knowledge-grid">
                <article v-for="file in filteredKnowledge" :key="file.id" class="knowledge-card">
                  <div class="knowledge-card-top"><span class="file-type large">{{ file.fileType?.toUpperCase() || 'DOC' }}</span><el-tag :type="file.auditStatus === 'APPROVED' ? 'success' : 'warning'" effect="plain">{{ auditLabel(file.auditStatus) }}</el-tag></div>
                  <h4>{{ file.title }}</h4><p>上传者 #{{ file.userId }} · 资源编号 {{ file.id }}</p>
                  <div class="card-stats"><span><View />{{ file.views || 0 }}</span><span><Download />{{ file.downloads || 0 }}</span><span><Star />{{ file.likes || 0 }}</span></div>
                  <div class="card-actions"><el-button text type="primary" @click="viewKnowledge(file)">阅读</el-button><el-button text @click="downloadKnowledge(file)">下载</el-button><el-dropdown trigger="click"><el-button text :icon="MoreFilled" /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="likeKnowledge(file)">点赞</el-dropdown-item><el-dropdown-item @click="collectKnowledge(file)">收藏</el-dropdown-item><el-dropdown-item divided @click="reportKnowledge(file)">举报</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
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
                  <div class="post-author"><div class="user-avatar">{{ post.userId }}</div><div><strong>社区用户 {{ post.userId }}</strong><span>帖子 #{{ post.id }}</span></div><el-tag v-if="post.status !== 'PUBLISHED'" type="warning">{{ post.status }}</el-tag></div>
                  <h3>{{ post.title }}</h3><p>{{ post.content }}</p>
                  <div v-if="post.imageUrls?.length" class="post-images"><img v-for="image in post.imageUrls" :key="image" :src="image" alt="帖子配图" /></div>
                  <div class="post-actions"><el-button text :icon="Star" @click="likePost(post)">{{ post.likes || 0 }} 点赞</el-button><el-button text :icon="ChatDotRound" @click="openComments(post)">评论</el-button><el-button text :icon="CollectionTag" @click="collectPost(post)">收藏</el-button></div>
                </article>
                <el-empty v-if="!feedPosts.length" description="还没有动态，发布第一条帖子吧" />
              </section>
              <aside class="surface community-side"><h3>我的创作</h3><button @click="loadDrafts"><Document />草稿箱<span>{{ drafts.length }}</span></button><button @click="loadMyPosts"><EditPen />我的帖子<ArrowRight /></button><h3>社区提示</h3><p>尊重原创，理性交流。发现不当内容可通过举报交由管理员处理。</p></aside>
            </div>
          </section>

          <section v-else-if="activeView === 'messages'" class="message-page">
            <section class="surface session-panel"><div class="surface-head"><div><h3>消息中心</h3><p>{{ notifications.length }} 条通知</p></div><el-button :icon="Plus" circle @click="newConversation" /></div><div class="session-list"><button v-for="session in sessions" :key="session" :class="{ active: messageForm.sessionId === session }" @click="openSession(session)"><div class="mini-avatar">{{ session }}</div><span><strong>会话 {{ session }}</strong><small>点击查看聊天记录</small></span></button></div><el-empty v-if="!sessions.length" description="暂无会话" /></section>
            <section class="surface conversation-panel"><div class="conversation-head"><div><strong>会话 {{ messageForm.sessionId }}</strong><span>本地私信</span></div><el-dropdown><el-button :icon="MoreFilled" circle text /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="clearCurrentSession">清空当前会话</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div><div class="message-list"><div v-for="message in messages" :key="message.id" :class="['message-bubble', message.senderId === currentUserId ? 'mine' : '']"><p>{{ message.content }}</p><small>#{{ message.id }}</small></div><el-empty v-if="!messages.length" description="开始一段新对话" /></div><div class="message-compose"><el-input v-model="messageForm.content" placeholder="输入消息" @keyup.enter="sendMessage" /><el-button type="primary" :icon="Promotion" @click="sendMessage">发送</el-button></div></section>
          </section>

          <section v-else-if="activeView === 'ai'" class="ai-page">
            <section class="surface ai-chat"><div class="ai-heading"><span class="ai-symbol"><MagicStick /></span><div><h3>知识库 AI</h3><p>回答将优先引用平台已收录的知识片段</p></div></div><div class="ai-messages"><div v-if="!aiMessages.length" class="ai-empty"><MagicStick /><h3>今天想了解什么？</h3><p>试试询问平台知识、文档内容或社区使用方式。</p><div><button v-for="prompt in aiPrompts" :key="prompt" @click="aiQuestion = prompt">{{ prompt }}</button></div></div><div v-for="(message, index) in aiMessages" :key="index" :class="['ai-message', message.role]"><span>{{ message.role === 'assistant' ? 'AI' : displayName.slice(0, 1) }}</span><p>{{ message.content }}</p></div></div><div class="ai-compose"><el-input v-model="aiQuestion" type="textarea" :rows="2" resize="none" placeholder="向知识库提问..." @keydown.ctrl.enter="askAi" /><el-button type="primary" :icon="Promotion" :loading="aiBusy" @click="askAi">发送</el-button></div></section>
            <aside class="surface ai-history"><div class="surface-head"><div><h3>对话历史</h3><p>最近的 AI 会话</p></div><el-button :icon="Refresh" circle text @click="loadAiHistory" /></div><button v-for="session in aiSessions" :key="session.id" @click="loadAiSession(session.id)"><ChatLineRound /><span><strong>{{ session.title }}</strong><small>{{ formatDate(session.created_at) }}</small></span></button><el-empty v-if="!aiSessions.length" description="暂无历史对话" /></aside>
          </section>

          <section v-else class="page-stack">
            <div class="page-toolbar"><div><h3>个人中心</h3><p>管理资料、关注关系和反馈工单</p></div><el-button type="primary" @click="saveProfile">保存资料</el-button></div>
            <div class="profile-layout"><section class="surface profile-card"><div class="profile-avatar">{{ displayName.slice(0, 1).toUpperCase() }}</div><h3>{{ displayName }}</h3><p>@{{ username }}</p><el-tag>{{ role }}</el-tag><div class="profile-counts"><span><strong>{{ followData.followedUserIds?.length || 0 }}</strong>关注</span><span><strong>{{ followData.followerUserIds?.length || 0 }}</strong>粉丝</span><span><strong>{{ drafts.length }}</strong>草稿</span></div></section><section class="surface profile-form"><h3>基础资料</h3><el-form label-position="top"><el-form-item label="昵称"><el-input v-model="profileForm.nickname" /></el-form-item><el-form-item label="头像地址"><el-input v-model="profileForm.avatarUrl" /></el-form-item><el-form-item label="个性签名"><el-input v-model="profileForm.signature" type="textarea" :rows="3" /></el-form-item></el-form></section><section class="surface feedback-card"><div class="surface-head"><div><h3>我的反馈</h3><p>问题进度与官方回复</p></div><el-button :icon="Plus" circle @click="feedbackDialog = true" /></div><article v-for="ticket in tickets" :key="ticket.id"><div><strong>{{ ticket.type }}</strong><p>{{ ticket.content }}</p><small v-if="ticket.reply">官方回复：{{ ticket.reply }}</small></div><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag></article><el-empty v-if="!tickets.length" description="暂无反馈工单" /></section></div>
          </section>
        </template>

        <template v-else>
          <section v-if="activeView === 'dashboard'" class="page-stack">
            <div class="page-toolbar"><div><h3>平台运营概览</h3><p>内容、用户、互动和待办事项的实时摘要</p></div><el-button :icon="Refresh" @click="loadAdminDashboard">刷新数据</el-button></div>
            <div class="metrics-grid admin-metrics"><div v-for="metric in adminMetrics" :key="metric.label" class="metric-tile"><span :class="['metric-icon', metric.color]"><component :is="metric.icon" /></span><div><strong>{{ metric.value }}</strong><span>{{ metric.label }}</span><small>{{ metric.hint }}</small></div></div></div>
            <div class="two-column"><section class="surface"><div class="surface-head"><div><h3>待处理事项</h3><p>优先处理积压内容</p></div></div><div class="task-list"><button @click="selectView('moderation')"><span class="task-dot amber"></span><span><strong>知识审核</strong><small>用户上传资源审核</small></span><b>{{ metricValue('knowledgeAdmin', 'pendingAudit') }}</b></button><button @click="selectView('moderation')"><span class="task-dot red"></span><span><strong>内容举报</strong><small>知识与用户举报</small></span><b>{{ knowledgeReports.length + userReports.length }}</b></button><button @click="selectView('tickets')"><span class="task-dot blue"></span><span><strong>反馈工单</strong><small>待回复用户问题</small></span><b>{{ metricValue('feedbackAdmin', 'pendingTickets') }}</b></button></div></section><section class="surface"><div class="surface-head"><div><h3>系统状态</h3><p>本地服务和数据能力</p></div><el-tag type="success">运行正常</el-tag></div><div class="status-list"><span><i></i>网关与微服务<strong>正常</strong></span><span><i></i>Nacos 服务发现<strong>正常</strong></span><span><i></i>AI 向量检索<strong>正常</strong></span><span><i></i>本地持久化<strong>正常</strong></span></div></section></div>
          </section>

          <section v-else-if="activeView === 'moderation'" class="page-stack"><div class="page-toolbar"><div><h3>内容审核</h3><p>处理知识、帖子、评论和举报</p></div><el-button :icon="Refresh" @click="loadModeration">刷新队列</el-button></div><section class="surface"><el-tabs v-model="moderationTab"><el-tab-pane label="知识资源" name="knowledge"><el-table :data="knowledgeFiles"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="fileType" label="格式" width="90" /><el-table-column prop="auditStatus" label="状态" width="110" /><el-table-column label="操作" width="170"><template #default="scope"><el-button text type="success" @click="auditKnowledge(scope.row, 'APPROVED')">通过</el-button><el-button text type="danger" @click="auditKnowledge(scope.row, 'REJECTED')">驳回</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane :label="`知识举报 ${knowledgeReports.length}`" name="reports"><el-table :data="knowledgeReports"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="fileId" label="资源" width="90" /><el-table-column prop="reason" label="举报原因" min-width="240" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="120"><template #default="scope"><el-button text type="primary" :disabled="scope.row.status === 'RESOLVED'" @click="resolveKnowledgeReport(scope.row)">结案</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane :label="`用户举报 ${userReports.length}`" name="users"><el-table :data="userReports"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="targetUserId" label="被举报用户" width="120" /><el-table-column prop="reason" label="原因" min-width="240" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="120"><template #default="scope"><el-button text type="primary" :disabled="scope.row.status === 'RESOLVED'" @click="resolveUserReport(scope.row)">结案</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane label="帖子" name="posts"><el-table :data="feedPosts"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column prop="status" label="状态" width="110" /><el-table-column label="操作" width="170"><template #default="scope"><el-button text type="success" @click="auditPost(scope.row, 'PUBLISHED')">发布</el-button><el-button text type="danger" @click="auditPost(scope.row, 'HIDDEN')">隐藏</el-button></template></el-table-column></el-table></el-tab-pane></el-tabs></section></section>

          <section v-else-if="activeView === 'users'" class="page-stack"><div class="page-toolbar"><div><h3>用户管理</h3><p>账号资料、状态和权限维护</p></div><el-input v-model="adminUserKeyword" class="table-search" placeholder="搜索用户" :prefix-icon="Search" clearable /></div><section class="surface"><el-table :data="filteredAdminUsers"><el-table-column label="用户" min-width="230"><template #default="scope"><div class="table-user"><div class="mini-avatar">{{ scope.row.nickname.slice(0, 1) }}</div><span><strong>{{ scope.row.nickname }}</strong><small>@{{ scope.row.username }}</small></span></div></template></el-table-column><el-table-column prop="role" label="角色" width="110" /><el-table-column prop="status" label="状态" width="110"><template #default="scope"><el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'danger'">{{ scope.row.status === 'ACTIVE' ? '正常' : '已禁用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="160"><template #default="scope"><el-button text :type="scope.row.status === 'ACTIVE' ? 'danger' : 'success'" @click="toggleUserStatus(scope.row)">{{ scope.row.status === 'ACTIVE' ? '禁用账号' : '恢复账号' }}</el-button></template></el-table-column></el-table></section></section>

          <section v-else-if="activeView === 'tickets'" class="page-stack"><div class="page-toolbar"><div><h3>工单与 FAQ</h3><p>跟进用户问题并维护自助答疑</p></div><el-button type="primary" :icon="Plus" @click="faqDialog = true">新增 FAQ</el-button></div><div class="two-column"><section class="surface"><div class="surface-head"><div><h3>反馈工单</h3><p>{{ adminTickets.length }} 条记录</p></div></div><article v-for="ticket in adminTickets" :key="ticket.id" class="ticket-row"><div><strong>#{{ ticket.id }} · {{ ticket.type }}</strong><p>{{ ticket.content }}</p><small v-if="ticket.reply">当前回复：{{ ticket.reply }}</small></div><div><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag><el-button text type="primary" @click="openTicketReply(ticket)">处理</el-button></div></article><el-empty v-if="!adminTickets.length" description="暂无工单" /></section><section class="surface"><div class="surface-head"><div><h3>常见问题</h3><p>用户端自助答疑内容</p></div></div><article v-for="faq in faqs" :key="faq.id" class="faq-row"><div><strong>{{ faq.question }}</strong><p>{{ faq.answer }}</p></div><div><el-button :icon="Edit" circle text @click="editFaq(faq)" /><el-button :icon="Delete" circle text type="danger" @click="deleteFaq(faq)" /></div></article><el-empty v-if="!faqs.length" description="暂无 FAQ" /></section></div></section>

          <section v-else class="page-stack"><div class="page-toolbar"><div><h3>AI 与系统配置</h3><p>控制知识检索范围、匹配规则并查看事件日志</p></div><el-button type="primary" @click="saveAiConfig">保存配置</el-button></div><div class="two-column"><section class="surface settings-form"><h3>AI 检索配置</h3><el-form label-position="top"><el-form-item label="知识数据源范围"><el-select v-model="aiConfig.data_source_scope"><el-option label="全部已审核知识" value="all-approved" /><el-option label="仅管理员指定" value="admin-selected" /></el-select></el-form-item><el-form-item label="单次匹配片段数"><el-input-number v-model="aiConfig.match_limit" :min="1" :max="20" /></el-form-item><el-form-item label="回复合规规则"><el-input v-model="aiConfig.compliance_rule" /></el-form-item></el-form><div class="config-status"><span>已索引知识片段<strong>{{ aiOverview.chunk_count || 0 }}</strong></span><span>AI 对话会话<strong>{{ aiOverview.session_count || 0 }}</strong></span></div></section><section class="surface"><div class="surface-head"><div><h3>业务事件日志</h3><p>最近 50 条本地事件</p></div><el-tag type="info">{{ adminEvents.length }}</el-tag></div><div class="event-list"><article v-for="event in adminEvents" :key="event.id"><span class="event-dot"></span><div><strong>{{ event.type }}</strong><p>对象 {{ event.aggregateId }}</p><small>{{ formatDate(event.createdAt) }}</small></div><el-tag size="small" effect="plain">{{ event.status }}</el-tag></article></div><el-empty v-if="!adminEvents.length" description="暂无事件" /></section></div></section>
        </template>
      </main>
    </section>

    <el-dialog v-model="knowledgeDialog" title="上传知识资料" width="min(520px, 92vw)"><el-form label-position="top"><el-form-item label="标题"><el-input v-model="knowledgeForm.title" /></el-form-item><div class="form-pair"><el-form-item label="文件名"><el-input v-model="knowledgeForm.filename" /></el-form-item><el-form-item label="格式"><el-select v-model="knowledgeForm.fileType"><el-option label="TXT" value="txt" /><el-option label="Markdown" value="md" /><el-option label="PDF 文本" value="pdf" /><el-option label="Word 文本" value="docx" /></el-select></el-form-item></div><el-form-item label="文件内容"><el-input v-model="knowledgeForm.content" type="textarea" :rows="7" placeholder="粘贴资料正文，内容将保存到本地知识库并建立检索索引" /></el-form-item></el-form><template #footer><el-button @click="knowledgeDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="uploadKnowledge">上传并索引</el-button></template></el-dialog>
    <el-dialog v-model="postDialog" title="发布社区帖子" width="min(560px, 92vw)"><el-form label-position="top"><el-form-item label="标题"><el-input v-model="postForm.title" /></el-form-item><el-form-item label="正文"><el-input v-model="postForm.content" type="textarea" :rows="6" /></el-form-item><el-form-item label="图片地址（每行一个，最多 9 张）"><el-input v-model="postForm.images" type="textarea" :rows="3" /></el-form-item></el-form><template #footer><el-button @click="saveDraft">存为草稿</el-button><el-button type="primary" :loading="busy" @click="createPost">发布</el-button></template></el-dialog>
    <el-drawer v-model="commentDrawer" title="帖子讨论" size="min(460px, 92vw)"><div class="comment-post"><strong>{{ selectedPost?.title }}</strong><p>{{ selectedPost?.content }}</p></div><div class="comment-list"><article v-for="comment in comments" :key="comment.id"><div class="mini-avatar">{{ comment.userId }}</div><div><strong>用户 {{ comment.userId }}</strong><p>{{ comment.content }}</p></div></article><el-empty v-if="!comments.length" description="暂无评论" /></div><div class="drawer-compose"><el-input v-model="commentText" placeholder="发表公开评论" /><el-button type="primary" @click="createComment">发送</el-button></div></el-drawer>
    <el-dialog v-model="feedbackDialog" title="提交反馈" width="min(480px, 92vw)"><el-form label-position="top"><el-form-item label="反馈类型"><el-select v-model="feedbackForm.type"><el-option label="系统问题" value="BUG" /><el-option label="产品建议" value="SUGGESTION" /><el-option label="客服咨询" value="SUPPORT" /></el-select></el-form-item><el-form-item label="问题描述"><el-input v-model="feedbackForm.content" type="textarea" :rows="5" /></el-form-item></el-form><template #footer><el-button @click="feedbackDialog = false">取消</el-button><el-button type="primary" @click="createTicket">提交</el-button></template></el-dialog>
    <el-dialog v-model="ticketDialog" title="处理反馈工单" width="min(500px, 92vw)"><el-form label-position="top"><el-form-item label="处理状态"><el-select v-model="ticketReply.status"><el-option label="处理中" value="PROCESSING" /><el-option label="已解决" value="RESOLVED" /></el-select></el-form-item><el-form-item label="官方回复"><el-input v-model="ticketReply.reply" type="textarea" :rows="5" /></el-form-item></el-form><template #footer><el-button @click="ticketDialog = false">取消</el-button><el-button type="primary" @click="replyTicket">保存回复</el-button></template></el-dialog>
    <el-dialog v-model="faqDialog" :title="faqForm.id ? '编辑 FAQ' : '新增 FAQ'" width="min(500px, 92vw)"><el-form label-position="top"><el-form-item label="问题"><el-input v-model="faqForm.question" /></el-form-item><el-form-item label="答案"><el-input v-model="faqForm.answer" type="textarea" :rows="5" /></el-form-item><el-form-item label="排序"><el-input-number v-model="faqForm.sortNo" :min="0" /></el-form-item></el-form><template #footer><el-button @click="faqDialog = false">取消</el-button><el-button type="primary" @click="saveFaq">保存</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowRight, Bell, ChatDotRound, ChatLineRound, CollectionTag, Delete, Document, Download, Edit, EditPen, Files, House, MagicStick, Menu, Message, MoreFilled, Plus, Promotion, Reading, Refresh, Search, Setting, Star, SwitchButton, Tickets, Upload, User, UserFilled, View } from '@element-plus/icons-vue';
import { deleteData, getAuthToken, getData, postData, putData, setAuthToken } from './api/client';

type KnowledgeFile = { id:number; userId:number; title:string; fileType:string; auditStatus:string; views?:number; downloads?:number; likes?:number };
type Post = { id:number; userId:number; title:string; content:string; status:string; imageUrls?:string[]; likes?:number };
type Ticket = { id:number; userId:number; type:string; content:string; status:string; reply?:string };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string; signature?:string; status:string; role:string };
type ChatMessage = { id:number; sessionId:number; senderId:number; content:string; status:string };
type Notice = { id:number; title:string; content:string; read:boolean };
type Draft = { id:number; title:string; content:string; userId:number };
type Report = { id:number; fileId?:number; targetUserId?:number; reason:string; status:string };
type Faq = { id:number; question:string; answer:string; sortNo:number };
type EventRecord = { id:number; type:string; aggregateId:string; status:string; createdAt:string };
type AiSession = { id:number; title:string; created_at:string };
type Comment = { id:number; userId:number; content:string };
type NavigationItem = { key:string; label:string; icon:ReturnType<typeof markRaw>; badge?:number };

const authenticated = ref(Boolean(getAuthToken()));
const busy = ref(false); const aiBusy = ref(false); const mobileMenuOpen = ref(false);
const username = ref(localStorage.getItem('ai-knowledge-username') || '');
const displayName = ref(localStorage.getItem('ai-knowledge-name') || username.value || '用户');
const role = ref(localStorage.getItem('ai-knowledge-role') || 'USER');
const currentUserId = ref(Number(localStorage.getItem('ai-knowledge-user-id') || 1));
const portal = ref<'client'|'admin'>(role.value === 'ADMIN' ? 'admin' : 'client');
const activeView = ref(portal.value === 'admin' ? 'dashboard' : 'home');
const globalSearch = ref(''); const knowledgeKeyword = ref(''); const knowledgeType = ref(''); const adminUserKeyword = ref('');
const feedMode = ref<'all'|'following'>('all'); const moderationTab = ref('knowledge');
const knowledgeDialog = ref(false); const postDialog = ref(false); const feedbackDialog = ref(false); const ticketDialog = ref(false); const faqDialog = ref(false); const commentDrawer = ref(false);

const loginForm = ref({ username:'demo', password:'demo' });
const profileForm = ref({ userId:currentUserId.value, nickname:displayName.value, avatarUrl:'', signature:'' });
const knowledgeForm = ref({ userId:currentUserId.value, title:'', filename:'knowledge.txt', fileType:'txt', content:'', fileUrl:'' });
const postForm = ref({ userId:currentUserId.value, title:'', content:'', images:'' });
const messageForm = ref({ sessionId:1, senderId:currentUserId.value, content:'' });
const feedbackForm = ref({ userId:currentUserId.value, type:'BUG', content:'' });
const ticketReply = ref({ ticketId:0, status:'PROCESSING', reply:'' });
const faqForm = ref({ id:0, question:'', answer:'', sortNo:10, enabled:1 });
const aiConfig = ref({ data_source_scope:'all-approved', match_limit:5, compliance_rule:'answer-with-references' });

const knowledgeFiles = ref<KnowledgeFile[]>([]); const feedPosts = ref<Post[]>([]); const tickets = ref<Ticket[]>([]); const adminTickets = ref<Ticket[]>([]);
const messages = ref<ChatMessage[]>([]); const notifications = ref<Notice[]>([]); const drafts = ref<Draft[]>([]); const sessions = ref<number[]>([]);
const adminUsers = ref<UserRecord[]>([]); const knowledgeReports = ref<Report[]>([]); const userReports = ref<Report[]>([]); const faqs = ref<Faq[]>([]); const adminEvents = ref<EventRecord[]>([]);
const adminOverview = ref<Record<string, Record<string, unknown>>>({}); const aiOverview = ref<Record<string, unknown>>({});
const followData = ref<{ followedUserIds?:number[]; followerUserIds?:number[] }>({});
const aiQuestion = ref(''); const aiMessages = ref<{role:'user'|'assistant';content:string}[]>([]); const aiSessions = ref<AiSession[]>([]); const aiSessionId = ref<number>();
const selectedPost = ref<Post>(); const comments = ref<Comment[]>([]); const commentText = ref('');
const aiPrompts = ['平台支持哪些知识格式？','如何使用全文搜索？','社区有哪些核心功能？'];

const clientNavigation: NavigationItem[] = [{key:'home',label:'工作台',icon:markRaw(House)},{key:'knowledge',label:'知识库',icon:markRaw(Files)},{key:'community',label:'社区广场',icon:markRaw(ChatDotRound)},{key:'messages',label:'消息中心',icon:markRaw(Message),badge:0},{key:'ai',label:'AI 问答',icon:markRaw(MagicStick)},{key:'profile',label:'个人中心',icon:markRaw(User)}];
const adminNavigation: NavigationItem[] = [{key:'dashboard',label:'运营概览',icon:markRaw(House)},{key:'moderation',label:'内容审核',icon:markRaw(CollectionTag)},{key:'users',label:'用户管理',icon:markRaw(UserFilled)},{key:'tickets',label:'工单与 FAQ',icon:markRaw(Tickets)},{key:'system',label:'AI 与系统',icon:markRaw(Setting)}];
const currentNavigation = computed(() => portal.value === 'admin' ? adminNavigation : clientNavigation.map(item => item.key === 'messages' ? {...item,badge:notifications.value.length || 0} : item));
const currentTitle = computed(() => currentNavigation.value.find(item => item.key === activeView.value)?.label || '工作台');
const greeting = computed(() => { const hour = new Date().getHours(); return hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'; });
const filteredKnowledge = computed(() => knowledgeFiles.value.filter(file => (!knowledgeType.value || file.fileType === knowledgeType.value) && (!knowledgeKeyword.value || file.title.toLowerCase().includes(knowledgeKeyword.value.toLowerCase()))));
const filteredAdminUsers = computed(() => adminUsers.value.filter(user => `${user.username}${user.nickname}`.toLowerCase().includes(adminUserKeyword.value.toLowerCase())));
const adminMetrics = computed(() => [{label:'注册用户',value:metricValue('userAdmin','totalUsers'),hint:`${metricValue('userAdmin','activeUsers')} 个正常账号`,color:'green',icon:markRaw(UserFilled)},{label:'知识资源',value:metricValue('knowledgeAdmin','totalFiles'),hint:`${metricValue('knowledgeAdmin','pendingAudit')} 个待审核`,color:'blue',icon:markRaw(Files)},{label:'社区帖子',value:metricValue('forumAdmin','publishedPosts'),hint:'全站内容产出',color:'amber',icon:markRaw(ChatDotRound)},{label:'反馈工单',value:metricValue('feedbackAdmin','tickets'),hint:`${metricValue('feedbackAdmin','pendingTickets')} 个待处理`,color:'red',icon:markRaw(Tickets)}]);

function metricValue(section:string,key:string){ const value=adminOverview.value[section]?.[key]; return typeof value==='number'?value:0; }
function auditLabel(status:string){ return ({APPROVED:'已通过',PENDING:'待审核',REJECTED:'已驳回'} as Record<string,string>)[status] || status; }
function ticketStatusLabel(status:string){ return ({PENDING:'待处理',PROCESSING:'处理中',RESOLVED:'已解决'} as Record<string,string>)[status] || status; }
function formatDate(value:string){ return value ? new Date(value).toLocaleString('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}) : ''; }
function notifyError(error:unknown){ ElMessage.error(error instanceof Error?error.message:String(error)); }
function useAccount(user:string,password:string){ loginForm.value={username:user,password}; }

async function login(){ busy.value=true; try { const result=await postData<{token:string;role:string;user:UserRecord}>('/user/login',loginForm.value); setAuthToken(result.token); username.value=result.user.username; displayName.value=result.user.nickname; role.value=result.role; currentUserId.value=result.user.id; localStorage.setItem('ai-knowledge-username',username.value); localStorage.setItem('ai-knowledge-name',displayName.value); localStorage.setItem('ai-knowledge-role',role.value); localStorage.setItem('ai-knowledge-user-id',String(currentUserId.value)); authenticated.value=true; portal.value=result.role==='ADMIN'?'admin':'client'; activeView.value=result.role==='ADMIN'?'dashboard':'home'; syncUserForms(); await refreshCurrentView(); ElMessage.success('登录成功'); } catch(error){ notifyError(error); } finally{busy.value=false;} }
function logout(){ localStorage.removeItem('ai-knowledge-local-token'); ['ai-knowledge-username','ai-knowledge-name','ai-knowledge-role','ai-knowledge-user-id'].forEach(key=>localStorage.removeItem(key)); authenticated.value=false; }
function syncUserForms(){ profileForm.value.userId=currentUserId.value; knowledgeForm.value.userId=currentUserId.value; postForm.value.userId=currentUserId.value; messageForm.value.senderId=currentUserId.value; feedbackForm.value.userId=currentUserId.value; }
function switchPortal(value:'client'|'admin'){ portal.value=value; activeView.value=value==='admin'?'dashboard':'home'; mobileMenuOpen.value=false; refreshCurrentView(); }
function selectView(key:string){ activeView.value=key; mobileMenuOpen.value=false; refreshCurrentView(); }
async function refreshCurrentView(){ try { if(portal.value==='admin'){ if(activeView.value==='dashboard') await loadAdminDashboard(); else if(activeView.value==='moderation') await loadModeration(); else if(activeView.value==='users') adminUsers.value=await getData('/user/admin/users'); else if(activeView.value==='tickets') await loadTicketsAdmin(); else await loadSystemAdmin(); } else { if(['home','knowledge'].includes(activeView.value)) await loadKnowledge(); if(['home','community'].includes(activeView.value)) await loadFeed(feedMode.value); if(['home','messages'].includes(activeView.value)) await loadMessageData(); if(activeView.value==='ai') await loadAiHistory(); if(activeView.value==='profile') await loadProfile(); if(activeView.value==='home') await loadFeedback(); } } catch(error){ notifyError(error); } }
async function runGlobalSearch(){ activeView.value='knowledge'; knowledgeKeyword.value=globalSearch.value; await searchKnowledge(); }

async function loadKnowledge(){ knowledgeFiles.value=await getData('/knowledge/list'); }
async function searchKnowledge(){ const results=knowledgeKeyword.value?await getData<KnowledgeFile[]>(`/knowledge/search/fulltext?keyword=${encodeURIComponent(knowledgeKeyword.value)}`):await getData<KnowledgeFile[]>('/knowledge/list'); knowledgeFiles.value=results; }
async function uploadKnowledge(){ if(!knowledgeForm.value.title.trim()||!knowledgeForm.value.content.trim()){ElMessage.warning('请填写标题和文件内容');return;} busy.value=true; try{const stored=await postData<{fileUrl:string}>('/knowledge/storage/upload',{filename:knowledgeForm.value.filename,content:knowledgeForm.value.content,fileType:knowledgeForm.value.fileType,title:knowledgeForm.value.title}); await postData('/knowledge/upload',{userId:currentUserId.value,title:knowledgeForm.value.title,fileType:knowledgeForm.value.fileType,fileUrl:stored.fileUrl,content:knowledgeForm.value.content}); knowledgeDialog.value=false; knowledgeForm.value.title=''; knowledgeForm.value.content=''; await loadKnowledge(); ElMessage.success('知识已上传并建立索引');}catch(error){notifyError(error);}finally{busy.value=false;} }
function openKnowledge(file:KnowledgeFile){ activeView.value='knowledge'; viewKnowledge(file); }
async function viewKnowledge(file:KnowledgeFile){ await postData('/knowledge/view',{fileId:file.id}); ElMessage.success(`已记录阅读：${file.title}`); await loadKnowledge(); }
async function downloadKnowledge(file:KnowledgeFile){ await postData('/knowledge/download',{userId:currentUserId.value,fileId:file.id}); ElMessage.success('下载记录已保存'); await loadKnowledge(); }
async function likeKnowledge(file:KnowledgeFile){ await postData('/knowledge/like',{userId:currentUserId.value,fileId:file.id}); ElMessage.success('已点赞'); await loadKnowledge(); }
async function collectKnowledge(file:KnowledgeFile){ await postData('/knowledge/collect',{userId:currentUserId.value,fileId:file.id}); ElMessage.success('已收藏'); }
async function reportKnowledge(file:KnowledgeFile){ const {value}=await ElMessageBox.prompt('请填写举报原因','举报知识资源',{inputValue:'内容不准确'}); await postData('/knowledge/report',{userId:currentUserId.value,fileId:file.id,reason:value}); ElMessage.success('举报已提交'); }

async function loadFeed(mode:'all'|'following'=feedMode.value){ feedMode.value=mode; if(mode==='following'){const relations=await getData<{followedUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`); feedPosts.value=await getData(`/square/following-feed?followedUserIds=${relations.followedUserIds.join(',')}`);}else feedPosts.value=await getData('/square/feed'); }
async function createPost(){ if(!postForm.value.title.trim()||!postForm.value.content.trim()){ElMessage.warning('请填写标题和正文');return;} busy.value=true;try{await postData('/post/create',{...postForm.value,imageUrls:postForm.value.images.split('\n').map(v=>v.trim()).filter(Boolean).slice(0,9)});postDialog.value=false;postForm.value.title='';postForm.value.content='';postForm.value.images='';await loadFeed();ElMessage.success('帖子已发布');}catch(error){notifyError(error);}finally{busy.value=false;} }
async function saveDraft(){ await postData('/post/draft',{userId:currentUserId.value,title:postForm.value.title||'未命名草稿',content:postForm.value.content});postDialog.value=false;await loadDrafts();ElMessage.success('草稿已保存'); }
async function loadDrafts(){ drafts.value=await getData(`/post/drafts?userId=${currentUserId.value}`); }
async function loadMyPosts(){ feedPosts.value=await getData(`/square/feed?authorUserId=${currentUserId.value}`); }
async function likePost(post:Post){ await postData('/post/like',{userId:currentUserId.value,postId:post.id});await loadFeed(); }
async function collectPost(post:Post){ await postData('/square/collect',{userId:currentUserId.value,postId:post.id});ElMessage.success('已收藏帖子'); }
async function openComments(post:Post){ selectedPost.value=post;comments.value=await getData(`/comment/list?postId=${post.id}`);commentDrawer.value=true; }
async function createComment(){ if(!commentText.value.trim()||!selectedPost.value)return;await postData('/comment/create',{postId:selectedPost.value.id,userId:currentUserId.value,content:commentText.value});commentText.value='';await openComments(selectedPost.value);ElMessage.success('评论已发布'); }

async function loadMessageData(){ sessions.value=await getData('/message/sessions');notifications.value=await getData(`/notification/list?userId=${currentUserId.value}`);if(sessions.value.length&&!sessions.value.includes(messageForm.value.sessionId))messageForm.value.sessionId=sessions.value[0];await loadMessages(); }
async function openSession(session:number){messageForm.value.sessionId=session;await loadMessages();}
async function loadMessages(){messages.value=await getData(`/message/list?sessionId=${messageForm.value.sessionId}`);}
function newConversation(){messageForm.value.sessionId=(sessions.value[sessions.value.length-1]||0)+1;messages.value=[];}
async function sendMessage(){if(!messageForm.value.content.trim())return;await postData('/message/send',messageForm.value);messageForm.value.content='';await loadMessageData();}
async function clearCurrentSession(){await ElMessageBox.confirm('确认清空当前会话记录？','清空会话',{type:'warning'});await postData('/message/clear',{sessionId:messageForm.value.sessionId});await loadMessageData();}

async function askAi(){if(!aiQuestion.value.trim())return;const question=aiQuestion.value;aiMessages.value.push({role:'user',content:question});aiQuestion.value='';aiBusy.value=true;try{const result=await postData<{session_id:number;answer:string}>('/ai/chat',{question,user_id:currentUserId.value,session_id:aiSessionId.value});aiSessionId.value=result.session_id;aiMessages.value.push({role:'assistant',content:result.answer});await loadAiHistory();}catch(error){notifyError(error);}finally{aiBusy.value=false;}}
async function loadAiHistory(){const history=await getData<{sessions:AiSession[]}> (`/ai/history?user_id=${currentUserId.value}`);aiSessions.value=history.sessions;}
async function loadAiSession(id:number){aiSessionId.value=id;const history=await getData<{messages:{role:'user'|'assistant';content:string}[]}>(`/ai/history?user_id=${currentUserId.value}&session_id=${id}`);aiMessages.value=history.messages;}

async function loadProfile(){const user=await getData<UserRecord>(`/user/info?username=${username.value}`);profileForm.value={userId:user.id,nickname:user.nickname,avatarUrl:user.avatarUrl||'',signature:user.signature||''};displayName.value=user.nickname;followData.value=await getData(`/user/follows?userId=${currentUserId.value}`);await loadDrafts();await loadFeedback();}
async function saveProfile(){const user=await postData<UserRecord>('/user/profile',profileForm.value);displayName.value=user.nickname;localStorage.setItem('ai-knowledge-name',user.nickname);ElMessage.success('资料已保存');}
async function loadFeedback(){tickets.value=await getData(`/feedback/tickets?userId=${currentUserId.value}`);}
async function createTicket(){if(!feedbackForm.value.content.trim())return;await postData('/feedback/ticket',feedbackForm.value);feedbackDialog.value=false;feedbackForm.value.content='';await loadFeedback();ElMessage.success('反馈已提交');}

async function loadAdminDashboard(){const overview={userAdmin:await getData<Record<string,unknown>>('/user/admin/overview'),knowledgeAdmin:await getData<Record<string,unknown>>('/knowledge/admin/overview'),forumAdmin:await getData<Record<string,unknown>>('/post/admin/overview'),messageAdmin:await getData<Record<string,unknown>>('/message/admin/overview?userId=1'),feedbackAdmin:await getData<Record<string,unknown>>('/feedback/admin/overview')};adminOverview.value=overview;await loadModeration();}
async function loadModeration(){[knowledgeFiles.value,knowledgeReports.value,userReports.value,feedPosts.value]=await Promise.all([getData<KnowledgeFile[]>('/knowledge/list'),getData<Report[]>('/knowledge/admin/reports'),getData<Report[]>('/user/admin/reports'),getData<Post[]>('/square/feed')]);}
async function auditKnowledge(file:KnowledgeFile,status:string){await postData('/knowledge/admin/audit',{fileId:file.id,auditStatus:status,reason:'管理员审核'});await loadModeration();ElMessage.success('审核状态已更新');}
async function auditPost(post:Post,status:string){await postData('/post/admin/audit',{postId:post.id,status,reason:'管理员审核'});await loadModeration();ElMessage.success('帖子状态已更新');}
async function resolveKnowledgeReport(report:Report){await postData('/knowledge/admin/report/resolve',{reportId:report.id,status:'RESOLVED',result:'管理员已处理'});await loadModeration();}
async function resolveUserReport(report:Report){await postData('/user/admin/report/resolve',{reportId:report.id,status:'RESOLVED',result:'管理员已处理'});await loadModeration();}
async function toggleUserStatus(user:UserRecord){await postData('/user/admin/status',{userId:user.id,status:user.status==='ACTIVE'?'DISABLED':'ACTIVE'});adminUsers.value=await getData('/user/admin/users');ElMessage.success('账号状态已更新');}
async function loadTicketsAdmin(){[adminTickets.value,faqs.value]=await Promise.all([getData<Ticket[]>('/feedback/tickets'),getData<Faq[]>('/feedback/faqs')]);}
function openTicketReply(ticket:Ticket){ticketReply.value={ticketId:ticket.id,status:ticket.status==='RESOLVED'?'RESOLVED':'PROCESSING',reply:ticket.reply||''};ticketDialog.value=true;}
async function replyTicket(){await postData('/feedback/admin/reply',ticketReply.value);ticketDialog.value=false;await loadTicketsAdmin();ElMessage.success('工单已更新');}
function editFaq(faq:Faq){faqForm.value={...faq,enabled:1};faqDialog.value=true;}
async function saveFaq(){await postData('/feedback/admin/faq',{...faqForm.value,id:faqForm.value.id||undefined});faqDialog.value=false;faqForm.value={id:0,question:'',answer:'',sortNo:10,enabled:1};await loadTicketsAdmin();ElMessage.success('FAQ 已保存');}
async function deleteFaq(faq:Faq){await ElMessageBox.confirm(`确认删除“${faq.question}”？`,'删除 FAQ',{type:'warning'});await deleteData('/feedback/admin/faq',{faqId:faq.id});await loadTicketsAdmin();}
async function loadSystemAdmin(){const [overview,events]=await Promise.all([getData<Record<string,unknown>>('/ai/admin/overview'),getData<EventRecord[]>('/event/list?limit=50')]);aiOverview.value=overview;adminEvents.value=events;const config=overview.configuration as typeof aiConfig.value|undefined;if(config)aiConfig.value={...config};}
async function saveAiConfig(){await postData('/ai/admin/config',aiConfig.value);ElMessage.success('AI 配置已保存');await loadSystemAdmin();}

onMounted(async()=>{syncUserForms();if(authenticated.value)await refreshCurrentView();});
</script>
