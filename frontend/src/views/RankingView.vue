<script setup lang="ts">
/**
 * 【Viewの役割】 ユーザー間ランキングページ。
 *
 * 機能:
 *  - `RankingList.vue` をラップし、全ユーザーの BEAT-PT / RATE-PT ランキングを一覧表示する。
 *  - 本ビューは枠組み（幅制約・アニメーション）のみを担当する。
 *
 * 依存:
 *  - `../components/RankingList.vue` — ランキング取得・描画本体。
 */
import RankingList from '../components/RankingList.vue'
</script>

<template>
  <!-- 画面全体: ランキング一覧。最大幅 5xl で中央寄せ、フェードイン表示 -->
  <RankingList class="w-full max-w-5xl mx-auto animate-fade-in" />
</template>
