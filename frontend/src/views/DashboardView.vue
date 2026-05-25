<script setup lang="ts">
/**
 * 【Viewの役割】 ホーム（ダッシュボード）画面。自分または他ユーザーのスコアサマリを表示する。
 *
 * 機能:
 *  - 自ユーザーの場合は provide された `scoreData` をそのまま `ScoreDashboard` に渡す。
 *  - 他ユーザーを閲覧中（URL param に userId が含まれる）は、上部に「フレンド申請バナー」を表示する。
 *  - `mode=friend` なら既にフレンドなので申請不要、`mode=admin` なら管理者ビュー扱いでバナー非表示。
 *  - 申請ボタン押下で `sendFriendRequest` を呼び、状態（idle / loading / sent / error）を管理する。
 *
 * 依存:
 *  - `../components/ScoreDashboard.vue` — BEAT-Tier / Rate-Tier / 最近のアクティビティ表示本体。
 *  - provide/inject: `scoreData` / `totalBeatTierPoints`（App.vue 側で供給）。
 *  - `useAuth` / `useFriends` — ログイン状態とフレンド申請 API。
 */
import { inject, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import type { Ref } from 'vue'
import ScoreDashboard from '../components/ScoreDashboard.vue'
import WrappedBanner from '../components/WrappedBanner.vue'
import type { ScoreData } from '../types/ScoreData'
import { useAuth } from '../composables/useAuth'
import { useFriends } from '../composables/useFriends'

/** 表示対象ユーザーのスコア配列（自分 or 他ユーザー）。App.vue で provide */
const scoreData = inject<Ref<ScoreData[]>>('scoreData')!
/** 合計 BEAT-Tier ポイント。表示対象ユーザーの集計値 */
const totalBeatTierPoints = inject<Ref<number>>('totalBeatTierPoints')!

const route = useRoute()
const { isLoggedIn } = useAuth()
const { sendFriendRequest } = useFriends()

/** URL `params.userId` を数値化。存在しなければ null（＝自分のダッシュボード） */
const viewingUserId = computed(() => {
    const id = route.params.userId
    return id ? Number(id) : null
})
/** バナーに出す表示名（query から取得） */
const viewingUserName = computed(() => (route.query.name as string) || '')
/** 閲覧モード。'friend' | 'admin' | undefined（公開リンク） */
const viewingMode = computed(() => route.query.mode as string | undefined)

/** 'friend' = 既にフレンド、'admin' = 管理者閲覧、undefined = 公開リンク経由 */
const isFriendMode = computed(() => viewingMode.value === 'friend')
const isAdminMode = computed(() => viewingMode.value === 'admin')

/** フレンド申請ボタンの状態遷移 */
type RequestState = 'idle' | 'loading' | 'sent' | 'error'
const requestState = ref<RequestState>('idle')
const requestError = ref('')

/**
 * 【関数の役割】 フレンド申請ボタンのクリックハンドラ。
 *
 * 処理の流れ:
 *  - 手順1: `viewingUserId` が無ければ何もしない（自分のダッシュボードに申請はできない）。
 *  - 手順2: ローディング状態に切り替えてAPIを叩く。
 *  - 手順3: 成功したら 'sent'、例外時は 'error' とエラーメッセージを記録。
 */
const handleSendRequest = async () => {
    if (!viewingUserId.value) return
    requestState.value = 'loading'
    requestError.value = ''
    try {
        await sendFriendRequest(viewingUserId.value)
        requestState.value = 'sent'
    } catch (e: any) {
        requestState.value = 'error'
        requestError.value = e.message || 'フレンド申請に失敗しました'
    }
}
</script>

<template>
  <div class="w-full max-w-6xl flex flex-col items-center gap-4">

    <!-- 月末振り返りバナー: 自分のダッシュボード閲覧時かつ表示ウィンドウ内のみ表示 -->
    <WrappedBanner v-if="!viewingUserId && isLoggedIn" />

    <!-- フレンド申請バナー: 他ユーザーの公開ダッシュボード閲覧時のみ表示。adminモード時は隠す -->
    <div
      v-if="viewingUserId && isLoggedIn && !isAdminMode"
      class="w-full bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl px-5 py-3 flex items-center justify-between gap-4 shadow-sm"
    >
      <div class="flex items-center gap-3 min-w-0">
        <div class="w-8 h-8 rounded-full bg-blue-100 dark:bg-blue-900/40 flex items-center justify-center flex-shrink-0">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-blue-600 dark:text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
          </svg>
        </div>
        <span class="font-bold text-slate-700 dark:text-slate-200 truncate text-sm">
          {{ viewingUserName || 'このユーザー' }} のダッシュボード
        </span>
      </div>

      <!-- 既にフレンドの場合: 緑色のチェックマークで「フレンド済み」を示す -->
      <div v-if="isFriendMode" class="flex items-center gap-1.5 text-xs font-bold text-emerald-600 dark:text-emerald-400 flex-shrink-0">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
        </svg>
        フレンド済み
      </div>

      <!-- 申請済みの場合: 青色のチェックマークで「申請済み」を示す -->
      <div v-else-if="requestState === 'sent'" class="flex items-center gap-1.5 text-xs font-bold text-blue-600 dark:text-blue-400 flex-shrink-0">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
        </svg>
        申請済み
      </div>

      <!-- エラー時: 失敗理由と「再試行」リンクで idle に戻す -->
      <div v-else-if="requestState === 'error'" class="flex items-center gap-2 flex-shrink-0">
        <span class="text-xs font-bold text-red-500 max-w-[160px] truncate">{{ requestError }}</span>
        <button
          @click="requestState = 'idle'"
          class="text-[10px] font-bold text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 underline"
        >再試行</button>
      </div>

      <!-- 通常時: 申請ボタン。loading 中は disabled にして重複送信を防ぐ -->
      <button
        v-else
        @click="handleSendRequest"
        :disabled="requestState === 'loading'"
        class="flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold bg-blue-600 hover:bg-blue-700 active:scale-95 text-white transition-all flex-shrink-0 disabled:opacity-50 disabled:cursor-not-allowed"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z" />
        </svg>
        <span v-if="requestState === 'loading'">送信中...</span>
        <span v-else>フレンド申請</span>
      </button>
    </div>

    <!-- メインコンテンツ: BEAT-Tier / Rate-Tier / 最近のアクティビティを描画 -->
    <ScoreDashboard
      :scores="scoreData"
      :totalPoints="totalBeatTierPoints"
      class="w-full"
    />
  </div>
</template>
