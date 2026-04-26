<script setup lang="ts">
/**
 * 【コンポーネントの役割】 useToast() ストアの内容を画面右下に重ねて描画する通知レイヤ。
 *
 * App.vue のルート直下に 1 度だけ配置する。各トーストは自動消去されるが、
 * クリックでも閉じられる。aria-live="polite" によりスクリーンリーダー対応。
 */
import { useToast } from '../composables/useToast';
import { useI18n } from '../composables/useI18n';

const { items, dismiss } = useToast();
const { t } = useI18n();
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed bottom-4 right-4 z-[200] flex flex-col gap-2 pointer-events-none max-w-sm w-[calc(100vw-2rem)]"
      aria-live="polite"
      aria-atomic="false"
    >
      <TransitionGroup
        enter-active-class="transition ease-out duration-200"
        enter-from-class="opacity-0 translate-y-2"
        enter-to-class="opacity-100 translate-y-0"
        leave-active-class="transition ease-in duration-150"
        leave-from-class="opacity-100"
        leave-to-class="opacity-0 translate-x-4"
      >
        <div
          v-for="toast in items"
          :key="toast.id"
          role="status"
          class="pointer-events-auto rounded-xl shadow-lg border px-4 py-3 flex items-start gap-3 backdrop-blur-sm"
          :class="{
            'bg-emerald-50/95 dark:bg-emerald-900/40 border-emerald-200 dark:border-emerald-800 text-emerald-800 dark:text-emerald-200': toast.type === 'success',
            'bg-red-50/95 dark:bg-red-900/40 border-red-200 dark:border-red-800 text-red-800 dark:text-red-200': toast.type === 'error',
            'bg-blue-50/95 dark:bg-blue-900/40 border-blue-200 dark:border-blue-800 text-blue-800 dark:text-blue-200': toast.type === 'info',
          }"
        >
          <svg v-if="toast.type === 'success'" aria-hidden="true" class="h-5 w-5 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
          </svg>
          <svg v-else-if="toast.type === 'error'" aria-hidden="true" class="h-5 w-5 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01M4.93 19h14.14a2 2 0 001.71-3l-7.07-12a2 2 0 00-3.42 0L3.22 16a2 2 0 001.71 3z" />
          </svg>
          <svg v-else aria-hidden="true" class="h-5 w-5 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
          <p class="text-sm font-medium leading-5 whitespace-pre-line flex-1">{{ toast.message }}</p>
          <button
            type="button"
            :aria-label="t('a11y.modal.close')"
            class="text-current/60 hover:text-current transition-colors -m-1 p-1"
            @click="dismiss(toast.id)"
          >
            <svg aria-hidden="true" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>
