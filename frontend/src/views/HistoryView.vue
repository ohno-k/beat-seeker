<script setup lang="ts">
/**
 * 【Viewの役割】 BEAT-PT 推移グラフ（アップロード履歴）ページ。
 *
 * 機能:
 *  - `UploadHistory.vue` をラップし、過去のスコアアップロード履歴とポイント推移を折れ線グラフ等で描画する。
 *  - このビュー自体は表示領域の制御のみを担う。
 *
 * 依存:
 *  - `../components/UploadHistory.vue` — 履歴取得・グラフ描画を行う本体。
 */
import UploadHistory from '../components/UploadHistory.vue'
</script>

<template>
  <!-- 画面全体: アップロード履歴とポイント推移。最大幅 6xl で広めに表示 -->
  <UploadHistory class="w-full max-w-6xl animate-fade-in" />
</template>
