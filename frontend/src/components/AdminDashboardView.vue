<template>
  <section class="page-stack">

            <div class="page-toolbar"><div><h3>平台运营概览</h3><p>内容、用户、互动和待办事项的实时摘要</p></div><el-button :icon="Refresh" @click="loadAdminDashboard">刷新数据</el-button></div>
            <div v-if="adminAttentionCount" class="admin-attention-banner"><span><Warning /><strong>当前共有 {{ adminAttentionCount }} 项待处理事项</strong></span><div><el-button v-if="moderationOpenCount" text type="warning" @click="openAdminQueue('knowledge')">审核 {{ moderationOpenCount }}</el-button><el-button v-if="ticketOpenCount" text type="primary" @click="selectView('tickets')">工单 {{ ticketOpenCount }}</el-button></div></div>
            <div class="metrics-grid admin-metrics"><div v-for="metric in adminMetrics" :key="metric.label" class="metric-tile"><span :class="['metric-icon', metric.color]"><component :is="metric.icon" /></span><div><strong>{{ metric.value }}</strong><span>{{ metric.label }}</span><small>{{ metric.hint }}</small></div></div></div>
            <div class="two-column"><section class="surface"><div class="surface-head"><div><h3>待处理事项</h3><p>优先处理积压内容</p></div></div><div class="task-list"><button @click="openAdminQueue('knowledge')"><span class="task-dot amber"></span><span><strong>知识审核</strong><small>用户上传资源审核</small></span><b>{{ metricValue('knowledgeAdmin','pendingAudit') }}</b></button><button @click="openAdminQueue('profiles')"><span class="task-dot amber"></span><span><strong>资料审核</strong><small>会员昵称与签名修改</small></span><b>{{ metricValue('userAdmin','pendingAudits') }}</b></button><button @click="openAdminQueue('reports')"><span class="task-dot red"></span><span><strong>内容举报</strong><small>知识与用户举报</small></span><b>{{ metricValue('knowledgeAdmin','openReports') + metricValue('userAdmin','openReports') }}</b></button><button @click="selectView('tickets')"><span class="task-dot blue"></span><span><strong>反馈工单</strong><small>待回复用户问题</small></span><b>{{ ticketOpenCount }}</b></button></div></section><section class="surface"><div class="surface-head"><div><h3>系统状态</h3><p>来自各服务健康接口的实时结果</p></div><el-tag :type="healthItems.every(item=>item.ok)?'success':'danger'">{{ healthItems.every(item=>item.ok)?'运行正常':'存在异常' }}</el-tag></div><div class="status-list"><span v-for="item in healthItems" :key="item.label"><i :class="{offline:!item.ok}"></i>{{ item.label }}<strong :class="{offline:!item.ok}">{{ item.ok?'正常':'异常' }}</strong></span></div></section></div>
  </section>
</template>

<script setup lang="ts">
import { computed, markRaw, type Ref, type ComputedRef } from 'vue';
import { ChatDotRound, Files, Refresh, Tickets, UserFilled } from '@element-plus/icons-vue';

/** Everything this view shows belongs to the page that loads it; it arrives together. */

type PageState = {
  metricValue:(section:string,key:string)=>number;
  moderationOpenCount:ComputedRef<number>;
  ticketOpenCount:ComputedRef<number>;
  systemHealth:Ref<Record<string,boolean>>;
  loadAdminDashboard:()=>Promise<void>;
  openAdminQueue:(tab:string)=>void;
  selectView:(key:string)=>Promise<void>;
};

const props = defineProps<{ page: PageState }>();

const { loadAdminDashboard, metricValue, moderationOpenCount, openAdminQueue, selectView, systemHealth, ticketOpenCount } = props.page;

const adminMetrics = computed(() => [{label:'注册用户',value:metricValue('userAdmin','totalUsers'),hint:`${metricValue('userAdmin','activeUsers')} 个正常账号`,color:'green',icon:markRaw(UserFilled)},{label:'知识资源',value:metricValue('knowledgeAdmin','totalFiles'),hint:`${metricValue('knowledgeAdmin','pendingAudit')} 个待审核`,color:'blue',icon:markRaw(Files)},{label:'社区帖子',value:metricValue('forumAdmin','publishedPosts'),hint:'全站内容产出',color:'amber',icon:markRaw(ChatDotRound)},{label:'反馈工单',value:metricValue('feedbackAdmin','tickets'),hint:`${metricValue('feedbackAdmin','pendingTickets')} 个待处理`,color:'red',icon:markRaw(Tickets)}]);
const healthItems = computed(() => [{label:'网关与用户服务',ok:systemHealth.value.user},{label:'知识与全文检索',ok:systemHealth.value.knowledge},{label:'社区与消息服务',ok:systemHealth.value.community&&systemHealth.value.message},{label:'AI 向量检索',ok:systemHealth.value.ai}]);
const adminAttentionCount = computed(()=>moderationOpenCount.value+ticketOpenCount.value);
</script>
