<template>
  <section class="page-stack">

            <div class="page-toolbar"><div><h3>工单与常见问题</h3><p>分配客服、跟进用户问题并维护自助答疑</p></div><el-button type="primary" :icon="Plus" @click="faqDialog = true">新增常见问题</el-button></div>
            <div class="two-column">
              <section class="surface"><div class="surface-head"><div><h3>反馈工单</h3><p>{{ adminTickets.length }} / {{ adminTicketTotal }} 条记录</p></div></div><div class="admin-filter-bar ticket-filter-bar"><el-input v-model="adminTicketKeyword" :prefix-icon="Search" clearable placeholder="搜索编号、用户或内容" /><el-select v-model="adminTicketStatus" clearable placeholder="全部状态"><el-option label="待处理" value="PENDING" /><el-option label="处理中" value="PROCESSING" /><el-option label="已解决" value="RESOLVED" /></el-select></div><article v-for="ticket in adminTickets" :key="ticket.id" class="ticket-row"><div><strong>#{{ ticket.id }} · {{ ticketTypeLabel(ticket.type) }}</strong><p>{{ ticket.content }}</p><small>用户 #{{ ticket.userId }}<template v-if="ticket.reply"> · 当前回复：{{ ticket.reply }}</template></small><small v-if="ticket.assignedAt">分配时间：{{ formatDate(ticket.assignedAt) }}</small></div><div class="ticket-actions"><el-select :model-value="ticket.assigneeUserId||0" size="small" class="ticket-assignee" @change="assignTicket(ticket, $event)"><el-option label="未分配" :value="0" /><el-option v-for="user in assignableAdmins" :key="user.id" :label="user.nickname" :value="user.id" /></el-select><el-tag :type="ticket.status === 'RESOLVED' ? 'success' : 'warning'">{{ ticketStatusLabel(ticket.status) }}</el-tag><el-button text type="primary" @click="openTicketReply(ticket)">处理</el-button></div></article><el-empty v-if="!adminTickets.length" description="没有匹配的工单" /><div v-if="adminTicketHasMore" class="knowledge-load-more"><el-button :loading="adminTicketLoading" @click="loadMoreAdminTickets">加载更多工单</el-button></div></section>
              <section class="surface"><div class="surface-head"><div><h3>常见问题</h3><p>用户端自助答疑内容</p></div></div><article v-for="faq in faqs" :key="faq.id" class="faq-row"><div><strong>{{ faq.question }}</strong><p>{{ faq.answer }}</p></div><div><el-button :icon="Edit" circle text @click="editFaq(faq)" /><el-button :icon="Delete" circle text type="danger" @click="deleteFaq(faq)" /></div></article><el-empty v-if="!faqs.length" description="暂无常见问题" /></section>
            </div>
  </section>
</template>

<script setup lang="ts">
import { type Ref } from 'vue';
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue';
import { formatDate } from '../utils/statusLabels';

/** Everything this view shows belongs to the page that loads it; it arrives together. */
type Ticket = { id:number; userId:number; type:string; content:string; status:string; reply?:string; assigneeUserId?:number; assignedAt?:string; closedAt?:string; createdAt?:string; updatedAt?:string };
type Faq = { id:number; question:string; answer:string; sortNo:number };

type PageState = {
  adminTickets:Ref<Ticket[]>;
  adminTicketKeyword:Ref<string>;
  adminTicketStatus:Ref<string>;
  adminTicketTotal:Ref<number>;
  adminTicketHasMore:Ref<boolean>;
  adminTicketLoading:Ref<boolean>;
  assignableAdmins:Ref<{id:number;nickname:string;username:string}[]>;
  faqs:Ref<Faq[]>;
  faqDialog:Ref<boolean>;
  ticketStatusLabel:(status:string)=>string;
  ticketTypeLabel:(type:string)=>string;
  openTicketReply:(ticket:Ticket)=>void;
  assignTicket:(ticket:Ticket,assigneeUserId:number|string)=>Promise<void>;
  loadMoreAdminTickets:()=>Promise<void>;
  editFaq:(faq:Faq)=>void;
  deleteFaq:(faq:Faq)=>Promise<void>;
};

const props = defineProps<{ page: PageState }>();

const { adminTicketHasMore, adminTicketKeyword, adminTicketLoading, adminTicketStatus, adminTicketTotal, adminTickets, assignTicket, assignableAdmins, deleteFaq, editFaq, faqDialog, faqs, loadMoreAdminTickets, openTicketReply, ticketStatusLabel, ticketTypeLabel } = props.page;
</script>
