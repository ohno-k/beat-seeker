<script setup lang="ts">
/**
 * 【コンポーネントの役割】 配置アンケート（RANDOM の「どっちが押しやすい？」）のモーダル。サイドバーの「配置アンケート」から開く。
 * 中身は RandomPairSurvey.vue。閉じたら v-if で外す（再生とキー操作を止めるため）。
 */
import { useModalEscape } from '../composables/useModalEscape';
import RandomPairSurvey from './RandomPairSurvey.vue';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{ (e: 'close'): void }>();

useModalEscape(() => props.open, () => emit('close'));
</script>

<template>
  <Teleport to="body">
    <Transition
      enter-active-class="transition-opacity duration-200"
      enter-from-class="opacity-0"
      enter-to-class="opacity-100"
      leave-active-class="transition-opacity duration-150"
      leave-from-class="opacity-100"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        role="dialog"
        aria-modal="true"
        aria-labelledby="random-survey-modal-title"
        class="fixed inset-0 z-[100] flex items-center justify-center p-4"
      >
        <div class="absolute inset-0 bg-slate-900/50 backdrop-blur-sm" @click="$emit('close')"></div>

        <div class="relative bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 shadow-xl max-w-xl w-full max-h-[90vh] overflow-y-auto">
          <div class="px-5 py-4 border-b border-slate-100 dark:border-slate-700 flex items-center gap-3">
            <h3 id="random-survey-modal-title" class="flex-1 text-sm font-bold text-slate-800 dark:text-slate-100">配置アンケート: どっちが押しやすい？</h3>
            <button
              type="button"
              @click="$emit('close')"
              aria-label="閉じる"
              class="p-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>
          <div class="p-5">
            <RandomPairSurvey />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
