<script setup lang="ts">
/**
 * 【コンポーネントの役割】 useToast() ストアの内容を画面右下に重ねて描画する通知レイヤ。
 *
 * App.vue のルート直下に 1 度だけ配置する。各トーストは自動消去されるが、
 * クリックでも閉じられる。aria-live="polite" によりスクリーンリーダー対応。
 */
import { useToast } from '../composables/useToast';
import { useI18n } from '../composables/useI18n';
import { mdiAlertOutline, mdiCheck, mdiInformationOutline } from '@mdi/js';

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
        <!-- v-alert の既定（flat・塗りつぶし）は不透明なので、そのまま重ねる -->
        <v-alert
          v-for="toast in items"
          :key="toast.id"
          role="status"
          :type="toast.type"
          :icon="toast.type === 'success' ? mdiCheck : toast.type === 'error' ? mdiAlertOutline : mdiInformationOutline"
          closable
          :close-label="t('a11y.modal.close')"
          elevation="6"
          class="pointer-events-auto"
          @click:close="dismiss(toast.id)"
        >
          <p class="whitespace-pre-line">{{ toast.message }}</p>
        </v-alert>
      </TransitionGroup>
    </div>
  </Teleport>
</template>
