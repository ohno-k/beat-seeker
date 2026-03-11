<script setup lang="ts">
import { inject, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import type { Ref } from 'vue'
import ScoreDashboard from '../components/ScoreDashboard.vue'
import type { ScoreData } from '../types/ScoreData'
import { useAuth } from '../composables/useAuth'
import { useFriends } from '../composables/useFriends'

const scoreData = inject<Ref<ScoreData[]>>('scoreData')!
const totalBeatTierPoints = inject<Ref<number>>('totalBeatTierPoints')!

const route = useRoute()
const { isLoggedIn } = useAuth()
const { sendFriendRequest } = useFriends()

// Only show friend UI when viewing another user's page
const viewingUserId = computed(() => {
    const id = route.params.userId
    return id ? Number(id) : null
})
const viewingUserName = computed(() => (route.query.name as string) || '')
const viewingMode = computed(() => route.query.mode as string | undefined)

// 'friend' = already friends, 'admin' = admin view, undefined = public link
const isFriendMode = computed(() => viewingMode.value === 'friend')
const isAdminMode = computed(() => viewingMode.value === 'admin')

type RequestState = 'idle' | 'loading' | 'sent' | 'error'
const requestState = ref<RequestState>('idle')
const requestError = ref('')

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

    <!-- Friend request banner – shown when viewing another user's public page -->
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

      <!-- Already friends -->
      <div v-if="isFriendMode" class="flex items-center gap-1.5 text-xs font-bold text-emerald-600 dark:text-emerald-400 flex-shrink-0">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
        </svg>
        フレンド済み
      </div>

      <!-- Request sent -->
      <div v-else-if="requestState === 'sent'" class="flex items-center gap-1.5 text-xs font-bold text-blue-600 dark:text-blue-400 flex-shrink-0">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
          <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
        </svg>
        申請済み
      </div>

      <!-- Error -->
      <div v-else-if="requestState === 'error'" class="flex items-center gap-2 flex-shrink-0">
        <span class="text-xs font-bold text-red-500 max-w-[160px] truncate">{{ requestError }}</span>
        <button
          @click="requestState = 'idle'"
          class="text-[10px] font-bold text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 underline"
        >再試行</button>
      </div>

      <!-- Send request button -->
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

    <ScoreDashboard
      :scores="scoreData"
      :totalPoints="totalBeatTierPoints"
      class="w-full"
    />
  </div>
</template>
