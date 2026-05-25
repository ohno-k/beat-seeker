<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ダッシュボード最上段に置く「月末振り返り」呼び出しバナー。
 *
 * 表示ウィンドウ:
 *  - 月末 6 日（=月の最終日から 5 日前まで遡る）から表示開始 → 当月の振り返りを案内
 *  - 翌月の 1〜6 日も表示 → 先月の振り返りを案内
 *  - それ以外は非表示
 *
 * 閉じる挙動:
 *  - 「×」を押すと localStorage `wrapped:dismissed:YYYY-MM` に '1' を保存し、その月の間は非表示
 *  - 月が変わればキーも変わるので、翌月の振り返りウィンドウが来たら再表示される
 *
 * 「自分のダッシュボード時のみ表示」のガードは呼び出し元（DashboardView）で v-if で行う想定。
 * 本コンポーネント自体はユーザー文脈に依存しない。
 */
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

interface WrappedTarget {
  year: number;
  month: number;
  isCurrent: boolean;
}

/**
 * 現在日時から振り返り対象月を導出する。
 *  - 月末 6 日（最終日 - 5 〜 最終日）: 当月
 *  - 月初 6 日（1 〜 6）: 先月
 *  - それ以外: null（バナー非表示）
 */
function getWrappedTarget(): WrappedTarget | null {
  const now = new Date();
  const day = now.getDate();
  const year = now.getFullYear();
  const month = now.getMonth() + 1;
  // 月の最終日: Date(year, month, 0) = "翌月の 0 日" = 当月最終日
  const lastDay = new Date(year, month, 0).getDate();

  if (day >= lastDay - 5) {
    return { year, month, isCurrent: true };
  }
  if (day <= 6) {
    const prev = new Date(year, month - 2, 1); // month は 1-12、Date は 0-11
    return { year: prev.getFullYear(), month: prev.getMonth() + 1, isCurrent: false };
  }
  return null;
}

const router = useRouter();
const target = getWrappedTarget();

const dismissKey = computed(() =>
  target ? `wrapped:dismissed:${target.year}-${String(target.month).padStart(2, '0')}` : '',
);

const dismissed = ref<boolean>(
  target ? localStorage.getItem(`wrapped:dismissed:${target.year}-${String(target.month).padStart(2, '0')}`) === '1' : false,
);

const visible = computed(() => target !== null && !dismissed.value);
const displayMonth = computed(() => (target ? `${target.year}年${target.month}月` : ''));
const headerText = computed(() => (target?.isCurrent ? '今月の振り返りが見られます' : '先月の振り返りが届きました'));

function open() {
  if (!target) return;
  router.push(`/wrapped/${target.year}/${target.month}`);
}

function close() {
  if (!target) return;
  dismissed.value = true;
  localStorage.setItem(dismissKey.value, '1');
}
</script>

<template>
  <div
    v-if="visible"
    class="w-full bg-gradient-to-r from-violet-600 via-blue-600 to-cyan-500 text-white rounded-2xl px-5 py-4 flex items-center justify-between gap-4 shadow-lg hover:shadow-xl transition-shadow"
  >
    <!-- 左側: ロゴ風アイコン + テキスト -->
    <button
      @click="open"
      class="flex items-center gap-3 min-w-0 flex-1 text-left cursor-pointer"
      aria-label="月末振り返りを開く"
    >
      <div class="w-10 h-10 rounded-full bg-white/20 flex items-center justify-center flex-shrink-0">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
          <path stroke-linecap="round" stroke-linejoin="round" d="M5 3v4M3 5h4M6 17v4m-2-2h4m5-16l2.286 6.857L21 12l-5.714 2.143L13 21l-2.286-6.857L5 12l5.714-2.143L13 3z" />
        </svg>
      </div>
      <div class="min-w-0">
        <p class="text-[10px] md:text-xs opacity-80 tracking-[0.25em] font-semibold">BEAT-SEEKER WRAPPED</p>
        <p class="font-bold text-sm md:text-base truncate">
          {{ displayMonth }} {{ headerText }}
        </p>
      </div>
    </button>

    <!-- 右側: 「見る」ボタン + 閉じるボタン -->
    <div class="flex items-center gap-1 md:gap-2 flex-shrink-0">
      <button
        @click="open"
        class="hidden sm:inline-flex px-4 py-1.5 rounded-xl text-xs font-bold bg-white/20 hover:bg-white/30 transition-colors"
      >
        見る →
      </button>
      <button
        @click="close"
        class="w-8 h-8 rounded-full hover:bg-white/20 flex items-center justify-center text-xl leading-none"
        aria-label="今月の振り返りを閉じる"
        title="今月の振り返りを閉じる"
      >
        ×
      </button>
    </div>
  </div>
</template>
