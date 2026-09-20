<template>
  <section class="page-stack audit-page">
    <div class="page-toolbar">
      <div>
        <h3>操作记录</h3>
        <p>管理员的账号处理、内容审核、删除和平台设置变更；记录只会追加，不能修改或删除</p>
      </div>
    </div>

    <section class="surface admin-list-surface">
      <div class="audit-filters">
        <el-select v-model="filters.category" clearable placeholder="全部类别" aria-label="按类别筛选">
          <el-option v-for="item in AUDIT_CATEGORIES" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input v-model="filters.actor" clearable placeholder="管理员用户名或 ID" aria-label="按管理员筛选" />
        <el-input v-model="filters.keyword" :prefix-icon="Search" clearable placeholder="对象名称、说明或编号" aria-label="搜索操作记录" />
        <div class="audit-dates">
          <label><span>从</span><input v-model="filters.from" type="date" :max="filters.to || today" aria-label="开始日期" /></label>
          <label><span>至</span><input v-model="filters.to" type="date" :min="filters.from || undefined" :max="today" aria-label="结束日期" /></label>
        </div>
        <el-button v-if="filtered" text type="primary" @click="clearAll">清除筛选</el-button>
      </div>
      <div v-if="filters.subjectUserId" class="audit-subject">
        <el-tag closable effect="plain" @close="filters.subjectUserId = null">涉及用户：{{ subjectLabel || `#${filters.subjectUserId}` }}</el-tag>
      </div>

      <div v-if="failed && !items.length" class="audit-state" role="alert">
        <span>操作记录加载失败</span>
        <el-button size="small" @click="load(true)">重试</el-button>
      </div>

      <template v-else>
        <el-table class="admin-desktop-table audit-table" :data="items" row-key="id"
                  :empty-text="loading ? '正在加载…' : filtered ? '没有符合条件的记录' : '还没有管理员操作记录'">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">
              <span class="audit-time">{{ formatTime(row.createdAt) }}</span>
              <small class="audit-muted">{{ sourceLabel(row.source) }}</small>
            </template>
          </el-table-column>
          <el-table-column label="管理员" width="150">
            <template #default="{ row }">
              <span class="audit-actor">{{ row.actorName || '未知' }}</span>
              <small class="audit-muted">#{{ row.actorId }}<template v-if="row.clientIp"> · {{ row.clientIp }}</template></small>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="isSevere(row.action) ? 'danger' : 'info'">{{ categoryLabel(row.category) }}</el-tag>
              <strong class="audit-action">{{ actionLabel(row.action) }}</strong>
            </template>
          </el-table-column>
          <el-table-column label="对象" min-width="200">
            <template #default="{ row }">
              <span class="audit-target">{{ row.targetLabel || '—' }}</span>
              <small v-if="row.targetId || row.subjectUserId" class="audit-muted">
                <template v-if="row.targetId">编号 {{ row.targetId }}</template>
                <template v-if="row.targetId && row.subjectUserId"> · </template>
                <button v-if="row.subjectUserId && row.subjectUserId !== filters.subjectUserId" type="button" class="audit-link"
                        :title="`只看涉及用户 #${row.subjectUserId} 的记录`" @click="filterSubject(row)">用户 #{{ row.subjectUserId }}</button>
                <template v-else-if="row.subjectUserId">用户 #{{ row.subjectUserId }}</template>
              </small>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="260">
            <template #default="{ row }">
              <AuditDetails :entry="row" />
            </template>
          </el-table-column>
        </el-table>

        <div class="admin-mobile-cards audit-cards">
          <article v-for="row in items" :key="row.id">
            <header>
              <strong>{{ actionLabel(row.action) }}</strong>
              <el-tag size="small" effect="plain" :type="isSevere(row.action) ? 'danger' : 'info'">{{ categoryLabel(row.category) }}</el-tag>
            </header>
            <p class="audit-target">{{ row.targetLabel || '—' }}<template v-if="row.targetId"> · 编号 {{ row.targetId }}</template></p>
            <AuditDetails :entry="row" />
            <footer>
              <span>{{ row.actorName || '未知' }} · {{ formatTime(row.createdAt) }}</span>
              <span v-if="row.clientIp">{{ row.clientIp }}</span>
            </footer>
          </article>
          <el-empty v-if="!items.length && !loading" :description="filtered ? '没有符合条件的记录' : '还没有管理员操作记录'" />
        </div>

        <div v-if="hasMore" class="knowledge-load-more">
          <el-button :loading="loading" @click="loadMore">加载更多记录</el-button>
        </div>
      </template>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, onMounted, watch, type PropType } from 'vue';
import { Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus/es/components/message/index.mjs';
import { getData } from '../api/client';
import { useAuditLog, type AuditPage } from '../composables/auditLog';
import {
  AUDIT_CATEGORIES, actionLabel, categoryLabel, changesOf, describeChange, detailFacts, isSevere, sourceLabel, type AuditEntry,
} from '../utils/auditLabels';

const props = defineProps<{ subjectUserId?: number | null; subjectLabel?: string }>();
const emit = defineEmits<{ 'update:subjectUserId': [value: number | null] }>();

const { filters, items, hasMore, loading, failed, load, loadMore, reset, dispose } = useAuditLog(
  (url) => getData<AuditPage>(url),
  (message) => ElMessage.error(message),
  { initial: { subjectUserId: props.subjectUserId ?? null } },
);

const today = localDay(new Date());
const filtered = computed(() => Boolean(filters.value.category || filters.value.actor.trim() || filters.value.keyword.trim()
  || filters.value.from || filters.value.to || filters.value.subjectUserId));

// The page can be opened for one member (from 资料与治理); clearing the chip clears it for the parent too.
watch(() => props.subjectUserId, (value) => { filters.value.subjectUserId = value ?? null; });
watch(() => filters.value.subjectUserId, (value) => { if (value !== (props.subjectUserId ?? null)) emit('update:subjectUserId', value); });

function filterSubject(row: AuditEntry) {
  filters.value.subjectUserId = row.subjectUserId;
}

function clearAll() {
  reset();
}

function localDay(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${localDay(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

/** Summary, field changes and other facts of one entry. */
const AuditDetails = defineComponent({
  props: { entry: { type: Object as PropType<AuditEntry>, required: true } },
  setup(detailProps) {
    return () => {
      const entry = detailProps.entry;
      const changes = changesOf(entry);
      const facts = detailFacts(entry);
      return h('div', { class: 'audit-details' }, [
        entry.summary ? h('p', { class: 'audit-summary' }, entry.summary) : null,
        changes.length ? h('ul', { class: 'audit-changes' }, changes.map(change => h('li', { key: change.field }, describeChange(change)))) : null,
        facts.length ? h('dl', { class: 'audit-facts' }, facts.flatMap(fact => [h('dt', fact.label), h('dd', fact.text)])) : null,
      ]);
    };
  },
});

onMounted(() => { void load(true); });
onBeforeUnmount(dispose);
</script>

<style scoped>
.audit-filters { display: grid; grid-template-columns: 150px minmax(150px, 1fr) minmax(200px, 1.4fr) auto auto; align-items: center; gap: 10px; margin-bottom: 12px; padding: 12px; background: var(--zh-surface-muted, #f6f9f7); border: 1px solid var(--zh-line, #e1e8e4); border-radius: 10px; }
.audit-dates { display: flex; align-items: center; gap: 8px; }
.audit-dates label { display: flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 13px; }
.audit-dates input { height: 32px; padding: 0 8px; color: var(--el-text-color-primary); font: inherit; font-size: 13px; background: var(--el-fill-color-blank); border: 1px solid var(--el-border-color); border-radius: var(--el-border-radius-base); }
.audit-dates input:focus { outline: none; border-color: var(--el-color-primary); }
.audit-subject { margin: -4px 0 12px; }
.audit-state { display: flex; align-items: center; gap: 12px; padding: 24px 12px; color: var(--el-text-color-secondary); }
.audit-time, .audit-actor, .audit-target { display: block; overflow-wrap: anywhere; }
.audit-action { display: block; margin-top: 4px; }
.audit-muted { display: block; margin-top: 2px; color: var(--el-text-color-secondary); font-size: 12px; overflow-wrap: anywhere; }
.audit-link { padding: 0; color: var(--el-color-primary); font: inherit; background: none; border: 0; cursor: pointer; }
.audit-link:hover { text-decoration: underline; }
:deep(.audit-details) { display: grid; gap: 4px; min-width: 0; }
:deep(.audit-summary) { margin: 0; overflow-wrap: anywhere; }
:deep(.audit-changes) { margin: 0; padding-left: 16px; color: var(--el-text-color-regular); font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
:deep(.audit-facts) { display: grid; grid-template-columns: auto 1fr; gap: 2px 8px; margin: 0; font-size: 12px; line-height: 1.6; }
:deep(.audit-facts dt) { color: var(--el-text-color-secondary); }
:deep(.audit-facts dd) { margin: 0; overflow-wrap: anywhere; }
.audit-cards article { display: grid; gap: 6px; }
.audit-cards header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.audit-cards .audit-target { margin: 0; color: var(--el-text-color-regular); font-size: 13px; }
.audit-cards footer { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 12px; margin-top: 4px; padding-top: 8px; color: var(--el-text-color-secondary); font-size: 12px; border-top: 1px solid var(--zh-line, #e7ece9); }
@media (max-width: 1100px) {
  .audit-filters { grid-template-columns: 150px 1fr 1fr; }
  .audit-dates { grid-column: 1 / -1; }
}
@media (max-width: 720px) {
  .audit-filters { grid-template-columns: 1fr; }
  .audit-dates { flex-wrap: wrap; }
  .audit-dates label { flex: 1 1 140px; }
  .audit-dates input { flex: 1; min-width: 0; }
}
</style>
