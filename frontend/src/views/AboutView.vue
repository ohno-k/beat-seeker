<script setup lang="ts">
/**
 * 【Viewの役割】 「このサイトについて」静的ページ。
 *
 * 機能:
 *  - `About.vue` コンポーネントをラップし、中央寄せ・最大幅制約を付けて表示するだけの薄いラッパー。
 *  - Vue Router 側で `/about` 的なルートに紐付ける想定。
 *
 * 依存:
 *  - `../components/About.vue` — 実際の本文コンテンツ（沿革・クレジット等）を描画。
 */
import About from '../components/About.vue'
</script>

<template>
  <!-- 画面全体: サイト説明本文。最大幅 5xl で中央寄せし、フェードインアニメーションで表示 -->
  <About class="w-full max-w-5xl mx-auto animate-fade-in" />
</template>
