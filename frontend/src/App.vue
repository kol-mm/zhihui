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
         <WorkbenchView v-if="activeView === 'home'" :page="workbenchViewPage" />
         <KnowledgeLibraryView v-else-if="activeView === 'knowledge'" :page="knowledgeLibraryViewPage" />
         <CommunityFeedView v-else-if="activeView === 'forum' || activeView === 'square'" :page="communityFeedViewPage" />
         <MessagesView v-else-if="activeView === 'messages'" :page="messagesViewPage" />
         <AiChatView v-else-if="activeView === 'ai'" :page="aiChatViewPage" />
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
          <AdminDashboardView v-if="activeView === 'dashboard'" :page="dashboardPage" />
          <AdminModerationView v-else-if="activeView === 'moderation'" :moderation="moderation" :actions="moderationActions" :metric-value="metricValue" :auditing-post-id="auditingPostId" :auditing-profile-change-id="auditingProfileChangeId" />
          <AdminUsersView v-else-if="activeView === 'users'" :refresh-key="usersRefreshKey" :current-user-id="currentUserId" :metrics="adminOverview.userAdmin" @governance="openUserGovernance" @overview="applyUserOverview" />
          <AdminAnalyticsView v-else-if="activeView === 'analytics'" :page="analyticsPage" />
          <AdminAuditLog v-else-if="activeView === 'audit'" :key="auditRefreshKey" v-model:subject-user-id="auditSubjectUserId" :subject-label="auditSubjectLabel" />
          <AdminTicketsView v-else-if="activeView === 'tickets'" :page="ticketsPage" />
          <AdminSettingsView v-else :settings="platformSettings" :actions="settingsActions" />
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
import { createListLoader, emptyModerationList, useModeration, type ModerationList, type ModerationPage, type OverviewSections } from './composables/moderation';
import { detailPath, linkedCommentFrom, noticeDestination, type NoticeTarget } from './utils/noticeTargets';
import { knowledgeCache } from './utils/knowledgeCache';
import { markOnboardingSeen, shouldShowOnboarding } from './utils/onboarding';
import { uploadSizeProblem } from './utils/uploadLimits';
import { parseStatusLabel, parseStatusTone } from './utils/parseStatus';
import { auditDecisionNote, auditSourceLabel, auditSourceTone } from './utils/auditSource';
import { reviewCounters, reviewVerdict, type ReviewHealth } from './utils/reviewHealth';
import { auditLabel, formatDate, postStatusLabel, publishPolicyLabel, reportStatusLabel, roleLabel, sessionStatusLabel } from './utils/statusLabels';
import { pendingChanges, profileAuditChanges, profileAuditStatusLabel, profileAuditStatusType, type ProfileAuditState, type ProfileChange } from './utils/profileAudit';
import AdminDashboardView from './components/AdminDashboardView.vue';
import AdminAnalyticsView from './components/AdminAnalyticsView.vue';
import AdminTicketsView from './components/AdminTicketsView.vue';
import AiChatView from './components/AiChatView.vue';
import MessagesView from './components/MessagesView.vue';
import CommunityFeedView from './components/CommunityFeedView.vue';
import KnowledgeLibraryView from './components/KnowledgeLibraryView.vue';
import WorkbenchView from './components/WorkbenchView.vue';
import AdminModerationView from './components/AdminModerationView.vue';
import AdminSettingsView from './components/AdminSettingsView.vue';
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
const adminTicketKeyword = ref(''); const adminTicketStatus = ref('');
const ADMIN_TICKET_PAGE_SIZE = 20;
const adminTicketTotal = ref(0); const adminTicketCursor = ref<number|string|null>(null); const adminTicketHasMore = ref(false); const adminTicketLoading = ref(false); let adminTicketToken = 0;
const assignableAdmins = ref<UserRecord[]>([]);
const adminConfigSnapshot = ref('');
const viewLoading = ref(false); const viewError = ref('');
const feedMode = ref<'all'|'following'|'mine'|'author'>('all'); const authorFilterUserId = ref(0); const governanceTab = ref('comments');
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
// Paged results are already filtered by the server; full-text search results keep the client-side filters.


const currentMessageSession = computed(() => sessions.value.find(session => session.id === messageForm.value.sessionId));
const postDialogTitle = computed(() => editingPostId.value ? '编辑社区帖子' : editingDraftId.value ? '编辑草稿' : '发布社区帖子');
const businessModeMismatch = computed(()=>new Set(runtimeModes.value.filter(item=>item.businessData&&item.available).map(item=>item.mode)).size>1);
const runtimeModeProblem = computed(()=>businessModeMismatch.value||runtimeModes.value.some(item=>!item.available||item.healthy===false));
const communityDirectory = computed<UserRecord[]>(() => [communityUser(currentUserId.value), ...Object.values(userSummaries.value).filter(user => user.id !== currentUserId.value)]);
const followedUsers = computed(() => (followData.value.followedUserIds || []).map(communityUser));
const followerUsers = computed(() => (followData.value.followerUserIds || []).map(communityUser));





const governanceResultCount = computed(() => ({ messages:governanceChats.value.total, comments:governanceComments.value.total, drafts:governanceDrafts.value.total, sessions:governanceAiSessions.value.total, chunks:governanceChunks.value.total }[governanceTab.value] || 0));


const auditingProfileChangeId = ref(0);
// 内容审核 keeps its lists, filters and loaders in a composable; the page keeps the dialogs that act on a row
// and the overview these counts feed.
const loadModerationList = createListLoader(<T,>(url:string)=>getData<ModerationPage<T>>(url));
const moderation = useModeration<KnowledgeFile,Post,Report,ProfileChange>({
  loadList: loadModerationList,
  fetchOverview: (url:string)=>getData<Record<string,unknown>>(url),
  onOverview: (sections:OverviewSections)=>{adminOverview.value={...adminOverview.value,...sections};},
  onError: (error:unknown)=>notifyError(error),
  isActive: ()=>portal.value==='admin'&&activeView.value==='moderation',
});
const { moderationTab, loadModeration } = moderation;
const governanceChats = ref<ModerationList<ChatSession>>(emptyModerationList());
const governanceComments = ref<ModerationList<Comment>>(emptyModerationList());
const governanceDrafts = ref<ModerationList<Draft>>(emptyModerationList());
const governanceAiSessions = ref<ModerationList<AiSession>>(emptyModerationList());
const governanceChunks = ref<ModerationList<AiChunk>>(emptyModerationList());





const moderationOpenCount = computed(()=>metricValue('knowledgeAdmin','pendingAudit')+metricValue('forumAdmin','pendingAudit')+metricValue('userAdmin','pendingAudits')+metricValue('knowledgeAdmin','openReports')+metricValue('userAdmin','openReports'));
const ticketOpenCount = computed(()=>metricValue('feedbackAdmin','pendingTickets')+metricValue('feedbackAdmin','processingTickets'));
const adminWorkspaceNavigation = computed<NavigationItem[]>(()=>adminNavigation.map(item=>({...item,badge:item.key==='moderation'?moderationOpenCount.value||undefined:item.key==='tickets'?ticketOpenCount.value||undefined:item.key==='system'&&runtimeModeProblem.value?1:undefined})));
const aiConfigDirty = computed(()=>Boolean(adminConfigSnapshot.value)&&JSON.stringify(aiConfig.value)!==adminConfigSnapshot.value);
// The three services each zero-fill the same day window, so the knowledge series carries the
// axis and the other two are looked up by date.

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
function ticketStatusLabel(status:string){ return ({PENDING:'待处理',PROCESSING:'处理中',RESOLVED:'已解决'} as Record<string,string>)[status] || status; }
function ticketTypeLabel(type:string){ return ({BUG:'系统问题',SUGGESTION:'产品建议',SUPPORT:'客服咨询'} as Record<string,string>)[type] || type; }
function notificationTypeLabel(type?:string){ return ({SYSTEM:'系统通知',MESSAGE:'私信通知',COMMENT:'评论通知',REPLY:'回复通知',FEEDBACK:'反馈通知'} as Record<string,string>)[type || 'SYSTEM'] || '系统通知'; }
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
async function loadMyPosts(){feedMode.value='mine';await loadFeedFirstPage('mine');}
function resetPostEditor(){editingPostId.value=0;editingDraftId.value=0;postForm.value={userId:currentUserId.value,title:'',content:''};selectedPostImages.value=[];postImageFiles.value=[];}
function existingImageFiles(imageUrls?:string[]):UploadUserFile[]{return(imageUrls||[]).map((url,index)=>({name:`图片 ${index+1}`,url,status:'success'}));}
function editPost(post:Post){editingPostId.value=post.id;editingDraftId.value=0;postForm.value={userId:currentUserId.value,title:post.title,content:post.content};selectedPostImages.value=[];postImageFiles.value=existingImageFiles(post.imageUrls);postDialog.value=true;}
function editDraft(draft:Draft){editingDraftId.value=draft.id;editingPostId.value=0;postForm.value={userId:currentUserId.value,title:draft.title,content:draft.content};selectedPostImages.value=[];postImageFiles.value=existingImageFiles(draft.imageUrls);postDialog.value=true;}
async function publishDraft(draft:Draft){await ElMessageBox.confirm(`确认发布草稿“${draft.title}”？`,'发布草稿',{type:'info'});await postData('/post/draft/publish',{id:draft.id});await loadDrafts();ElMessage.success('帖子已提交审核');}
async function deleteDraft(draft:Draft){await ElMessageBox.confirm(`确认删除草稿“${draft.title}”？`,'删除草稿',{type:'warning'});await deleteData('/post/draft',{draftId:draft.id});await loadDrafts();ElMessage.success('草稿已删除');}
function openPostDetail(post:Post){navigateToDetail('community',post.id);}
async function likeDetailPost(post:Post){const result=await postData<PostLikeResult>('/post/like',{postId:post.id});if(detailPost.value?.id===post.id)detailPost.value={...detailPost.value,likes:result.likes,liked:result.liked};if(result.liked){await recordBehavior('LIKE','POST',post.id);ElMessage.success('已点赞');}else ElMessage.success('已取消点赞');}
async function collectPost(post:Post){const result=await postData<PostCollectResult>('/square/collect',{postId:post.id});post.collected=result.collected;if(detailPost.value?.id===post.id)detailPost.value={...detailPost.value,collected:result.collected};if(result.collected){await recordBehavior('COLLECT','POST',post.id);ElMessage.success('已收藏帖子');}else{collectedPosts.value=collectedPosts.value.filter(item=>item.id!==post.id);ElMessage.success('已取消收藏');}}
async function removeCollectedPost(post:Post){if(post.collected)await collectPost(post);}
async function deletePost(post:Post){await ElMessageBox.confirm(`删除帖子“${post.title}”后评论和互动记录都无法恢复，确认继续？`,'删除帖子',{type:'warning',confirmButtonText:'确认删除'});await deleteData('/post',{postId:post.id});feedPosts.value=feedPosts.value.filter(item=>item.id!==post.id);collectedPosts.value=collectedPosts.value.filter(item=>item.id!==post.id);if(detailPost.value?.id===post.id)leaveDetail();ElMessage.success('帖子已删除');}
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
// Admins can restrict or archive a conversation; sync keeps this status current while it is open.
const shouldPollMessages=computed(()=>authenticated.value&&portal.value==='client'&&activeView.value==='messages'&&!detailRoute.value&&Boolean(messageForm.value.sessionId)&&pageVisible.value);
function stopMessagePolling(){if(messagePollTimer!==undefined){window.clearInterval(messagePollTimer);messagePollTimer=undefined;}}
watch(shouldPollMessages,(active,wasActive)=>{
  stopMessagePolling();
  if(!active)return;
  messagePollTimer=window.setInterval(()=>{void fetchNewMessages().catch(()=>undefined);void refreshUnreadCount();},MESSAGE_POLL_INTERVAL);
  if(wasActive===false)void fetchNewMessages().catch(()=>undefined);
});
function handleVisibilityChange(){pageVisible.value=document.visibilityState!=='hidden';}
async function resolveConversationUser(){const query=conversationUsername.value.trim();if(!query){conversationTargetUser.value=undefined;conversationTargetId.value=0;return;}try{const user=await getData<UserRecord>(`/user/info?username=${encodeURIComponent(query)}`);if(user.id===currentUserId.value)throw new Error('不能给自己发起私信');conversationTargetUser.value=user;conversationTargetId.value=user.id;}catch(error){conversationTargetUser.value=undefined;conversationTargetId.value=0;notifyError(error);}}
async function createConversation(){if(!conversationTargetId.value)return;busy.value=true;try{const session=await postData<ChatSession>('/message/session',{targetUserId:conversationTargetId.value});await loadMessageData();selectMessageSession(session.id);await loadMessages();conversationDialog.value=false;activeView.value='messages';ElMessage.success('私信会话已创建');}catch(error){notifyError(error);}finally{busy.value=false;}}

// The detail page loads the file by id and reports it when it is no longer available.
async function loadAiHistory(){const history=await getData<{sessions:AiSession[]}> (`/ai/history?user_id=${currentUserId.value}&include_messages=false`);aiSessions.value=history.sessions;}

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
onMounted(async()=>{onSessionExpired(()=>{if(!authenticated.value)return;endLocalSession();ElMessage.warning('登录已失效，请重新登录');});syncUserForms();window.addEventListener('popstate',handlePopState);window.addEventListener('keydown',handleGlobalKeydown);window.addEventListener('beforeunload',handleBeforeUnload);document.addEventListener('visibilitychange',handleVisibilityChange);await loadPublicConfig();if(authenticated.value)await restoreSession();else await loadCaptcha();});
onBeforeUnmount(()=>{window.removeEventListener('popstate',handlePopState);window.removeEventListener('keydown',handleGlobalKeydown);window.removeEventListener('beforeunload',handleBeforeUnload);document.removeEventListener('visibilitychange',handleVisibilityChange);stopMessagePolling();feedObserver?.disconnect();knowledgeObserver?.disconnect();document.body.classList.remove('mobile-menu-active');if(captchaCooldownTimer)clearInterval(captchaCooldownTimer);if(captchaExpiryTimer)clearTimeout(captchaExpiryTimer);pdfPreviewUrl.value='';detailPdfPreviewUrl.value='';});
// What 内容审核 is allowed to do to a row. Each one opens or drives a dialog that belongs to the page,
// which is why they stay here and are handed to the view rather than moving into it.
const moderationActions = { auditKnowledge, auditPost, auditProfileChange, deleteAdminKnowledge, openKnowledgeMetadata, rejectProfileChange, resolveKnowledgeReport, resolveUserReport, reviewKnowledge, reviewPost, reviewReportedKnowledge, reviewReportedUser, updateKnowledgeStatus, openAdminKnowledgeUpload };

// AI 与系统 edits configuration the whole application reads, so the state stays here and the view is
// handed it; the same goes for the actions, which reach the page's own loaders and guards.
const platformSettings = { aiConfig, aiConfigDirty, aiSourceOptions, businessModeMismatch, reviewHealth, runtimeModes, runtimeModeProblem, superAdmin, aiSourceSearching, aiIndexProgress, adminEvents, aiOverview };
const settingsActions = { saveAiConfig, discardAiConfig, searchAiSourceFiles };

const dashboardPage = { loadAdminDashboard, metricValue, moderationOpenCount, openAdminQueue, selectView, systemHealth, ticketOpenCount };

const analyticsPage = { analyticsDays, forumAnalytics, knowledgeAnalytics, metricValue, ticketAnalytics };

const ticketsPage = { adminTicketHasMore, adminTicketKeyword, adminTicketLoading, adminTicketStatus, adminTicketTotal, adminTickets, assignTicket, assignableAdmins, deleteFaq, editFaq, faqDialog, faqs, loadMoreAdminTickets, openTicketReply, ticketStatusLabel, ticketTypeLabel };

const aiChatViewPage = { aiBusy, aiMessages, aiQuestion, aiSessionId, aiSessions, currentUserId, displayName, loadAiHistory, messages, mobileAiHistoryOpen, navigateToDetail, notifyError, role };

const messagesViewPage = { MESSAGE_PAGE_SIZE, communityUser, conversationDialog, conversationTargetId, conversationTargetUser, conversationUsername, currentMessageSession, currentUserId, fetchNewMessages, isMessageListNearBottom, loadMessageData, loadMessages, loadedMessageSessionId, mergeMessages, messageDrafts, messageForm, messageHasOlder, messageHistoryLoading, messageListRef, messagePageUrl, messageRefreshing, messageSending, messageUnseenCount, messages, notifyError, platformConfig, scrollMessagesToBottom, selectMessageSession, sessions, username };

const communityFeedViewPage = { activeView, authorFilterUserId, avatarUrl, collectPost, communityUser, currentUserId, deletePost, drafts, editPost, feedHasMore, feedLoadError, feedLoadingMore, feedMode, feedPosts, feedSentinelRef, isFollowing, loadAuthorPosts, loadFeed, loadMoreFeed, loadMyPosts, loadProfile, openPostDetail, platformConfig, postDialog, profileToolTab, recordBehavior, toggleFollowAuthor, username };

const knowledgeLibraryViewPage = { activeView, collectKnowledge, communityUser, currentUserId, deleteKnowledge, downloadKnowledge, forwardKnowledge, isFollowing, knowledgeActivityType, knowledgeCategories, knowledgeCategoryCounts, knowledgeCategoryId, knowledgeDialog, knowledgeFiles, knowledgeHasMore, knowledgeKeyword, knowledgeLoadError, knowledgeLoadingMore, knowledgeRanking, knowledgeSearchMode, knowledgeSentinelRef, knowledgeTotal, knowledgeType, loadMoreKnowledge, myKnowledge, openKnowledge, platformConfig, recordBehavior, reportKnowledge, searchKnowledge, toggleFollowAuthor, username };

const workbenchViewPage = { activeView, communityPostCount, communityUser, displayName, feedPosts, knowledgeDialog, knowledgeFiles, knowledgeTotal, notificationUnread, openKnowledge, openPostDetail, platformConfig, selectView, tickets };

</script>
