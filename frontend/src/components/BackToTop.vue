<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ページ右下に出る「ページ上部へ戻る」FAB。
 *
 * スクロール量がしきい値を超えたら現れ、クリックでスムーズに上部へ戻す。
 * 長いテーブル（ScoreSummary）や履歴ビューを延々スクロールした後の戻り操作を 1 タップに短縮する。
 *
 * グローバルに 1 つ App ルート直下に置く想定。Teleport は使わず通常の fixed 配置。
 */
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useI18n } from '../composables/useI18n';
import { mdiChevronUp } from '@mdi/js';

const { t } = useI18n();

/** FAB を出すスクロールしきい値（px）。これ以下では非表示。 */
const SCROLL_THRESHOLD = 320;

const visible = ref(false);

const onScroll = () => {
  visible.value = window.scrollY > SCROLL_THRESHOLD;
};

const scrollToTop = () => {
  window.scrollTo({ top: 0, behavior: 'smooth' });
};

onMounted(() => {
  // passive: true で main thread をブロックしない（スクロール頻発イベントなので重要）。
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();
});

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll);
});
</script>

<template>
  <Transition
    enter-active-class="transition ease-out duration-200"
    enter-from-class="opacity-0 translate-y-2"
    enter-to-class="opacity-100 translate-y-0"
    leave-active-class="transition ease-in duration-150"
    leave-from-class="opacity-100"
    leave-to-class="opacity-0 translate-y-2"
  >
    <v-btn
      v-if="visible"
      icon
      elevation="4"
      :aria-label="t('a11y.backToTop')"
      class="fixed bottom-20 right-4 z-40"
      @click="scrollToTop"
    >
      <v-icon :icon="mdiChevronUp" />
    </v-btn>
  </Transition>
</template>
