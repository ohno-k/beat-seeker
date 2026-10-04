<script setup lang="ts">
/**
 * PatternChips.vue
 *
 * 【コンポーネントの役割】 鍵盤の並び（"3726145" のように左のレーンから元の鍵盤番号）を 7 つの小さな鍵盤で表示する。
 * 元の白鍵（1・3・5・7）は白、黒鍵（2・4・6）は青で塗るので、RANDOM で白鍵がどのレーンに来たかが一目で分かる。
 * 「.」は当たり配置ランキングの自由入力のワイルドカード（どの鍵盤でもよいレーン）で、点線の枠で出す。
 */
defineProps<{
  pattern: string;
  /** 小さめ表示（一覧の行の中など） */
  small?: boolean;
}>();

const WHITE = new Set(['1', '3', '5', '7']);
</script>

<template>
  <span class="chips" :class="{ small }" :aria-label="`鍵盤の並び ${pattern}`">
    <span v-for="(d, i) in pattern" :key="i" class="chip tabular-nums" :class="d === '.' ? 'wild' : WHITE.has(d) ? 'white' : 'black'">{{ d === '.' ? '?' : d }}</span>
  </span>
</template>

<style scoped>
.chips { display: inline-flex; gap: 2px; vertical-align: middle; }
.chip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.15rem;
  height: 1.45rem;
  border-radius: 0.2rem;
  font-size: 0.72rem;
  font-weight: 700;
  line-height: 1;
}
.small .chip { width: 0.95rem; height: 1.2rem; font-size: 0.65rem; }
.chip.white { background: white; color: rgb(30 41 59); box-shadow: inset 0 0 0 1px rgb(148 163 184); }
.chip.black { background: rgb(37 99 235); color: white; }
.dark .chip.white { background: rgb(226 232 240); color: rgb(15 23 42); box-shadow: none; }
.dark .chip.black { background: rgb(59 130 246); }
.chip.wild { background: transparent; color: rgb(100 116 139); box-shadow: none; outline: 1px dashed rgb(148 163 184); outline-offset: -1px; }
.dark .chip.wild { color: rgb(148 163 184); }
</style>
