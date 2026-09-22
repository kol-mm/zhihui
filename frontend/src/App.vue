<template>
  <div v-if="!authenticated" class="auth-screen">
    <section class="login-panel">
      <div class="brand-mark"><Reading /></div>
      <p class="product-label">人工智能知识社区</p>
      <h1>{{ platformConfig.platform_name }}</h1>
      <p class="login-copy">访问知识库、参与社区讨论，并使用知识增强的 AI 问答。</p>
      <el-form label-position="top" @submit.prevent="login">
        <el-form-item label="用户名"><el-input v-model="loginForm.username" size="large" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="loginForm.password" size="large" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="人机验证"><div class="captcha-control"><div class="captcha-challenge" aria-live="polite"><img v-if="captchaImage" :src="captchaImage" alt="图片计算验证码" /><span v-else>{{ captchaLoading ? '正在获取验证码…' : '验证码暂不可用' }}</span></div><div class="captcha-row"><el-input v-model="loginForm.captchaAnswer" size="large" inputmode="numeric" autocomplete="off" maxlength="3" placeholder="请输入图片中的计算结果" /><el-button type="info" plain :loading="captchaLoading" :disabled="captchaCooldownRemaining > 0" @click="loadCaptcha">{{ captchaCooldownRemaining > 0 ? `${captchaCooldownRemaining} 秒后可换` : '换一张' }}</el-button></div><p class="captcha-hint">验证码 3 分钟内有效，验证失败后会自动更换。</p></div></el-form-item>
        <el-button class="login-button" type="primary" size="large" :loading="busy" native-type="submit">登录</el-button>
        <el-button v-if="platformConfig.registration_enabled" class="register-entry" text type="primary" @click="openRegisterDialog">没有账号？立即注册</el-button>
        <el-button class="forgot-entry" text @click="openForgotDialog">忘记密码？</el-button>
      </el-form>
    </section>
    <el-dialog v-model="registerDialog" class="register-dialog" title="注册社区账号" width="min(480px, 92vw)" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" @closed="resetRegistrationForm">
      <el-form label-position="top" @submit.prevent="registerAccount">
        <el-form-item label="用户名">
          <el-input v-model.trim="registerForm.username" autocomplete="username" autocapitalize="none" :spellcheck="false" maxlength="32" clearable enterkeyhint="next" placeholder="3-32 位字母、数字、_ 或 -" />
          <p class="registration-field-hint" :class="{ valid: registrationUsernameValid, 'is-neutral': !registerForm.username }" aria-live="polite">{{ registrationUsernameHint }}</p>
        </el-form-item>
        <el-form-item label="昵称（选填）"><el-input v-model="registerForm.nickname" autocomplete="nickname" maxlength="64" show-word-limit clearable enterkeyhint="next" placeholder="用于社区展示，留空时使用用户名" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="registerForm.password" type="password" show-password autocomplete="new-password" maxlength="128" enterkeyhint="next" placeholder="请设置登录密码" />
          <div v-if="registerForm.password" class="password-strength" :class="`is-${registrationPasswordStrength.level}`" aria-live="polite"><span><i :style="{ width: `${registrationPasswordStrength.percent}%` }"></i></span><small>密码强度：{{ registrationPasswordStrength.label }}</small></div>
          <div class="registration-rules">
            <span :class="{ valid: registrationPasswordChecks.length }">8-128 位</span>
            <span :class="{ valid: registrationPasswordChecks.letter }">包含字母</span>
            <span :class="{ valid: registrationPasswordChecks.number }">包含数字</span>
            <span :class="{ valid: registrationPasswordChecks.noWhitespace }">不含空格</span>
            <span :class="{ valid: registrationPasswordChecks.differsFromUsername }">不同于用户名</span>
          </div>
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="registerForm.confirmPassword" type="password" show-password autocomplete="new-password" maxlength="128" enterkeyhint="next" placeholder="请再次输入密码" />
          <p v-if="registerForm.confirmPassword" class="registration-field-hint" :class="{ valid: registrationPasswordsMatch }" aria-live="polite">{{ registrationPasswordsMatch ? '两次输入的密码一致' : '两次输入的密码不一致' }}</p>
        </el-form-item>
        <el-form-item label="人机验证"><div class="captcha-control"><div class="captcha-challenge" aria-live="polite"><img v-if="captchaImage" :src="captchaImage" alt="图片计算验证码" /><span v-else>{{ captchaLoading ? '正在获取验证码…' : '验证码暂不可用' }}</span></div><div class="captcha-row"><el-input v-model="registerForm.captchaAnswer" inputmode="numeric" autocomplete="off" maxlength="3" placeholder="请输入图片中的计算结果" /><el-button type="info" plain :loading="captchaLoading" :disabled="captchaCooldownRemaining > 0" @click="loadCaptcha">{{ captchaCooldownRemaining > 0 ? `${captchaCooldownRemaining} 秒后可换` : '换一张' }}</el-button></div><p class="captcha-hint">验证码 3 分钟内有效，验证失败后会自动更换。</p></div></el-form-item>
        <button class="form-submit-proxy" type="submit" tabindex="-1" aria-hidden="true"></button>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="registerDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="registerAccount">创建账号并登录</el-button></template>
    </el-dialog>
    <el-dialog v-model="forgotDialog" class="register-dialog forgot-dialog" title="找回密码" width="min(480px, 92vw)" destroy-on-close :close-on-click-modal="!busy" :close-on-press-escape="!busy" @closed="resetForgotForm">
      <el-segmented v-model="forgotStep" class="forgot-steps" block :options="[{ label: '1. 申请重置码', value: 'request' }, { label: '2. 设置新密码', value: 'complete' }]" />
      <p class="forgot-intro">{{ forgotStep === 'request' ? '账号未绑定邮箱或手机，重置码由管理员核实身份后私下发给你。请留下能联系到你的方式。' : '输入管理员发给你的重置码。重置码 30 分钟内有效，输错 5 次需重新申请；重置后所有设备上的登录都会退出。' }}</p>
      <el-form label-position="top" @submit.prevent="submitForgot">
        <el-form-item label="用户名"><el-input v-model.trim="forgotForm.username" autocomplete="username" autocapitalize="none" :spellcheck="false" maxlength="32" clearable placeholder="要找回的账号用户名" /></el-form-item>
        <el-form-item v-if="forgotStep === 'request'" label="联系方式（选填）"><el-input v-model="forgotForm.contact" maxlength="100" show-word-limit clearable placeholder="如邮箱、微信或 QQ，方便管理员核实身份" /></el-form-item>
        <template v-else>
          <el-form-item label="重置码"><el-input v-model="forgotForm.code" autocomplete="one-time-code" autocapitalize="characters" :spellcheck="false" maxlength="20" clearable placeholder="XXXX-XXXX-XXXX" /></el-form-item>
          <el-form-item label="新密码"><el-input v-model="forgotForm.newPassword" type="password" show-password autocomplete="new-password" maxlength="128" placeholder="8-128 位，需包含字母和数字" /></el-form-item>
          <el-form-item label="确认新密码"><el-input v-model="forgotForm.confirmPassword" type="password" show-password autocomplete="new-password" maxlength="128" placeholder="请再次输入新密码" /></el-form-item>
        </template>
        <el-form-item label="人机验证"><div class="captcha-control"><div class="captcha-challenge" aria-live="polite"><img v-if="captchaImage" :src="captchaImage" alt="图片计算验证码" /><span v-else>{{ captchaLoading ? '正在获取验证码…' : '验证码暂不可用' }}</span></div><div class="captcha-row"><el-input v-model="forgotForm.captchaAnswer" inputmode="numeric" autocomplete="off" maxlength="3" placeholder="请输入图片中的计算结果" /><el-button type="info" plain :loading="captchaLoading" :disabled="captchaCooldownRemaining > 0" @click="loadCaptcha">{{ captchaCooldownRemaining > 0 ? `${captchaCooldownRemaining} 秒后可换` : '换一张' }}</el-button></div><p class="captcha-hint">验证码 3 分钟内有效，验证失败后会自动更换。</p></div></el-form-item>
        <button class="form-submit-proxy" type="submit" tabindex="-1" aria-hidden="true"></button>
      </el-form>
      <template #footer><el-button :disabled="busy" @click="forgotDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="submitForgot">{{ forgotStep === 'request' ? '提交申请' : '重置密码' }}</el-button></template>
    </el-dialog>
  </div>

  <div v-else class="app-layout" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <aside class="sidebar" :class="{ open: mobileMenuOpen }">
      <div class="sidebar-brand">
        <el-button class="sidebar-collapse" :icon="sidebarCollapsed ? ArrowRight : Menu" circle text :title="sidebarCollapsed ? '展开导航' : '收起导航'" @click="sidebarCollapsed = !sidebarCollapsed" />
        <div class="brand-mark small"><Reading /></div>
        <div><strong>{{ platformConfig.platform_name }}</strong><span>人工智能知识社区</span></div>
        <el-button class="mobile-sidebar-close" :icon="Close" circle text title="关闭导航" @click="mobileMenuOpen = false" />
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
        <div class="user-avatar"><img v-if="avatarUrl" :src="resolveApiUrl(avatarUrl)" alt="头像" /><span v-else>{{ displayName.slice(0, 1).toUpperCase() }}</span></div>
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
          <el-button v-if="portal === 'admin' && activeView === 'system'" :icon="Refresh" :loading="aiIndexBusy" title="从已审核知识重建 AI 索引" @click="rebuildAiIndex">{{ aiIndexBusy && aiIndexProgress ? `已处理 ${aiIndexProgress} 份` : '重建知识索引' }}</el-button>
          <el-button class="topbar-refresh" :icon="Refresh" circle title="刷新当前数据" @click="refreshVisiblePage" />
        </div>
      </header>

      <main class="content-area">
        <div v-if="detailLoading" class="view-loading" role="status"><el-icon class="is-loading"><Loading /></el-icon><span>正在加载详情...</span></div>
        <div v-else-if="detailError" class="view-error"><el-icon><Warning /></el-icon><span>{{ detailError }}</span><el-button size="small" type="primary" @click="loadDetailRoute">重试</el-button></div>
        <KnowledgeDetailPage
          v-else-if="detailRoute?.kind === 'knowledge' && detailKnowledge"
          :file="detailKnowledge"
          :author="communityUser(detailKnowledge.userId)"
          :blocks="detailKnowledgeBlocks"
          :pdf-preview-url="detailPdfPreviewUrl"
          :current-user-id="currentUserId"
          :following="isFollowing(detailKnowledge.userId)"
          @back="leaveDetail"
          @download="downloadDetailKnowledge"
          @like="likeDetailKnowledge"
          @collect="collectKnowledge"
          @forward="forwardKnowledge"
          @report="reportKnowledge"
          @delete="deleteKnowledge"
          @follow="toggleFollowAuthor"
        />
        <CommunityDetailPage
          v-else-if="detailRoute?.kind === 'community' && detailPost"
          :post="detailPost"
          :comments="detailComments"
          :comment-total="detailCommentTotal"
          :comments-has-more="detailCommentsHasMore"
          :comments-loading="detailCommentsLoading"
          :comments-error="detailCommentsError"
          :users="communityDirectory"
          :current-user-id="currentUserId"
          :following="isFollowing(detailPost.userId)"
          :comments-enabled="platformConfig.comments_enabled"
          :max-comment-length="platformConfig.max_comment_length"
          :focus-comment-id="detailFocusCommentId"
          :on-submit-comment="createDetailComment"
          @back="leaveDetail"
          @like="likeDetailPost"
          @collect="collectPost"
          @edit="editPost"
          @delete-post="deletePost"
          @delete-comment="deleteComment"
          @load-more-comments="loadMoreDetailComments"
          @follow="toggleFollowAuthor"
        />
        <div v-else-if="viewLoading" class="view-loading" role="status"><el-icon class="is-loading"><Loading /></el-icon><span>正在加载当前页面...</span></div>
        <div v-else-if="viewError" class="view-error"><el-icon><Warning /></el-icon><span>{{ viewError }}</span><el-button size="small" type="primary" @click="refreshCurrentView">重试</el-button></div>
        <template v-else-if="portal === 'client'">
          <section v-if="activeView === 'home'" class="page-stack">
            <el-alert v-if="platformConfig.platform_notice" :title="platformConfig.platform_notice" type="info" :closable="false" show-icon />
            <div class="welcome-row">
              <div><p class="section-kicker">今日概览</p><h3>{{ greeting }}，{{ displayName }}</h3><p>继续探索知识、社区动态和你的 AI 对话。</p></div>
              <el-button v-if="platformConfig.knowledge_upload_enabled" type="primary" :icon="Upload" @click="knowledgeDialog = true">上传知识</el-button>
            </div>
            <div class="metrics-grid">
              <div class="metric-tile"><span class="metric-icon green"><Files /></span><div><strong>{{ knowledgeTotal }}</strong><span>知识资源</span></div></div>
              <div class="metric-tile"><span class="metric-icon blue"><ChatDotRound /></span><div><strong>{{ communityPostCount }}</strong><span>社区动态</span></div></div>
              <div class="metric-tile"><span class="metric-icon amber"><Bell /></span><div><strong>{{ notificationUnread }}</strong><span>未读通知</span></div></div>
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
                <div class="surface-head"><div><h3>关注动态</h3><p>查看已关注作者的最新内容</p></div><el-button text type="primary" @click="selectView('square')">进入广场</el-button></div>
                <div v-if="feedPosts.length" class="feed-mini">
                  <article v-for="post in feedPosts.slice(0, 3)" :key="post.id" class="feed-mini-link" @click="openPostDetail(post)"><div class="mini-avatar">{{ communityUser(post.userId).nickname.slice(0, 1) }}</div><div><strong>{{ post.title }}</strong><p>{{ post.content }}</p><small>{{ communityUser(post.userId).nickname }} · {{ post.likes || 0 }} 赞</small></div></article>
                </div><el-empty v-else description="暂无社区动态" />
              </section>
            </div>
          </section>

          <section v-else-if="activeView === 'knowledge'" class="page-stack">
            <div class="page-toolbar"><div><h3>知识库</h3><p>检索、阅读并管理社区知识资源</p></div><el-button v-if="platformConfig.knowledge_upload_enabled" type="primary" :icon="Upload" @click="knowledgeDialog = true">上传资料</el-button></div>
            <section class="surface filter-bar"><el-input v-model="knowledgeKeyword" placeholder="输入标题或正文关键词" :prefix-icon="Search" clearable @keyup.enter="searchKnowledge" /><el-select v-model="knowledgeType" placeholder="全部格式" clearable><el-option label="Word" value="docx" /><el-option label="PDF" value="pdf" /><el-option label="TXT" value="txt" /><el-option label="Markdown" value="md" /></el-select><el-button :icon="Search" @click="searchKnowledge">搜索</el-button></section>
            <section class="surface">
              <div class="category-filter">
                <el-radio-group v-model="knowledgeCategoryId" size="small">
                  <el-radio-button :value="0">全部 {{ knowledgeSearchMode ? knowledgeFiles.length : knowledgeTotal }}</el-radio-button>
                  <el-radio-button v-for="category in knowledgeCategories" :key="category.id" :value="category.id">
                    {{ category.name }} {{ categoryCount(category.id) }}
                  </el-radio-button>
                </el-radio-group>
              </div>
              <div v-if="filteredKnowledge.length" class="knowledge-grid">
                <article v-for="file in filteredKnowledge" :key="file.id" class="knowledge-card">
                  <el-tag v-if="file.collected" class="knowledge-collected-badge" type="success" effect="plain">已收藏</el-tag>
                  <img v-if="file.coverUrl" class="knowledge-cover" loading="lazy" decoding="async" :src="resolveApiUrl(file.coverUrl)" :alt="`${file.title}封面`" />
                  <div class="knowledge-card-top"><span class="file-type large">{{ file.fileType?.toUpperCase() || '文档' }}</span><el-tag :type="file.auditStatus === 'APPROVED' ? 'success' : 'warning'" effect="plain">{{ auditLabel(file.auditStatus) }}</el-tag></div>
                  <h4>{{ file.title }}</h4><p>{{ communityUser(file.userId).nickname }} · @{{ communityUser(file.userId).username }}</p>
                  <div class="card-stats"><span><View />{{ file.views || 0 }}</span><span><Download />{{ file.downloads || 0 }}</span><span><Star />{{ file.likes || 0 }}</span></div>
                  <div class="card-actions"><el-button text type="primary" @click="openKnowledge(file)">阅读</el-button><el-button v-if="file.userId !== currentUserId" text :type="isFollowing(file.userId) ? 'success' : 'default'" @click="toggleFollowAuthor(file.userId)">{{ isFollowing(file.userId) ? '取消关注' : '关注作者' }}</el-button><el-button v-if="file.fileUrl" text @click="downloadKnowledge(file)">下载</el-button><el-dropdown trigger="click"><el-button text :icon="MoreFilled" /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="likeKnowledge(file)">{{ file.liked ? '取消点赞' : '点赞' }}</el-dropdown-item><el-dropdown-item @click="collectKnowledge(file)">{{ file.collected ? '取消收藏' : '收藏' }}</el-dropdown-item><el-dropdown-item @click="forwardKnowledge(file)">转发</el-dropdown-item><el-dropdown-item v-if="file.userId === currentUserId" divided @click="deleteKnowledge(file)">删除资源</el-dropdown-item><el-dropdown-item v-else divided @click="reportKnowledge(file)">举报</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div>
                </article>
              </div>              <div v-if="!knowledgeSearchMode && (knowledgeHasMore || knowledgeLoadingMore || knowledgeLoadError)" ref="knowledgeSentinelRef" class="knowledge-load-more"><el-button :loading="knowledgeLoadingMore" @click="loadMoreKnowledge">{{ knowledgeLoadingMore ? '正在加载资源' : knowledgeLoadError ? `${knowledgeLoadError}，点击重试` : '加载更多资源' }}</el-button></div>
              <el-empty v-if="!filteredKnowledge.length" description="没有匹配的知识资源" />
            </section>
            <section v-if="platformConfig.user_ranking_enabled" class="surface"><div class="surface-head"><div><h3>知识贡献榜</h3><p>综合上传量、浏览量、下载量和违规频次排序</p></div><el-tag type="info">前 {{ knowledgeRanking.length }} 名</el-tag></div><div class="ranking-list"><article v-for="item in knowledgeRanking" :key="item.userId"><strong>{{ item.rank }}</strong><div class="mini-avatar">{{ communityUser(item.userId).nickname.slice(0,1) }}</div><span><b>{{ communityUser(item.userId).nickname }}</b><small>上传 {{ item.uploads }} · 浏览 {{ item.views }} · 下载 {{ item.downloads }} · 违规 {{ item.violations }}</small></span><em>{{ item.score }} 分</em></article></div><el-empty v-if="!knowledgeRanking.length" description="暂无榜单数据" /></section>
          </section>

          <section v-else-if="activeView === 'forum' || activeView === 'square'" class="page-stack community-page">
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

          <section v-else-if="activeView === 'messages'" class="message-page" :class="{ 'has-session': messageForm.sessionId }">
            <section class="surface session-panel"><div class="surface-head"><div><h3>消息中心</h3><p>{{ sessions.length }} 个联系人</p></div><div><el-button :icon="Delete" circle text type="danger" title="清空全部聊天记录" @click="clearAllMessages" /><el-button :icon="Plus" circle title="发起私信" @click="newConversation" /></div></div><div class="session-list"><button v-for="session in sessions" :key="session.id" :class="{ active: messageForm.sessionId === session.id }" @click="openSession(session)"><div class="mini-avatar">{{ sessionPartner(session).nickname.slice(0,1).toUpperCase() }}</div><span><strong>{{ sessionPartner(session).nickname }}</strong><small>{{ session.lastMessage || `@${sessionPartner(session).username}` }}</small></span></button></div><el-empty v-if="!sessions.length" description="暂无私信会话" /></section>
            <section class="surface conversation-panel"><div class="conversation-head"><el-button class="mobile-conversation-back" :icon="ArrowLeft" circle text title="返回联系人列表" @click="closeMobileConversation" /><div><strong>{{ currentMessagePartner?.nickname || '选择联系人开始私信' }}</strong><span v-if="currentMessagePartner">@{{ currentMessagePartner.username }} · 私密会话</span></div><el-button v-if="messageForm.sessionId" :icon="Refresh" :loading="messageRefreshing" circle text title="获取新消息" @click="refreshMessages" /><el-dropdown v-if="messageForm.sessionId"><el-button :icon="MoreFilled" circle text /><template #dropdown><el-dropdown-menu><el-dropdown-item @click="clearCurrentSession">清空当前会话</el-dropdown-item><el-dropdown-item divided @click="deleteCurrentSession">删除整个会话</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div><div ref="messageListRef" class="message-list" @scroll.passive="handleMessageScroll"><div v-if="messageForm.sessionId && messages.length" class="message-history-state"><el-button v-if="messageHasOlder || messageHistoryLoading" text size="small" :loading="messageHistoryLoading" @click="loadOlderMessages">{{ messageHistoryLoading ? '正在加载更早的消息' : '查看更早的消息' }}</el-button><small v-else>已显示全部消息</small></div><div v-for="message in messages" :key="message.id" :class="['message-bubble', message.senderId === currentUserId ? 'mine' : '']"><p>{{ message.content }}</p><div class="message-meta"><small>{{ formatDate(message.createdAt) }}</small><el-button v-if="message.senderId === currentUserId" :icon="Delete" circle text type="danger" title="删除消息" @click="deleteMessage(message)" /></div></div><el-empty v-if="!messages.length" :description="messageForm.sessionId ? '发送第一条消息，开始对话' : '从左侧选择联系人'" /></div><button v-if="messageUnseenCount" type="button" class="message-new-indicator" @click="jumpToLatestMessages">{{ messageUnseenCount }} 条新消息</button><p v-if="openSessionClosed" class="message-compose-notice" role="status">该会话{{ sessionStatusLabel(openSessionStatus) }}，暂时不能发送消息</p><div class="message-compose"><el-input v-model="messageForm.content" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }" resize="none" :maxlength="platformConfig.max_message_length" :disabled="!messageForm.sessionId || messageSending || openSessionClosed" placeholder="输入消息，Ctrl+Enter 发送" @keydown.ctrl.enter.prevent="sendMessage" /><el-button type="primary" :icon="Promotion" :loading="messageSending" :disabled="!messageForm.sessionId || !messageForm.content.trim() || openSessionClosed" @click="sendMessage">发送</el-button></div></section>
          </section>

          <section v-else-if="activeView === 'ai'" class="ai-page" :class="{ 'history-open': mobileAiHistoryOpen }">
            <section class="surface ai-chat"><div class="ai-heading"><span class="ai-symbol"><MagicStick /></span><div><h3>知识库 AI</h3><p>回答将优先引用平台已收录的知识片段</p></div><el-button class="mobile-ai-history-toggle" :icon="ChatLineRound" circle text title="查看对话历史" @click="mobileAiHistoryOpen = !mobileAiHistoryOpen" /></div><div class="ai-messages"><div v-if="!aiMessages.length" class="ai-empty"><MagicStick /><h3>今天想了解什么？</h3><p>试试询问平台知识、文档内容或社区使用方式。</p><div><button v-for="prompt in aiPrompts" :key="prompt" @click="aiQuestion = prompt">{{ prompt }}</button></div></div><div v-for="(message, index) in aiMessages" :key="index" :class="['ai-message', message.role]"><span>{{ message.role === 'assistant' ? 'AI' : displayName.slice(0, 1) }}</span><div class="ai-message-content"><p>{{ message.content }}</p><div v-if="message.references?.length" class="ai-references"><strong>知识来源</strong><button v-for="reference in message.references" :key="reference.id" @click="openAiReference(reference)"><Document /><span>{{ reference.title }}</span><small>知识 #{{ reference.file_id }}</small><ArrowRight /></button></div></div></div></div><div class="ai-compose"><el-input v-model="aiQuestion" type="textarea" :rows="2" resize="none" placeholder="向知识库提问..." @keydown.ctrl.enter="askAi" /><el-button type="primary" :icon="Promotion" :loading="aiBusy" @click="askAi">发送</el-button></div></section>
            <aside class="surface ai-history"><div class="surface-head"><div><h3>对话历史</h3><p>最近的 AI 会话</p></div><div class="ai-history-head-actions"><el-button class="mobile-ai-history-toggle" :icon="Close" circle text title="返回问答" @click="mobileAiHistoryOpen = false" /><el-button :icon="Plus" circle title="新建会话" @click="newAiSession" /><el-button :icon="Refresh" circle text title="刷新会话" @click="loadAiHistory" /></div></div><el-input v-model="aiHistoryKeyword" class="ai-history-search" :prefix-icon="Search" clearable placeholder="搜索会话" /><div class="ai-history-list"><article v-for="session in filteredAiSessions" :key="session.id" :class="{ active: aiSessionId === session.id }"><button class="ai-session-main" @click="loadAiSession(session.id)"><ChatLineRound /><span><strong>{{ session.title }}</strong><small>{{ formatDate(session.created_at) }}</small></span></button><div class="ai-session-actions"><el-button :icon="Edit" circle text title="重命名会话" @click.stop="renameAiSession(session)" /><el-button :icon="Delete" circle text type="danger" title="删除会话" @click.stop="deleteAiSession(session)" /></div></article></div><el-empty v-if="!filteredAiSessions.length" :description="aiHistoryKeyword ? '没有匹配的历史会话' : '暂无历史对话'" /></aside>
          </section>

          <section v-else class="page-stack">
            <div class="page-toolbar"><div><h3>个人中心</h3><p>管理资料、关注关系和反馈工单</p></div><el-button type="primary" @click="saveProfile">保存资料</el-button></div>
            <div class="profile-layout"><section class="surface profile-card"><div class="profile-avatar"><img v-if="avatarUrl" :src="resolveApiUrl(avatarUrl)" alt="头像" /><span v-else>{{ displayName.slice(0, 1).toUpperCase() }}</span></div><h3>{{ displayName }}</h3><p>@{{ username }}</p><el-tag>{{ roleLabel(role) }}</el-tag><el-button v-if="avatarUrl" class="remove-avatar" text type="danger" @click="removeAvatar">删除头像</el-button><el-button class="onboarding-replay" text type="primary" @click="openOnboarding">查看新手引导</el-button><div class="profile-counts"><span><strong>{{ followData.followedUserIds?.length || 0 }}</strong>关注</span><span><strong>{{ followData.followerUserIds?.length || 0 }}</strong>粉丝</span><span><strong>{{ drafts.length }}</strong>草稿</span></div></section><section class="surface profile-form"><h3>基础资料</h3><el-alert v-if="profileAudit?.status==='PENDING'" class="profile-audit-notice" type="warning" :closable="false" show-icon title="资料修改待管理员审核"><ul class="profile-diff"><li v-for="change in profileAuditPending" :key="change.field">{{ change.description }}</li></ul><small>审核通过前，其他成员看到的仍是原来的资料。再次保存可以修改提交的内容。</small></el-alert><el-alert v-else-if="profileAudit?.status==='REJECTED'" class="profile-audit-notice" type="error" :closable="false" show-icon title="资料修改未通过审核"><p>{{ profileAudit.reason || '资料未通过审核' }}</p><small>可以修改后重新提交。</small></el-alert><el-form label-position="top"><el-form-item label="昵称"><el-input v-model="profileForm.nickname" /></el-form-item><el-form-item label="个性签名"><el-input v-model="profileForm.signature" type="textarea" :rows="3" /></el-form-item></el-form><el-divider>邮箱</el-divider><EmailBinding /><el-divider>修改密码</el-divider><el-form label-position="top"><el-form-item label="当前密码"><el-input v-model="passwordForm.currentPassword" type="password" show-password autocomplete="current-password" /></el-form-item><el-form-item label="新密码"><el-input v-model="passwordForm.newPassword" type="password" show-password autocomplete="new-password" maxlength="128" placeholder="8-128 位，需包含字母和数字" /></el-form-item><el-button type="primary" plain @click="changePassword">更新密码</el-button></el-form></section><section class="surface feedback-card"><div class="surface-head"><div><h3>我的反馈</h3><p>问题进度与官方回复</p></div><el-button v-if="platformConfig.feedback_enabled" :icon="Plus" circle @click="feedbackDialog = true" /></div><article v-for="ticket in tickets" :key="ticket.id" :data-ticket-id="ticket.id" :class="{ 'is-linked': linkedTicketId === ticket.id }"><div><strong>{{ ticketTypeLabel(ticket.type) }}</strong><p>{{ ticket.content }}</p><small v-if="ticket.reply">官方回复：{{ ticket.reply }}</small></div><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag></article><el-empty v-if="!tickets.length" description="暂无反馈工单" /></section></div>
            <section class="surface"><div class="surface-head"><div><h3>我的知识资源</h3><p>统一查看上传、收藏、点赞、下载和转发记录</p></div><el-tag type="info">{{ myKnowledgeTotal }} 条</el-tag><el-radio-group v-model="knowledgeActivityType" size="small" @change="loadMyKnowledge"><el-radio-button value="UPLOADED">上传</el-radio-button><el-radio-button value="COLLECTED">收藏</el-radio-button><el-radio-button value="LIKED">点赞</el-radio-button><el-radio-button value="DOWNLOADED">下载</el-radio-button><el-radio-button value="FORWARDED">转发</el-radio-button></el-radio-group></div><div class="activity-list"><article v-for="file in myKnowledge" :key="file.id"><span class="file-type">{{ file.fileType?.toUpperCase() }}</span><div><strong>{{ file.title }}</strong><p>资源 #{{ file.id }} · {{ auditLabel(file.auditStatus) }}</p></div><el-button text type="primary" @click="openKnowledge(file)">阅读</el-button></article><div v-if="myKnowledgeHasMore || myKnowledgeLoading || myKnowledgeError" class="knowledge-load-more"><el-button text type="primary" :loading="myKnowledgeLoading" @click="loadMoreMyKnowledge">{{ myKnowledgeLoading ? '正在加载记录' : myKnowledgeError ? `${myKnowledgeError}，点击重试` : '加载更多记录' }}</el-button></div><el-empty v-if="!myKnowledge.length" description="暂无对应资源记录" /></div></section>
            <section class="surface"><div class="surface-head"><div><h3>收藏的社区帖子</h3><p>集中查看收藏内容，进入帖子后可继续参与讨论</p></div><el-tag type="info">{{ collectedPosts.length }} 篇</el-tag></div><div class="activity-list"><article v-for="post in collectedPosts" :key="post.id"><span class="file-type">帖子</span><div><strong>{{ post.title }}</strong><p>帖子 #{{ post.id }} · {{ post.likes || 0 }} 赞</p></div><div class="collection-actions"><el-button text type="primary" @click="openPostDetail(post)">查看</el-button><el-button text type="danger" @click="removeCollectedPost(post)">取消收藏</el-button></div></article><el-empty v-if="!collectedPosts.length" description="暂无收藏的社区帖子" /></div></section>
            <div class="two-column account-tools">
              <section class="surface"><div class="surface-head"><div><h3>关系与安全</h3><p>输入完整用户名后管理关注、拉黑和举报</p></div></div><el-form label-position="top"><el-form-item label="对方完整用户名"><div class="exact-user-search"><el-input v-model="relationForm.username" placeholder="输入完整用户名" clearable @clear="relationTargetUser = undefined; relationForm.targetUserId = 0" @keyup.enter="resolveRelationUser" /><el-button :icon="Search" @click="resolveRelationUser">查找</el-button></div></el-form-item></el-form><div v-if="relationTargetUser" class="exact-user-result"><strong>{{ relationTargetUser.nickname }}</strong><span>@{{ relationTargetUser.username }}</span></div><div class="relation-actions"><el-button type="primary" @click="followUser">关注</el-button><el-button @click="unfollowUser">取消关注</el-button><el-button type="warning" @click="blockUser">拉黑</el-button><el-button @click="unblockUser">解除拉黑</el-button><el-button type="danger" text @click="reportUser">举报用户</el-button></div><div class="relation-summary"><span>已关注 <strong>{{ followedUsers.length }} 人</strong></span><span>粉丝 <strong>{{ followerUsers.length }} 人</strong></span><span>黑名单 <strong>{{ blockedUserIds.length }} 人</strong></span></div><el-tabs class="relation-lists"><el-tab-pane :label="`关注 ${followedUsers.length}`"><div class="compact-user-list"><article v-for="user in followedUsers" :key="user.id"><div class="mini-avatar">{{ user.nickname.slice(0,1) }}</div><span><strong>{{ user.nickname }}</strong><small>@{{ user.username }}</small></span><el-button text type="primary" @click="loadAuthorPostsFromProfile(user.id)">查看动态</el-button></article><el-empty v-if="!followedUsers.length" description="还没有关注用户" /></div></el-tab-pane><el-tab-pane :label="`粉丝 ${followerUsers.length}`"><div class="compact-user-list"><article v-for="user in followerUsers" :key="user.id"><div class="mini-avatar">{{ user.nickname.slice(0,1) }}</div><span><strong>{{ user.nickname }}</strong><small>@{{ user.username }}</small></span><el-button text type="primary" @click="loadAuthorPostsFromProfile(user.id)">查看动态</el-button></article><el-empty v-if="!followerUsers.length" description="暂无粉丝" /></div></el-tab-pane></el-tabs></section>
              <section class="surface"><el-tabs v-model="profileToolTab"><el-tab-pane label="行为足迹" name="activity"><div class="activity-list behavior-list"><article v-for="item in behaviors" :key="item.id" :class="{ 'activity-clickable': behaviorTargetCanOpen(item) }" :role="behaviorTargetCanOpen(item) ? 'link' : undefined" :tabindex="behaviorTargetCanOpen(item) ? 0 : undefined" @click="openBehaviorTarget(item)" @keydown.enter="openBehaviorTarget(item)"><span class="event-dot"></span><div><strong>{{ behaviorActionLabel(item.action) }} · {{ behaviorTargetLabel(item.targetType) }}</strong><p>{{ behaviorTargetLabel(item.targetType) }} #{{ item.targetId }}</p><small>{{ formatDate(item.createdAt) }}</small></div><ArrowRight v-if="behaviorTargetCanOpen(item)" class="activity-arrow" /></article><el-empty v-if="!behaviors.length" description="暂无行为记录" /></div></el-tab-pane><el-tab-pane :label="`我的草稿 ${drafts.length}`" name="drafts"><article v-for="draft in drafts" :key="draft.id" class="draft-row"><div><strong>{{ draft.title }}</strong><p>{{ draft.content }}</p><small>{{ draft.imageUrls?.length || 0 }} 张图片 · {{ formatDate(draft.updatedAt || '') }}</small></div><div class="draft-actions"><el-button text type="primary" @click="editDraft(draft)">编辑</el-button><el-button text type="success" @click="publishDraft(draft)">发布</el-button><el-button text type="danger" @click="deleteDraft(draft)">删除</el-button></div></article><el-empty v-if="!drafts.length" description="暂无草稿" /></el-tab-pane></el-tabs></section>
            </div>
            <section class="surface faq-help"><div class="surface-head"><div><h3>使用帮助</h3><p>常见问题与平台使用说明</p></div><el-tag type="info">{{ faqs.length }} 条</el-tag></div><el-collapse><el-collapse-item v-for="faq in faqs" :key="faq.id" :title="faq.question" :name="faq.id"><p>{{ faq.answer }}</p></el-collapse-item></el-collapse><el-empty v-if="!faqs.length" description="暂无使用帮助" /></section>
          </section>
        </template>

        <template v-else>
          <nav class="admin-workspace-nav" aria-label="管理后台快捷导航">
            <button v-for="item in adminWorkspaceNavigation" :key="item.key" :class="{ active: activeView === item.key }" @click="selectView(item.key)">
              <component :is="item.icon" />
              <span>{{ item.label }}</span>
              <el-badge v-if="item.badge" :value="item.badge" />
            </button>
          </nav>
          <section v-if="activeView === 'dashboard'" class="page-stack">
            <div class="page-toolbar"><div><h3>平台运营概览</h3><p>内容、用户、互动和待办事项的实时摘要</p></div><el-button :icon="Refresh" @click="loadAdminDashboard">刷新数据</el-button></div>
            <div v-if="adminAttentionCount" class="admin-attention-banner"><span><Warning /><strong>当前共有 {{ adminAttentionCount }} 项待处理事项</strong></span><div><el-button v-if="moderationOpenCount" text type="warning" @click="openAdminQueue('knowledge')">审核 {{ moderationOpenCount }}</el-button><el-button v-if="ticketOpenCount" text type="primary" @click="selectView('tickets')">工单 {{ ticketOpenCount }}</el-button></div></div>
            <div class="metrics-grid admin-metrics"><div v-for="metric in adminMetrics" :key="metric.label" class="metric-tile"><span :class="['metric-icon', metric.color]"><component :is="metric.icon" /></span><div><strong>{{ metric.value }}</strong><span>{{ metric.label }}</span><small>{{ metric.hint }}</small></div></div></div>
            <div class="two-column"><section class="surface"><div class="surface-head"><div><h3>待处理事项</h3><p>优先处理积压内容</p></div></div><div class="task-list"><button @click="openAdminQueue('knowledge')"><span class="task-dot amber"></span><span><strong>知识审核</strong><small>用户上传资源审核</small></span><b>{{ metricValue('knowledgeAdmin','pendingAudit') }}</b></button><button @click="openAdminQueue('profiles')"><span class="task-dot amber"></span><span><strong>资料审核</strong><small>会员昵称与签名修改</small></span><b>{{ metricValue('userAdmin','pendingAudits') }}</b></button><button @click="openAdminQueue('reports')"><span class="task-dot red"></span><span><strong>内容举报</strong><small>知识与用户举报</small></span><b>{{ metricValue('knowledgeAdmin','openReports') + metricValue('userAdmin','openReports') }}</b></button><button @click="selectView('tickets')"><span class="task-dot blue"></span><span><strong>反馈工单</strong><small>待回复用户问题</small></span><b>{{ ticketOpenCount }}</b></button></div></section><section class="surface"><div class="surface-head"><div><h3>系统状态</h3><p>来自各服务健康接口的实时结果</p></div><el-tag :type="healthItems.every(item=>item.ok)?'success':'danger'">{{ healthItems.every(item=>item.ok)?'运行正常':'存在异常' }}</el-tag></div><div class="status-list"><span v-for="item in healthItems" :key="item.label"><i :class="{offline:!item.ok}"></i>{{ item.label }}<strong :class="{offline:!item.ok}">{{ item.ok?'正常':'异常' }}</strong></span></div></section></div>
          </section>

          <section v-else-if="activeView === 'moderation'" class="page-stack">
            <div class="page-toolbar"><div><h3>内容审核</h3><p>先查看完整内容，再处理知识、帖子和举报</p></div><div><el-button :icon="Upload" @click="openAdminKnowledgeUpload">新增资源</el-button><el-button :icon="Refresh" @click="loadModeration">刷新队列</el-button></div></div>
            <section class="surface admin-list-surface">
              <div class="admin-filter-bar"><el-input v-model="adminModerationKeyword" :prefix-icon="Search" clearable placeholder="搜索标题、编号、用户或原因" /><el-select v-model="adminModerationStatus" clearable placeholder="全部状态"><el-option v-for="option in moderationStatusOptions" :key="option.value" :label="option.label" :value="option.value" /></el-select><span>找到 <strong>{{ moderationResultCount }}</strong> 条</span></div>
              <el-tabs v-model="moderationTab">
              <el-tab-pane label="知识资源" name="knowledge"><el-table class="admin-desktop-table" :data="moderationKnowledge.items"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="fileType" label="格式" width="90" /><el-table-column label="状态" width="170"><template #default="scope"><span class="stacked-status">{{ auditLabel(scope.row.auditStatus) }}<el-tag v-if="parseStatusLabel(scope.row.parseStatus)" size="small" effect="plain" :type="parseStatusTone(scope.row.parseStatus)">{{ parseStatusLabel(scope.row.parseStatus) }}</el-tag><el-tag v-if="auditSourceLabel(scope.row.auditSource)" size="small" effect="plain" :type="auditSourceTone(scope.row.auditSource)">{{ auditSourceLabel(scope.row.auditSource) }}</el-tag></span><small v-if="scope.row.auditReason" class="muted-text audit-reason">{{ scope.row.auditReason }}</small></template></el-table-column><el-table-column label="操作" width="430"><template #default="scope"><el-button text type="primary" @click="reviewKnowledge(scope.row)">查看内容</el-button><el-button text @click="openKnowledgeMetadata(scope.row)">编辑</el-button><el-button v-if="['PENDING','REJECTED'].includes(scope.row.auditStatus)" text type="success" @click="auditKnowledge(scope.row, 'APPROVED')">{{ scope.row.auditStatus === 'PENDING' ? '通过' : '重新通过' }}</el-button><el-button v-else-if="scope.row.auditStatus === 'APPROVED'" text type="warning" @click="updateKnowledgeStatus(scope.row, 'HIDDEN')">下架</el-button><el-button v-else-if="scope.row.auditStatus === 'HIDDEN'" text type="success" @click="updateKnowledgeStatus(scope.row, 'APPROVED')">恢复</el-button><el-button text type="danger" @click="deleteAdminKnowledge(scope.row)">删除</el-button></template></el-table-column></el-table><div class="admin-mobile-cards"><article v-for="file in moderationKnowledge.items" :key="file.id"><header><span class="file-type">{{ file.fileType?.toUpperCase() }}</span><span class="stacked-status"><el-tag size="small">{{ auditLabel(file.auditStatus) }}</el-tag><el-tag v-if="auditSourceLabel(file.auditSource)" size="small" :type="auditSourceTone(file.auditSource)" effect="plain">{{ auditSourceLabel(file.auditSource) }}</el-tag><el-tag v-if="parseStatusLabel(file.parseStatus)" size="small" effect="plain" :type="parseStatusTone(file.parseStatus)">{{ parseStatusLabel(file.parseStatus) }}</el-tag></span></header><h4>{{ file.title }}</h4><p>资源 #{{ file.id }}</p><p v-if="file.auditReason" class="muted-text">{{ auditDecisionNote(file.auditSource, file.auditReason) }}</p><footer><el-button type="primary" plain @click="reviewKnowledge(file)">查看</el-button><el-button @click="openKnowledgeMetadata(file)">编辑</el-button><el-button v-if="['PENDING','REJECTED','HIDDEN'].includes(file.auditStatus)" type="success" plain @click="file.auditStatus === 'HIDDEN' ? updateKnowledgeStatus(file, 'APPROVED') : auditKnowledge(file, 'APPROVED')">{{ file.auditStatus === 'HIDDEN' ? '恢复' : '通过' }}</el-button><el-button v-else type="warning" plain @click="updateKnowledgeStatus(file, 'HIDDEN')">下架</el-button></footer></article><el-empty v-if="!moderationKnowledge.items.length" description="没有匹配的知识资源" /></div><div v-if="moderationKnowledge.hasMore" class="knowledge-load-more"><el-button :loading="moderationKnowledge.loading" @click="loadMoreModeration">加载更多资源</el-button></div></el-tab-pane>
              <el-tab-pane :label="`知识举报 ${metricValue('knowledgeAdmin','openReports')}`" name="reports"><el-table class="admin-desktop-table" :data="moderationKnowledgeReports.items"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="fileId" label="资源" width="90" /><el-table-column prop="reason" label="举报原因" min-width="240" /><el-table-column label="状态" width="110"><template #default="scope">{{ reportStatusLabel(scope.row.status) }}</template></el-table-column><el-table-column label="操作" width="190"><template #default="scope"><el-button text @click="reviewReportedKnowledge(scope.row)">查看资源</el-button><el-button text type="primary" :disabled="scope.row.status === 'RESOLVED'" @click="resolveKnowledgeReport(scope.row)">结案</el-button></template></el-table-column></el-table><div class="admin-mobile-cards"><article v-for="report in moderationKnowledgeReports.items" :key="report.id"><header><strong>举报 #{{ report.id }}</strong><el-tag size="small">{{ reportStatusLabel(report.status) }}</el-tag></header><h4>{{ report.reason }}</h4><p>知识资源 #{{ report.fileId }}</p><footer><el-button @click="reviewReportedKnowledge(report)">查看资源</el-button><el-button type="primary" :disabled="report.status === 'RESOLVED'" @click="resolveKnowledgeReport(report)">结案</el-button></footer></article><el-empty v-if="!moderationKnowledgeReports.items.length" description="没有匹配的知识举报" /></div><div v-if="moderationKnowledgeReports.hasMore" class="knowledge-load-more"><el-button :loading="moderationKnowledgeReports.loading" @click="loadMoreModeration">加载更多举报</el-button></div></el-tab-pane>
              <el-tab-pane :label="`用户举报 ${metricValue('userAdmin','openReports')}`" name="users"><el-table class="admin-desktop-table" :data="moderationUserReports.items"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="targetUserId" label="被举报用户" width="120" /><el-table-column prop="reason" label="原因" min-width="240" /><el-table-column label="状态" width="110"><template #default="scope">{{ reportStatusLabel(scope.row.status) }}</template></el-table-column><el-table-column label="操作" width="190"><template #default="scope"><el-button text @click="reviewReportedUser(scope.row)">查看用户</el-button><el-button text type="primary" :disabled="scope.row.status === 'RESOLVED'" @click="resolveUserReport(scope.row)">结案</el-button></template></el-table-column></el-table><div class="admin-mobile-cards"><article v-for="report in moderationUserReports.items" :key="report.id"><header><strong>举报 #{{ report.id }}</strong><el-tag size="small">{{ reportStatusLabel(report.status) }}</el-tag></header><h4>{{ report.reason }}</h4><p>被举报用户 #{{ report.targetUserId }}</p><footer><el-button @click="reviewReportedUser(report)">查看用户</el-button><el-button type="primary" :disabled="report.status === 'RESOLVED'" @click="resolveUserReport(report)">结案</el-button></footer></article><el-empty v-if="!moderationUserReports.items.length" description="没有匹配的用户举报" /></div><div v-if="moderationUserReports.hasMore" class="knowledge-load-more"><el-button :loading="moderationUserReports.loading" @click="loadMoreModeration">加载更多举报</el-button></div></el-tab-pane>
              <el-tab-pane :label="`资料审核 ${metricValue('userAdmin','pendingAudits')}`" name="profiles"><el-table class="admin-desktop-table" :data="moderationProfileChanges.items" empty-text="当前没有待审核的资料"><el-table-column prop="id" label="ID" width="70" /><el-table-column label="会员" min-width="170"><template #default="scope"><span class="table-user"><span><strong>{{ scope.row.before.nickname || scope.row.username }}</strong><small>@{{ scope.row.username }} · #{{ scope.row.userId }}</small></span></span></template></el-table-column><el-table-column label="修改内容" min-width="300"><template #default="scope"><ul class="profile-diff"><li v-for="change in profileAuditChanges(scope.row.before, scope.row.after)" :key="change.field">{{ change.description }}</li><li v-if="!profileAuditChanges(scope.row.before, scope.row.after).length" class="muted-text">与当前资料一致</li></ul><el-tag v-if="scope.row.stale" size="small" type="warning" effect="plain">账号已被修改</el-tag><small v-if="scope.row.reason" class="muted-text">驳回原因：{{ scope.row.reason }}</small></template></el-table-column><el-table-column label="状态" width="110"><template #default="scope"><el-tag size="small" effect="plain" :type="profileAuditStatusType(scope.row.status)">{{ profileAuditStatusLabel(scope.row.status) }}</el-tag></template></el-table-column><el-table-column label="提交时间" width="130"><template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template></el-table-column><el-table-column label="操作" width="180" fixed="right"><template #default="scope"><template v-if="scope.row.status==='PENDING'"><el-button text type="success" :loading="auditingProfileChangeId===scope.row.id" :disabled="auditingProfileChangeId!==0" @click="auditProfileChange(scope.row,'APPROVED')">通过</el-button><el-button text type="danger" :disabled="auditingProfileChangeId!==0" @click="rejectProfileChange(scope.row)">驳回</el-button></template><span v-else class="muted-text">已处理</span></template></el-table-column></el-table><div class="admin-mobile-cards"><article v-for="change in moderationProfileChanges.items" :key="change.id"><header><div class="table-user"><span><strong>{{ change.before.nickname || change.username }}</strong><small>@{{ change.username }} · #{{ change.id }}</small></span></div><el-tag size="small" effect="plain" :type="profileAuditStatusType(change.status)">{{ profileAuditStatusLabel(change.status) }}</el-tag></header><ul class="profile-diff"><li v-for="item in profileAuditChanges(change.before, change.after)" :key="item.field">{{ item.description }}</li><li v-if="!profileAuditChanges(change.before, change.after).length" class="muted-text">与当前资料一致</li></ul><p v-if="change.stale" class="muted-text">账号资料已被管理员修改过</p><p v-if="change.reason" class="muted-text">驳回原因：{{ change.reason }}</p><footer v-if="change.status==='PENDING'"><el-button type="success" plain :loading="auditingProfileChangeId===change.id" :disabled="auditingProfileChangeId!==0" @click="auditProfileChange(change,'APPROVED')">通过</el-button><el-button type="danger" plain :disabled="auditingProfileChangeId!==0" @click="rejectProfileChange(change)">驳回</el-button></footer></article><el-empty v-if="!moderationProfileChanges.items.length" description="当前没有待审核的资料" /></div><div v-if="moderationProfileChanges.hasMore" class="knowledge-load-more"><el-button :loading="moderationProfileChanges.loading" @click="loadMoreModeration">加载更多</el-button></div></el-tab-pane><el-tab-pane :label="`待审帖子 ${metricValue('forumAdmin','pendingAudit')}`" name="posts"><el-table class="admin-desktop-table" :data="moderationPendingPosts.items"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column label="状态" width="150"><template #default="scope"><span class="stacked-status">{{ postStatusLabel(scope.row.status) }}<el-tag v-if="auditSourceLabel(scope.row.auditSource)" size="small" :type="auditSourceTone(scope.row.auditSource)" effect="plain">{{ auditSourceLabel(scope.row.auditSource) }}</el-tag></span><small v-if="scope.row.auditReason" class="muted-text audit-reason">{{ scope.row.auditReason }}</small></template></el-table-column><el-table-column label="操作" width="250"><template #default="scope"><el-button text type="primary" @click="reviewPost(scope.row)">查看内容</el-button><el-button text type="success" :loading="auditingPostId === scope.row.id" :disabled="auditingPostId !== 0" @click="auditPost(scope.row, 'PUBLISHED')">发布</el-button><el-button text type="danger" :loading="auditingPostId === scope.row.id" :disabled="auditingPostId !== 0" @click="auditPost(scope.row, 'HIDDEN')">驳回</el-button></template></el-table-column></el-table><div class="admin-mobile-cards"><article v-for="post in moderationPendingPosts.items" :key="post.id"><header><strong>帖子 #{{ post.id }}</strong><span class="stacked-status"><el-tag size="small" type="warning">待审核</el-tag><el-tag v-if="auditSourceLabel(post.auditSource)" size="small" :type="auditSourceTone(post.auditSource)" effect="plain">{{ auditSourceLabel(post.auditSource) }}</el-tag></span></header><h4>{{ post.title }}</h4><p>作者 #{{ post.userId }}</p><p v-if="post.auditReason" class="muted-text">{{ auditDecisionNote(post.auditSource, post.auditReason) }}</p><footer><el-button @click="reviewPost(post)">查看</el-button><el-button type="success" :loading="auditingPostId===post.id" @click="auditPost(post,'PUBLISHED')">发布</el-button><el-button type="danger" plain :loading="auditingPostId===post.id" @click="auditPost(post,'HIDDEN')">驳回</el-button></footer></article><el-empty v-if="!moderationPendingPosts.items.length" description="当前没有待审帖子" /></div><div v-if="moderationPendingPosts.hasMore" class="knowledge-load-more"><el-button :loading="moderationPendingPosts.loading" @click="loadMoreModeration">加载更多帖子</el-button></div></el-tab-pane>
              <el-tab-pane :label="`帖子管理 ${metricValue('forumAdmin','publishedPosts')+metricValue('forumAdmin','hiddenPosts')}`" name="post-management"><el-table class="admin-desktop-table" :data="moderationManagedPosts.items"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="标题" min-width="220" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column label="状态" width="150"><template #default="scope"><span class="stacked-status">{{ postStatusLabel(scope.row.status) }}<el-tag v-if="auditSourceLabel(scope.row.auditSource)" size="small" :type="auditSourceTone(scope.row.auditSource)" effect="plain">{{ auditSourceLabel(scope.row.auditSource) }}</el-tag></span><small v-if="scope.row.auditReason" class="muted-text audit-reason">{{ scope.row.auditReason }}</small></template></el-table-column><el-table-column label="操作" width="220"><template #default="scope"><el-button text type="primary" @click="reviewPost(scope.row)">查看内容</el-button><el-button v-if="scope.row.status === 'PUBLISHED'" text type="warning" :loading="auditingPostId === scope.row.id" @click="auditPost(scope.row, 'HIDDEN')">隐藏</el-button><el-button v-else text type="success" :loading="auditingPostId === scope.row.id" @click="auditPost(scope.row, 'PUBLISHED')">恢复</el-button></template></el-table-column></el-table><div class="admin-mobile-cards"><article v-for="post in moderationManagedPosts.items" :key="post.id"><header><strong>帖子 #{{ post.id }}</strong><el-tag size="small" :type="post.status==='PUBLISHED'?'success':'info'">{{ postStatusLabel(post.status) }}</el-tag></header><h4>{{ post.title }}</h4><p>作者 #{{ post.userId }}</p><footer><el-button @click="reviewPost(post)">查看</el-button><el-button :type="post.status==='PUBLISHED'?'warning':'success'" plain :loading="auditingPostId===post.id" @click="auditPost(post,post.status==='PUBLISHED'?'HIDDEN':'PUBLISHED')">{{ post.status==='PUBLISHED'?'隐藏':'恢复' }}</el-button></footer></article><el-empty v-if="!moderationManagedPosts.items.length" description="没有匹配的帖子" /></div><div v-if="moderationManagedPosts.hasMore" class="knowledge-load-more"><el-button :loading="moderationManagedPosts.loading" @click="loadMoreModeration">加载更多帖子</el-button></div></el-tab-pane>
            </el-tabs></section>
          </section>

          <AdminUsersView v-else-if="activeView === 'users'" :refresh-key="usersRefreshKey" :current-user-id="currentUserId" :metrics="adminOverview.userAdmin" @governance="openUserGovernance" @overview="applyUserOverview" />
          <section v-else-if="activeView === 'analytics'" class="page-stack"><div class="page-toolbar"><div><h3>平台数据统计</h3><p>社区产出、互动热度、违规风险和反馈趋势</p></div><el-segmented v-model="analyticsDays" :options="[{label:'近 7 天',value:7},{label:'近 30 天',value:30},{label:'近 90 天',value:90}]" /></div><div class="metrics-grid admin-metrics"><div class="metric-tile"><span class="metric-icon green"><UserFilled /></span><div><strong>{{ metricValue('userAdmin','activeUsers') }}</strong><span>活跃账号</span><small>全站正常用户</small></div></div><div class="metric-tile"><span class="metric-icon blue"><Files /></span><div><strong>{{ analyticsKnowledgeCount }}</strong><span>知识产出</span><small>所选周期内</small></div></div><div class="metric-tile"><span class="metric-icon amber"><ChatDotRound /></span><div><strong>{{ analyticsPostCount }}</strong><span>社区帖子</span><small>已发布内容</small></div></div><div class="metric-tile"><span class="metric-icon red"><DataAnalysis /></span><div><strong>{{ analyticsInteractions }}</strong><span>内容互动</span><small>浏览、下载与点赞</small></div></div></div><section class="surface"><div class="surface-head"><div><h3>近 {{ analyticsTrendDays }} 天内容趋势</h3><p>每日新增知识、帖子与反馈工单</p></div><div class="analytics-legend"><span class="knowledge">知识</span><span class="post">帖子</span><span class="ticket">工单</span></div></div><div class="analytics-trend"><div v-for="item in analyticsTrend" :key="item.label" class="trend-day"><div class="trend-bars"><i class="knowledge" :style="{height:`${Math.max(3,item.knowledge/analyticsTrendMax*100)}%`}" :title="`知识 ${item.knowledge}`"></i><i class="post" :style="{height:`${Math.max(3,item.posts/analyticsTrendMax*100)}%`}" :title="`帖子 ${item.posts}`"></i><i class="ticket" :style="{height:`${Math.max(3,item.tickets/analyticsTrendMax*100)}%`}" :title="`工单 ${item.tickets}`"></i></div><small>{{ item.label }}</small></div></div></section><div class="two-column"><section class="surface"><div class="surface-head"><div><h3>热门知识</h3><p>全站累计，按浏览、下载和点赞综合排序，每分钟更新</p></div></div><div class="analytics-ranking"><article v-for="(file,index) in topKnowledge" :key="file.id"><b>{{ index+1 }}</b><span><strong>{{ file.title }}</strong><small>浏览 {{ file.views||0 }} · 下载 {{ file.downloads||0 }} · 点赞 {{ file.likes||0 }}</small></span></article></div><el-empty v-if="!topKnowledge.length" description="暂无知识数据" /></section><section class="surface"><div class="surface-head"><div><h3>热门帖子与风险</h3><p>互动内容及待处置事项</p></div><el-tag type="danger" effect="plain">{{ analyticsReportCount }} 项举报</el-tag></div><div class="analytics-ranking"><article v-for="(post,index) in topPosts" :key="post.id"><b>{{ index+1 }}</b><span><strong>{{ post.title }}</strong><small>点赞 {{ post.likes||0 }} · 作者 #{{ post.userId }}</small></span></article></div><el-empty v-if="!topPosts.length" description="暂无社区数据" /></section></div><section class="surface"><div class="surface-head"><div><h3>反馈结构</h3><p>所选周期内工单类型与处理进度</p></div></div><div class="governance-user-summary"><span>系统问题 <strong>{{ analyticsFeedback.bug }}</strong></span><span>产品建议 <strong>{{ analyticsFeedback.suggestion }}</strong></span><span>客服咨询 <strong>{{ analyticsFeedback.support }}</strong></span><span>已完结 <strong>{{ analyticsFeedback.resolved }}</strong></span></div></section></section>

          <AdminAuditLog v-else-if="activeView === 'audit'" :key="auditRefreshKey" v-model:subject-user-id="auditSubjectUserId" :subject-label="auditSubjectLabel" />
          <section v-else-if="activeView === 'tickets'" class="page-stack">
            <div class="page-toolbar"><div><h3>工单与常见问题</h3><p>分配客服、跟进用户问题并维护自助答疑</p></div><el-button type="primary" :icon="Plus" @click="faqDialog = true">新增常见问题</el-button></div>
            <div class="two-column">
              <section class="surface"><div class="surface-head"><div><h3>反馈工单</h3><p>{{ adminTickets.length }} / {{ adminTicketTotal }} 条记录</p></div></div><div class="admin-filter-bar ticket-filter-bar"><el-input v-model="adminTicketKeyword" :prefix-icon="Search" clearable placeholder="搜索编号、用户或内容" /><el-select v-model="adminTicketStatus" clearable placeholder="全部状态"><el-option label="待处理" value="PENDING" /><el-option label="处理中" value="PROCESSING" /><el-option label="已解决" value="RESOLVED" /></el-select></div><article v-for="ticket in adminTickets" :key="ticket.id" class="ticket-row"><div><strong>#{{ ticket.id }} · {{ ticketTypeLabel(ticket.type) }}</strong><p>{{ ticket.content }}</p><small>用户 #{{ ticket.userId }}<template v-if="ticket.reply"> · 当前回复：{{ ticket.reply }}</template></small><small v-if="ticket.assignedAt">分配时间：{{ formatDate(ticket.assignedAt) }}</small></div><div class="ticket-actions"><el-select :model-value="ticket.assigneeUserId||0" size="small" class="ticket-assignee" @change="assignTicket(ticket, $event)"><el-option label="未分配" :value="0" /><el-option v-for="user in assignableAdmins" :key="user.id" :label="user.nickname" :value="user.id" /></el-select><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag><el-button text type="primary" @click="openTicketReply(ticket)">处理</el-button></div></article><el-empty v-if="!adminTickets.length" description="没有匹配的工单" /><div v-if="adminTicketHasMore" class="knowledge-load-more"><el-button :loading="adminTicketLoading" @click="loadMoreAdminTickets">加载更多工单</el-button></div></section>
              <section class="surface"><div class="surface-head"><div><h3>常见问题</h3><p>用户端自助答疑内容</p></div></div><article v-for="faq in faqs" :key="faq.id" class="faq-row"><div><strong>{{ faq.question }}</strong><p>{{ faq.answer }}</p></div><div><el-button :icon="Edit" circle text @click="editFaq(faq)" /><el-button :icon="Delete" circle text type="danger" @click="deleteFaq(faq)" /></div></article><el-empty v-if="!faqs.length" description="暂无常见问题" /></section>
            </div>
          </section>

          <section v-else class="page-stack">
            <div class="page-toolbar"><div><h3>平台与 AI 配置</h3><p>统一管理平台开放范围、审核规则、容量限制和知识检索</p></div><el-button type="primary" :disabled="!aiConfigDirty" @click="saveAiConfig">{{ aiConfigDirty ? '保存全部配置' : '配置已保存' }}</el-button></div>
            <div v-if="aiConfigDirty" class="admin-unsaved-notice"><span><Warning />当前页面有未保存的配置更改</span><el-button text type="primary" @click="discardAiConfig">撤销更改</el-button></div>
            <section class="surface runtime-mode-panel"><div class="surface-head"><div><h3>系统运行模式</h3><p>显示当前服务实际使用的数据与基础设施模式</p></div><el-tag :type="runtimeModeProblem ? 'warning' : 'success'">{{ runtimeModeSummary }}</el-tag></div><el-alert v-if="businessModeMismatch" title="业务数据模式不一致，长期运行前应统一切换为 MySQL" type="warning" :closable="false" show-icon /><div class="runtime-mode-grid"><article v-for="item in runtimeModes" :key="item.key"><div><strong>{{ item.label }}</strong><p>{{ item.detail }}</p></div><el-tag :type="modeTagType(item)" effect="plain">{{ !item.available ? '无法读取' : item.healthy === false ? `${runtimeModeLabel(item.mode)}（连接异常）` : runtimeModeLabel(item.mode) }}</el-tag></article></div></section>
            <section class="surface settings-form platform-settings"><div class="surface-head"><div><h3>平台运营配置</h3><p>修改后由各业务服务读取并执行，敏感部署参数不在网页中展示</p></div></div><el-form label-position="top"><div class="config-field-grid"><el-form-item label="平台名称"><el-input v-model="aiConfig.platform_name" maxlength="60" show-word-limit /></el-form-item><el-form-item label="新用户默认发帖策略"><el-select v-model="aiConfig.default_publish_policy"><el-option label="标准审核" value="STANDARD" /><el-option label="强制预审" value="PRE_REVIEW" /><el-option label="禁止发布" value="BLOCKED" /></el-select></el-form-item></div><el-form-item label="平台公告"><el-input v-model="aiConfig.platform_notice" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="留空则不展示公告" /></el-form-item><el-divider>功能开放</el-divider><div class="config-switch-grid"><label><span><strong>开放用户注册</strong><small>关闭后保留现有账号登录</small></span><el-switch v-model="aiConfig.registration_enabled" /></label><label><span><strong>AI 问答</strong><small>控制用户端 AI 会话入口</small></span><el-switch v-model="aiConfig.ai_chat_enabled" /></label><label><span><strong>用户上传知识</strong><small>管理员仍可维护资源</small></span><el-switch v-model="aiConfig.knowledge_upload_enabled" /></label><label><span><strong>知识贡献榜</strong><small>控制榜单展示与查询</small></span><el-switch v-model="aiConfig.user_ranking_enabled" /></label><label><span><strong>社区与广场</strong><small>关闭帖子浏览和发布</small></span><el-switch v-model="aiConfig.community_enabled" /></label><label><span><strong>评论互动</strong><small>管理员仍可审查历史评论</small></span><el-switch v-model="aiConfig.comments_enabled" /></label><label><span><strong>用户私信</strong><small>关闭新会话和消息发送</small></span><el-switch v-model="aiConfig.private_messages_enabled" /></label><label><span><strong>通知提醒</strong><small>关闭通知生成与用户入口</small></span><el-switch v-model="aiConfig.notifications_enabled" /></label><label><span><strong>反馈提交</strong><small>历史工单仍可查询和处理</small></span><el-switch v-model="aiConfig.feedback_enabled" /></label><label><span><strong>帖子发布前审核</strong><small>关闭后帖子与修改将直接发布</small></span><el-switch v-model="aiConfig.post_audit_required" /></label><label><span><strong>资料修改先审后改</strong><small>开启后昵称与签名的修改需管理员通过；头像立即生效</small></span><el-switch v-model="aiConfig.profile_audit_required" /></label></div><el-divider>容量与保留规则</el-divider><div class="config-field-grid"><el-form-item label="知识文件上限（MB）"><el-input-number v-model="aiConfig.max_upload_mb" :min="1" :max="200" /></el-form-item><el-form-item label="PDF 上限（MB）"><el-input-number v-model="aiConfig.pdf_max_upload_mb" :min="1" :max="200" /></el-form-item><el-form-item label="帖子最多配图"><el-input-number v-model="aiConfig.max_post_images" :min="0" :max="9" /></el-form-item><el-form-item label="评论长度上限"><el-input-number v-model="aiConfig.max_comment_length" :min="100" :max="5000" /></el-form-item><el-form-item label="私信长度上限"><el-input-number v-model="aiConfig.max_message_length" :min="100" :max="5000" /></el-form-item><el-form-item label="草稿保留天数"><el-input-number v-model="aiConfig.draft_retention_days" :min="1" :max="3650" /></el-form-item></div></el-form></section>
            <div class="two-column"><section class="surface settings-form"><h3>AI 内容审核</h3><p class="muted-text">开启后，上传的知识与发布的帖子先由 AI 判断：明确合规的自动通过，明确违规的自动驳回，把握不足或服务不可用时仍交由人工审核。</p><el-form label-position="top"><el-form-item label="AI 内容审核"><el-switch v-model="aiConfig.ai_audit_enabled" /></el-form-item><el-form-item label="自动通过门槛"><el-slider v-model="aiConfig.ai_audit_approve_confidence" :min="0.5" :max="1" :step="0.05" show-input :disabled="!aiConfig.ai_audit_enabled" /></el-form-item><el-form-item label="自动驳回门槛"><el-slider v-model="aiConfig.ai_audit_reject_confidence" :min="0.5" :max="1" :step="0.05" show-input :disabled="!aiConfig.ai_audit_enabled" /></el-form-item><el-form-item label="通过后抽样复核比例（%）"><el-slider v-model="aiConfig.ai_audit_sample_percent" :min="0" :max="100" :step="5" show-input :disabled="!aiConfig.ai_audit_enabled" /><small class="muted-text">被抽中的内容即使 AI 判定通过，也会转人工确认；内容本身可以影响模型，抽样是发现这种情况的办法。</small></el-form-item><el-form-item label="运行状况"><div class="review-health"><el-tag :type="reviewVerdict(reviewHealth).tone" effect="plain">{{ reviewVerdict(reviewHealth).headline }}</el-tag><p class="muted-text">{{ reviewVerdict(reviewHealth).detail }}</p><div class="review-health-counters"><span v-for="item in reviewCounters(reviewHealth)" :key="item.label">{{ item.label }}<strong>{{ item.value }}</strong></span></div><p v-if="reviewHealth.last_failure" class="muted-text">最近失败：{{ reviewHealth.last_failure }}<template v-if="reviewHealth.last_failure_at"> · {{ formatDate(reviewHealth.last_failure_at) }}</template></p></div></el-form-item></el-form></section><section class="surface settings-form"><h3>AI 检索配置</h3><p v-if="!superAdmin" class="muted-text">模型与接口地址决定 API Key 发往何处，只有超级管理员可以修改；其余设置照常保存。</p><el-form label-position="top"><el-form-item label="AI 提供商"><el-select v-model="aiConfig.provider" :disabled="!superAdmin"><el-option label="本地知识检索" value="local" /><el-option label="OpenAI 兼容接口" value="openai-compatible" /></el-select></el-form-item><el-form-item label="模型名称"><el-input v-model="aiConfig.model" :disabled="!superAdmin" /></el-form-item><el-form-item label="完整请求地址"><el-input v-model="aiConfig.request_url" :disabled="aiConfig.provider === 'local' || !superAdmin" placeholder="例如 https://api.example.com/v1/chat/completions" /></el-form-item><el-form-item label="兼容接口基础地址（旧配置）"><el-input v-model="aiConfig.base_url" :disabled="aiConfig.provider === 'local' || !superAdmin" placeholder="留空即可；仅用于兼容旧配置" /></el-form-item><el-form-item label="生成温度（0-1）"><el-slider v-model="aiConfig.temperature" :min="0" :max="1" :step="0.1" show-input /><div class="config-help">数值越低，回答越稳定严谨；数值越高，表达变化越多，但更容易偏离知识内容。知识库问答建议使用 0.1-0.3。</div></el-form-item><el-form-item label="知识数据源范围"><el-select v-model="aiConfig.data_source_scope"><el-option label="全部已审核知识" value="all-approved" /><el-option label="仅管理员指定" value="admin-selected" /></el-select></el-form-item><el-form-item v-if="aiConfig.data_source_scope === 'admin-selected'" label="指定知识来源"><el-select v-model="aiConfig.selected_file_ids" multiple filterable remote :remote-method="searchAiSourceFiles" :loading="aiSourceSearching" collapse-tags placeholder="搜索并选择允许 AI 调用的已审核资料"><el-option v-for="file in aiSourceOptions" :key="file.id" :label="file.title" :value="file.id" /></el-select><div class="config-help">未选中的资料不会进入 AI 检索结果。</div></el-form-item><el-form-item label="单次匹配片段数"><el-input-number v-model="aiConfig.match_limit" :min="1" :max="20" /></el-form-item><el-form-item label="回复合规规则"><el-select v-model="aiConfig.compliance_rule"><el-option label="回答并标注参考资料" value="answer-with-references" /><el-option label="严格事实模式" value="strict-factual" /><el-option label="简洁回答" value="concise" /></el-select><div class="config-help">规则会直接约束本地回答和外部兼容模型的系统提示。</div></el-form-item></el-form><div class="config-status"><span>已索引知识片段<strong>{{ aiOverview.chunk_count || 0 }}</strong></span><span>AI 对话会话<strong>{{ aiOverview.session_count || 0 }}</strong></span></div></section><section class="surface"><div class="surface-head"><div><h3>业务事件日志</h3><p>最近 50 条事件</p></div><el-tag type="info">{{ adminEvents.length }}</el-tag></div><div class="event-list"><article v-for="event in adminEvents" :key="event.id"><span class="event-dot"></span><div><strong>{{ eventTypeLabel(event.type) }}</strong><p>对象 {{ event.aggregateId }}</p><small>{{ formatDate(event.createdAt) }}</small></div><el-tag size="small" effect="plain">{{ eventStatusLabel(event.status) }}</el-tag></article></div><el-empty v-if="!adminEvents.length" description="暂无事件" /></section></div>
          </section>
        </template>
      </main>
    </section>

    <nav class="mobile-bottom-nav" aria-label="移动端快捷导航">
      <button v-for="item in mobileNavigation" :key="item.key" :class="{ active: activeView === item.key && !detailRoute }" @click="selectView(item.key)"><component :is="item.icon" /><span>{{ item.label }}</span><el-badge v-if="item.badge" :value="item.badge" /></button>
      <button :class="{ active: mobileNavigation.every(item => item.key !== activeView) }" @click="mobileMenuOpen = true"><Menu /><span>更多</span></button>
    </nav>

    <el-dialog v-model="knowledgeDialog" title="上传知识资料" width="min(640px, 94vw)" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标题"><el-input v-model="knowledgeForm.title" placeholder="留空时使用文件名或首张图片名" /></el-form-item>
        <el-form-item label="知识分类"><el-select v-model="knowledgeForm.categoryId" clearable placeholder="选择分类"><el-option v-for="category in knowledgeCategories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item>
        <el-form-item label="资料文件">
          <el-upload drag :auto-upload="false" :limit="1" accept=".txt,.md,.pdf,.docx" :on-change="handleKnowledgeFile" :on-remove="clearKnowledgeFile"><UploadFilled /><div class="el-upload__text">拖放文件到这里，或<em>点击选择</em></div><template #tip><div class="el-upload__tip">支持 TXT、Markdown、PDF、DOCX；PDF 不超过 {{ platformConfig.pdf_max_upload_mb }} MB，其他文件不超过 {{ platformConfig.max_upload_mb }} MB；DOCX 正文图片可在线预览</div></template></el-upload>
        </el-form-item>
        <el-divider>或直接录入文本</el-divider>
        <div class="form-pair"><el-form-item label="文件名"><el-input v-model="knowledgeForm.filename" /></el-form-item><el-form-item label="格式"><el-select v-model="knowledgeForm.fileType"><el-option label="TXT" value="txt" /><el-option label="Markdown" value="md" /></el-select></el-form-item></div>
        <el-form-item label="文本正文"><el-input v-model="knowledgeForm.content" type="textarea" :rows="6" placeholder="可直接录入纯文本；正文图片只能包含在上传的原始文件中" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="knowledgeDialog = false">取消</el-button><el-button type="primary" :loading="busy" @click="uploadKnowledge">上传并索引</el-button></template>
    </el-dialog>
    <OnboardingGuide v-if="onboardingVisible" :visible="onboardingVisible" :platform-name="platformConfig.platform_name" @close="closeOnboarding" />
    <el-dialog v-model="readerDialog" class="reader-dialog" width="min(900px, 96vw)" top="3vh" destroy-on-close>
      <template #header><div class="reader-header"><span class="file-type large">{{ selectedKnowledge?.fileType?.toUpperCase() }}</span><div><h3>{{ selectedKnowledge?.title }}</h3><p>资源 #{{ selectedKnowledge?.id }} · {{ reviewingKnowledge ? auditLabel(selectedKnowledge?.auditStatus || '') : `浏览 ${selectedKnowledge?.views || 0} 次` }}</p><p v-if="reviewingKnowledge && auditDecisionNote(selectedKnowledge?.auditSource, selectedKnowledge?.auditReason)" class="muted-text">{{ auditDecisionNote(selectedKnowledge?.auditSource, selectedKnowledge?.auditReason) }}</p></div></div></template>
      <div v-if="readerError" class="reader-state" role="alert"><el-alert type="error" :closable="false" show-icon :title="readerError" /><el-button size="small" @click="retryReader">重试</el-button></div>
      <div v-if="readerLoading || readerPreviewLoading" class="reader-state" role="status"><span class="reader-spinner" aria-hidden="true"></span><span>{{ readerLoading ? '正在加载内容…' : '正在加载 PDF，可先处理其他内容…' }}</span></div>
      <PdfViewer v-if="selectedKnowledge?.fileType === 'pdf' && pdfPreviewUrl" :src="pdfPreviewUrl" :document-id="selectedKnowledge.id" />
      <article v-else-if="!readerLoading" class="knowledge-body rich-knowledge-body">
        <template v-for="(block, index) in knowledgeContentBlocks" :key="`${block.type}-${index}`">
          <img v-if="block.type === 'image'" class="knowledge-inline-image" loading="lazy" decoding="async" :src="resolveApiUrl(block.url || '')" :alt="block.text || '知识插图'" />
          <h3 v-else-if="block.type === 'heading'">{{ block.text }}</h3>
          <div v-else-if="block.type === 'table'" class="knowledge-table-wrap"><table><tbody><tr v-for="(row, rowIndex) in knowledgeTableRows(block.text)" :key="rowIndex"><td v-for="(cell, cellIndex) in row" :key="cellIndex">{{ cell }}</td></tr></tbody></table></div>
          <p v-else :class="{ 'knowledge-list-item': block.type === 'list' }">{{ block.text }}</p>
        </template>
        <el-empty v-if="!knowledgeContentBlocks.length" description="暂无可预览正文" />
      </article>
      <template #footer><el-button @click="closeReader">关闭</el-button><template v-if="reviewingKnowledge && selectedKnowledge"><el-button v-if="selectedKnowledge.auditStatus === 'PENDING'" type="danger" @click="auditKnowledge(selectedKnowledge, 'REJECTED')">驳回</el-button><el-button v-if="selectedKnowledge.auditStatus === 'PENDING' || selectedKnowledge.auditStatus === 'REJECTED'" type="success" @click="auditKnowledge(selectedKnowledge, 'APPROVED')">{{ selectedKnowledge.auditStatus === 'REJECTED' ? '重新通过' : '通过审核' }}</el-button><el-button v-else-if="selectedKnowledge.auditStatus === 'APPROVED'" type="warning" @click="updateKnowledgeStatus(selectedKnowledge, 'HIDDEN'); readerDialog = false">下架</el-button><el-button v-else-if="selectedKnowledge.auditStatus === 'HIDDEN'" type="success" @click="updateKnowledgeStatus(selectedKnowledge, 'APPROVED'); readerDialog = false">恢复</el-button></template><el-button v-else-if="selectedKnowledge?.fileUrl" type="primary" :icon="Download" @click="selectedKnowledge && downloadKnowledge(selectedKnowledge)">下载资料</el-button></template>
    </el-dialog>
    <el-dialog v-model="postReviewDialog" width="min(760px, 94vw)" top="5vh" destroy-on-close><template #header><div class="reader-header"><span class="file-type large">帖子</span><div><h3>{{ selectedReviewPost?.title }}</h3><p>帖子 #{{ selectedReviewPost?.id }} · 用户 #{{ selectedReviewPost?.userId }} · {{ postStatusLabel(selectedReviewPost?.status || '') }}</p></div></div></template><article class="knowledge-body">{{ selectedReviewPost?.content }}</article><div v-if="selectedReviewPost?.imageUrls?.length" class="post-images"><img v-for="image in selectedReviewPost.imageUrls" :key="image" loading="lazy" decoding="async" :src="resolveApiUrl(image)" alt="待审核帖子配图" /></div><template #footer><el-button :disabled="auditingPostId !== 0" @click="postReviewDialog = false">关闭</el-button><el-button v-if="selectedReviewPost?.status === 'PENDING' || selectedReviewPost?.status === 'PUBLISHED'" type="danger" :loading="auditingPostId === selectedReviewPost?.id" :disabled="auditingPostId !== 0" @click="selectedReviewPost && auditPost(selectedReviewPost, 'HIDDEN')">{{ selectedReviewPost?.status === 'PENDING' ? '驳回并隐藏' : '隐藏' }}</el-button><el-button v-if="selectedReviewPost?.status === 'PENDING' || selectedReviewPost?.status === 'HIDDEN'" type="success" :loading="auditingPostId === selectedReviewPost?.id" :disabled="auditingPostId !== 0" @click="selectedReviewPost && auditPost(selectedReviewPost, 'PUBLISHED')">{{ selectedReviewPost?.status === 'HIDDEN' ? '恢复' : '发布' }}</el-button></template></el-dialog>
    <el-dialog v-model="postDialog" :title="postDialogTitle" width="min(560px, 92vw)" destroy-on-close @closed="resetPostEditor"><el-form label-position="top"><el-form-item label="标题"><el-input v-model="postForm.title" /></el-form-item><el-form-item label="正文"><el-input v-model="postForm.content" type="textarea" :rows="6" /></el-form-item><el-form-item v-if="platformConfig.max_post_images > 0" label="帖子配图"><el-upload v-model:file-list="postImageFiles" list-type="picture-card" :auto-upload="false" :limit="platformConfig.max_post_images" accept="image/jpeg,image/png,image/gif,image/webp" :on-change="handlePostImages" :on-remove="handlePostImages"><Plus /></el-upload><div class="el-upload__tip">最多 {{ platformConfig.max_post_images }} 张，支持 JPEG、PNG、GIF、WebP，单张不超过 10 MB</div></el-form-item></el-form><template #footer><el-button @click="postDialog = false">取消</el-button><el-button v-if="!editingPostId" :loading="busy" @click="saveDraft">{{ editingDraftId ? '保存草稿' : '存为草稿' }}</el-button><el-button type="primary" :loading="busy" @click="createPost">{{ editingPostId ? '保存修改' : editingDraftId ? '发布草稿' : '发布' }}</el-button></template></el-dialog>
    <el-dialog v-model="feedbackDialog" title="提交反馈" width="min(480px, 92vw)"><el-form label-position="top"><el-form-item label="反馈类型"><el-select v-model="feedbackForm.type"><el-option label="系统问题" value="BUG" /><el-option label="产品建议" value="SUGGESTION" /><el-option label="客服咨询" value="SUPPORT" /></el-select></el-form-item><el-form-item label="问题描述"><el-input v-model="feedbackForm.content" type="textarea" :rows="5" /></el-form-item></el-form><template #footer><el-button @click="feedbackDialog = false">取消</el-button><el-button type="primary" @click="createTicket">提交</el-button></template></el-dialog>
    <el-dialog v-model="conversationDialog" title="发起私信" width="min(460px, 92vw)"><el-form label-position="top"><el-form-item label="对方完整用户名"><div class="exact-user-search"><el-input v-model="conversationUsername" placeholder="输入完整用户名" clearable @clear="conversationTargetUser = undefined; conversationTargetId = 0" @keyup.enter="resolveConversationUser" /><el-button :icon="Search" @click="resolveConversationUser">查找</el-button></div></el-form-item></el-form><div v-if="conversationTargetUser" class="exact-user-result"><strong>{{ conversationTargetUser.nickname }}</strong><span>@{{ conversationTargetUser.username }}</span></div><template #footer><el-button @click="conversationDialog = false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!conversationTargetId" @click="createConversation">开始聊天</el-button></template></el-dialog>
    <el-dialog v-model="ticketDialog" title="处理反馈工单" width="min(500px, 92vw)"><el-form label-position="top"><el-form-item label="处理状态"><el-select v-model="ticketReply.status"><el-option label="处理中" value="PROCESSING" /><el-option label="已解决" value="RESOLVED" /></el-select></el-form-item><el-form-item label="官方回复"><el-input v-model="ticketReply.reply" type="textarea" :rows="5" /></el-form-item></el-form><template #footer><el-button @click="ticketDialog = false">取消</el-button><el-button type="primary" @click="replyTicket">保存回复</el-button></template></el-dialog>
    <el-dialog v-model="faqDialog" :title="faqForm.id ? '编辑常见问题' : '新增常见问题'" width="min(500px, 92vw)"><el-form label-position="top"><el-form-item label="问题"><el-input v-model="faqForm.question" /></el-form-item><el-form-item label="答案"><el-input v-model="faqForm.answer" type="textarea" :rows="5" /></el-form-item><el-form-item label="排序"><el-input-number v-model="faqForm.sortNo" :min="0" /></el-form-item></el-form><template #footer><el-button @click="faqDialog = false">取消</el-button><el-button type="primary" @click="saveFaq">保存</el-button></template></el-dialog>
    <el-dialog v-model="governanceDialog" class="governance-dialog" title="深度内容审查" width="min(1000px, 94vw)" top="5vh">
      <div class="governance-search"><el-input v-model="governanceKeyword" :prefix-icon="Search" clearable placeholder="检索 ID、用户、标题或正文" /><span>{{ governanceResultCount }} 条结果</span></div>
      <el-tabs v-model="governanceTab">
        <el-tab-pane label="私信监管" name="messages"><el-table :data="governanceChats.items" max-height="520" @row-click="(row: ChatSession) => loadAdminChatMessages(row)"><el-table-column prop="id" label="会话" width="80" /><el-table-column prop="userAId" label="用户 A" width="90" /><el-table-column prop="userBId" label="用户 B" width="90" /><el-table-column prop="messageCount" label="消息数" width="90" /><el-table-column label="状态" width="100"><template #default="scope">{{ sessionStatusLabel(scope.row.status) }}</template></el-table-column><el-table-column prop="updatedAt" label="更新时间" min-width="170" /><el-table-column label="操作" width="220"><template #default="scope"><el-button text type="warning" @click.stop="setAdminSessionStatus(scope.row, 'RESTRICTED')">限制</el-button><el-button text type="info" @click.stop="setAdminSessionStatus(scope.row, 'ARCHIVED')">封存</el-button><el-button text type="success" @click.stop="setAdminSessionStatus(scope.row, 'ACTIVE')">恢复</el-button></template></el-table-column></el-table><div v-if="governanceChats.hasMore" class="knowledge-load-more"><el-button :loading="governanceChats.loading" @click="loadMoreGovernance">加载更多</el-button></div><div v-if="adminChatMessages.length" class="admin-message-preview"><div v-if="adminChatHasMore" class="knowledge-load-more"><el-button size="small" :loading="adminChatLoading" @click="adminChatSession && loadAdminChatMessages(adminChatSession, true)">加载更早消息</el-button></div><article v-for="message in adminChatMessages" :key="message.id"><strong>用户 {{ message.senderId }}</strong><span>{{ message.content }}</span><small>{{ formatDate(message.createdAt) }}</small></article></div><el-empty v-else description="选择会话查看消息" /></el-tab-pane>
        <el-tab-pane :label="`评论 ${metricValue('forumAdmin','comments')}`" name="comments"><el-table :data="governanceComments.items" max-height="520"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="postId" label="帖子" width="80" /><el-table-column prop="userId" label="用户" width="80" /><el-table-column prop="content" label="评论内容" min-width="260" show-overflow-tooltip /><el-table-column label="状态" width="90"><template #default="scope">{{ scope.row.status === 'HIDDEN' ? '已隐藏' : '可见' }}</template></el-table-column><el-table-column label="操作" width="240"><template #default="scope"><el-button text type="primary" @click="previewGovernance('评论', `评论 #${scope.row.id}`, scope.row.content)">预览</el-button><el-button text :type="scope.row.status === 'HIDDEN' ? 'success' : 'warning'" @click="setAdminCommentStatus(scope.row, scope.row.status === 'HIDDEN' ? 'VISIBLE' : 'HIDDEN')">{{ scope.row.status === 'HIDDEN' ? '恢复' : '隐藏' }}</el-button><el-button text type="danger" @click="deleteAdminComment(scope.row)">删除</el-button></template></el-table-column></el-table><div v-if="governanceComments.hasMore" class="knowledge-load-more"><el-button :loading="governanceComments.loading" @click="loadMoreGovernance">加载更多</el-button></div></el-tab-pane>
        <el-tab-pane :label="`草稿 ${metricValue('forumAdmin','draftsTracked')}`" name="drafts"><div class="page-toolbar"><span>草稿仅管理员可进行治理操作</span><el-button type="danger" plain @click="cleanupExpiredDrafts">清理 {{ aiConfig.draft_retention_days }} 天前草稿</el-button></div><el-table :data="governanceDrafts.items" max-height="470"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="userId" label="用户" width="90" /><el-table-column prop="title" label="标题" min-width="200" /><el-table-column prop="content" label="内容" min-width="280" show-overflow-tooltip /><el-table-column label="操作" width="150"><template #default="scope"><el-button text type="primary" @click="previewGovernance('草稿', scope.row.title, scope.row.content)">预览</el-button><el-button text type="danger" @click="deleteAdminDraft(scope.row)">删除</el-button></template></el-table-column></el-table><div v-if="governanceDrafts.hasMore" class="knowledge-load-more"><el-button :loading="governanceDrafts.loading" @click="loadMoreGovernance">加载更多</el-button></div></el-tab-pane>
        <el-tab-pane :label="`AI 会话 ${Number(aiOverview.session_count || 0)}`" name="sessions"><el-table :data="governanceAiSessions.items" max-height="520"><el-table-column prop="id" label="ID" width="80" /><el-table-column prop="user_id" label="用户" width="100" /><el-table-column prop="title" label="会话标题" min-width="280" /><el-table-column prop="created_at" label="创建时间" min-width="180" /><el-table-column label="操作" width="90"><template #default="scope"><el-button text type="primary" @click="previewAiAuditSession(scope.row)">预览</el-button></template></el-table-column></el-table><div v-if="governanceAiSessions.hasMore" class="knowledge-load-more"><el-button :loading="governanceAiSessions.loading" @click="loadMoreGovernance">加载更多</el-button></div></el-tab-pane>
        <el-tab-pane :label="`知识切片 ${Number(aiOverview.chunk_count || 0)}`" name="chunks"><el-table :data="governanceChunks.items" max-height="520"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="file_id" label="文件" width="90" /><el-table-column prop="title" label="标题" min-width="180" /><el-table-column prop="content" label="切片内容" min-width="300" show-overflow-tooltip /><el-table-column label="操作" width="90"><template #default="scope"><el-button text type="primary" @click="previewGovernance('知识切片', scope.row.title, scope.row.content)">预览</el-button></template></el-table-column></el-table><div v-if="governanceChunks.hasMore" class="knowledge-load-more"><el-button :loading="governanceChunks.loading" @click="loadMoreGovernance">加载更多</el-button></div></el-tab-pane>
      </el-tabs>
      <template #footer><el-button :icon="Refresh" @click="loadGovernance">刷新数据</el-button><el-button type="primary" @click="governanceDialog = false">完成</el-button></template>
    </el-dialog>
    <el-dialog v-model="governancePreviewDialog" :title="governancePreviewKind" width="min(760px, 94vw)" top="6vh" destroy-on-close><div class="reader-header"><span class="file-type large">VIEW</span><div><h3>{{ governancePreviewTitle }}</h3></div></div><article class="knowledge-body governance-preview-body">{{ governancePreviewContent }}</article><template #footer><el-button type="primary" @click="governancePreviewDialog=false">关闭</el-button></template></el-dialog>
    <el-dialog v-model="notificationsDialog" :title="notificationsDialogTitle" width="min(620px, 92vw)"><div class="notification-list"><article v-for="notice in notifications" :key="notice.id"><span v-if="noticeActor(notice)?.avatarUrl" class="notification-avatar"><img :src="resolveApiUrl(noticeActor(notice)?.avatarUrl || '')" :alt="`${noticeActor(notice)?.nickname} 的头像`" loading="lazy" /></span><span v-else-if="noticeActor(notice)" class="notification-avatar is-initial">{{ (noticeActor(notice)?.nickname || '').slice(0, 1) }}</span><span v-else class="notification-symbol"><Bell /></span><div><strong>{{ notice.title }}</strong><p v-if="noticeActor(notice)" class="notification-actor">{{ noticeActor(notice)?.nickname }} <small>@{{ noticeActor(notice)?.username }}</small></p><p>{{ notice.content }}</p><small>{{ notificationTypeLabel(notice.type) }} · {{ formatDate(notice.createdAt || '') }}</small></div><div class="notification-actions"><el-button v-if="noticeDestination(notice.target)" text type="primary" @click="openNotice(notice)">查看</el-button><el-button v-if="!notice.read" text type="primary" @click="markNotificationRead(notice)">标为已读</el-button><el-tag v-else type="info" size="small">已读</el-tag></div></article><div v-if="notificationHasMore || notificationLoading || notificationError" class="notification-more"><el-button text type="primary" :loading="notificationLoading" @click="loadMoreNotifications">{{ notificationLoading ? '正在加载通知' : notificationError ? `${notificationError}，点击重试` : '加载更多通知' }}</el-button></div><el-empty v-if="!notifications.length" description="暂无通知" /></div><template #footer><el-button :icon="Refresh" @click="openNotifications">刷新</el-button><el-button :disabled="!notificationUnread" @click="markAllNotificationsRead">全部已读</el-button><el-button type="primary" @click="notificationsDialog = false">关闭</el-button></template></el-dialog>
    <el-dialog v-model="categoryDialog" title="知识分类管理" width="min(520px, 92vw)"><el-form inline @submit.prevent="saveCategory"><el-form-item><el-input v-model="categoryForm.name" placeholder="分类名称" /></el-form-item><el-form-item><el-input-number v-model="categoryForm.sortNo" :min="0" /></el-form-item><el-button type="primary" @click="saveCategory">{{ categoryForm.id ? '保存修改' : '新增分类' }}</el-button></el-form><div class="category-admin-list"><article v-for="category in knowledgeCategories" :key="category.id"><span>{{ category.name }}</span><small>排序 {{ category.sortNo || 0 }}</small><div><el-button text @click="editCategory(category)">编辑</el-button><el-button text type="danger" @click="removeCategory(category)">删除</el-button></div></article></div><template #footer><el-button @click="categoryDialog=false">关闭</el-button></template></el-dialog>
    <el-dialog v-model="knowledgeMetadataDialog" title="编辑知识资源" width="min(520px, 92vw)"><el-form label-position="top"><el-form-item label="标题"><el-input v-model="knowledgeMetadataForm.title" maxlength="255" /></el-form-item><el-form-item label="分类"><el-select v-model="knowledgeMetadataForm.categoryId" clearable><el-option v-for="category in knowledgeCategories" :key="category.id" :label="category.name" :value="category.id" /></el-select></el-form-item><el-form-item label="状态"><el-select v-model="knowledgeMetadataForm.auditStatus"><el-option label="待审核" value="PENDING" /><el-option label="已通过" value="APPROVED" /><el-option label="已驳回" value="REJECTED" /><el-option label="已下架" value="HIDDEN" /></el-select></el-form-item></el-form><template #footer><el-button @click="knowledgeMetadataDialog=false">取消</el-button><el-button type="primary" @click="saveKnowledgeMetadata">保存</el-button></template></el-dialog>
    <el-dialog v-model="userGovernanceDialog" title="用户资料与治理" width="min(880px, 94vw)" top="4vh"><el-tabs><el-tab-pane label="账号设置"><el-form label-position="top" class="governance-user-form"><div class="form-pair"><el-form-item label="昵称"><el-input v-model="userGovernanceForm.nickname" maxlength="64" /></el-form-item><el-form-item label="账号状态"><el-select v-model="userGovernanceForm.status" :disabled="governingSelf"><el-option label="正常" value="ACTIVE" /><el-option label="禁用" value="DISABLED" /><el-option label="已删除" value="DELETED" /></el-select></el-form-item></div><div class="form-pair"><el-form-item label="角色"><el-select v-model="userGovernanceForm.role" :disabled="governingSelf || !superAdmin"><el-option label="社区用户" value="USER" /><el-option label="平台管理员" value="ADMIN" /></el-select><small v-if="!superAdmin" class="muted-text">只有超级管理员可以任命或撤销管理员</small></el-form-item><el-form-item label="发帖策略"><el-select v-model="userGovernanceForm.publishPolicy"><el-option label="标准审核" value="STANDARD" /><el-option label="强制预审" value="PRE_REVIEW" /><el-option label="禁止发布" value="BLOCKED" /></el-select></el-form-item></div><p v-if="governingSelf" class="governance-self-note">这是你自己的账号：不能在这里停用、删除或修改角色，以免失去管理权限。</p><el-form-item label="允许私信"><el-switch v-model="userGovernanceForm.messagingEnabled" /></el-form-item><el-form-item label="绑定邮箱"><div class="governance-email"><template v-if="userGovernanceForm.email"><span :class="{ 'is-removed': userGovernanceForm.removeEmail }">{{ userGovernanceForm.email }}</span><el-tag size="small" effect="plain" :type="userGovernanceForm.emailVerified ? 'success' : 'info'">{{ userGovernanceForm.emailVerified ? '已验证' : '未验证' }}</el-tag><el-checkbox v-model="userGovernanceForm.removeEmail">保存时解除绑定</el-checkbox></template><span v-else class="muted-text">未绑定</span></div></el-form-item><el-form-item label="头像地址"><el-input v-model="userGovernanceForm.avatarUrl" /></el-form-item><el-form-item label="个人签名"><el-input v-model="userGovernanceForm.signature" type="textarea" :rows="3" maxlength="500" /></el-form-item><el-form-item label="重置密码"><el-input v-model="userGovernanceForm.resetPassword" type="password" show-password placeholder="留空表示不修改，至少 8 位" /></el-form-item></el-form></el-tab-pane><el-tab-pane label="个人内容"><div class="governance-user-summary"><span>知识资源 <strong>{{ adminUserKnowledge.length }}</strong></span><span>帖子 <strong>{{ adminUserPosts.length }}</strong></span><span>草稿 <strong>{{ adminUserDrafts.length }}</strong></span><span>行为足迹 <strong>{{ adminUserBehaviors.length }}</strong></span></div><el-table :data="adminUserKnowledge" max-height="220"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="知识资源" min-width="220" /><el-table-column label="状态" width="100"><template #default="scope">{{ auditLabel(scope.row.auditStatus) }}</template></el-table-column></el-table><el-table :data="adminUserPosts" max-height="220"><el-table-column prop="id" label="ID" width="70" /><el-table-column prop="title" label="帖子" min-width="220" /><el-table-column label="状态" width="100"><template #default="scope">{{ postStatusLabel(scope.row.status) }}</template></el-table-column></el-table><div class="activity-list compact"><article v-for="item in adminUserBehaviors.slice(0,20)" :key="item.id"><span class="event-dot"></span><div><strong>{{ behaviorActionLabel(item.action) }} · {{ behaviorTargetLabel(item.targetType) }}</strong><small>{{ formatDate(item.createdAt) }}</small></div></article><el-empty v-if="!adminUserBehaviors.length" description="暂无行为足迹" /></div></el-tab-pane></el-tabs><template #footer><el-button class="governance-history" :icon="Notebook" text @click="openUserAudit">查看操作记录</el-button><el-button @click="userGovernanceDialog=false">取消</el-button><el-button type="primary" @click="saveUserGovernance">保存治理设置</el-button></template></el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, markRaw, onBeforeUnmount, onMounted, nextTick, ref, watch } from 'vue';
import type { UploadFile, UploadRawFile, UploadUserFile } from 'element-plus';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { ElMessageBox } from 'element-plus/es/components/message-box/index.mjs';
import { ArrowLeft, ArrowRight, Bell, ChatDotRound, ChatLineRound, Close, CollectionTag, DataAnalysis, Delete, Document, Download, Edit, EditPen, Files, House, Loading, MagicStick, Menu, Message, MoreFilled, Notebook, Plus, Promotion, Reading, Refresh, Search, Setting, Star, SwitchButton, Tickets, Upload, UploadFilled, User, UserFilled, View, Warning } from '@element-plus/icons-vue';
import { detailPath, linkedCommentFrom, noticeDestination, type NoticeTarget } from './utils/noticeTargets';
import { knowledgeCache } from './utils/knowledgeCache';
import { markOnboardingSeen, shouldShowOnboarding } from './utils/onboarding';
import { uploadSizeProblem } from './utils/uploadLimits';
import { parseStatusLabel, parseStatusTone } from './utils/parseStatus';
import { auditDecisionNote, auditSourceLabel, auditSourceTone } from './utils/auditSource';
import { reviewCounters, reviewVerdict, type ReviewHealth } from './utils/reviewHealth';
import { auditLabel, formatDate, postStatusLabel, publishPolicyLabel, reportStatusLabel, roleLabel, sessionStatusLabel } from './utils/statusLabels';
import { pendingChanges, profileAuditChanges, profileAuditStatusLabel, profileAuditStatusType, type ProfileAuditState, type ProfileChange } from './utils/profileAudit';
import AdminUsersView from './components/AdminUsersView.vue';
import { clearLegacyAuthToken, deleteData, downloadData, getData, getLegacyAuthToken, getStoredValue, isSessionExpiredError, onSessionExpired, postData, postFormData, putData, removeStoredValue, resolveApiUrl, setStoredValue, toUserMessage } from './api/client';
// Loaded when first shown: most visits never open a detail page or a PDF.
const CommunityDetailPage = defineAsyncComponent(() => import('./components/CommunityDetailPage.vue'));
const KnowledgeDetailPage = defineAsyncComponent(() => import('./components/KnowledgeDetailPage.vue'));
const PdfViewer = defineAsyncComponent(() => import('./components/PdfViewer.vue'));
const EmailBinding = defineAsyncComponent(() => import('./components/EmailBinding.vue'));
const AdminAuditLog = defineAsyncComponent(() => import('./components/AdminAuditLog.vue'));
const OnboardingGuide = defineAsyncComponent(() => import('./components/OnboardingGuide.vue'));

type KnowledgeFile = { id:number; userId:number; categoryId?:number; title:string; fileType:string; auditStatus:string; parseStatus?:string; auditSource?:string; auditReason?:string; fileUrl?:string; content?:string; contentBlocks?:KnowledgeContentBlock[]; imageUrls?:string[]; coverUrl?:string; views?:number; downloads?:number; likes?:number; liked?:boolean; collected?:boolean; createdAt?:string };
type KnowledgeLikeResult = { fileId:number; liked:boolean; likes:number };
type KnowledgeCollectResult = { userId:number; fileId:number; collected:boolean };
type KnowledgeRanking = { rank:number; userId:number; uploads:number; views:number; downloads:number; violations:number; score:number };
type KnowledgeContentBlock = { type:'image'|'heading'|'list'|'paragraph'|'table'; text?:string; url?:string };
type Post = { id:number; userId:number; title:string; content:string; status:string; auditSource?:string; auditReason?:string; imageUrls?:string[]; likes?:number; liked?:boolean; collected?:boolean; commentCount?:number; createdAt?:string };
type PostLikeResult = { postId:number; liked:boolean; created:boolean; likes:number };
type PostCollectResult = { postId:number; collected:boolean };
type Ticket = { id:number; userId:number; type:string; content:string; status:string; reply?:string; assigneeUserId?:number; assignedAt?:string; closedAt?:string; createdAt?:string; updatedAt?:string };
type UserRecord = { id:number; username:string; nickname:string; avatarUrl?:string; signature?:string; status:string; role:string; publishPolicy?:string; messagingEnabled?:boolean; email?:string|null; emailVerified?:boolean; profileAudit?:ProfileAuditState|null };
type ChatMessage = { id:number; sessionId:number; senderId:number; content:string; status:string; createdAt:string };
// What an open conversation missed since it was loaded (GET /message/sync).
type MessageSync = { sessionMissing:boolean; sessionStatus?:string; messages?:ChatMessage[]; hasMoreMessages?:boolean; removedMessageIds?:number[]; clearedThroughId?:number|null; removalCursor?:number; hasMoreRemovals?:boolean };
type ChatSession = { id:number; userAId:number; userBId:number; otherUserId:number; status:string; updatedAt:string; lastMessage:string; messageCount?:number };
type Notice = { id:number; type?:string; title:string; content:string; read:boolean; createdAt?:string; target?:NoticeTarget|null; actorUserId?:number|null };
type Draft = { id:number; title:string; content:string; userId:number; imageUrls?:string[]; updatedAt?:string };
type Report = { id:number; fileId?:number; targetUserId?:number; reason:string; status:string };
type Faq = { id:number; question:string; answer:string; sortNo:number };
type EventRecord = { id:number; type:string; aggregateId:string; status:string; createdAt:string };
type AiSession = { id:number; user_id?:number; title:string; created_at:string };
type Comment = { id:number; postId?:number; userId:number; parentId?:number; rootId?:number; content:string; status?:string; placeholder?:boolean };
type CommentThreadPage = { items:Comment[]; nextCursor:number|null; hasMore:boolean; total:number };
type NotificationPage = { items:Notice[]; nextCursor:number|null; hasMore:boolean; unread:number };
type KnowledgePage = { items:KnowledgeFile[]; nextCursor:number|null; hasMore:boolean; total:number };
type KnowledgeCategoryCounts = { total:number; counts:{ categoryId:number; count:number }[] };
type FeedPage = { items:Post[]; nextCursor:number|null; hasMore:boolean };
type FeedMode = 'all'|'following'|'mine'|'author';
type BehaviorRecord = { id:number; action:string; targetType:string; targetId:number; createdAt:string };
type AiChunk = { id:number; file_id:number; title:string; content:string; created_at:string };
type AiReference = { id:number; file_id:number; title:string; content:string; score?:number };
type AiMessageView = { role:'user'|'assistant'; content:string; references?:AiReference[] };
type NavigationItem = { key:string; label:string; icon:ReturnType<typeof markRaw>; badge?:number };
type DetailRoute = { kind:'knowledge'|'community'; id:number };
type AuthResult = { role:string; user:UserRecord };
type RuntimeModeItem = { key:string; label:string; mode:string; detail:string; available:boolean; healthy?:boolean; businessData?:boolean };

// The session itself is an httpOnly cookie; the stored id only says whether one is worth checking on load.
const authenticated = ref(Boolean(getStoredValue('ai-knowledge-user-id') || getLegacyAuthToken()));
const busy = ref(false); const aiBusy = ref(false); const aiIndexBusy = ref(false); const mobileMenuOpen = ref(false); const sidebarCollapsed = ref(false);
const mobileAiHistoryOpen = ref(false);
const username = ref(getStoredValue('ai-knowledge-username'));
const displayName = ref(getStoredValue('ai-knowledge-name',username.value || '用户'));
const avatarUrl = ref(getStoredValue('ai-knowledge-avatar'));
const role = ref(getStoredValue('ai-knowledge-role','USER'));
/** Appoints administrators and owns the AI upstream settings; an ordinary administrator keeps the rest. */
const superAdmin = ref(getStoredValue('ai-knowledge-super-admin','') === '1');
const currentUserId = ref(Number(getStoredValue('ai-knowledge-user-id','1')));
const portal = ref<'client'|'admin'>(role.value === 'ADMIN' ? 'admin' : 'client');
const activeView = ref(portal.value === 'admin' ? 'dashboard' : 'home');
const globalSearch = ref(''); const knowledgeKeyword = ref(''); const knowledgeType = ref(''); const knowledgeCategoryId = ref(0);
const adminModerationKeyword = ref(''); const adminModerationStatus = ref(''); const adminTicketKeyword = ref(''); const adminTicketStatus = ref('');
const ADMIN_TICKET_PAGE_SIZE = 20;
const adminTicketTotal = ref(0); const adminTicketCursor = ref<number|string|null>(null); const adminTicketHasMore = ref(false); const adminTicketLoading = ref(false); let adminTicketToken = 0;
const assignableAdmins = ref<UserRecord[]>([]);
const adminConfigSnapshot = ref('');
const viewLoading = ref(false); const viewError = ref('');
const feedMode = ref<'all'|'following'|'mine'|'author'>('all'); const authorFilterUserId = ref(0); const moderationTab = ref('knowledge'); const governanceTab = ref('comments');
const governanceKeyword = ref('');
const profileToolTab = ref('activity');
const onboardingVisible = ref(false);
const knowledgeDialog = ref(false); const readerDialog = ref(false); const postDialog = ref(false); const postReviewDialog = ref(false); const feedbackDialog = ref(false); const ticketDialog = ref(false); const faqDialog = ref(false); const categoryDialog = ref(false); const registerDialog = ref(false); const governanceDialog = ref(false); const notificationsDialog = ref(false); const conversationDialog = ref(false); const knowledgeMetadataDialog = ref(false); const userGovernanceDialog = ref(false);

const loginForm = ref({ username:'', password:'', captchaId:'', captchaAnswer:'' });
const registerForm = ref({ username:'', nickname:'', password:'', confirmPassword:'', captchaId:'', captchaAnswer:'' });
const forgotDialog = ref(false); const forgotStep = ref<'request'|'complete'>('request');
const forgotForm = ref({ username:'', contact:'', code:'', newPassword:'', confirmPassword:'', captchaId:'', captchaAnswer:'' });
const captchaImage = ref(''); const captchaLoading = ref(false); const captchaCooldownRemaining = ref(0); const captchaExpiresAt = ref(0); let captchaCooldownTimer: ReturnType<typeof setInterval> | undefined; let captchaExpiryTimer: ReturnType<typeof setTimeout> | undefined; let captchaRefreshPending = false;
const profileForm = ref({ userId:currentUserId.value, nickname:displayName.value, avatarUrl:'', signature:'' });
const profileAudit = ref<ProfileAuditState|null>(null);
const profileAuditPending = computed(()=>profileAudit.value?.status==='PENDING'?pendingChanges({nickname:displayName.value,signature:profileForm.value.signature},profileAudit.value):[]);
const passwordForm = ref({ currentPassword:'', newPassword:'' });
const knowledgeForm = ref({ userId:currentUserId.value, title:'', filename:'knowledge.txt', fileType:'txt', content:'', fileUrl:'', categoryId:null as number|null });
type KnowledgeCategory = { id:number; name:string; parentId?:number; sortNo?:number };
const selectedKnowledgeFile = ref<File>();
const postForm = ref({ userId:currentUserId.value, title:'', content:'' });
const selectedPostImages = ref<UploadRawFile[]>([]);
const postImageFiles = ref<UploadUserFile[]>([]);
const auditingPostId = ref(0);
const messageForm = ref({ sessionId:0, senderId:currentUserId.value, content:'' });
const messageSending = ref(false); const messageListRef = ref<HTMLElement>();
const MESSAGE_PAGE_SIZE = 30; const MESSAGE_POLL_INTERVAL = 9000; const MESSAGE_BOTTOM_THRESHOLD = 80;
const messageHasOlder = ref(false); const messageHistoryLoading = ref(false); const messageRefreshing = ref(false); const messageUnseenCount = ref(0);
const pageVisible = ref(typeof document === 'undefined' || document.visibilityState !== 'hidden');
let messageRemovalCursor = 0; let loadedMessageSessionId = 0; let messagePollTimer: number | undefined; let messageFetchInFlight = false;
const conversationTargetId = ref(0);
const feedbackForm = ref({ userId:currentUserId.value, type:'BUG', content:'' });
const ticketReply = ref({ ticketId:0, status:'PROCESSING', reply:'' });
const faqForm = ref({ id:0, question:'', answer:'', sortNo:10, enabled:1 });
const aiConfig = ref({ platform_name:'知汇', platform_notice:'', registration_enabled:true, ai_chat_enabled:true, knowledge_upload_enabled:true, user_ranking_enabled:true, community_enabled:true, comments_enabled:true, private_messages_enabled:true, notifications_enabled:true, feedback_enabled:true, post_audit_required:true, profile_audit_required:false,ai_audit_enabled:false,ai_audit_approve_confidence:0.9,ai_audit_reject_confidence:0.85,ai_audit_sample_percent:10, default_publish_policy:'STANDARD', max_upload_mb:25, pdf_max_upload_mb:200, max_post_images:9, max_comment_length:2000, max_message_length:2000, draft_retention_days:30, data_source_scope:'all-approved', match_limit:5, compliance_rule:'answer-with-references', provider:'local', model:'local-rag', base_url:'', request_url:'', temperature:0.2, selected_file_ids:[] as number[] });
const analyticsDays = ref(30);
type AnalyticsTrendPoint = { date: string; count: number };
type KnowledgeAnalytics = { days: number; trendDays: number; files: number; views: number; downloads: number; likes: number; trend: AnalyticsTrendPoint[]; top: { id: number; title: string; views: number; downloads: number; likes: number }[] };
type ForumAnalytics = { days: number; trendDays: number; posts: number; likes: number; trend: AnalyticsTrendPoint[]; top: { id: number; userId: number; title: string; likes: number }[] };
type TicketAnalytics = { days: number; trendDays: number; total: number; bug: number; suggestion: number; support: number; resolved: number; trend: AnalyticsTrendPoint[] };
const knowledgeAnalytics = ref<KnowledgeAnalytics>(); const forumAnalytics = ref<ForumAnalytics>(); const ticketAnalytics = ref<TicketAnalytics>(); let analyticsRequestToken = 0;
const aiSourceOptions = ref<{id:number;title:string}[]>([]); const aiSourceSearching = ref(false); let aiSourceToken = 0; const aiIndexProgress = ref('');
const platformConfig = ref({ platform_name:'知汇', platform_notice:'', registration_enabled:true, ai_chat_enabled:true, knowledge_upload_enabled:true, user_ranking_enabled:true, community_enabled:true, comments_enabled:true, private_messages_enabled:true, notifications_enabled:true, feedback_enabled:true, post_audit_required:true, profile_audit_required:false,ai_audit_enabled:false,ai_audit_approve_confidence:0.9,ai_audit_reject_confidence:0.85,ai_audit_sample_percent:10, default_publish_policy:'STANDARD', max_upload_mb:25, pdf_max_upload_mb:200, max_post_images:9, max_comment_length:2000, max_message_length:2000, draft_retention_days:30 });
const relationForm = ref({ username:'', targetUserId:0 });
const categoryForm = ref({ id:0, name:'', sortNo:10 });
const knowledgeMetadataForm = ref({ fileId:0, title:'', categoryId:null as number|null, auditStatus:'PENDING' });
const auditRefreshKey = ref(0); const auditSubjectUserId = ref<number|null>(null); const auditSubjectLabel = ref(''); let keepAuditSubject = false;
const userGovernanceForm = ref({ userId:0, nickname:'', avatarUrl:'', signature:'', status:'ACTIVE', role:'USER', publishPolicy:'STANDARD', messagingEnabled:true, resetPassword:'', email:'' as string|null, emailVerified:false, removeEmail:false });

const FEED_PAGE_SIZE = 10; const feedCursor = ref<number|null>(null); const feedHasMore = ref(false); const feedLoadingMore = ref(false); const feedLoadError = ref(''); const feedSentinelRef = ref<HTMLElement>();
let feedRequestToken = 0; let feedPageMode: FeedMode = 'all'; let feedObserver: IntersectionObserver | undefined;
const KNOWLEDGE_PAGE_SIZE = 12; const knowledgeTotal = ref(0); const knowledgeCursor = ref<number|null>(null);
const knowledgeHasMore = ref(false); const knowledgeLoadingMore = ref(false); const knowledgeLoadError = ref(''); const knowledgeSearchMode = ref(false);
const knowledgeCategoryCounts = ref<Record<number, number>>({}); const knowledgeSentinelRef = ref<HTMLElement>();
let knowledgeRequestToken = 0; let knowledgeObserver: IntersectionObserver | undefined;
const knowledgeFiles = ref<KnowledgeFile[]>([]); const feedPosts = ref<Post[]>([]); const communityPostCount = ref(0); const collectedPosts = ref<Post[]>([]); const tickets = ref<Ticket[]>([]); const adminTickets = ref<Ticket[]>([]);
const NOTIFICATION_PAGE_SIZE = 20; const notificationUnread = ref(0); const notificationCursor = ref<number|null>(null);
const notificationHasMore = ref(false); const notificationLoading = ref(false); const notificationError = ref('');
const messages = ref<ChatMessage[]>([]); const notifications = ref<Notice[]>([]); const drafts = ref<Draft[]>([]); const sessions = ref<ChatSession[]>([]); const adminChatMessages = ref<ChatMessage[]>([]); const adminChatSession = ref<ChatSession>(); const adminChatCursor = ref<number|null>(null); const adminChatHasMore = ref(false); const adminChatLoading = ref(false); let adminChatToken = 0;
const faqs = ref<Faq[]>([]); const adminEvents = ref<EventRecord[]>([]);
const blockedUserIds = ref<number[]>([]); const behaviors = ref<BehaviorRecord[]>([]);
const adminUserKnowledge = ref<KnowledgeFile[]>([]); const adminUserPosts = ref<Post[]>([]); const adminUserDrafts = ref<Draft[]>([]); const adminUserBehaviors = ref<BehaviorRecord[]>([]);
const MY_KNOWLEDGE_PAGE_SIZE = 12; const myKnowledgeTotal = ref(0); const myKnowledgeCursor = ref<number|null>(null);
const myKnowledgeHasMore = ref(false); const myKnowledgeLoading = ref(false); const myKnowledgeError = ref(''); let myKnowledgeToken = 0;
const myKnowledge = ref<KnowledgeFile[]>([]); const knowledgeActivityType = ref('UPLOADED'); const knowledgeCategories = ref<KnowledgeCategory[]>([]); const knowledgeRanking = ref<KnowledgeRanking[]>([]);
const adminOverview = ref<Record<string, Record<string, unknown>>>({}); const aiOverview = ref<Record<string, unknown>>({});
/** Review that is quietly doing nothing looks like review that works, so the counters are shown. */
const reviewHealth = computed<ReviewHealth>(() => (aiOverview.value.reviewHealth as ReviewHealth) || {});
const runtimeModes = ref<RuntimeModeItem[]>([]);
const followData = ref<{ followedUserIds?:number[]; followerUserIds?:number[] }>({});
const relationTargetUser = ref<UserRecord>(); const conversationUsername = ref(''); const conversationTargetUser = ref<UserRecord>(); const systemHealth = ref<Record<string,boolean>>({});
const userSummaries = ref<Record<number, UserRecord>>({}); const resolvedUserIds = new Set<number>();
const aiQuestion = ref(''); const aiMessages = ref<AiMessageView[]>([]); const aiSessions = ref<AiSession[]>([]); const aiSessionId = ref<number>();
const aiHistoryKeyword = ref('');
const editingPostId = ref(0); const editingDraftId = ref(0);
const selectedKnowledge = ref<KnowledgeFile>();
const pdfPreviewUrl = ref('');
const detailRoute = ref<DetailRoute>();
const detailLoading = ref(false); const detailError = ref('');
const detailKnowledge = ref<KnowledgeFile>(); const detailKnowledgeBlocks = ref<KnowledgeContentBlock[]>([]); const detailPdfPreviewUrl = ref('');
const detailPost = ref<Post>(); const detailComments = ref<Comment[]>([]); const detailFocusCommentId = ref(0);
const COMMENT_THREAD_PAGE_SIZE = 10; const detailCommentTotal = ref(0); const detailCommentCursor = ref<number|null>(null);
const detailCommentsHasMore = ref(false); const detailCommentsLoading = ref(false); const detailCommentsError = ref('');
const selectedReviewPost = ref<Post>(); const reviewingKnowledge = ref(false);
const readerLoading = ref(false); const readerPreviewLoading = ref(false); const readerError = ref('');
let readerToken = 0;
const governancePreviewDialog = ref(false); const governancePreviewKind = ref('内容预览'); const governancePreviewTitle = ref(''); const governancePreviewContent = ref('');
const aiPrompts = ['平台支持哪些知识格式？','如何使用全文搜索？','社区有哪些核心功能？'];

const clientNavigation: NavigationItem[] = [{key:'home',label:'工作台',icon:markRaw(House)},{key:'knowledge',label:'知识库',icon:markRaw(Files)},{key:'forum',label:'社区论坛',icon:markRaw(ChatDotRound)},{key:'square',label:'关注广场',icon:markRaw(CollectionTag)},{key:'messages',label:'消息中心',icon:markRaw(Message)},{key:'notifications',label:'通知中心',icon:markRaw(Bell),badge:0},{key:'ai',label:'AI 问答',icon:markRaw(MagicStick)},{key:'profile',label:'个人中心',icon:markRaw(User)}];
const adminNavigation: NavigationItem[] = [{key:'dashboard',label:'运营概览',icon:markRaw(House)},{key:'moderation',label:'内容审核',icon:markRaw(CollectionTag)},{key:'governance',label:'深度审查',icon:markRaw(View)},{key:'analytics',label:'平台数据统计',icon:markRaw(DataAnalysis)},{key:'users',label:'用户管理',icon:markRaw(UserFilled)},{key:'tickets',label:'工单与常见问题',icon:markRaw(Tickets)},{key:'audit',label:'操作记录',icon:markRaw(Notebook)},{key:'system',label:'AI 与系统',icon:markRaw(Setting)}];
const registrationUsernameValid = computed(()=>/^[A-Za-z0-9_-]{3,32}$/.test(registerForm.value.username.trim()));
const registrationUsernameHint = computed(()=>!registerForm.value.username?'用户名用于登录，注册后不可修改':registrationUsernameValid.value?'用户名格式正确':'仅支持 3-32 位字母、数字、下划线或连字符');
const registrationPasswordChecks = computed(()=>{
  const password=registerForm.value.password;
  return {length:password.length>=8&&password.length<=128,letter:/[A-Za-z]/.test(password),number:/\d/.test(password),noWhitespace:password.length>0&&!/\s/.test(password),differsFromUsername:password.length>0&&password.toLowerCase()!==registerForm.value.username.trim().toLowerCase()};
});
const registrationPasswordsMatch = computed(()=>Boolean(registerForm.value.confirmPassword)&&registerForm.value.password===registerForm.value.confirmPassword);
const registrationPasswordStrength = computed(()=>{
  const password=registerForm.value.password;let score=0;
  if(password.length>=8)score++;if(password.length>=12)score++;if(/[A-Z]/.test(password)&&/[a-z]/.test(password))score++;if(/\d/.test(password))score++;if(/[^A-Za-z0-9\s]/.test(password))score++;
  if(score>=4)return{label:'强',level:'strong',percent:100};if(score>=3)return{label:'中',level:'medium',percent:66};return{label:'弱',level:'weak',percent:33};
});
const currentNavigation = computed(() => portal.value === 'admin' ? adminWorkspaceNavigation.value : clientNavigation
  .filter(item => platformConfig.value.community_enabled || !['forum','square'].includes(item.key))
  .filter(item => platformConfig.value.private_messages_enabled || item.key !== 'messages')
  .filter(item => platformConfig.value.notifications_enabled || item.key !== 'notifications')
  .filter(item => platformConfig.value.ai_chat_enabled || item.key !== 'ai')
  .map(item => item.key === 'notifications' ? {...item,badge:notificationUnread.value || 0} : item));
const mobileNavigation = computed(() => {
  const keys = portal.value === 'admin'
    ? ['dashboard','moderation','analytics','users','system']
    : ['home','knowledge','forum','messages','ai'];
  return keys.flatMap(key => {
    const item = currentNavigation.value.find(candidate => candidate.key === key);
    return item ? [item] : [];
  });
});
const notificationsDialogTitle = computed(() => notificationUnread.value ? `通知中心（${notificationUnread.value} 条未读）` : '通知中心');
const currentTitle = computed(() => detailKnowledge.value?.title || detailPost.value?.title || currentNavigation.value.find(item => item.key === activeView.value)?.label || '工作台');
const greeting = computed(() => { const hour = new Date().getHours(); return hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'; });
// Paged results are already filtered by the server; full-text search results keep the client-side filters.
const filteredKnowledge = computed(() => knowledgeSearchMode.value
  ? knowledgeFiles.value.filter(file => (!knowledgeType.value || file.fileType === knowledgeType.value) && (!knowledgeCategoryId.value || file.categoryId === knowledgeCategoryId.value))
  : knowledgeFiles.value);


const adminMetrics = computed(() => [{label:'注册用户',value:metricValue('userAdmin','totalUsers'),hint:`${metricValue('userAdmin','activeUsers')} 个正常账号`,color:'green',icon:markRaw(UserFilled)},{label:'知识资源',value:metricValue('knowledgeAdmin','totalFiles'),hint:`${metricValue('knowledgeAdmin','pendingAudit')} 个待审核`,color:'blue',icon:markRaw(Files)},{label:'社区帖子',value:metricValue('forumAdmin','publishedPosts'),hint:'全站内容产出',color:'amber',icon:markRaw(ChatDotRound)},{label:'反馈工单',value:metricValue('feedbackAdmin','tickets'),hint:`${metricValue('feedbackAdmin','pendingTickets')} 个待处理`,color:'red',icon:markRaw(Tickets)}]);
const healthItems = computed(() => [{label:'网关与用户服务',ok:systemHealth.value.user},{label:'知识与全文检索',ok:systemHealth.value.knowledge},{label:'社区与消息服务',ok:systemHealth.value.community&&systemHealth.value.message},{label:'AI 向量检索',ok:systemHealth.value.ai}]);
const currentMessageSession = computed(() => sessions.value.find(session => session.id === messageForm.value.sessionId));
const currentMessagePartner = computed(() => currentMessageSession.value ? sessionPartner(currentMessageSession.value) : undefined);
const postDialogTitle = computed(() => editingPostId.value ? '编辑社区帖子' : editingDraftId.value ? '编辑草稿' : '发布社区帖子');
const businessModeMismatch = computed(()=>new Set(runtimeModes.value.filter(item=>item.businessData&&item.available).map(item=>item.mode)).size>1);
const runtimeModeProblem = computed(()=>businessModeMismatch.value||runtimeModes.value.some(item=>!item.available||item.healthy===false));
const runtimeModeSummary = computed(()=>businessModeMismatch.value?'模式不一致':runtimeModes.value.some(item=>!item.available||item.healthy===false)?'部分状态异常':'运行正常');
const communityDirectory = computed<UserRecord[]>(() => [communityUser(currentUserId.value), ...Object.values(userSummaries.value).filter(user => user.id !== currentUserId.value)]);
const followedUsers = computed(() => (followData.value.followedUserIds || []).map(communityUser));
const followerUsers = computed(() => (followData.value.followerUserIds || []).map(communityUser));
const filteredAiSessions = computed(() => {const keyword=aiHistoryKeyword.value.trim().toLowerCase();return keyword?aiSessions.value.filter(session=>session.title.toLowerCase().includes(keyword)):aiSessions.value;});





const governanceResultCount = computed(() => ({ messages:governanceChats.value.total, comments:governanceComments.value.total, drafts:governanceDrafts.value.total, sessions:governanceAiSessions.value.total, chunks:governanceChunks.value.total }[governanceTab.value] || 0));


type ModerationList<T> = { items:T[]; cursor:number|string|null; hasMore:boolean; total:number; loading:boolean };
const emptyModerationList = <T,>():ModerationList<T> => ({items:[],cursor:null,hasMore:false,total:0,loading:false});
const MODERATION_PAGE_SIZE = 20;
const moderationKnowledge = ref<ModerationList<KnowledgeFile>>(emptyModerationList());
const moderationPendingPosts = ref<ModerationList<Post>>(emptyModerationList());
const moderationManagedPosts = ref<ModerationList<Post>>(emptyModerationList());
const moderationKnowledgeReports = ref<ModerationList<Report>>(emptyModerationList());
const moderationUserReports = ref<ModerationList<Report>>(emptyModerationList());
const moderationProfileChanges = ref<ModerationList<ProfileChange>>(emptyModerationList());
const auditingProfileChangeId = ref(0);
const moderationTokens: Record<string, number> = {};
const governanceChats = ref<ModerationList<ChatSession>>(emptyModerationList());
const governanceComments = ref<ModerationList<Comment>>(emptyModerationList());
const governanceDrafts = ref<ModerationList<Draft>>(emptyModerationList());
const governanceAiSessions = ref<ModerationList<AiSession>>(emptyModerationList());
const governanceChunks = ref<ModerationList<AiChunk>>(emptyModerationList());




const moderationStatusOptions = computed(()=>['reports','users'].includes(moderationTab.value)?[{label:'待处理',value:'PENDING'},{label:'处理中',value:'PROCESSING'},{label:'已结案',value:'RESOLVED'}]:moderationTab.value==='post-management'?[{label:'已发布',value:'PUBLISHED'},{label:'已隐藏',value:'HIDDEN'}]:['posts'].includes(moderationTab.value)?[{label:'待审核',value:'PENDING'}]:moderationTab.value==='profiles'?[{label:'待审核',value:'PENDING'},{label:'已通过',value:'APPROVED'},{label:'已驳回',value:'REJECTED'}]:[{label:'待审核',value:'PENDING'},{label:'已通过',value:'APPROVED'},{label:'已驳回',value:'REJECTED'},{label:'已下架',value:'HIDDEN'}]);
const moderationResultCount = computed(()=>({knowledge:moderationKnowledge.value.total,reports:moderationKnowledgeReports.value.total,users:moderationUserReports.value.total,posts:moderationPendingPosts.value.total,profiles:moderationProfileChanges.value.total,'post-management':moderationManagedPosts.value.total}[moderationTab.value]||0));

const moderationOpenCount = computed(()=>metricValue('knowledgeAdmin','pendingAudit')+metricValue('forumAdmin','pendingAudit')+metricValue('userAdmin','pendingAudits')+metricValue('knowledgeAdmin','openReports')+metricValue('userAdmin','openReports'));
const ticketOpenCount = computed(()=>metricValue('feedbackAdmin','pendingTickets')+metricValue('feedbackAdmin','processingTickets'));
const adminAttentionCount = computed(()=>moderationOpenCount.value+ticketOpenCount.value);
const adminWorkspaceNavigation = computed<NavigationItem[]>(()=>adminNavigation.map(item=>({...item,badge:item.key==='moderation'?moderationOpenCount.value||undefined:item.key==='tickets'?ticketOpenCount.value||undefined:item.key==='system'&&runtimeModeProblem.value?1:undefined})));
const aiConfigDirty = computed(()=>Boolean(adminConfigSnapshot.value)&&JSON.stringify(aiConfig.value)!==adminConfigSnapshot.value);
const analyticsKnowledgeCount = computed(() => knowledgeAnalytics.value?.files ?? 0);
const analyticsPostCount = computed(() => forumAnalytics.value?.posts ?? 0);
const analyticsInteractions = computed(() => (knowledgeAnalytics.value?.views ?? 0)+(knowledgeAnalytics.value?.downloads ?? 0)+(knowledgeAnalytics.value?.likes ?? 0)+(forumAnalytics.value?.likes ?? 0));
const analyticsFeedback = computed(() => ({bug:ticketAnalytics.value?.bug ?? 0,suggestion:ticketAnalytics.value?.suggestion ?? 0,support:ticketAnalytics.value?.support ?? 0,resolved:ticketAnalytics.value?.resolved ?? 0}));
const analyticsReportCount = computed(() => metricValue('knowledgeAdmin','reports')+metricValue('userAdmin','reports'));
const analyticsTrendDays = computed(() => knowledgeAnalytics.value?.trendDays ?? Math.min(analyticsDays.value,14));
const topKnowledge = computed(() => knowledgeAnalytics.value?.top ?? []);
const topPosts = computed(() => forumAnalytics.value?.top ?? []);
// The three services each zero-fill the same day window, so the knowledge series carries the
// axis and the other two are looked up by date.
const analyticsTrend = computed(() => {
  const posts=new Map((forumAnalytics.value?.trend??[]).map(point=>[point.date,point.count]));
  const tickets=new Map((ticketAnalytics.value?.trend??[]).map(point=>[point.date,point.count]));
  return (knowledgeAnalytics.value?.trend??[]).map(point=>{
    const day=new Date(`${point.date}T00:00:00`);
    return{label:`${day.getMonth()+1}/${day.getDate()}`,knowledge:point.count,posts:posts.get(point.date)??0,tickets:tickets.get(point.date)??0};
  });
});
const analyticsTrendMax = computed(() => Math.max(1,...analyticsTrend.value.flatMap(item=>[item.knowledge,item.posts,item.tickets])));

function parseKnowledgeContent(file?:KnowledgeFile):KnowledgeContentBlock[] {
  if (file?.contentBlocks?.length) {
    return file.contentBlocks.filter(block => ['image','heading','list','paragraph','table'].includes(block.type));
  }
  const content = file?.content || '';
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
}
const knowledgeContentBlocks = computed<KnowledgeContentBlock[]>(() => parseKnowledgeContent(selectedKnowledge.value));
function knowledgeTableRows(text?:string){return(text||'').split('\n').filter(Boolean).map(row=>row.split('\t'));}

const usersRefreshKey = ref(0);
function applyUserOverview(overview:Record<string,unknown>){adminOverview.value={...adminOverview.value,userAdmin:overview};}
function metricValue(section:string,key:string){ const value=adminOverview.value[section]?.[key]; return typeof value==='number'?value:0; }
function categoryCount(categoryId:number){ return knowledgeSearchMode.value ? knowledgeFiles.value.filter(file=>file.categoryId===categoryId).length : (knowledgeCategoryCounts.value[categoryId] || 0); }
function ticketStatusLabel(status:string){ return ({PENDING:'待处理',PROCESSING:'处理中',RESOLVED:'已解决'} as Record<string,string>)[status] || status; }
function ticketTypeLabel(type:string){ return ({BUG:'系统问题',SUGGESTION:'产品建议',SUPPORT:'客服咨询'} as Record<string,string>)[type] || type; }
function notificationTypeLabel(type?:string){ return ({SYSTEM:'系统通知',MESSAGE:'私信通知',COMMENT:'评论通知',REPLY:'回复通知',FEEDBACK:'反馈通知'} as Record<string,string>)[type || 'SYSTEM'] || '系统通知'; }
function eventTypeLabel(type:string){ return ({MESSAGE_SENT:'私信已发送',MESSAGE_CLEARED:'私信已清空',MESSAGE_DELETED:'私信已删除',FEEDBACK_TICKET_CREATED:'反馈工单已创建',FEEDBACK_TICKET_REPLIED:'反馈工单已回复'} as Record<string,string>)[type] || type; }
function eventStatusLabel(status:string){ return ({LOCAL_STORED:'已保存到本地',RABBITMQ_READY:'已发送到消息队列'} as Record<string,string>)[status] || status; }
function runtimeModeLabel(mode:string){ return ({mysql:'MySQL',local:'本地模式',minio:'MinIO',redis:'Redis',nacos:'Nacos',direct:'服务直连',sqlite:'SQLite',elasticsearch:'Elasticsearch',rabbitmq:'RabbitMQ',milvus:'Milvus',chroma:'ChromaDB'} as Record<string,string>)[mode] || mode.toUpperCase(); }
function modeTagType(item:RuntimeModeItem){if(!item.available||item.healthy===false)return 'danger';return ['mysql','minio','redis','nacos','elasticsearch','rabbitmq','milvus','chroma'].includes(item.mode)?'success':'info';}
function behaviorActionLabel(action:string){return ({VIEW:'浏览',LIKE:'点赞',COLLECT:'收藏',COMMENT:'评论',UPLOAD:'上传',DOWNLOAD:'下载',FORWARD:'转发',PUBLISH:'发布'} as Record<string,string>)[action]||action;}
function behaviorTargetLabel(target:string){return ({KNOWLEDGE:'知识',POST:'帖子',COMMENT:'评论',USER:'用户'} as Record<string,string>)[target]||target;}
function behaviorTargetCanOpen(item:BehaviorRecord){return item.targetId>0&&['KNOWLEDGE','POST'].includes(item.targetType);}
function openBehaviorTarget(item:BehaviorRecord){if(!behaviorTargetCanOpen(item))return;if(item.targetType==='KNOWLEDGE')openKnowledge({id:item.targetId} as KnowledgeFile);else openPostDetail({id:item.targetId} as Post);}

// The API sends times with their offset, so the browser shows them in the reader's own time zone.
function notifyError(error:unknown){ if(isSessionExpiredError(error))return; ElMessage.error(toUserMessage(error)); }
function clearCaptchaCooldown(){captchaCooldownRemaining.value=0;if(captchaCooldownTimer){clearInterval(captchaCooldownTimer);captchaCooldownTimer=undefined;}}
function startCaptchaCooldown(seconds:number){ captchaCooldownRemaining.value=Math.max(0,Math.ceil(seconds)); if(captchaCooldownTimer)clearInterval(captchaCooldownTimer); if(captchaCooldownRemaining.value>0){ captchaCooldownTimer=setInterval(()=>{ captchaCooldownRemaining.value=Math.max(0,captchaCooldownRemaining.value-1); if(captchaCooldownRemaining.value===0 && captchaCooldownTimer){clearInterval(captchaCooldownTimer); captchaCooldownTimer=undefined; if(captchaRefreshPending){captchaRefreshPending=false;void loadCaptcha();} } },1000); } }
function scheduleCaptchaExpiry(expiresAt:number){captchaExpiresAt.value=expiresAt;if(captchaExpiryTimer)clearTimeout(captchaExpiryTimer);const delay=Math.max(0,expiresAt-Date.now());captchaExpiryTimer=setTimeout(()=>{invalidateCaptcha(true);captchaRefreshPending=false;void loadCaptcha({silent:true});},delay);}
function invalidateCaptcha(allowImmediateRefresh=false){captchaImage.value='';captchaExpiresAt.value=0;loginForm.value.captchaId='';loginForm.value.captchaAnswer='';registerForm.value.captchaId='';registerForm.value.captchaAnswer='';forgotForm.value.captchaId='';forgotForm.value.captchaAnswer='';if(captchaExpiryTimer){clearTimeout(captchaExpiryTimer);captchaExpiryTimer=undefined;}if(allowImmediateRefresh)clearCaptchaCooldown();}
async function loadCaptcha(options?:{silent?:boolean}){
  if(captchaLoading.value || captchaCooldownRemaining.value>0)return;
  captchaLoading.value=true;
  try{
    const result=await getData<{captchaId?:string;image?:string;expiresAt?:number;expiresInSeconds?:number;refreshAfterSeconds?:number;cooldown?:boolean;retryAfterSeconds?:number}>('/user/captcha');
    if(result.captchaId&&result.image){captchaImage.value=result.image;loginForm.value.captchaId=result.captchaId;registerForm.value.captchaId=result.captchaId;forgotForm.value.captchaId=result.captchaId;const expiresAt=result.expiresInSeconds?Date.now()+result.expiresInSeconds*1000:result.expiresAt;if(expiresAt)scheduleCaptchaExpiry(expiresAt);}
    if(result.cooldown){ const seconds=result.retryAfterSeconds || 10; startCaptchaCooldown(seconds); if(!result.captchaId||!result.image){captchaRefreshPending=true;if(!options?.silent)ElMessage.info(`验证码刷新请等待 ${seconds} 秒`);} return; }
    if(!result.captchaId || !result.image)throw new Error('验证码获取失败，请稍后重试');
    loginForm.value.captchaAnswer='';registerForm.value.captchaAnswer='';forgotForm.value.captchaAnswer='';startCaptchaCooldown(result.refreshAfterSeconds || 10);
  }catch(error){if(!options?.silent)notifyError(error);}finally{captchaLoading.value=false;}
}

async function completeAuthentication(result:AuthResult){
  invalidateCaptcha(true);
  username.value=result.user.username;displayName.value=result.user.nickname;avatarUrl.value=result.user.avatarUrl||'';role.value=result.role;superAdmin.value=Boolean((result.user as {superAdmin?:boolean}).superAdmin);currentUserId.value=result.user.id;
  setStoredValue('ai-knowledge-username',username.value);setStoredValue('ai-knowledge-name',displayName.value);setStoredValue('ai-knowledge-avatar',avatarUrl.value);setStoredValue('ai-knowledge-super-admin',superAdmin.value?'1':'0');setStoredValue('ai-knowledge-role',role.value);setStoredValue('ai-knowledge-user-id',String(currentUserId.value));
  authenticated.value=true;portal.value=result.role==='ADMIN'?'admin':'client';activeView.value=result.role==='ADMIN'?'dashboard':'home';syncUserForms();await loadPublicConfig();await refreshCurrentView();await loadDetailRoute();
  maybeShowOnboarding();
}

function openRegisterDialog(){
  registerDialog.value=true;
  registerForm.value.captchaId=loginForm.value.captchaId;
  registerForm.value.captchaAnswer='';
  if(!captchaImage.value||captchaExpiresAt.value<=Date.now()){invalidateCaptcha(true);void loadCaptcha({silent:true});}
}
// Mirrors user-service's reserved names so the form can say so before a captcha is spent.
const RESERVED_USERNAMES = new Set(['admin','administrator','root','system','sysadmin','superuser','moderator','support','official','service','security','null','undefined']);
// The password rules user-service applies to registration and password changes.
function passwordRuleMessage(password:string,accountName:string){
  if(password.length<8||password.length>128)return '密码长度须为 8-128 位';
  if(!/[A-Za-z]/.test(password)||!/\d/.test(password))return '密码须同时包含字母和数字';
  if(/\s/.test(password))return '密码不能包含空格';
  if(accountName&&password.toLowerCase()===accountName.toLowerCase())return '密码不能与用户名相同';
  return '';
}
function openForgotDialog(){
  forgotStep.value='request';forgotForm.value.username=loginForm.value.username.trim();forgotDialog.value=true;
  forgotForm.value.captchaId=loginForm.value.captchaId;forgotForm.value.captchaAnswer='';
  if(!captchaImage.value||captchaExpiresAt.value<=Date.now()){invalidateCaptcha(true);void loadCaptcha({silent:true});}
}
function resetForgotForm(){forgotForm.value={username:'',contact:'',code:'',newPassword:'',confirmPassword:'',captchaId:loginForm.value.captchaId,captchaAnswer:''};forgotStep.value='request';}
function forgotValidationMessage(){
  const form=forgotForm.value;const name=form.username.trim();
  if(!name)return '请输入用户名';
  if(forgotStep.value==='request'){if(form.contact.trim().length>100)return '联系方式不能超过 100 个字符';}
  else{
    if(!form.code.trim())return '请输入重置码';
    const rule=passwordRuleMessage(form.newPassword,name);if(rule)return rule;
    if(!form.confirmPassword)return '请再次输入新密码';
    if(form.newPassword!==form.confirmPassword)return '两次输入的新密码不一致';
  }
  if(!form.captchaAnswer.trim())return '请输入验证码';
  return '';
}
// Both steps spend the captcha, so a fresh one is fetched whatever the outcome.
async function submitForgot(){
  if(busy.value)return;
  const message=forgotValidationMessage();if(message){ElMessage.warning(message);return;}
  busy.value=true;const form=forgotForm.value;const name=form.username.trim();
  try{
    if(forgotStep.value==='request'){
      await postData('/user/password-reset/request',{username:name,contact:form.contact.trim(),captchaId:form.captchaId,captchaAnswer:form.captchaAnswer.trim()});
      forgotStep.value='complete';
      void ElMessageBox.alert('如果该账号存在，管理员核实身份后会通过你留下的联系方式发送重置码。收到后在这里输入重置码并设置新密码。','申请已提交',{type:'success',confirmButtonText:'知道了'}).catch(()=>undefined);
    }else{
      await postData('/user/password-reset/complete',{username:name,code:form.code.trim(),newPassword:form.newPassword,captchaId:form.captchaId,captchaAnswer:form.captchaAnswer.trim()});
      forgotDialog.value=false;loginForm.value.username=name;loginForm.value.password='';
      ElMessage.success('密码已重置，请使用新密码登录');
    }
  }catch(error){notifyError(error);}
  finally{busy.value=false;invalidateCaptcha(true);captchaRefreshPending=false;await loadCaptcha({silent:true});}
}
// Mirrors user-service's NicknamePolicy so the form can say so early; the server has the final say.
const STAFF_TITLES = ['管理员','管理員','管理组','管理組','超管','官方','客服','系统管理','系統管理','系统消息','系統消息','系统通知','系統通知','版主','站长','站長','运营团队','運營團隊','审核员','審核員','知汇'];
const STAFF_WORDS = /(^|[^a-z])(admin|administrator|sysadmin|official|moderator|staff|support|system|root)(?![a-z])/;
const LOOKALIKE_LETTERS: Record<string,string> = {'0':'o','1':'i','!':'i','|':'i','3':'e','4':'a','@':'a','5':'s','$':'s','7':'t'};
function nicknameImpersonatesStaff(nickname:string){
  if(!nickname.trim())return false;
  const folded=nickname.normalize('NFKC').toLowerCase();
  const compact=folded.replace(/[^\p{L}\p{N}]/gu,'');
  const siteName=(platformConfig.value.platform_name||'').normalize('NFKC').toLowerCase().replace(/\s+/g,'');
  if(STAFF_TITLES.some(title=>compact.includes(title))||(siteName.length>=2&&compact.includes(siteName)))return true;
  const unmasked=folded.replace(/[013457!|@$]/g,character=>LOOKALIKE_LETTERS[character]||character);
  return STAFF_WORDS.test(folded)||STAFF_WORDS.test(unmasked);
}
function resetRegistrationForm(){registerForm.value={username:'',nickname:'',password:'',confirmPassword:'',captchaId:loginForm.value.captchaId,captchaAnswer:''};}
function registrationValidationMessage(){
  const username=registerForm.value.username.trim();const checks=registrationPasswordChecks.value;
  if(!username)return '请输入用户名';
  if(!registrationUsernameValid.value)return '用户名须由 3-32 位字母、数字、下划线或连字符组成';
  if(RESERVED_USERNAMES.has(username.toLowerCase()))return '该用户名为系统保留名称，请更换';
  const nickname=registerForm.value.nickname.trim();
  if(nicknameImpersonatesStaff(nickname||username))return nickname&&nickname!==username?'昵称不能冒充平台管理员、官方或客服，请更换':'该用户名为系统保留名称，请更换';
  if(!checks.length)return '密码长度须为 8-128 位';
  if(!checks.letter||!checks.number)return '密码须同时包含字母和数字';
  if(!checks.noWhitespace)return '密码不能包含空格';
  if(!checks.differsFromUsername)return '密码不能与用户名相同';
  if(!registerForm.value.confirmPassword)return '请再次输入密码';
  if(!registrationPasswordsMatch.value)return '两次输入的密码不一致';
  if(!registerForm.value.captchaAnswer.trim())return '请输入验证码';
  return '';
}
async function registerAccount(){
  if(busy.value)return;
  const validationMessage=registrationValidationMessage();if(validationMessage){ElMessage.warning(validationMessage);return;}
  busy.value=true;
  try{
    const registrationRequest={username:registerForm.value.username.trim(),nickname:registerForm.value.nickname.trim(),password:registerForm.value.password,captchaId:registerForm.value.captchaId,captchaAnswer:registerForm.value.captchaAnswer.trim()};
    const result=await postData<AuthResult>('/user/register',registrationRequest);
    registerDialog.value=false;
    await completeAuthentication(result);
    ElMessage.success('注册并登录成功');
  }catch(error){ notifyError(error); invalidateCaptcha(true); captchaRefreshPending=false; await loadCaptcha({silent:true}); }
  finally{ busy.value=false; }
}

async function login(){if(busy.value)return;const loginUsername=loginForm.value.username.trim();if(!loginUsername){ElMessage.warning('请输入用户名');return;}if(!loginForm.value.password){ElMessage.warning('请输入密码');return;}if(!loginForm.value.captchaAnswer.trim()){ElMessage.warning('请输入验证码');return;}busy.value=true;try{const result=await postData<AuthResult>('/user/login',{...loginForm.value,username:loginUsername});await completeAuthentication(result);ElMessage.success('登录成功');}catch(error){notifyError(error);invalidateCaptcha(true);captchaRefreshPending=false;await loadCaptcha({silent:true});}finally{busy.value=false;}}
function logout(){ void postData('/user/logout',{}).catch(()=>undefined); endLocalSession(); }
function endLocalSession(){ ['ai-knowledge-local-token','ai-knowledge-username','ai-knowledge-name','ai-knowledge-role','ai-knowledge-super-admin','ai-knowledge-user-id'].forEach(removeStoredValue); messageDrafts.clear(); messageForm.value={sessionId:0,senderId:0,content:''}; resetMessagePaging(); resetNotificationPaging(); sessions.value=[]; notifications.value=[]; authenticated.value=false; invalidateCaptcha(true); void loadCaptcha({silent:true}); }
async function restoreSession(){try{if(getLegacyAuthToken()){try{await postData('/user/session/adopt',{});}catch{/* an expired stored token is simply dropped */}finally{clearLegacyAuthToken();}}const session=await getData<{userId:number;username:string;role:string;superAdmin?:boolean}>('/user/session');currentUserId.value=session.userId;username.value=session.username;role.value=session.role;superAdmin.value=Boolean(session.superAdmin);setStoredValue('ai-knowledge-user-id',String(session.userId));syncUserForms();maybeShowOnboarding();await loadPublicConfig();await refreshCurrentView();await loadDetailRoute();}catch{logout();}}
async function loadPublicConfig(){try{platformConfig.value={...platformConfig.value,...await getData<typeof platformConfig.value>('/ai/config/public')};}catch{/* 配置服务短暂不可用时继续使用安全默认值。 */}}
function syncUserForms(){ profileForm.value.userId=currentUserId.value; knowledgeForm.value.userId=currentUserId.value; postForm.value.userId=currentUserId.value; messageForm.value.senderId=currentUserId.value; feedbackForm.value.userId=currentUserId.value; }
function resetDetailState(){detailRoute.value=undefined;detailFocusCommentId.value=0;detailError.value='';detailKnowledge.value=undefined;detailKnowledgeBlocks.value=[];detailPost.value=undefined;resetDetailCommentPaging();detailPdfPreviewUrl.value='';}
function returnToRoot(){if(window.location.pathname!=='/')window.history.pushState({},'', '/');resetDetailState();}
async function confirmDiscardAdminConfig(){if(!aiConfigDirty.value)return true;try{await ElMessageBox.confirm('平台配置还有未保存的更改，离开后这些更改会丢失。','离开配置页面？',{confirmButtonText:'放弃更改并离开',cancelButtonText:'继续编辑',type:'warning'});return true;}catch{return false;}}
async function switchPortal(value:'client'|'admin'){ if(value!==portal.value&&portal.value==='admin'&&activeView.value==='system'&&!await confirmDiscardAdminConfig())return;returnToRoot();portal.value=value; activeView.value=value==='admin'?'dashboard':'home'; mobileMenuOpen.value=false; await refreshCurrentView(); }
async function selectView(key:string){ if(key==='governance'){governanceDialog.value=true;mobileMenuOpen.value=false;await loadGovernance();return;} if(key==='notifications'){mobileMenuOpen.value=false;await openNotifications();return;} if(key!==activeView.value&&portal.value==='admin'&&activeView.value==='system'&&!await confirmDiscardAdminConfig())return; if(key==='audit'&&!keepAuditSubject){auditSubjectUserId.value=null;auditSubjectLabel.value='';} keepAuditSubject=false; returnToRoot();if(key==='forum')feedMode.value='all';if(key==='square')feedMode.value='following';activeView.value=key; mobileMenuOpen.value=false; mobileAiHistoryOpen.value=false; window.scrollTo({top:0,behavior:'smooth'}); await refreshCurrentView(); }
function openAdminQueue(tab:string){moderationTab.value=tab;void selectView('moderation');}
async function refreshCurrentView(){ viewLoading.value=true; viewError.value=''; if(portal.value==='client')void refreshUnreadCount(); try { if(portal.value==='admin'){ if(activeView.value==='dashboard') await loadAdminDashboard(); else if(activeView.value==='moderation') await loadModeration(); else if(activeView.value==='analytics') await loadAnalytics(); else if(activeView.value==='users') usersRefreshKey.value++; else if(activeView.value==='tickets') await loadTicketsAdmin(); else if(activeView.value==='audit') auditRefreshKey.value++; else await loadSystemAdmin(); } else { if(['home','knowledge'].includes(activeView.value)) await loadKnowledge(); if(activeView.value==='home')await Promise.all([loadFeed('following'),loadCommunityPostCount()]);if(activeView.value==='forum')await loadFeed(feedMode.value==='author'?'author':feedMode.value==='mine'?'mine':'all');if(activeView.value==='square')await loadFeed(feedMode.value==='author'?'author':feedMode.value==='mine'?'mine':'following'); if(['home','messages'].includes(activeView.value)) await loadMessageData(); if(activeView.value==='ai') await loadAiHistory(); if(activeView.value==='profile') await loadProfile(); if(activeView.value==='home') await loadFeedback(); } } catch(error){ viewError.value=toUserMessage(error,'页面加载失败，请重试'); notifyError(error); } finally { viewLoading.value=false; } }
async function refreshVisiblePage(){if(portal.value==='admin'&&activeView.value==='system'&&aiConfigDirty.value&&!await confirmDiscardAdminConfig())return;if(detailRoute.value)await loadDetailRoute();else await refreshCurrentView();}
async function runGlobalSearch(){ returnToRoot();portal.value='client';activeView.value='knowledge'; knowledgeKeyword.value=globalSearch.value; await searchKnowledge(); }

function parseDetailPath(path=window.location.pathname):DetailRoute|undefined {
  const match=path.match(/^\/(knowledge|community)\/(\d+)\/?$/);
  if(!match)return undefined;
  const id=Number(match[2]);
  return Number.isSafeInteger(id)&&id>0?{kind:match[1] as DetailRoute['kind'],id}:undefined;
}
async function loadDetailRoute(){
  const route=parseDetailPath();
  if(!route){resetDetailState();return;}
  detailRoute.value=route;detailLoading.value=true;detailError.value='';portal.value='client';activeView.value=route.kind==='community'?'forum':route.kind;
  try{
    if(route.kind==='knowledge'){
      // A cached copy is shown at once; the server copy replaces it when it differs.
      const cached=knowledgeCache.read(route.id,currentUserId.value) as KnowledgeFile|null;
      if(cached){showKnowledgeDetail(cached);detailLoading.value=false;}
      const pending=postData<KnowledgeFile>('/knowledge/view',{fileId:route.id});
      if(!cached){
        const detail=await pending;
        if(detailRoute.value?.id!==route.id)return;
        showKnowledgeDetail(detail);knowledgeCache.write(detail.id,currentUserId.value,detail);
        await loadKnowledgePreview(detail);
      }else{
        void pending.then(async detail=>{
          if(detailRoute.value?.kind!=='knowledge'||detailRoute.value.id!==route.id)return;
          if(knowledgeCache.changed(route.id,currentUserId.value,detail))showKnowledgeDetail(detail);
          knowledgeCache.write(detail.id,currentUserId.value,detail);
          await loadKnowledgePreview(detail);
        }).catch(()=>{/* the cached copy stays on screen; the next visit tries again */});
        await loadKnowledgePreview(cached);
      }
      await recordBehavior('VIEW','KNOWLEDGE',route.id);
    }else{
      const [post,commentPage]=await Promise.all([getData<Post>(`/post/detail?id=${route.id}`),getData<CommentThreadPage>(commentThreadsUrl(route.id))]);
      detailPost.value=post;applyCommentThreadPage(commentPage,true);detailKnowledge.value=undefined;detailKnowledgeBlocks.value=[];
      await loadUserSummaries([post.userId,...commentPage.items.map(comment=>comment.userId)]);
      // A notification link names a comment that may sit further down the discussion: load its thread too.
      let linkedComment=linkedCommentFromUrl();
      if(linkedComment&&!detailComments.value.some(comment=>comment.id===linkedComment&&!comment.placeholder)){
        try{const thread=await getData<{items:Comment[]}>(`/comment/thread?postId=${route.id}&commentId=${linkedComment}`);if(detailPost.value?.id!==route.id)return;detailComments.value=mergeComments(detailComments.value,thread.items);await loadUserSummaries(thread.items.map(comment=>comment.userId));}
        catch{linkedComment=0;ElMessage.info('这条评论已删除或暂不可见');}
      }
      detailFocusCommentId.value=linkedComment;
      await recordBehavior('VIEW','POST',post.id);
    }
    window.scrollTo({top:0,behavior:'auto'});
  }catch(error){detailError.value=toUserMessage(error,'详情加载失败，请重试');}
  finally{detailLoading.value=false;}
}
function showKnowledgeDetail(detail:KnowledgeFile){
  detailKnowledge.value=detail;detailKnowledgeBlocks.value=parseKnowledgeContent(detail);detailPost.value=undefined;resetDetailCommentPaging();
  void loadUserSummaries([detail.userId]);
}
/** The PDF is fetched once per opened document; switching away cancels nothing but the URL is always released. */
/** The address of the document itself: pdf.js reads it in pieces, so nothing is downloaded up front. */
function knowledgePreviewUrl(file:KnowledgeFile){return resolveApiUrl(`/knowledge/file/${file.id}/preview`);}
function loadKnowledgePreview(detail:KnowledgeFile){
  detailPdfPreviewUrl.value='';
  if(detail.fileType?.toLowerCase()!=='pdf'||!detail.fileUrl)return;
  detailPdfPreviewUrl.value=knowledgePreviewUrl(detail);
}
/** New members see a short guide once; it can be skipped, and reopened from 个人中心. */
function maybeShowOnboarding(){
  if(!authenticated.value)return;
  onboardingVisible.value=shouldShowOnboarding(currentUserId.value,browserStorage());
}
function openOnboarding(){onboardingVisible.value=true;}
function closeOnboarding(){
  onboardingVisible.value=false;
  markOnboardingSeen(currentUserId.value,browserStorage());
}
function browserStorage(){
  try{return window.localStorage;}catch{return undefined;}
}
function navigateToDetail(kind:DetailRoute['kind'],id:number,commentId?:number){const path=detailPath(kind,id,commentId);if(window.location.pathname+window.location.search!==path)window.history.pushState({kind,id},'',path);void loadDetailRoute();}
function linkedCommentFromUrl(){return linkedCommentFrom(window.location.search);}
function leaveDetail(){const destination=detailRoute.value?.kind==='community'?'forum':detailRoute.value?.kind||'home';returnToRoot();portal.value='client';activeView.value=destination;void refreshCurrentView();window.scrollTo({top:0,behavior:'auto'});}
function handlePopState(){const route=parseDetailPath();if(route)void loadDetailRoute();else{resetDetailState();void refreshCurrentView();}}

async function loadFollowData(){followData.value=await getData<{followedUserIds:number[];followerUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`);}
function isFollowing(userId:number){return Boolean(followData.value.followedUserIds?.includes(userId));}
async function toggleFollowAuthor(userId:number){if(userId===currentUserId.value)return;const wasFollowing=isFollowing(userId);if(wasFollowing)await deleteData('/user/follow',{targetUserId:userId});else await postData('/user/follow',{targetUserId:userId});await loadFollowData();if(wasFollowing&&feedMode.value==='following')feedPosts.value=feedPosts.value.filter(post=>post.userId!==userId);ElMessage.success(wasFollowing?'已取消关注':'已关注作者');}
async function loadKnowledge(){
  const token=++knowledgeRequestToken;
  const [page,counts,categories,follows,ranking]=await Promise.all([
    getData<KnowledgePage>(knowledgePageUrl()),
    getData<KnowledgeCategoryCounts>(knowledgeCountsUrl()),
    getData<KnowledgeCategory[]>('/knowledge/categories'),
    getData<{followedUserIds:number[];followerUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`),
    getData<KnowledgeRanking[]>('/knowledge/ranking')
  ]);
  if(token!==knowledgeRequestToken)return;
  knowledgeSearchMode.value=false;
  applyKnowledgePage(page,true);
  applyCategoryCounts(counts);
  knowledgeCategories.value=categories; followData.value=follows; knowledgeRanking.value=ranking;
  await loadUserSummaries([...page.items.map(file=>file.userId),...ranking.map(item=>item.userId)]);
}
function knowledgePageUrl(cursor?:number|null){
  const params=new URLSearchParams({limit:String(KNOWLEDGE_PAGE_SIZE)});
  if(knowledgeCategoryId.value)params.set('categoryId',String(knowledgeCategoryId.value));
  if(knowledgeType.value)params.set('fileType',knowledgeType.value);
  if(cursor)params.set('cursor',String(cursor));
  return `/knowledge/page?${params}`;
}
function knowledgeCountsUrl(){const params=new URLSearchParams();if(knowledgeType.value)params.set('fileType',knowledgeType.value);return `/knowledge/category-counts?${params}`;}
function applyKnowledgePage(page:KnowledgePage,reset:boolean){
  const known=new Set(reset?[]:knowledgeFiles.value.map(file=>file.id));
  knowledgeFiles.value=reset?page.items:[...knowledgeFiles.value,...page.items.filter(file=>!known.has(file.id))];
  knowledgeCursor.value=page.nextCursor;
  knowledgeHasMore.value=page.hasMore;
  // The 全部 tab keeps the unfiltered total, which comes from the counts endpoint.
  knowledgeLoadError.value='';
}
function applyCategoryCounts(counts:KnowledgeCategoryCounts){
  knowledgeCategoryCounts.value=Object.fromEntries(counts.counts.map(entry=>[entry.categoryId,entry.count]));
  knowledgeTotal.value=counts.total;
}
async function reloadKnowledgePage(){
  const token=++knowledgeRequestToken;
  knowledgeLoadError.value='';
  try{
    const [page,counts]=await Promise.all([getData<KnowledgePage>(knowledgePageUrl()),getData<KnowledgeCategoryCounts>(knowledgeCountsUrl())]);
    if(token!==knowledgeRequestToken)return;
    knowledgeSearchMode.value=false;
    applyKnowledgePage(page,true);
    applyCategoryCounts(counts);
    await loadUserSummaries(page.items.map(file=>file.userId));
  }catch(error){notifyError(error);}
}
async function loadMoreKnowledge(){
  if(knowledgeSearchMode.value||!knowledgeHasMore.value||knowledgeLoadingMore.value)return;
  const token=knowledgeRequestToken;
  knowledgeLoadingMore.value=true;
  knowledgeLoadError.value='';
  try{
    const page=await getData<KnowledgePage>(knowledgePageUrl(knowledgeCursor.value));
    if(token!==knowledgeRequestToken)return;
    applyKnowledgePage(page,false);
    await loadUserSummaries(page.items.map(file=>file.userId));
  }catch(error){if(token===knowledgeRequestToken)knowledgeLoadError.value=toUserMessage(error,'资源加载失败');}
  finally{knowledgeLoadingMore.value=false;}
}
watch([knowledgeCategoryId,knowledgeType],()=>{ if(activeView.value==='knowledge'&&!knowledgeSearchMode.value)void reloadKnowledgePage(); });
watch(knowledgeSentinelRef,element=>{
  knowledgeObserver?.disconnect();
  knowledgeObserver=undefined;
  if(!element||typeof IntersectionObserver==='undefined')return;
  knowledgeObserver=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting)&&!knowledgeLoadError.value)void loadMoreKnowledge();},{rootMargin:'240px 0px'});
  knowledgeObserver.observe(element);
});
async function searchKnowledge(){
  if(!knowledgeKeyword.value){await reloadKnowledgePage();return;}
  const token=++knowledgeRequestToken;
  const results=await getData<KnowledgeFile[]>(`/knowledge/search/fulltext?keyword=${encodeURIComponent(knowledgeKeyword.value)}`);
  if(token!==knowledgeRequestToken)return;
  knowledgeSearchMode.value=true;
  knowledgeFiles.value=results;
  knowledgeHasMore.value=false;
  knowledgeCursor.value=null;
  await loadUserSummaries(results.map(file=>file.userId));
}
function handleKnowledgeFile(file:UploadFile){const problem=uploadSizeProblem(file.name,file.size||0,platformConfig.value);if(problem){selectedKnowledgeFile.value=undefined;ElMessage.error(problem);return;}selectedKnowledgeFile.value=file.raw;if(file.raw&&!knowledgeForm.value.title)knowledgeForm.value.title=file.name.replace(/\.[^.]+$/,'');}
function clearKnowledgeFile(){selectedKnowledgeFile.value=undefined;}
async function uploadKnowledge(){
  if(!selectedKnowledgeFile.value&&!knowledgeForm.value.content.trim()){ElMessage.warning('请选择文件或填写文本正文');return;}
  if(knowledgeCategories.value.length&&knowledgeForm.value.categoryId===null){ElMessage.warning('请选择知识分类');return;}
  busy.value=true;
  try{
    let created:KnowledgeFile;
    if(selectedKnowledgeFile.value){
      const form=new FormData();form.append('file',selectedKnowledgeFile.value);form.append('title',knowledgeForm.value.title);
      if(knowledgeForm.value.categoryId!==null)form.append('categoryId',String(knowledgeForm.value.categoryId));
      created=await postFormData<KnowledgeFile>('/knowledge/file/upload',form);
    }else{
      const stored=await postData<{fileUrl:string}>('/knowledge/storage/upload',{filename:knowledgeForm.value.filename,content:knowledgeForm.value.content,fileType:knowledgeForm.value.fileType,title:knowledgeForm.value.title});
      created=await postData<KnowledgeFile>('/knowledge/upload',{title:knowledgeForm.value.title||knowledgeForm.value.filename,fileType:knowledgeForm.value.fileType,fileUrl:stored.fileUrl,content:knowledgeForm.value.content,categoryId:knowledgeForm.value.categoryId||null});
    }
    await recordBehavior('UPLOAD','KNOWLEDGE',created.id);
    knowledgeDialog.value=false;selectedKnowledgeFile.value=undefined;knowledgeForm.value.title='';knowledgeForm.value.content='';knowledgeForm.value.categoryId=null;
    if(portal.value==='admin')await loadModeration();else await loadKnowledge();ElMessage.success('资料已保存并完成正文索引，等待管理员审核');
  }catch(error){notifyError(error);}finally{busy.value=false;}
}
function openKnowledge(file:KnowledgeFile){navigateToDetail('knowledge',file.id);}
function prepareKnowledgePreview(file:KnowledgeFile,token=readerToken){
  pdfPreviewUrl.value='';
  if(file.fileType?.toLowerCase()!=='pdf'||!file.fileUrl)return;
  if(token!==readerToken)return;
  // No fetch here any more: the viewer loads the document itself and shows its own progress.
  readerPreviewLoading.value=false;
  pdfPreviewUrl.value=knowledgePreviewUrl(file);
}
async function viewKnowledge(file:KnowledgeFile){
  openReader(file,false);
  const token=readerToken;
  try{
    const detail=await postData<KnowledgeFile>('/knowledge/view',{fileId:file.id});
    if(token!==readerToken)return;
    selectedKnowledge.value=detail;readerLoading.value=false;
    knowledgeCache.write(detail.id,currentUserId.value,detail);
    void prepareKnowledgePreview(detail,token);
    await recordBehavior('VIEW','KNOWLEDGE',file.id);
    await loadKnowledge();
  }catch(error){if(token===readerToken){readerLoading.value=false;readerError.value=toUserMessage(error,'内容加载失败，请重试');}notifyError(error);}
}
/**
 * The review dialog opens straight away and fills in as the content arrives: a large PDF used to keep the
 * reviewer waiting with nothing on screen, unable to do anything else.
 */
async function reviewKnowledge(file:KnowledgeFile){
  openReader(file,true);
  const token=readerToken;
  try{
    const detail=await getData<KnowledgeFile>(`/knowledge/admin/preview?fileId=${file.id}`);
    if(token!==readerToken)return;
    selectedKnowledge.value=detail;readerLoading.value=false;
    void prepareKnowledgePreview(detail,token);
  }catch(error){if(token===readerToken){readerLoading.value=false;readerError.value=toUserMessage(error,'内容加载失败，请重试');}notifyError(error);}
}
function openReader(file:KnowledgeFile,reviewing:boolean){
  readerToken++;
  pdfPreviewUrl.value='';
  reviewingKnowledge.value=reviewing;selectedKnowledge.value=file;readerError.value='';
  readerLoading.value=true;readerPreviewLoading.value=false;readerDialog.value=true;
}
function closeReader(){readerToken++;readerDialog.value=false;readerLoading.value=false;readerPreviewLoading.value=false;}
async function retryReader(){const file=selectedKnowledge.value;if(!file)return;if(reviewingKnowledge.value)await reviewKnowledge(file);else await viewKnowledge(file);}
function reviewPost(post:Post){selectedReviewPost.value=post;postReviewDialog.value=true;}
async function downloadKnowledge(file:KnowledgeFile){try{const blob=await downloadData(`/knowledge/file/${file.id}`);const url=URL.createObjectURL(blob);const anchor=document.createElement('a');anchor.href=url;anchor.download=`${file.title}.${file.fileType||'bin'}`;anchor.click();URL.revokeObjectURL(url);await recordBehavior('DOWNLOAD','KNOWLEDGE',file.id);await loadKnowledge();}catch(error){notifyError(error);}}
async function downloadDetailKnowledge(file:KnowledgeFile){await downloadKnowledge(file);if(detailKnowledge.value?.id===file.id)detailKnowledge.value={...detailKnowledge.value,downloads:(detailKnowledge.value.downloads||0)+1};}
async function likeKnowledge(file:KnowledgeFile){const result=await postData<KnowledgeLikeResult>('/knowledge/like',{fileId:file.id});file.likes=result.likes;file.liked=result.liked;knowledgeCache.invalidate(file.id);if(result.liked){await recordBehavior('LIKE','KNOWLEDGE',file.id);ElMessage.success('已点赞');}else{if(knowledgeActivityType.value==='LIKED')myKnowledge.value=myKnowledge.value.filter(item=>item.id!==file.id);ElMessage.success('已取消点赞');}}
async function likeDetailKnowledge(file:KnowledgeFile){const result=await postData<KnowledgeLikeResult>('/knowledge/like',{fileId:file.id});if(detailKnowledge.value?.id===file.id)detailKnowledge.value={...detailKnowledge.value,likes:result.likes,liked:result.liked};if(result.liked){await recordBehavior('LIKE','KNOWLEDGE',file.id);ElMessage.success('已点赞');}else ElMessage.success('已取消点赞');}
async function collectKnowledge(file:KnowledgeFile){ const result=await postData<KnowledgeCollectResult>('/knowledge/collect',{fileId:file.id}); file.collected=result.collected; knowledgeCache.invalidate(file.id); if(detailKnowledge.value?.id===file.id) detailKnowledge.value={...detailKnowledge.value,collected:result.collected}; if(knowledgeActivityType.value==='COLLECTED'&&!result.collected) myKnowledge.value=myKnowledge.value.filter(item=>item.id!==file.id); if(result.collected){await recordBehavior('COLLECT','KNOWLEDGE',file.id);ElMessage.success('已收藏知识');}else ElMessage.success('已取消收藏'); }
async function forwardKnowledge(file:KnowledgeFile){await postData('/knowledge/forward',{fileId:file.id});await recordBehavior('FORWARD','KNOWLEDGE',file.id);ElMessage.success('已记录转发');await loadMyKnowledge();}
async function reportKnowledge(file:KnowledgeFile){ const {value}=await ElMessageBox.prompt('请填写举报原因','举报知识资源',{inputValue:'内容不准确'}); await postData('/knowledge/report',{userId:currentUserId.value,fileId:file.id,reason:value}); ElMessage.success('举报已提交'); }
async function deleteKnowledge(file:KnowledgeFile){await ElMessageBox.confirm(`删除“${file.title}”后正文、收藏和互动记录都无法恢复，确认继续？`,'删除知识资源',{type:'warning',confirmButtonText:'确认删除'});await deleteData('/knowledge/file',{fileId:file.id});knowledgeCache.invalidate(file.id);if(role.value==='ADMIN'){try{await deleteData(`/ai/admin/index/file/${file.id}`);}catch{/* 本地全文索引和业务数据已完成删除，AI 索引可由管理员稍后重建。 */}}knowledgeFiles.value=knowledgeFiles.value.filter(item=>item.id!==file.id);myKnowledge.value=myKnowledge.value.filter(item=>item.id!==file.id);if(detailKnowledge.value?.id===file.id)leaveDetail();ElMessage.success('知识资源已删除');}

async function loadFeed(mode:FeedMode=feedMode.value){ if(mode==='mine'){await Promise.all([loadMyPosts(),loadFollowData()]);return;} feedMode.value=mode; const relations=await getData<{followedUserIds:number[];followerUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`);followData.value=relations;await loadFeedFirstPage(mode==='author'&&!authorFilterUserId.value?'all':mode); }
function feedPageUrl(mode:FeedMode,cursor?:number|null){
  const params=new URLSearchParams({limit:String(FEED_PAGE_SIZE)});
  if(mode==='following'){params.set('scope','following');params.set('followedUserIds',(followData.value.followedUserIds||[]).join(','));}
  else if(mode==='mine'||mode==='author'){params.set('scope','author');params.set('authorUserId',String(mode==='mine'?currentUserId.value:authorFilterUserId.value));}
  else params.set('scope','all');
  if(cursor)params.set('cursor',String(cursor));
  return `/square/feed/page?${params}`;
}
async function loadFeedFirstPage(mode:FeedMode){
  const token=++feedRequestToken;
  feedLoadError.value='';
  const page=await getData<FeedPage>(feedPageUrl(mode));
  if(token!==feedRequestToken)return;
  feedPageMode=mode;
  feedPosts.value=page.items;
  feedCursor.value=page.nextCursor;
  feedHasMore.value=page.hasMore;
  await loadUserSummaries(page.items.map(post=>post.userId));
}
async function loadMoreFeed(){
  if(!feedHasMore.value||feedLoadingMore.value||portal.value!=='client'||!['forum','square'].includes(activeView.value))return;
  const token=feedRequestToken;
  feedLoadingMore.value=true;
  feedLoadError.value='';
  try{
    const page=await getData<FeedPage>(feedPageUrl(feedPageMode,feedCursor.value));
    if(token!==feedRequestToken)return;
    const known=new Set(feedPosts.value.map(post=>post.id));
    feedPosts.value=[...feedPosts.value,...page.items.filter(post=>!known.has(post.id))];
    feedCursor.value=page.nextCursor;
    feedHasMore.value=page.hasMore;
    await loadUserSummaries(page.items.map(post=>post.userId));
  }catch(error){if(token===feedRequestToken)feedLoadError.value=toUserMessage(error,'帖子加载失败');}
  finally{feedLoadingMore.value=false;}
}
watch(feedSentinelRef,element=>{
  feedObserver?.disconnect();
  feedObserver=undefined;
  if(!element||typeof IntersectionObserver==='undefined')return;
  feedObserver=new IntersectionObserver(entries=>{if(entries.some(entry=>entry.isIntersecting)&&!feedLoadError.value)void loadMoreFeed();},{rootMargin:'240px 0px'});
  feedObserver.observe(element);
});
async function loadCommunityPostCount(){communityPostCount.value=(await getData<{total:number}>('/square/feed/count')).total;}
async function loadAuthorPosts(userId:number){authorFilterUserId.value=userId;feedMode.value='author';await loadFeed('author');}
async function loadAuthorPostsFromProfile(userId:number){activeView.value='forum';await loadAuthorPosts(userId);}
/** The member behind a notification, once their summary has arrived. */
function noticeActor(notice:Notice):UserRecord|undefined{
  return notice.actorUserId?userSummaries.value[notice.actorUserId]:undefined;
}
function communityUser(userId:number):UserRecord{return userId===currentUserId.value?{id:userId,username:username.value,nickname:displayName.value,avatarUrl:avatarUrl.value,status:'ACTIVE',role:role.value}:userSummaries.value[userId]||{id:userId,username:'unknown',nickname:'已注销用户',status:'DISABLED',role:'USER'};}
async function loadUserSummaries(userIds:number[]){const requested=[...new Set(userIds.filter(id=>Number.isSafeInteger(id)&&id>0&&!resolvedUserIds.has(id)))];if(!requested.length)return;try{const summaries=await getData<UserRecord[]>(`/user/summaries?ids=${requested.join(',')}`);requested.forEach(id=>resolvedUserIds.add(id));userSummaries.value={...userSummaries.value,...Object.fromEntries(summaries.map(user=>[user.id,user]))};}catch{/* 资料服务短暂不可用时保留内容页面，后续刷新会重试。 */}}
function handlePostImages(_file:UploadFile, files:UploadFile[]){selectedPostImages.value=files.map(file=>file.raw).filter((file):file is UploadRawFile=>Boolean(file));}
function retainedPostImageUrls(){return postImageFiles.value.filter(file=>!file.raw&&file.url).map(file=>String(file.url));}
async function uploadSelectedPostImages(){if(!selectedPostImages.value.length)return[];const form=new FormData();selectedPostImages.value.forEach(file=>form.append('files',file));const uploaded=await postFormData<{imageUrls:string[]}>('/post/media/upload',form);return uploaded.imageUrls;}
async function currentPostImageUrls(){return [...retainedPostImageUrls(),...await uploadSelectedPostImages()];}
async function createPost(){ if(!postForm.value.title.trim()||!postForm.value.content.trim()){ElMessage.warning('请填写标题和正文');return;} const wasEditing=Boolean(editingPostId.value);busy.value=true;try{const imageUrls=await currentPostImageUrls();let saved:Post;if(editingPostId.value){saved=await putData<Post>('/post/update',{id:editingPostId.value,title:postForm.value.title,content:postForm.value.content,imageUrls});}else if(editingDraftId.value){saved=await postData<Post>('/post/draft/publish',{id:editingDraftId.value,title:postForm.value.title,content:postForm.value.content,imageUrls});}else{saved=await postData<Post>('/post/create',{...postForm.value,imageUrls});}await recordBehavior(wasEditing?'UPLOAD':'PUBLISH','POST',saved.id);postDialog.value=false;await loadDrafts();const nextMode=feedMode.value==='mine'?'mine':activeView.value==='square'?'following':'all';await loadFeed(nextMode);if(detailRoute.value?.kind==='community')await loadDetailRoute();ElMessage.success(platformConfig.value.post_audit_required?(wasEditing?'修改已提交审核':'帖子已提交审核'):(wasEditing?'帖子修改已发布':'帖子已发布'));}catch(error){notifyError(error);}finally{busy.value=false;} }
async function saveDraft(){if(!postForm.value.title.trim()&&!postForm.value.content.trim()){ElMessage.warning('请至少填写标题或正文');return;}busy.value=true;try{const payload={title:postForm.value.title||'未命名草稿',content:postForm.value.content,imageUrls:await currentPostImageUrls()};if(editingDraftId.value)await putData('/post/draft',{id:editingDraftId.value,...payload});else await postData('/post/draft',payload);postDialog.value=false;await loadDrafts();ElMessage.success('草稿已保存');}catch(error){notifyError(error);}finally{busy.value=false;} }
async function loadDrafts(){ drafts.value=await getData(`/post/drafts?userId=${currentUserId.value}`); }
function myKnowledgeUrl(cursor?:number|null){const params=new URLSearchParams({type:knowledgeActivityType.value,limit:String(MY_KNOWLEDGE_PAGE_SIZE)});if(cursor)params.set('cursor',String(cursor));return `/knowledge/mine/page?${params}`;}
function applyMyKnowledgePage(page:KnowledgePage,reset:boolean){
  const known=new Set(reset?[]:myKnowledge.value.map(file=>file.id));
  myKnowledge.value=reset?page.items:[...myKnowledge.value,...page.items.filter(file=>!known.has(file.id))];
  myKnowledgeCursor.value=page.nextCursor;
  myKnowledgeHasMore.value=page.hasMore;
  myKnowledgeTotal.value=page.total;
  myKnowledgeError.value='';
}
async function loadMyKnowledge(){
  const token=++myKnowledgeToken;
  const page=await getData<KnowledgePage>(myKnowledgeUrl());
  if(token!==myKnowledgeToken)return;
  applyMyKnowledgePage(page,true);
}
async function loadMoreMyKnowledge(){
  if(!myKnowledgeHasMore.value||myKnowledgeLoading.value)return;
  const token=myKnowledgeToken;
  myKnowledgeLoading.value=true;
  myKnowledgeError.value='';
  try{
    const page=await getData<KnowledgePage>(myKnowledgeUrl(myKnowledgeCursor.value));
    if(token!==myKnowledgeToken)return;
    applyMyKnowledgePage(page,false);
  }catch(error){if(token===myKnowledgeToken)myKnowledgeError.value=toUserMessage(error,'记录加载失败');}
  finally{myKnowledgeLoading.value=false;}
}
async function loadCollectedPosts(){collectedPosts.value=await getData<Post[]>('/square/collections');await loadUserSummaries(collectedPosts.value.map(post=>post.userId));}
async function openDrafts(){activeView.value='profile';profileToolTab.value='drafts';await loadProfile();}
async function loadMyPosts(){feedMode.value='mine';await loadFeedFirstPage('mine');}
function resetPostEditor(){editingPostId.value=0;editingDraftId.value=0;postForm.value={userId:currentUserId.value,title:'',content:''};selectedPostImages.value=[];postImageFiles.value=[];}
function existingImageFiles(imageUrls?:string[]):UploadUserFile[]{return(imageUrls||[]).map((url,index)=>({name:`图片 ${index+1}`,url,status:'success'}));}
function editPost(post:Post){editingPostId.value=post.id;editingDraftId.value=0;postForm.value={userId:currentUserId.value,title:post.title,content:post.content};selectedPostImages.value=[];postImageFiles.value=existingImageFiles(post.imageUrls);postDialog.value=true;}
function editDraft(draft:Draft){editingDraftId.value=draft.id;editingPostId.value=0;postForm.value={userId:currentUserId.value,title:draft.title,content:draft.content};selectedPostImages.value=[];postImageFiles.value=existingImageFiles(draft.imageUrls);postDialog.value=true;}
async function publishDraft(draft:Draft){await ElMessageBox.confirm(`确认发布草稿“${draft.title}”？`,'发布草稿',{type:'info'});await postData('/post/draft/publish',{id:draft.id});await loadDrafts();ElMessage.success('帖子已提交审核');}
async function deleteDraft(draft:Draft){await ElMessageBox.confirm(`确认删除草稿“${draft.title}”？`,'删除草稿',{type:'warning'});await deleteData('/post/draft',{draftId:draft.id});await loadDrafts();ElMessage.success('草稿已删除');}
function openPostDetail(post:Post){navigateToDetail('community',post.id);}
async function likePost(post:Post){const result=await postData<PostLikeResult>('/post/like',{postId:post.id});post.likes=result.likes;post.liked=result.liked;if(result.liked){await recordBehavior('LIKE','POST',post.id);ElMessage.success('已点赞');}else ElMessage.success('已取消点赞');}
async function likeDetailPost(post:Post){const result=await postData<PostLikeResult>('/post/like',{postId:post.id});if(detailPost.value?.id===post.id)detailPost.value={...detailPost.value,likes:result.likes,liked:result.liked};if(result.liked){await recordBehavior('LIKE','POST',post.id);ElMessage.success('已点赞');}else ElMessage.success('已取消点赞');}
async function collectPost(post:Post){const result=await postData<PostCollectResult>('/square/collect',{postId:post.id});post.collected=result.collected;if(detailPost.value?.id===post.id)detailPost.value={...detailPost.value,collected:result.collected};if(result.collected){await recordBehavior('COLLECT','POST',post.id);ElMessage.success('已收藏帖子');}else{collectedPosts.value=collectedPosts.value.filter(item=>item.id!==post.id);ElMessage.success('已取消收藏');}}
async function removeCollectedPost(post:Post){if(post.collected)await collectPost(post);}
async function deletePost(post:Post){await ElMessageBox.confirm(`删除帖子“${post.title}”后评论和互动记录都无法恢复，确认继续？`,'删除帖子',{type:'warning',confirmButtonText:'确认删除'});await deleteData('/post',{postId:post.id});feedPosts.value=feedPosts.value.filter(item=>item.id!==post.id);collectedPosts.value=collectedPosts.value.filter(item=>item.id!==post.id);if(detailPost.value?.id===post.id)leaveDetail();ElMessage.success('帖子已删除');}
async function quickComment(post:Post){const {value}=await ElMessageBox.prompt('输入公开评论内容','快捷评论',{inputPattern:/\S+/,inputErrorMessage:'评论不能为空',confirmButtonText:'发布'});if(value.length>platformConfig.value.max_comment_length){ElMessage.warning(`评论不能超过 ${platformConfig.value.max_comment_length} 个字符`);return;}await postData('/square/quick-comment',{postId:post.id,content:value});await recordBehavior('COMMENT','POST',post.id);ElMessage.success('评论已发布');}
async function createDetailComment(payload:{content:string;parentId:number}){
  if(!detailPost.value)return;
  const postId=detailPost.value.id;
  const saved=await postData<Comment>('/comment/create',{postId,content:payload.content,parentId:payload.parentId});
  ElMessage.success(payload.parentId?'回复已发布':'评论已发布');
  void recordBehavior('COMMENT','POST',postId).catch(()=>{});
  if(detailPost.value?.id!==postId)return;
  detailComments.value=mergeComments(detailComments.value,[saved]);
  detailCommentTotal.value+=1;
  void loadUserSummaries([saved.userId]);
}
function resetDetailCommentPaging(){detailComments.value=[];detailCommentTotal.value=0;detailCommentCursor.value=null;detailCommentsHasMore.value=false;detailCommentsLoading.value=false;detailCommentsError.value='';}
function commentThreadsUrl(postId:number,cursor?:number|null){const params=new URLSearchParams({postId:String(postId),limit:String(COMMENT_THREAD_PAGE_SIZE)});if(cursor)params.set('cursor',String(cursor));return `/comment/threads?${params}`;}
function mergeComments(current:Comment[],incoming:Comment[]){const byId=new Map<number,Comment>();[...current,...incoming].forEach(comment=>byId.set(comment.id,comment));return [...byId.values()].sort((a,b)=>a.id-b.id);}
function applyCommentThreadPage(page:CommentThreadPage,reset:boolean){detailComments.value=mergeComments(reset?[]:detailComments.value,page.items);detailCommentCursor.value=page.nextCursor;detailCommentsHasMore.value=page.hasMore;detailCommentTotal.value=page.total;detailCommentsError.value='';}
async function loadMoreDetailComments(){
  const postId=detailPost.value?.id;
  if(!postId||!detailCommentsHasMore.value||detailCommentsLoading.value)return;
  detailCommentsLoading.value=true;
  detailCommentsError.value='';
  try{
    const page=await getData<CommentThreadPage>(commentThreadsUrl(postId,detailCommentCursor.value));
    if(detailPost.value?.id!==postId)return;
    applyCommentThreadPage(page,false);
    await loadUserSummaries(page.items.map(comment=>comment.userId));
  }catch(error){if(detailPost.value?.id===postId)detailCommentsError.value=toUserMessage(error,'评论加载失败');}
  finally{detailCommentsLoading.value=false;}
}
function removeDetailCommentSubtree(commentId:number){
  const removed=new Set([commentId]);
  for(let changed=true;changed;){changed=false;for(const item of detailComments.value){if(item.parentId&&removed.has(item.parentId)&&!removed.has(item.id)){removed.add(item.id);changed=true;}}}
  const removedCount=detailComments.value.filter(item=>removed.has(item.id)&&!item.placeholder).length;
  let remaining=detailComments.value.filter(item=>!removed.has(item.id));
  // 占位评论只用于承载可见回复，回复被删光后一并移除。
  for(let pruned=true;pruned;){const parentIds=new Set(remaining.map(item=>item.parentId));const next=remaining.filter(item=>!item.placeholder||parentIds.has(item.id));pruned=next.length!==remaining.length;remaining=next;}
  detailComments.value=remaining;
  detailCommentTotal.value=Math.max(0,detailCommentTotal.value-removedCount);
}
async function deleteComment(comment:Comment){await ElMessageBox.confirm('删除评论后，其下的回复也会一并删除，确认继续？','删除评论',{type:'warning',confirmButtonText:'确认删除'});await deleteData('/comment',{commentId:comment.id});removeDetailCommentSubtree(comment.id);ElMessage.success('评论已删除');}

const messageDrafts = new Map<number,string>();
function selectMessageSession(sessionId:number){
  const previous=messageForm.value.sessionId;
  if(previous===sessionId)return;
  if(previous)messageDrafts.set(previous,messageForm.value.content);
  messageForm.value.sessionId=sessionId;
  messageForm.value.content=sessionId?(messageDrafts.get(sessionId)||''):'';
  resetMessagePaging();
}
function resetMessagePaging(){messages.value=[];messageRemovalCursor=0;loadedMessageSessionId=0;messageHasOlder.value=false;messageUnseenCount.value=0;}
async function loadMessageData(options:{reloadMessages?:boolean}={}){
  const [chatSessions,noticePage]=await Promise.all([
    getData<ChatSession[]>(`/message/sessions?userId=${currentUserId.value}`),
    getData<NotificationPage>(notificationPageUrl()).catch(()=>null)
  ]);
  sessions.value=chatSessions;
  if(noticePage)applyNotificationPage(noticePage,true);
  await loadUserSummaries(chatSessions.map(session=>session.otherUserId));
  if(!chatSessions.some(session=>session.id===messageForm.value.sessionId)){
    const firstSessionId=window.matchMedia('(max-width: 820px)').matches?0:(chatSessions[0]?.id||0);
    selectMessageSession(firstSessionId);
  }
  if(options.reloadMessages!==false||loadedMessageSessionId!==messageForm.value.sessionId)await loadMessages();
}
function notificationPageUrl(cursor?:number|null){const params=new URLSearchParams({userId:String(currentUserId.value),limit:String(NOTIFICATION_PAGE_SIZE)});if(cursor)params.set('cursor',String(cursor));return `/notification/page?${params}`;}
function applyNotificationPage(page:NotificationPage,reset:boolean){
  const known=new Set(reset?[]:notifications.value.map(notice=>notice.id));
  notifications.value=reset?page.items:[...notifications.value,...page.items.filter(notice=>!known.has(notice.id))];
  // Older notifications carry no actor; those simply keep the bell icon.
  void loadUserSummaries(page.items.map(notice=>notice.actorUserId||0).filter(id=>id>0));
  notificationCursor.value=page.nextCursor;
  notificationHasMore.value=page.hasMore;
  notificationUnread.value=page.unread;
  notificationError.value='';
}
function resetNotificationPaging(){notifications.value=[];notificationCursor.value=null;notificationHasMore.value=false;notificationLoading.value=false;notificationError.value='';notificationUnread.value=0;}
async function openNotifications(){applyNotificationPage(await getData<NotificationPage>(notificationPageUrl()),true);notificationsDialog.value=true;}
async function loadMoreNotifications(){
  if(!notificationHasMore.value||notificationLoading.value)return;
  notificationLoading.value=true;
  notificationError.value='';
  try{applyNotificationPage(await getData<NotificationPage>(notificationPageUrl(notificationCursor.value)),false);}
  catch(error){notificationError.value=toUserMessage(error,'通知加载失败');}
  finally{notificationLoading.value=false;}
}
async function refreshUnreadCount(){
  if(!authenticated.value)return;
  try{notificationUnread.value=(await getData<{unread:number}>(`/notification/unread-count?userId=${currentUserId.value}`)).unread;}
  catch{/* 通知服务短暂不可用时保留上一次的未读数。 */}
}
// Opens what the notification is about; opening it counts as reading it.
async function openNotice(notice:Notice){
  const destination=noticeDestination(notice.target);if(!destination)return;
  if(!notice.read){try{const result=await postData<{unread:number}>('/notification/read',{notificationId:notice.id});notice.read=true;notificationUnread.value=result.unread;}catch{/* the link still opens */}}
  notificationsDialog.value=false;
  if(destination.kind==='detail'){navigateToDetail(destination.detail,destination.id,destination.commentId);return;}
  // switchPortal may be declined (unsaved admin settings); read the portal again afterwards.
  if(!inClientPortal()){await switchPortal('client');if(!inClientPortal())return;}
  if(destination.kind==='chat'){
    selectMessageSession(destination.sessionId);
    await selectView('messages');
    if(messageForm.value.sessionId!==destination.sessionId)ElMessage.info('这个会话已关闭或暂不可用');
    return;
  }
  await selectView('profile');
  await nextTick();
  const ticket=document.querySelector<HTMLElement>(`[data-ticket-id="${destination.ticketId}"]`);
  if(!ticket){ElMessage.info('没有找到这条反馈工单');return;}
  ticket.scrollIntoView({block:'center'});
  linkedTicketId.value=destination.ticketId;window.clearTimeout(linkedTicketTimer);linkedTicketTimer=window.setTimeout(()=>{linkedTicketId.value=0;},3000);
}
const linkedTicketId = ref(0); let linkedTicketTimer:number|undefined;
function inClientPortal(){return portal.value==='client';}
async function markNotificationRead(notice:Notice){const result=await postData<{unread:number}>('/notification/read',{notificationId:notice.id});notice.read=true;notificationUnread.value=result.unread;ElMessage.success('已标记为已读');}
async function markAllNotificationsRead(){const result=await postData<{unread:number}>('/notification/read-all',{});notifications.value=notifications.value.map(notice=>({...notice,read:true}));notificationUnread.value=result.unread;ElMessage.success('全部通知已读');}
function sessionPartner(session:ChatSession){return communityUser(session.otherUserId);}
async function openSession(session:ChatSession){selectMessageSession(session.id);try{await loadMessages();}catch(error){notifyError(error);}}
function closeMobileConversation(){selectMessageSession(0);}
function scrollMessagesToBottom(){const list=messageListRef.value;if(list)list.scrollTop=list.scrollHeight;messageUnseenCount.value=0;}
function isMessageListNearBottom(){const list=messageListRef.value;return !list||list.scrollHeight-list.scrollTop-list.clientHeight<=MESSAGE_BOTTOM_THRESHOLD;}
function mergeMessages(current:ChatMessage[],incoming:ChatMessage[]){const byId=new Map<number,ChatMessage>();[...current,...incoming].forEach(message=>byId.set(message.id,message));return [...byId.values()].sort((a,b)=>a.id-b.id);}
function messagePageUrl(sessionId:number,cursor:{beforeId?:number;afterId?:number}={},limit=MESSAGE_PAGE_SIZE){const params=new URLSearchParams({sessionId:String(sessionId),limit:String(limit)});if(cursor.beforeId)params.set('beforeId',String(cursor.beforeId));if(cursor.afterId!==undefined)params.set('afterId',String(cursor.afterId));return `/message/page?${params}`;}
async function loadMessages(){
  const sessionId=messageForm.value.sessionId;
  if(!sessionId){resetMessagePaging();return;}
  const loaded=await getData<ChatMessage[]>(messagePageUrl(sessionId));
  if(messageForm.value.sessionId!==sessionId)return;
  messages.value=mergeMessages([],loaded);
  loadedMessageSessionId=sessionId;
  messageHasOlder.value=loaded.length>=MESSAGE_PAGE_SIZE;
  await nextTick();
  scrollMessagesToBottom();
}
async function loadOlderMessages(){
  const sessionId=messageForm.value.sessionId;
  const oldest=messages.value[0];
  if(!sessionId||!oldest||loadedMessageSessionId!==sessionId||!messageHasOlder.value||messageHistoryLoading.value)return;
  messageHistoryLoading.value=true;
  try{
    const older=await getData<ChatMessage[]>(messagePageUrl(sessionId,{beforeId:oldest.id}));
    if(messageForm.value.sessionId!==sessionId||loadedMessageSessionId!==sessionId)return;
    const list=messageListRef.value;
    const distanceFromBottom=list?list.scrollHeight-list.scrollTop:0;
    messages.value=mergeMessages(older,messages.value);
    messageHasOlder.value=older.length>=MESSAGE_PAGE_SIZE;
    await nextTick();
    if(list)list.scrollTop=list.scrollHeight-distanceFromBottom;
  }catch(error){notifyError(error);}
  finally{messageHistoryLoading.value=false;}
}
function handleMessageScroll(){
  const list=messageListRef.value;
  if(!list)return;
  if(list.scrollTop<=60)void loadOlderMessages();
  if(isMessageListNearBottom())messageUnseenCount.value=0;
}
function jumpToLatestMessages(){const list=messageListRef.value;if(list)list.scrollTo({top:list.scrollHeight,behavior:'smooth'});messageUnseenCount.value=0;}
async function fetchNewMessages(manual=false){
  const sessionId=messageForm.value.sessionId;
  if(!sessionId||messageFetchInFlight)return 0;
  if(loadedMessageSessionId!==sessionId){if(manual)await loadMessages();return 0;}
  messageFetchInFlight=true;
  try{
    const received:ChatMessage[]=[];
    let removedAny=false;
    for(let round=0;round<5;round++){
      const newest=messages.value[messages.value.length-1];
      const sync=await getData<MessageSync>(messageSyncUrl(sessionId,newest?.id??0,messageRemovalCursor));
      if(messageForm.value.sessionId!==sessionId||loadedMessageSessionId!==sessionId)return 0;
      if(sync.sessionMissing){
        // The other side (or an admin) deleted the conversation while it was open here.
        selectMessageSession(0);messageDrafts.delete(sessionId);
        ElMessage.info('该会话已被删除');
        void loadMessageData({reloadMessages:false}).catch(()=>undefined);
        return 0;
      }
      // Removals first: a message deleted before this poll must not be shown even briefly.
      const removed=new Set(sync.removedMessageIds||[]);const cleared=sync.clearedThroughId||0;
      if(removed.size||cleared){
        const kept=messages.value.filter(message=>!removed.has(message.id)&&message.id>cleared);
        if(kept.length!==messages.value.length){messages.value=kept;removedAny=true;}
      }
      messageRemovalCursor=sync.removalCursor??messageRemovalCursor;
      const session=sessions.value.find(item=>item.id===sessionId);
      if(session&&sync.sessionStatus)session.status=sync.sessionStatus;
      const known=new Set(messages.value.map(message=>message.id));
      const fresh=(sync.messages||[]).filter(message=>!known.has(message.id)&&!removed.has(message.id));
      if(fresh.length){
        const stickToBottom=isMessageListNearBottom();
        messages.value=mergeMessages(messages.value,fresh);
        received.push(...fresh);
        await nextTick();
        if(stickToBottom)scrollMessagesToBottom();
        else messageUnseenCount.value+=fresh.filter(message=>message.senderId!==currentUserId.value).length;
      }
      if(!sync.hasMoreMessages&&!sync.hasMoreRemovals)break;
    }
    const latest=received[received.length-1];
    const session=sessions.value.find(item=>item.id===sessionId);
    if(latest&&session){session.lastMessage=latest.content;session.updatedAt=latest.createdAt;}
    // The conversation list shows the last message, which may be the one that just went away.
    if(removedAny&&!latest)void loadMessageData({reloadMessages:false}).catch(()=>undefined);
    return received.length;
  }finally{messageFetchInFlight=false;}
}
function messageSyncUrl(sessionId:number,afterId:number,removalCursor:number){return `/message/sync?${new URLSearchParams({sessionId:String(sessionId),afterId:String(afterId),removalCursor:String(removalCursor),limit:'100'})}`;}
async function refreshMessages(){
  if(messageRefreshing.value)return;
  messageRefreshing.value=true;
  try{const count=await fetchNewMessages(true);if(!count)ElMessage.info('暂无新消息');}
  catch(error){notifyError(error);}
  finally{messageRefreshing.value=false;}
}
// Admins can restrict or archive a conversation; sync keeps this status current while it is open.
const openSessionStatus=computed(()=>sessions.value.find(session=>session.id===messageForm.value.sessionId)?.status||'ACTIVE');
const openSessionClosed=computed(()=>Boolean(messageForm.value.sessionId)&&openSessionStatus.value!=='ACTIVE');
const shouldPollMessages=computed(()=>authenticated.value&&portal.value==='client'&&activeView.value==='messages'&&!detailRoute.value&&Boolean(messageForm.value.sessionId)&&pageVisible.value);
function stopMessagePolling(){if(messagePollTimer!==undefined){window.clearInterval(messagePollTimer);messagePollTimer=undefined;}}
watch(shouldPollMessages,(active,wasActive)=>{
  stopMessagePolling();
  if(!active)return;
  messagePollTimer=window.setInterval(()=>{void fetchNewMessages().catch(()=>undefined);void refreshUnreadCount();},MESSAGE_POLL_INTERVAL);
  if(wasActive===false)void fetchNewMessages().catch(()=>undefined);
});
function handleVisibilityChange(){pageVisible.value=document.visibilityState!=='hidden';}
async function newConversation(){conversationUsername.value='';conversationTargetUser.value=undefined;conversationTargetId.value=0;conversationDialog.value=true;}
async function resolveConversationUser(){const query=conversationUsername.value.trim();if(!query){conversationTargetUser.value=undefined;conversationTargetId.value=0;return;}try{const user=await getData<UserRecord>(`/user/info?username=${encodeURIComponent(query)}`);if(user.id===currentUserId.value)throw new Error('不能给自己发起私信');conversationTargetUser.value=user;conversationTargetId.value=user.id;}catch(error){conversationTargetUser.value=undefined;conversationTargetId.value=0;notifyError(error);}}
async function createConversation(){if(!conversationTargetId.value)return;busy.value=true;try{const session=await postData<ChatSession>('/message/session',{targetUserId:conversationTargetId.value});await loadMessageData();selectMessageSession(session.id);await loadMessages();conversationDialog.value=false;activeView.value='messages';ElMessage.success('私信会话已创建');}catch(error){notifyError(error);}finally{busy.value=false;}}
async function sendMessage(){
  const sessionId=messageForm.value.sessionId;
  const content=messageForm.value.content.trim();
  if(!sessionId||!content||messageSending.value)return;
  messageSending.value=true;
  try{
    const saved=await postData<ChatMessage>('/message/send',{sessionId,content});
    messageDrafts.set(sessionId,'');
    if(messageForm.value.sessionId===sessionId){
      messageForm.value.content='';
      messages.value=mergeMessages(messages.value,[saved]);
      await nextTick();
      scrollMessagesToBottom();
    }
    const session=sessions.value.find(item=>item.id===sessionId);
    if(session){session.lastMessage=content;session.updatedAt=saved.createdAt;}
  }catch(error){notifyError(error);}
  finally{messageSending.value=false;}
}
async function clearCurrentSession(){await ElMessageBox.confirm('确认清空当前会话记录？','清空会话',{type:'warning'});await postData('/message/clear',{sessionId:messageForm.value.sessionId,userId:currentUserId.value});await loadMessageData();}
async function clearAllMessages(){await ElMessageBox.confirm('确认清空全部私信会话中的聊天记录？会话联系人仍会保留。','清空全部聊天记录',{type:'warning',confirmButtonText:'确认清空'});await postData('/message/clear-all',{});await loadMessageData();ElMessage.success('全部聊天记录已清空');}
async function deleteCurrentSession(){if(!messageForm.value.sessionId)return;await ElMessageBox.confirm('删除会话后，该会话及其中消息都无法恢复。','删除会话',{type:'warning',confirmButtonText:'确认删除'});const sessionId=messageForm.value.sessionId;await deleteData('/message/session',{sessionId});selectMessageSession(0);messageDrafts.delete(sessionId);await loadMessageData();ElMessage.success('会话已删除');}
async function deleteMessage(message:ChatMessage){await deleteData('/message',{messageId:message.id,userId:currentUserId.value});messages.value=messages.value.filter(item=>item.id!==message.id);await loadMessageData({reloadMessages:false});ElMessage.success('消息已删除');}

async function askAi(){if(!aiQuestion.value.trim())return;const question=aiQuestion.value;aiMessages.value.push({role:'user',content:question});aiQuestion.value='';aiBusy.value=true;try{const result=await postData<{session_id:number;answer:string;references:AiReference[]}>('/ai/chat',{question,user_id:currentUserId.value,session_id:aiSessionId.value});aiSessionId.value=result.session_id;const references=[...new Map((result.references||[]).map(reference=>[reference.file_id,reference])).values()].slice(0,3);aiMessages.value.push({role:'assistant',content:result.answer,references});await loadAiHistory();}catch(error){notifyError(error);}finally{aiBusy.value=false;}}
// The detail page loads the file by id and reports it when it is no longer available.
function openAiReference(reference:AiReference){if(!reference.file_id){ElMessage.warning('该知识来源当前不可访问');return;}navigateToDetail('knowledge',reference.file_id);}
async function loadAiHistory(){const history=await getData<{sessions:AiSession[]}> (`/ai/history?user_id=${currentUserId.value}&include_messages=false`);aiSessions.value=history.sessions;}
async function loadAiSession(id:number){aiSessionId.value=id;const history=await getData<{messages:{role:'user'|'assistant';content:string}[]}>(`/ai/history?user_id=${currentUserId.value}&session_id=${id}`);aiMessages.value=history.messages;mobileAiHistoryOpen.value=false;}
function newAiSession(){aiSessionId.value=undefined;aiMessages.value=[];aiQuestion.value='';mobileAiHistoryOpen.value=false;}
async function renameAiSession(session:AiSession){const {value}=await ElMessageBox.prompt('请输入新的会话名称','重命名 AI 会话',{inputValue:session.title,inputPattern:/\S+/,inputErrorMessage:'会话名称不能为空',confirmButtonText:'保存'});const result=await putData<{id:number;title:string}>(`/ai/session/${session.id}`,{title:value});session.title=result.title;ElMessage.success('会话名称已更新');}
async function deleteAiSession(session:AiSession){await ElMessageBox.confirm(`确认删除会话“${session.title}”及其全部消息？`,'删除 AI 会话',{type:'warning',confirmButtonText:'确认删除'});await deleteData(`/ai/session/${session.id}`);aiSessions.value=aiSessions.value.filter(item=>item.id!==session.id);if(aiSessionId.value===session.id)newAiSession();ElMessage.success('AI 会话已删除');}

async function loadProfile(){const [user,follows,blocks,history,faqResult]=await Promise.all([getData<UserRecord>(`/user/info?username=${username.value}`),getData<{followedUserIds:number[];followerUserIds:number[]}>(`/user/follows?userId=${currentUserId.value}`),getData<{blockedUserIds:number[]}>(`/user/blocks?userId=${currentUserId.value}`),getData<BehaviorRecord[]>(`/user/behaviors?userId=${currentUserId.value}`),getData<Faq[]>('/feedback/faqs')]);profileAudit.value=user.profileAudit??null;
  // A waiting change is what the member last asked for, so the form shows that rather than the live values.
  const pending=profileAudit.value?.status==='PENDING'?profileAudit.value:null;
  profileForm.value={userId:user.id,nickname:pending?pending.nickname||user.nickname:user.nickname,avatarUrl:user.avatarUrl||'',signature:pending?pending.signature||'':user.signature||''};displayName.value=user.nickname;followData.value=follows;blockedUserIds.value=blocks.blockedUserIds;behaviors.value=history.slice(-100).reverse();faqs.value=faqResult;await loadUserSummaries([...(follows.followedUserIds||[]),...(follows.followerUserIds||[])]);await Promise.all([loadDrafts(),loadFeedback(),loadMyKnowledge(),loadCollectedPosts()]);}
async function saveProfile(){const nickname=profileForm.value.nickname.trim();if(role.value!=='ADMIN'&&nickname!==displayName.value&&nicknameImpersonatesStaff(nickname)){ElMessage.warning('昵称不能冒充平台管理员、官方或客服，请更换');return;}const user=await postData<UserRecord>('/user/profile',profileForm.value);applyOwnProfile(user);
  ElMessage.success(user.profileAudit?.status==='PENDING'?'资料已提交，等待管理员审核':'资料已保存');}

/**
 * Keeps the header and the stored name on the approved identity: a change waiting for review must not look as if
 * it already took effect.
 */
function applyOwnProfile(user:UserRecord){
  profileAudit.value=user.profileAudit??null;
  displayName.value=user.nickname;
  avatarUrl.value=user.avatarUrl||'';
  setStoredValue('ai-knowledge-name',user.nickname);
  setStoredValue('ai-knowledge-avatar',avatarUrl.value);
}
async function removeAvatar(){await ElMessageBox.confirm('确认删除当前头像？','删除头像',{type:'warning'});const user=await postData<UserRecord>('/user/profile',{...profileForm.value,avatarUrl:''});profileForm.value.avatarUrl='';applyOwnProfile(user);ElMessage.success('头像已删除');}
async function changePassword(){if(!passwordForm.value.currentPassword){ElMessage.warning('请输入当前密码');return;}const passwordRule=passwordRuleMessage(passwordForm.value.newPassword,username.value);if(passwordRule){ElMessage.warning(passwordRule);return;}if(passwordForm.value.newPassword===passwordForm.value.currentPassword){ElMessage.warning('新密码不能与当前密码相同');return;}try{await postData('/user/password',passwordForm.value);passwordForm.value={currentPassword:'',newPassword:''};ElMessage.success('密码已更新，其他设备上的登录已退出');}catch(error){notifyError(error);}}
async function handleAvatarFile(file: UploadFile){const raw=file.raw as UploadRawFile|undefined;if(!raw)return;const form=new FormData();form.append('file',raw);try{const user=await postFormData<UserRecord>('/user/avatar/upload',form);profileForm.value.avatarUrl=user.avatarUrl||'';avatarUrl.value=user.avatarUrl||'';setStoredValue('ai-knowledge-avatar',avatarUrl.value);ElMessage.success('头像已更新');}catch(error){notifyError(error);}}
async function recordBehavior(action:string,targetType:string,targetId:number){await postData('/user/behavior',{userId:currentUserId.value,action,targetType,targetId});}
async function resolveRelationUser(){const query=relationForm.value.username.trim();if(!query){relationTargetUser.value=undefined;relationForm.value.targetUserId=0;return;}try{const user=await getData<UserRecord>(`/user/info?username=${encodeURIComponent(query)}`);if(user.id===currentUserId.value)throw new Error('不能对自己执行社交操作');relationTargetUser.value=user;relationForm.value.targetUserId=user.id;}catch(error){relationTargetUser.value=undefined;relationForm.value.targetUserId=0;notifyError(error);}}
function relationTarget(){if(!relationForm.value.targetUserId){ElMessage.warning('请先输入完整用户名并查找');return 0;}return relationForm.value.targetUserId;}
async function followUser(){if(!relationTarget())return;await postData('/user/follow',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已关注用户');}
async function unfollowUser(){if(!relationTarget())return;await deleteData('/user/follow',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已取消关注');}
async function blockUser(){if(!relationTarget())return;await postData('/user/block',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已加入黑名单');}
async function unblockUser(){if(!relationTarget())return;await deleteData('/user/block',{targetUserId:relationForm.value.targetUserId});await loadProfile();ElMessage.success('已解除拉黑');}
async function reportUser(){if(!relationTarget())return;const {value}=await ElMessageBox.prompt('请填写举报原因','举报用户',{inputValue:'发布不当内容'});await postData('/user/report',{targetUserId:relationForm.value.targetUserId,reason:value});ElMessage.success('用户举报已提交');}
async function loadFeedback(){tickets.value=await getData(`/feedback/tickets?userId=${currentUserId.value}`);}
async function createTicket(){if(!feedbackForm.value.content.trim())return;await postData('/feedback/ticket',feedbackForm.value);feedbackDialog.value=false;feedbackForm.value.content='';await loadFeedback();ElMessage.success('反馈已提交');}

async function loadAdminDashboard(){const [userAdmin,knowledgeAdmin,forumAdmin,messageAdmin,feedbackAdmin,checks]=await Promise.all([getData<Record<string,unknown>>('/user/admin/overview'),getData<Record<string,unknown>>('/knowledge/admin/overview'),getData<Record<string,unknown>>('/post/admin/overview'),getData<Record<string,unknown>>('/message/admin/overview?userId=1'),getData<Record<string,unknown>>('/feedback/admin/overview'),Promise.allSettled(['/user/health','/knowledge/health','/post/health','/message/health','/ai/health'].map(url=>getData(url)))]);adminOverview.value={userAdmin,knowledgeAdmin,forumAdmin,messageAdmin,feedbackAdmin};systemHealth.value={user:checks[0].status==='fulfilled',knowledge:checks[1].status==='fulfilled',community:checks[2].status==='fulfilled',message:checks[3].status==='fulfilled',ai:checks[4].status==='fulfilled'};}
function moderationPageUrl(tab:string,cursor:number|string|null){
  const params=new URLSearchParams({limit:String(MODERATION_PAGE_SIZE)});
  if(cursor)params.set('cursor',String(cursor));
  const keyword=adminModerationKeyword.value.trim();
  if(keyword)params.set('keyword',keyword);
  if(tab==='knowledge'||tab==='reports'||tab==='users'){
    if(adminModerationStatus.value)params.set('status',adminModerationStatus.value);
    const base=({knowledge:'/knowledge/admin/files/page',reports:'/knowledge/admin/reports/page',users:'/user/admin/reports/page'} as Record<string,string>)[tab];
    return `${base}?${params.toString()}`;
  }
  if(tab==='profiles'){
    params.set('status',adminModerationStatus.value||'PENDING');
    return `/user/admin/profile-changes/page?${params.toString()}`;
  }
  params.set('status',tab==='posts'?'PENDING':adminModerationStatus.value||'PUBLISHED,HIDDEN');
  return `/post/admin/posts/page?${params.toString()}`;
}
async function loadModerationList<T extends {id:number}>(tab:string,list:{value:ModerationList<T>},reset:boolean,url:(cursor:number|string|null)=>string=cursor=>moderationPageUrl(tab,cursor)){
  const token=(moderationTokens[tab]||0)+1;moderationTokens[tab]=token;
  list.value={...list.value,loading:true};
  try{
    const page=await getData<AdminPage<T>>(url(reset?null:list.value.cursor));
    if(token!==moderationTokens[tab])return;
    const known=new Set(reset?[]:list.value.items.map(item=>item.id));
    list.value={items:reset?page.items:[...list.value.items,...page.items.filter(item=>!known.has(item.id))],cursor:page.nextCursor,hasMore:page.hasMore,total:page.total??list.value.total,loading:false};
  } finally { if(token===moderationTokens[tab]&&list.value.loading)list.value={...list.value,loading:false}; }
}
// Only the open tab is fetched; the report tabs keep filtering their short lists in the browser.
async function loadModerationTab(tab=moderationTab.value,reset=true){
  if(tab==='knowledge')await loadModerationList(tab,moderationKnowledge,reset);
  else if(tab==='posts')await loadModerationList(tab,moderationPendingPosts,reset);
  else if(tab==='profiles')await loadModerationList(tab,moderationProfileChanges,reset);
  else if(tab==='post-management')await loadModerationList(tab,moderationManagedPosts,reset);
  else if(tab==='reports')await loadModerationList(tab,moderationKnowledgeReports,reset);
  else if(tab==='users')await loadModerationList(tab,moderationUserReports,reset);
}
async function loadMoreModeration(){ await loadModerationTab(moderationTab.value,false); }
async function loadModeration(){
  const [knowledgeAdmin,forumAdmin,userAdmin]=await Promise.all([
    getData<Record<string,unknown>>('/knowledge/admin/overview'),
    getData<Record<string,unknown>>('/post/admin/overview'),
    getData<Record<string,unknown>>('/user/admin/overview'),
    loadModerationTab(moderationTab.value,true)]);
  adminOverview.value={...adminOverview.value,knowledgeAdmin,forumAdmin,userAdmin};
}
let moderationFilterTimer:ReturnType<typeof setTimeout>|undefined;
watch([moderationTab,adminModerationKeyword,adminModerationStatus],([tab],[previousTab])=>{
  if(portal.value!=='admin'||activeView.value!=='moderation')return;
  clearTimeout(moderationFilterTimer);
  moderationFilterTimer=setTimeout(()=>void loadModerationTab(moderationTab.value,true).catch(notifyError),tab!==previousTab?0:250);
});
async function openAdminKnowledgeUpload(){knowledgeCategories.value=await getData<KnowledgeCategory[]>('/knowledge/categories');knowledgeDialog.value=true;}
async function openKnowledgeMetadata(file:KnowledgeFile){knowledgeCategories.value=await getData<KnowledgeCategory[]>('/knowledge/categories');knowledgeMetadataForm.value={fileId:file.id,title:file.title,categoryId:file.categoryId||null,auditStatus:file.auditStatus};knowledgeMetadataDialog.value=true;}
async function saveKnowledgeMetadata(){const updated=await putData<KnowledgeFile>('/knowledge/admin/file',knowledgeMetadataForm.value);knowledgeCache.invalidate(updated.id);knowledgeMetadataDialog.value=false;await syncKnowledgeIndex(updated);await loadModeration();ElMessage.success('知识资源已更新');}
async function updateKnowledgeStatus(file:KnowledgeFile,auditStatus:string){const updated=await putData<KnowledgeFile>('/knowledge/admin/file',{fileId:file.id,auditStatus});knowledgeCache.invalidate(file.id);await syncKnowledgeIndex(updated);await loadModeration();ElMessage.success(auditStatus==='APPROVED'?'资源已恢复':'资源已下架');}
async function syncKnowledgeIndex(file:KnowledgeFile){if(file.auditStatus==='APPROVED'){const detail=await getData<KnowledgeFile>(`/knowledge/admin/preview?fileId=${file.id}`);if(detail.content?.trim())await postData('/ai/parse',{file_id:file.id,title:detail.title,text:detail.content});}else{try{await deleteData(`/ai/admin/index/file/${file.id}`);}catch{/* 资源状态已更新，索引可通过重建功能再次校准。 */}}}
async function deleteAdminKnowledge(file:KnowledgeFile){await deleteKnowledge(file);await loadModeration();}
async function openCategoryManager(){knowledgeCategories.value=await getData<KnowledgeCategory[]>('/knowledge/categories');categoryForm.value={id:0,name:'',sortNo:10};categoryDialog.value=true;}
function editCategory(category:KnowledgeCategory){categoryForm.value={id:category.id,name:category.name,sortNo:category.sortNo||0};}
async function saveCategory(){if(!categoryForm.value.name.trim())return;await postData('/knowledge/admin/category',{...categoryForm.value});categoryForm.value={id:0,name:'',sortNo:10};knowledgeCategories.value=await getData('/knowledge/categories');ElMessage.success('分类已保存');}
async function removeCategory(category:KnowledgeCategory){await ElMessageBox.confirm(`确认删除分类“${category.name}”？`,'删除分类',{type:'warning'});await deleteData('/knowledge/admin/category',{categoryId:category.id});knowledgeCategories.value=await getData('/knowledge/categories');}
async function auditKnowledge(file:KnowledgeFile,status:string,reason?:string){let note=(reason??'').trim();
  if(status==='REJECTED'&&!note){
    // The reason is kept with the decision, so a later reviewer can see why it went that way.
    const answer=await ElMessageBox.prompt('请说明驳回原因，审核记录会保留这条说明','驳回知识资源',
      {inputPlaceholder:'例如：与知识库主题无关',confirmButtonText:'驳回',cancelButtonText:'取消',
        inputValidator:(value:string)=>value.trim().length>0&&value.trim().length<=200||'请填写 1-200 字的原因'}).catch(()=>null);
    if(!answer)return;
    note=String(answer.value).trim();
  }
  await postData('/knowledge/admin/audit',{fileId:file.id,auditStatus:status,reason:note});knowledgeCache.invalidate(file.id);if(status==='APPROVED'){const detail=await getData<KnowledgeFile>(`/knowledge/admin/preview?fileId=${file.id}`);if(detail.content?.trim())await postData('/ai/parse',{file_id:file.id,title:detail.title,text:detail.content});}else{await deleteData(`/ai/admin/index/file/${file.id}`);}if(reviewingKnowledge.value&&selectedKnowledge.value?.id===file.id){readerDialog.value=false;reviewingKnowledge.value=false;}await loadModeration();ElMessage.success('审核状态与 AI 索引已更新');}
async function auditPost(post:Post,status:string){if(auditingPostId.value)return;auditingPostId.value=post.id;try{const result=await postData<{post:Post}>('/post/admin/audit',{postId:post.id,status,reason:'管理员审核'});if(selectedReviewPost.value?.id===post.id){selectedReviewPost.value=result.post;postReviewDialog.value=false;}await loadModeration();ElMessage.success(status==='PUBLISHED'?(post.status==='HIDDEN'?'帖子已恢复':'帖子已发布'):(post.status==='PENDING'?'帖子已驳回并隐藏':'帖子已隐藏'));}catch(error){notifyError(error);await loadModeration();}finally{auditingPostId.value=0;}}
async function auditProfileChange(change:ProfileChange,status:string,reason=''){
  if(auditingProfileChangeId.value)return;
  auditingProfileChangeId.value=change.id;
  try{ await postData('/user/admin/profile-change/audit',{changeId:change.id,status,reason}); ElMessage.success(status==='APPROVED'?'资料修改已通过':'资料修改已驳回'); }
  finally{ auditingProfileChangeId.value=0; await loadModeration(); }
}
async function rejectProfileChange(change:ProfileChange){
  // The reason reaches the member on their own profile page, so it is asked for rather than assumed.
  const answer=await ElMessageBox.prompt('请说明驳回原因，会员会在个人中心看到','驳回资料修改',
    {inputPlaceholder:'例如：昵称含有推广信息',confirmButtonText:'驳回',cancelButtonText:'取消',
      inputValidator:(value:string)=>value.trim().length>0&&value.trim().length<=200||'请填写 1-200 字的原因'}).catch(()=>null);
  if(!answer)return;
  await auditProfileChange(change,'REJECTED',String(answer.value).trim());
}
async function resolveKnowledgeReport(report:Report){await postData('/knowledge/admin/report/resolve',{reportId:report.id,status:'RESOLVED',result:'管理员已处理'});await loadModeration();}
async function resolveUserReport(report:Report){await postData('/user/admin/report/resolve',{reportId:report.id,status:'RESOLVED',result:'管理员已处理'});await loadModeration();}
async function reviewReportedKnowledge(report:Report){
  // The preview is fetched by id, so the reported file does not need to be on a loaded page.
  try{await reviewKnowledge({id:report.fileId} as KnowledgeFile);}catch{ElMessage.warning('未找到被举报资源，可能已被删除');}
}
async function reviewReportedUser(report:Report){
  const page=await getData<AdminPage<UserRecord>>(`/user/admin/users/page?userId=${report.targetUserId}&limit=1`);
  const user=page.items[0];
  if(user)void openUserGovernance(user);else ElMessage.warning('未找到被举报用户');
}
const GOVERNANCE_PAGE_SIZE = 20;
const GOVERNANCE_ENDPOINTS: Record<string, string> = {messages:'/message/admin/sessions/page',comments:'/comment/admin/page',drafts:'/post/admin/drafts/page',sessions:'/ai/admin/sessions/page',chunks:'/ai/admin/chunks/page'};
function governancePageUrl(tab:string,cursor:number|string|null){
  const params=new URLSearchParams({limit:String(GOVERNANCE_PAGE_SIZE)});
  if(cursor!==null&&cursor!=='')params.set('cursor',String(cursor));
  const keyword=governanceKeyword.value.trim();
  if(keyword)params.set('keyword',keyword);
  return `${GOVERNANCE_ENDPOINTS[tab]}?${params.toString()}`;
}
// Only the open tab is fetched; the shared keyword is applied by each service.
async function loadGovernanceTab(tab=governanceTab.value,reset=true){
  const url=(cursor:number|string|null)=>governancePageUrl(tab,cursor);
  const key=`governance:${tab}`;
  if(tab==='messages')await loadModerationList(key,governanceChats,reset,url);
  else if(tab==='comments')await loadModerationList(key,governanceComments,reset,url);
  else if(tab==='drafts')await loadModerationList(key,governanceDrafts,reset,url);
  else if(tab==='sessions')await loadModerationList(key,governanceAiSessions,reset,url);
  else if(tab==='chunks')await loadModerationList(key,governanceChunks,reset,url);
}
async function loadMoreGovernance(){ await loadGovernanceTab(governanceTab.value,false); }
async function loadGovernance(){
  adminChatToken++;adminChatMessages.value=[];adminChatSession.value=undefined;adminChatCursor.value=null;adminChatHasMore.value=false;adminChatLoading.value=false;
  const [forumAdmin,aiStats]=await Promise.all([
    getData<Record<string,unknown>>('/post/admin/overview'),
    getData<Record<string,unknown>>('/ai/admin/overview'),
    loadGovernanceTab(governanceTab.value,true)]);
  adminOverview.value={...adminOverview.value,forumAdmin};aiOverview.value=aiStats;
}
let governanceFilterTimer:ReturnType<typeof setTimeout>|undefined;
watch([governanceTab,governanceKeyword],([tab],[previousTab])=>{
  if(!governanceDialog.value)return;
  clearTimeout(governanceFilterTimer);
  governanceFilterTimer=setTimeout(()=>void loadGovernanceTab(governanceTab.value,true).catch(notifyError),tab!==previousTab?0:250);
});
// The preview opens on the latest messages; older ones are prepended on request.
async function loadAdminChatMessages(session:ChatSession,older=false){
  const token=++adminChatToken;adminChatLoading.value=true;
  try{
    const params=new URLSearchParams({sessionId:String(session.id),limit:'30'});
    if(older&&adminChatCursor.value)params.set('cursor',String(adminChatCursor.value));
    const page=await getData<AdminPage<ChatMessage>>(`/message/admin/messages/page?${params.toString()}`);
    if(token!==adminChatToken)return;
    adminChatSession.value=session;
    adminChatMessages.value=older?[...page.items,...adminChatMessages.value]:page.items;
    adminChatCursor.value=typeof page.nextCursor==='number'?page.nextCursor:null;adminChatHasMore.value=page.hasMore;
  } finally { if(token===adminChatToken)adminChatLoading.value=false; }
}
async function setAdminSessionStatus(session:ChatSession,status:string){await postData('/message/admin/session/status',{sessionId:session.id,status});await loadGovernance();ElMessage.success('会话状态已更新');}
function previewGovernance(kind:string,title:string,content:string){governancePreviewKind.value=kind;governancePreviewTitle.value=title||'未命名内容';governancePreviewContent.value=content||'暂无正文';governancePreviewDialog.value=true;}
async function previewAiAuditSession(session:AiSession){const history=await getData<{messages:{role:string;content:string;created_at?:string}[]}>(`/ai/history?user_id=${session.user_id}&session_id=${session.id}`);const content=history.messages.map(message=>`${message.role==='user'?'用户':'AI'}：${message.content}`).join('\n\n');previewGovernance('AI 会话',session.title,content);}
async function deleteAdminComment(comment:Comment){await ElMessageBox.confirm('确认删除这条违规评论？','删除评论',{type:'warning'});await deleteData('/comment/admin',{commentId:comment.id});await loadGovernance();ElMessage.success('评论已删除');}
async function setAdminCommentStatus(comment:Comment,status:string){await postData('/comment/admin/status',{commentId:comment.id,status});await loadGovernance();ElMessage.success(status==='HIDDEN'?'评论已隐藏':'评论已恢复');}
async function deleteAdminDraft(draft:Draft){await ElMessageBox.confirm(`确认删除草稿“${draft.title}”？`,'删除草稿',{type:'warning'});await deleteData('/post/admin/draft',{draftId:draft.id});await loadGovernance();ElMessage.success('草稿已删除');}
async function cleanupExpiredDrafts(){const days=aiConfig.value.draft_retention_days;await ElMessageBox.confirm(`确认清理 ${days} 天前未更新的草稿？`,'清理过期草稿',{type:'warning'});const result=await deleteData<{removed:number}>('/post/admin/drafts/expired',{retentionDays:days});await loadGovernance();ElMessage.success(`已清理 ${result.removed} 份过期草稿`);}
async function openUserGovernance(user:UserRecord){userGovernanceForm.value={userId:user.id,nickname:user.nickname,avatarUrl:user.avatarUrl||'',signature:user.signature||'',status:user.status,role:user.role,publishPolicy:user.publishPolicy||'STANDARD',messagingEnabled:user.messagingEnabled!==false,resetPassword:'',email:user.email||null,emailVerified:user.emailVerified===true,removeEmail:false};const [knowledge,posts,draftsResult,behaviorResult]=await Promise.all([getData<KnowledgeFile[]>(`/knowledge/mine?type=UPLOADED&userId=${user.id}`),getData<Post[]>(`/square/feed?authorUserId=${user.id}`),getData<Draft[]>(`/post/drafts?userId=${user.id}`),getData<BehaviorRecord[]>(`/user/behaviors?userId=${user.id}`)]);adminUserKnowledge.value=knowledge;adminUserPosts.value=posts;adminUserDrafts.value=draftsResult;adminUserBehaviors.value=behaviorResult;userGovernanceDialog.value=true;}
const governingSelf = computed(()=>userGovernanceForm.value.userId===currentUserId.value);
async function openUserAudit(){const form=userGovernanceForm.value;auditSubjectUserId.value=form.userId;auditSubjectLabel.value=form.nickname||`#${form.userId}`;keepAuditSubject=true;userGovernanceDialog.value=false;await selectView('audit');}
async function saveUserGovernance(){const {email:_email,emailVerified:_verified,...changes}=userGovernanceForm.value;await postData('/user/admin/governance',changes);userGovernanceDialog.value=false;usersRefreshKey.value++;ElMessage.success('用户资料与权限已更新');}
// total is only sent with the first page; later pages send null and the table keeps the first figure.
type AdminPage<T> = { items:T[]; nextCursor:number|string|null; hasMore:boolean; total:number|null };
function adminTicketsUrl(cursor:number|string|null){
  const params=new URLSearchParams({limit:String(ADMIN_TICKET_PAGE_SIZE)});
  if(cursor)params.set('cursor',String(cursor));
  if(adminTicketKeyword.value.trim())params.set('keyword',adminTicketKeyword.value.trim());
  if(adminTicketStatus.value)params.set('status',adminTicketStatus.value);
  return `/feedback/tickets/page?${params.toString()}`;
}
async function loadAdminTickets(reset=true){
  const token=++adminTicketToken;adminTicketLoading.value=true;
  try{
    const [page,overview]=await Promise.all([
      getData<AdminPage<Ticket>>(adminTicketsUrl(reset?null:adminTicketCursor.value)),
      reset?getData<Record<string,unknown>>('/feedback/admin/overview'):Promise.resolve(undefined)]);
    if(token!==adminTicketToken)return;
    const known=new Set(reset?[]:adminTickets.value.map(ticket=>ticket.id));
    adminTickets.value=reset?page.items:[...adminTickets.value,...page.items.filter(ticket=>!known.has(ticket.id))];
    adminTicketCursor.value=page.nextCursor;adminTicketHasMore.value=page.hasMore;adminTicketTotal.value=page.total??adminTicketTotal.value;
    if(overview)adminOverview.value={...adminOverview.value,feedbackAdmin:overview};
  } finally { if(token===adminTicketToken)adminTicketLoading.value=false; }
}
async function loadMoreAdminTickets(){ if(adminTicketHasMore.value&&!adminTicketLoading.value)await loadAdminTickets(false); }
async function loadTicketsAdmin(){
  const [,faqList,admins]=await Promise.all([
    loadAdminTickets(true),
    getData<Faq[]>('/feedback/faqs'),
    getData<AdminPage<UserRecord>>('/user/admin/users/page?role=ADMIN&status=ACTIVE&limit=100')]);
  faqs.value=faqList;assignableAdmins.value=admins.items;
}
let adminTicketFilterTimer:ReturnType<typeof setTimeout>|undefined;
watch([adminTicketKeyword,adminTicketStatus],()=>{
  if(portal.value!=='admin'||activeView.value!=='tickets')return;
  clearTimeout(adminTicketFilterTimer);
  adminTicketFilterTimer=setTimeout(()=>void loadAdminTickets(true).catch(notifyError),250);
});
function openTicketReply(ticket:Ticket){ticketReply.value={ticketId:ticket.id,status:ticket.status==='RESOLVED'?'RESOLVED':'PROCESSING',reply:ticket.reply||''};ticketDialog.value=true;}
async function replyTicket(){await postData('/feedback/admin/reply',ticketReply.value);ticketDialog.value=false;await loadTicketsAdmin();ElMessage.success('工单已更新');}
async function assignTicket(ticket:Ticket,assigneeUserId:number|string){await postData('/feedback/admin/assign',{ticketId:ticket.id,assigneeUserId:Number(assigneeUserId)||0});await loadTicketsAdmin();ElMessage.success(assigneeUserId?'工单已分配':'已取消分配');}
function editFaq(faq:Faq){faqForm.value={...faq,enabled:1};faqDialog.value=true;}
async function saveFaq(){await postData('/feedback/admin/faq',{...faqForm.value,id:faqForm.value.id||undefined});faqDialog.value=false;faqForm.value={id:0,question:'',answer:'',sortNo:10,enabled:1};await loadTicketsAdmin();ElMessage.success('常见问题已保存');}
async function deleteFaq(faq:Faq){await ElMessageBox.confirm(`确认删除“${faq.question}”？`,'删除常见问题',{type:'warning'});await deleteData('/feedback/admin/faq',{faqId:faq.id});await loadTicketsAdmin();}
async function loadAnalytics(){
  const token=++analyticsRequestToken;const days=analyticsDays.value;
  const [userOverview,knowledgeOverview,forumOverview,feedbackOverview,knowledgeStats,forumStats,ticketStats]=await Promise.all([
    getData<Record<string,unknown>>('/user/admin/overview'),
    getData<Record<string,unknown>>('/knowledge/admin/overview'),
    getData<Record<string,unknown>>('/post/admin/overview'),
    getData<Record<string,unknown>>('/feedback/admin/overview'),
    getData<KnowledgeAnalytics>(`/knowledge/admin/analytics?days=${days}`),
    getData<ForumAnalytics>(`/post/admin/analytics?days=${days}`),
    getData<TicketAnalytics>(`/feedback/admin/analytics?days=${days}`)]);
  if(token!==analyticsRequestToken)return;
  adminOverview.value={userAdmin:userOverview,knowledgeAdmin:knowledgeOverview,forumAdmin:forumOverview,feedbackAdmin:feedbackOverview};
  knowledgeAnalytics.value=knowledgeStats;forumAnalytics.value=forumStats;ticketAnalytics.value=ticketStats;
}
watch(analyticsDays,()=>{if(portal.value==='admin'&&activeView.value==='analytics')void refreshCurrentView();});
async function loadRuntimeModes(){
  const endpoints=['/gateway/status','/user/health','/knowledge/health','/post/health','/message/health','/knowledge/storage/status','/knowledge/search/status','/event/status','/ai/health','/ai/vector/status'];
  const results=await Promise.allSettled(endpoints.map(endpoint=>getData<Record<string,unknown>>(endpoint)));
  const value=(index:number,key:string,fallback='unknown')=>results[index].status==='fulfilled'?String(results[index].value[key]??fallback):'unknown';
  const flag=(index:number,key:string,fallback=true)=>results[index].status==='fulfilled'?Boolean(results[index].value[key]??fallback):false;
  const available=(index:number)=>results[index].status==='fulfilled';
  runtimeModes.value=[
    {key:'user-data',label:'用户数据',mode:value(1,'dataMode'),detail:'账号、关系和行为记录',available:available(1),businessData:true},
    {key:'knowledge-data',label:'知识数据',mode:value(2,'dataMode'),detail:'知识元数据与互动记录',available:available(2),businessData:true},
    {key:'community-data',label:'社区数据',mode:value(3,'dataMode'),detail:'帖子、评论和草稿',available:available(3),businessData:true},
    {key:'message-data',label:'消息数据',mode:value(4,'dataMode'),detail:'私信、通知与反馈工单',available:available(4),businessData:true},
    {key:'file-storage',label:'文件存储',mode:value(5,'mode'),detail:'知识文件和正文附件',available:available(5),healthy:value(5,'mode')!=='minio'||flag(5,'minioReady')},
    {key:'community-media',label:'社区图片',mode:value(3,'mediaStorageMode'),detail:'帖子配图存储',available:available(3)},
    {key:'search',label:'全文检索',mode:value(6,'mode'),detail:'知识标题与正文搜索',available:available(6),healthy:value(6,'mode')==='local'||flag(6,'elasticsearchReady')},
    {key:'events',label:'消息事件',mode:value(7,'mode'),detail:'业务事件投递与留存',available:available(7),healthy:value(7,'mode')==='local'||flag(7,'rabbitReady')},
    {key:'vector',label:'向量检索',mode:value(9,'mode'),detail:'AI 知识片段检索',available:available(9),healthy:value(9,'mode')==='local'||flag(9,'external_ready')},
    {key:'ai-database',label:'AI 数据库',mode:value(8,'database_mode'),detail:'AI 会话与知识切片',available:available(8)},
    {key:'rate-limit',label:'网关限流',mode:value(0,'rateLimitMode'),detail:'接口访问频率控制',available:available(0)},
    {key:'discovery',label:'服务注册',mode:value(0,'discoveryMode'),detail:'微服务发现与路由',available:available(0)}
  ];
}
async function loadSystemAdmin(){const [overview,events]=await Promise.all([getData<Record<string,unknown>>('/ai/admin/overview'),getData<EventRecord[]>('/event/list?limit=50'),loadRuntimeModes()]);aiOverview.value=overview;adminEvents.value=events;const config=overview.configuration as typeof aiConfig.value|undefined;if(config)aiConfig.value={...aiConfig.value,...config,selected_file_ids:Array.isArray(config.selected_file_ids)?config.selected_file_ids:[]};adminConfigSnapshot.value=JSON.stringify(aiConfig.value);await Promise.all([loadSelectedAiSources(),searchAiSourceFiles('')]);}
// The source picker searches the approved library on the server; chosen files keep their labels between searches.
const AI_SOURCE_LOOKUP_BATCH = 200;
async function loadSelectedAiSources(){
  const ids=[...new Set(aiConfig.value.selected_file_ids)];
  const found:{id:number;title:string}[]=[];
  for(let start=0;start<ids.length;start+=AI_SOURCE_LOOKUP_BATCH){
    found.push(...await getData<{id:number;title:string}[]>(`/knowledge/admin/files/by-ids?ids=${ids.slice(start,start+AI_SOURCE_LOOKUP_BATCH).join(',')}`));
  }
  const known=new Map(aiSourceOptions.value.map(file=>[file.id,file]));
  found.forEach(file=>known.set(file.id,{id:file.id,title:file.title}));
  aiSourceOptions.value=[...known.values()];
}
async function searchAiSourceFiles(keyword:string){
  const token=++aiSourceToken;aiSourceSearching.value=true;
  try{
    const params=new URLSearchParams({limit:'20'});
    if(keyword.trim())params.set('keyword',keyword.trim());
    const page=await getData<AdminPage<KnowledgeFile>>(`/knowledge/page?${params.toString()}`);
    if(token!==aiSourceToken)return;
    const selected=new Set(aiConfig.value.selected_file_ids);
    aiSourceOptions.value=[...aiSourceOptions.value.filter(file=>selected.has(file.id)),...page.items.filter(file=>!selected.has(file.id)).map(file=>({id:file.id,title:file.title}))];
  } finally { if(token===aiSourceToken)aiSourceSearching.value=false; }
}
async function mapWithConcurrency<T,R>(items:T[],limit:number,task:(item:T)=>Promise<R>):Promise<R[]>{
  const results:R[]=new Array(items.length);let next=0;
  await Promise.all(Array.from({length:Math.min(limit,items.length)},async()=>{while(next<items.length){const index=next++;results[index]=await task(items[index]);}}));
  return results;
}
async function discardAiConfig(){await loadSystemAdmin();ElMessage.success('已恢复为当前生效配置');}
async function saveAiConfig(){if(!aiConfig.value.platform_name.trim()){ElMessage.warning('平台名称不能为空');return;}if(aiConfig.value.data_source_scope==='admin-selected'&&!aiConfig.value.selected_file_ids.length){ElMessage.warning('请至少选择一个 AI 知识来源');return;}await postData('/ai/admin/config',aiConfig.value);await Promise.all([loadSystemAdmin(),loadPublicConfig()]);ElMessage.success('平台配置已保存并开始生效');}
// Rebuilds page through the approved library and index it in batches; the first batch clears the old index.
const AI_REBUILD_BATCH = 50;
async function rebuildAiIndex(){
  aiIndexBusy.value=true;aiIndexProgress.value='';
  try{
    let cursor:number|string|null=null;let first=true;let scanned=0;let documentsTotal=0;let chunksTotal=0;
    do{
      const params=new URLSearchParams({limit:String(AI_REBUILD_BATCH)});
      if(cursor!==null)params.set('cursor',String(cursor));
      const page:AdminPage<KnowledgeFile>=await getData<AdminPage<KnowledgeFile>>(`/knowledge/page?${params.toString()}`);
      const details=await mapWithConcurrency(page.items,4,file=>getData<KnowledgeFile>(`/knowledge/admin/preview?fileId=${file.id}`));
      const documents=details.filter(detail=>detail.content?.trim()).map(detail=>({file_id:detail.id,title:detail.title,text:detail.content as string}));
      const result=await postData<{documents:number;chunks:number}>('/ai/admin/index/rebuild',{documents,reset:first});
      first=false;scanned+=page.items.length;documentsTotal+=result.documents;chunksTotal+=result.chunks;
      aiIndexProgress.value=String(scanned);
      cursor=page.hasMore?page.nextCursor:null;
    }while(cursor!==null);
    await loadSystemAdmin();
    ElMessage.success(`已重建 ${documentsTotal} 个文档、${chunksTotal} 个知识切片`);
  }catch(error){notifyError(error);}finally{aiIndexBusy.value=false;aiIndexProgress.value='';}
}

function handleGlobalKeydown(event:KeyboardEvent){if(event.key==='Escape')mobileMenuOpen.value=false;}
function handleBeforeUnload(event:BeforeUnloadEvent){if(!aiConfigDirty.value)return;event.preventDefault();event.returnValue='';}
watch(mobileMenuOpen,open=>document.body.classList.toggle('mobile-menu-active',open));
watch(moderationTab,()=>adminModerationStatus.value='');
onMounted(async()=>{onSessionExpired(()=>{if(!authenticated.value)return;endLocalSession();ElMessage.warning('登录已失效，请重新登录');});syncUserForms();window.addEventListener('popstate',handlePopState);window.addEventListener('keydown',handleGlobalKeydown);window.addEventListener('beforeunload',handleBeforeUnload);document.addEventListener('visibilitychange',handleVisibilityChange);await loadPublicConfig();if(authenticated.value)await restoreSession();else await loadCaptcha();});
onBeforeUnmount(()=>{window.removeEventListener('popstate',handlePopState);window.removeEventListener('keydown',handleGlobalKeydown);window.removeEventListener('beforeunload',handleBeforeUnload);document.removeEventListener('visibilitychange',handleVisibilityChange);stopMessagePolling();feedObserver?.disconnect();knowledgeObserver?.disconnect();document.body.classList.remove('mobile-menu-active');if(captchaCooldownTimer)clearInterval(captchaCooldownTimer);if(captchaExpiryTimer)clearTimeout(captchaExpiryTimer);pdfPreviewUrl.value='';detailPdfPreviewUrl.value='';});
</script>
