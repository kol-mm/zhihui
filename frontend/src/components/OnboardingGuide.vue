<template>
  <el-dialog
    :model-value="visible"
    class="onboarding-dialog"
    width="min(520px, 94vw)"
    :close-on-click-modal="false"
    :show-close="false"
    append-to-body
    @update:model-value="close('dismiss')"
  >
    <template #header>
      <div class="onboarding-header">
        <h3>欢迎加入{{ platformName }}</h3>
        <p>花一分钟了解三个常用功能，随时可以跳过。</p>
      </div>
    </template>

    <ol class="onboarding-progress" aria-label="引导进度">
      <li v-for="(step, index) in steps" :key="step.key" :class="{ 'is-current': index === current, 'is-done': index < current }">
        <span>{{ index + 1 }}</span>
      </li>
    </ol>

    <section class="onboarding-step" :aria-labelledby="`onboarding-title-${activeStep.key}`">
      <h4 :id="`onboarding-title-${activeStep.key}`">{{ activeStep.title }}</h4>
      <p>{{ activeStep.body }}</p>
      <small>{{ activeStep.hint }}</small>
    </section>

    <template #footer>
      <div class="onboarding-footer">
        <el-button text @click="close('skip')">跳过</el-button>
        <div>
          <el-button v-if="current > 0" @click="current -= 1">上一步</el-button>
          <el-button v-if="current < steps.length - 1" type="primary" @click="current += 1">下一步</el-button>
          <el-button v-else type="primary" @click="close('finish')">开始使用</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ONBOARDING_STEPS } from '../utils/onboarding';

const props = withDefaults(defineProps<{ visible:boolean; platformName?:string }>(), { platformName: '知汇' });
const emit = defineEmits<{ close:[reason:'skip'|'finish'|'dismiss'] }>();

const steps = ONBOARDING_STEPS;
const current = ref(0);
const activeStep = computed(() => steps[Math.min(current.value, steps.length - 1)]);

// Reopening the guide starts from the beginning.
watch(() => props.visible, value => { if (value) current.value = 0; });

function close(reason:'skip'|'finish'|'dismiss'){
  emit('close', reason);
}
</script>

<style scoped>
.onboarding-header h3 { margin: 0 0 4px; }
.onboarding-header p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.onboarding-progress { display: flex; gap: 8px; margin: 0 0 14px; padding: 0; list-style: none; }
.onboarding-progress li { flex: 1; }
.onboarding-progress span { display: flex; align-items: center; justify-content: center; width: 100%; height: 26px; color: var(--el-text-color-secondary); font-size: 12px; background: var(--el-fill-color-light); border-radius: 99px; }
.onboarding-progress .is-current span { color: #fff; background: var(--el-color-primary); }
.onboarding-progress .is-done span { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.onboarding-step { display: grid; gap: 8px; min-height: 132px; align-content: start; }
.onboarding-step h4 { margin: 0; font-size: 16px; }
.onboarding-step p { margin: 0; color: var(--el-text-color-regular); line-height: 1.7; }
.onboarding-step small { color: var(--el-text-color-secondary); line-height: 1.6; }
.onboarding-footer { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
@media (max-width: 520px) {
  .onboarding-footer { flex-direction: column-reverse; align-items: stretch; }
  .onboarding-footer > div { display: flex; gap: 8px; }
  .onboarding-footer > div .el-button { flex: 1; margin-left: 0; }
}
</style>
