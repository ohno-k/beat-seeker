<script setup lang="ts">
/**
 * 【Viewの役割】 過去作ランキング（作品ごとの最終 PT アーカイブ）ページ。
 *
 * 機能:
 *  - `PastRanking.vue` コンポーネントをそのまま表示する薄いラッパー。
 *  - ルーティング上の責務のみを担い、取得・描画は委譲する。
 *
 * 依存:
 *  - `../components/PastRanking.vue` — 作品セレクタと順位表の本体。
 */
import PastRanking from '../components/PastRanking.vue'
</script>

<template>
  <!-- 画面全体: 過去作ランキング。最大幅 5xl で中央寄せ、フェードイン表示 -->
  <PastRanking class="w-full max-w-5xl mx-auto animate-fade-in" />
</template>
