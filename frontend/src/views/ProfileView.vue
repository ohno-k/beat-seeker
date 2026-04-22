<script setup lang="ts">
/**
 * 【Viewの役割】 プロフィール編集・eagate 連携ダッシュボード。
 *
 * 機能:
 *  - `ProfileDashboard.vue` をラップし、ユーザー情報編集や各種連携設定画面を表示する。
 *  - このビューではルーティング上の枠組みだけを提供する。
 *
 * 依存:
 *  - `../components/ProfileDashboard.vue` — プロフィールタブ群の本体。
 */
import ProfileDashboard from '../components/ProfileDashboard.vue'
</script>

<template>
  <!-- 画面全体: プロフィール編集ダッシュボード。最大幅 6xl で表示 -->
  <ProfileDashboard class="w-full max-w-6xl" />
</template>
