<script setup lang="ts">
/**
 * 【Viewの役割】 利用規約の静的ページ。
 *
 * 機能:
 *  - `Terms.vue` をラップし、規約本文を表示するだけの薄いコンポーネント。
 *  - リンク遷移先としてルータに登録される想定。
 *
 * 依存:
 *  - `../components/Terms.vue` — 利用規約本文を含むコンポーネント。
 */
import Terms from '../components/Terms.vue'
</script>

<template>
  <!-- 画面全体: 利用規約本文。最大幅 5xl で中央寄せ、フェードイン表示 -->
  <Terms class="w-full max-w-5xl mx-auto animate-fade-in" />
</template>
