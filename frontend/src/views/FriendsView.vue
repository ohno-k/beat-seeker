<script setup lang="ts">
/**
 * 【Viewの役割】 フレンド・ライバル管理ページ。
 *
 * 機能:
 *  - `Friends.vue` をラップし、フレンド一覧・追加・削除・比較ビュー遷移を提供する。
 *  - 一覧行クリック時にフレンドのダッシュボードへ遷移するハンドラを用意する。
 *
 * 依存:
 *  - `../components/Friends.vue` — フレンド機能本体。
 *  - vue-router — ユーザーダッシュボードへの遷移に使用。
 */
import { useRouter } from 'vue-router'
import Friends from '../components/Friends.vue'

/** Vue Router インスタンス。選択したフレンドの個別画面へ `push` するのに使う */
const router = useRouter()

/**
 * 【関数の役割】 フレンド一覧でクリックされたユーザーのダッシュボード画面へ遷移する。
 *
 * 処理の流れ:
 *  - 手順1: `user-dashboard` という名前のルートへ `push`。
 *  - 手順2: パスパラメータにフレンドの `id`、クエリには表示用の `name` と
 *           「フレンド経由」であることを示す `mode=friend` を付与する。
 *
 * @param friend クリックされたフレンドの識別情報（id / displayName / iidxId）
 */
const handleViewUser = (friend: { id: number; displayName: string; iidxId: string }) => {
  router.push({ name: 'user-dashboard', params: { userId: friend.id }, query: { name: friend.displayName, mode: 'friend' } })
}
</script>

<template>
  <!-- 画面全体: フレンド一覧。行クリック時はフレンドのダッシュボードへ遷移 -->
  <Friends class="w-full max-w-6xl animate-fade-in" @view-user="handleViewUser" />
</template>
