<script setup lang="ts">
/**
 * 【コンポーネントの役割】 非公式難易度（例: "12.3"）を ★12.3 形式の小さなバッジで表示する。
 *
 * - 全画面で曲名の隣に置いて、ユーザーが繰り返し非公式難易度を目にして覚えるようにするための共通バッジ。
 * - 数値ランクは 11.x→緑系、12.0-12.4→青系、12.5-12.7→琥珀系、12.8-13.0→赤系で塗り分ける。
 * - 非数値ランク（個人差/Uncategorized 等）は灰色で表示。
 * - rank が undefined のときは何も描画しない（呼び出し側で v-if 不要）。
 */
import { computed } from 'vue';

const props = defineProps<{
  rank?: string | null;
  size?: 'xs' | 'sm' | 'md';
}>();

const size = computed(() => props.size ?? 'sm');

/** 数値ランクなら数値、非数値ランクなら NaN を返す。 */
const numericRank = computed(() => {
  if (!props.rank) return NaN;
  const n = Number(props.rank);
  return Number.isFinite(n) ? n : NaN;
});

/** 数値ランクから色クラスを決める。 */
const colorClass = computed(() => {
  const n = numericRank.value;
  if (Number.isNaN(n)) {
    return 'bg-slate-100 text-slate-500 border-slate-200 dark:bg-slate-700/40 dark:text-slate-400 dark:border-slate-600';
  }
  if (n < 12.0) return 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-900/30 dark:text-emerald-300 dark:border-emerald-800';
  if (n < 12.5) return 'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-900/30 dark:text-blue-300 dark:border-blue-800';
  if (n < 12.8) return 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-900/30 dark:text-amber-300 dark:border-amber-800';
  return 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-900/30 dark:text-rose-300 dark:border-rose-800';
});

const sizeClass = computed(() => {
  if (size.value === 'xs') return 'px-1 py-px text-[9px]';
  if (size.value === 'md') return 'px-2 py-0.5 text-xs';
  return 'px-1.5 py-px text-[10px]';
});

/** 表示テキスト。数値なら ★12.3、文字列なら ★ + 文字列をそのまま。 */
const displayText = computed(() => {
  if (!props.rank) return '';
  return `★${props.rank}`;
});
</script>

<template>
  <span
    v-if="rank"
    :class="['inline-flex items-center font-bold tabular-nums rounded border whitespace-nowrap leading-none', colorClass, sizeClass]"
    :title="`非公式難易度 ${rank}`"
  >
    {{ displayText }}
  </span>
</template>
