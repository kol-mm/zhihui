<template>
  <section class="page-stack">
<div class="page-toolbar"><div><h3>平台数据统计</h3><p>社区产出、互动热度、违规风险和反馈趋势</p></div><el-segmented v-model="analyticsDays" :options="[{label:'近 7 天',value:7},{label:'近 30 天',value:30},{label:'近 90 天',value:90}]" /></div><div class="metrics-grid admin-metrics"><div class="metric-tile"><span class="metric-icon green"><UserFilled /></span><div><strong>{{ metricValue('userAdmin','activeUsers') }}</strong><span>活跃账号</span><small>全站正常用户</small></div></div><div class="metric-tile"><span class="metric-icon blue"><Files /></span><div><strong>{{ analyticsKnowledgeCount }}</strong><span>知识产出</span><small>所选周期内</small></div></div><div class="metric-tile"><span class="metric-icon amber"><ChatDotRound /></span><div><strong>{{ analyticsPostCount }}</strong><span>社区帖子</span><small>已发布内容</small></div></div><div class="metric-tile"><span class="metric-icon red"><DataAnalysis /></span><div><strong>{{ analyticsInteractions }}</strong><span>内容互动</span><small>浏览、下载与点赞</small></div></div></div><section class="surface"><div class="surface-head"><div><h3>近 {{ analyticsTrendDays }} 天内容趋势</h3><p>每日新增知识、帖子与反馈工单</p></div><div class="analytics-legend"><span class="knowledge">知识</span><span class="post">帖子</span><span class="ticket">工单</span></div></div><div class="analytics-trend"><div v-for="item in analyticsTrend" :key="item.label" class="trend-day"><div class="trend-bars"><i class="knowledge" :style="{height:`${Math.max(3,item.knowledge/analyticsTrendMax*100)}%`}" :title="`知识 ${item.knowledge}`"></i><i class="post" :style="{height:`${Math.max(3,item.posts/analyticsTrendMax*100)}%`}" :title="`帖子 ${item.posts}`"></i><i class="ticket" :style="{height:`${Math.max(3,item.tickets/analyticsTrendMax*100)}%`}" :title="`工单 ${item.tickets}`"></i></div><small>{{ item.label }}</small></div></div></section><div class="two-column"><section class="surface"><div class="surface-head"><div><h3>热门知识</h3><p>全站累计，按浏览、下载和点赞综合排序，每分钟更新</p></div></div><div class="analytics-ranking"><article v-for="(file,index) in topKnowledge" :key="file.id"><b>{{ index+1 }}</b><span><strong>{{ file.title }}</strong><small>浏览 {{ file.views||0 }} · 下载 {{ file.downloads||0 }} · 点赞 {{ file.likes||0 }}</small></span></article></div><el-empty v-if="!topKnowledge.length" description="暂无知识数据" /></section><section class="surface"><div class="surface-head"><div><h3>热门帖子与风险</h3><p>互动内容及待处置事项</p></div><el-tag type="danger" effect="plain">{{ analyticsReportCount }} 项举报</el-tag></div><div class="analytics-ranking"><article v-for="(post,index) in topPosts" :key="post.id"><b>{{ index+1 }}</b><span><strong>{{ post.title }}</strong><small>点赞 {{ post.likes||0 }} · 作者 #{{ post.userId }}</small></span></article></div><el-empty v-if="!topPosts.length" description="暂无社区数据" /></section></div><section class="surface"><div class="surface-head"><div><h3>反馈结构</h3><p>所选周期内工单类型与处理进度</p></div></div><div class="governance-user-summary"><span>系统问题 <strong>{{ analyticsFeedback.bug }}</strong></span><span>产品建议 <strong>{{ analyticsFeedback.suggestion }}</strong></span><span>客服咨询 <strong>{{ analyticsFeedback.support }}</strong></span><span>已完结 <strong>{{ analyticsFeedback.resolved }}</strong></span></div></section>
  </section>
</template>

<script setup lang="ts">
import { computed, type Ref } from 'vue';
import { ChatDotRound, DataAnalysis, Files, UserFilled } from '@element-plus/icons-vue';

type AnalyticsTrendPoint = { date: string; count: number };
type KnowledgeAnalytics = { days: number; trendDays: number; files: number; views: number; downloads: number; likes: number; trend: AnalyticsTrendPoint[]; top: { id: number; title: string; views: number; downloads: number; likes: number }[] };
type ForumAnalytics = { days: number; trendDays: number; posts: number; likes: number; trend: AnalyticsTrendPoint[]; top: { id: number; userId: number; title: string; likes: number }[] };
type TicketAnalytics = { days: number; trendDays: number; total: number; bug: number; suggestion: number; support: number; resolved: number; trend: AnalyticsTrendPoint[] };

/** Everything this view shows belongs to the page that loads it; it arrives together. */
const props = defineProps<{ page: {
  knowledgeAnalytics:Ref<KnowledgeAnalytics|undefined>;
  forumAnalytics:Ref<ForumAnalytics|undefined>;
  ticketAnalytics:Ref<TicketAnalytics|undefined>;
  analyticsDays:Ref<number>;
  metricValue:(section:string,key:string)=>number;
} }>();

const { analyticsDays, forumAnalytics, knowledgeAnalytics, metricValue, ticketAnalytics } = props.page;

const analyticsKnowledgeCount = computed(() => knowledgeAnalytics.value?.files ?? 0);
const analyticsPostCount = computed(() => forumAnalytics.value?.posts ?? 0);
const analyticsInteractions = computed(() => (knowledgeAnalytics.value?.views ?? 0)+(knowledgeAnalytics.value?.downloads ?? 0)+(knowledgeAnalytics.value?.likes ?? 0)+(forumAnalytics.value?.likes ?? 0));
const analyticsFeedback = computed(() => ({bug:ticketAnalytics.value?.bug ?? 0,suggestion:ticketAnalytics.value?.suggestion ?? 0,support:ticketAnalytics.value?.support ?? 0,resolved:ticketAnalytics.value?.resolved ?? 0}));
const analyticsReportCount = computed(() => metricValue('knowledgeAdmin','reports')+metricValue('userAdmin','reports'));
const analyticsTrendDays = computed(() => knowledgeAnalytics.value?.trendDays ?? Math.min(analyticsDays.value,14));
const topKnowledge = computed(() => knowledgeAnalytics.value?.top ?? []);
const topPosts = computed(() => forumAnalytics.value?.top ?? []);
const analyticsTrend = computed(() => {
  const posts=new Map((forumAnalytics.value?.trend??[]).map(point=>[point.date,point.count]));
  const tickets=new Map((ticketAnalytics.value?.trend??[]).map(point=>[point.date,point.count]));
  return (knowledgeAnalytics.value?.trend??[]).map(point=>{
    const day=new Date(`${point.date}T00:00:00`);
    return{label:`${day.getMonth()+1}/${day.getDate()}`,knowledge:point.count,posts:posts.get(point.date)??0,tickets:tickets.get(point.date)??0};
  });
});
const analyticsTrendMax = computed(() => Math.max(1,...analyticsTrend.value.flatMap(item=>[item.knowledge,item.posts,item.tickets])));
</script>
