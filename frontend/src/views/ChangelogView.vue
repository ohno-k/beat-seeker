<script setup lang="ts">
/**
 * 【Viewの役割】 更新履歴（Changelog）静的ページ。
 *
 * 機能:
 *  - `Changelog.vue` コンポーネントをそのまま表示する薄いラッパー。
 *  - ルーティング上の責務のみを担い、本文ロジックは委譲する。
 *
 * 依存:
 *  - `../components/Changelog.vue` — バージョン別の更新項目を列挙するコンポーネント。
 */
import Changelog from '../components/Changelog.vue'
</script>

<template>
  <!-- 画面全体: 更新履歴本文。最大幅 5xl で中央寄せ、フェードイン表示 -->
  <Changelog class="w-full max-w-5xl mx-auto animate-fade-in" />
</template>
