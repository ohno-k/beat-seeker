<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ヘッダーの鐘アイコン押下時にポップオーバーで表示する通知パネル。
 *
 * 機能:
 *  - フレンド申請タブ: 承認/拒否ボタン
 *  - アクティビティタブ: スコア抜かれ/フレンド昇格などの通知一覧、まとめて既読
 *  - 30 秒ごとにポーリングして新着を自動取得
 *
 * props:
 *  - isOpen: 表示/非表示
 * emits:
 *  - close: 外部から閉じる（背景クリック等）
 */
import { onMounted, onUnmounted, ref, computed } from 'vue';
import { useFriends } from '../composables/useFriends';
import { formatJstShortDateTime } from '../utils/jstTime';
import { mdiClose } from '@mdi/js';

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

// フレンド関連・通知関連の API をまとめたコンポーザブルから必要分を取得。
const { pendingRequests, fetchPendingRequests, acceptRequest, rejectRequest, appNotifications, fetchAppNotifications, markAllNotificationsRead } = useFriends();
/** 現在「処理中」のフレンド申請 ID（ボタンの二度押し防止用）。 */
const isActionLoading = ref<number | null>(null);
/** アクティブなタブ（フレンド申請 or アプリ通知）。 */
const activeTab = ref<'friend' | 'app'>('friend');

// マウント時に即座に一度取得し、その後 30 秒間隔でポーリング。
// NOTE: onUnmounted を onMounted の中で呼んでいるが、setup スコープで登録されていれば機能する。
onMounted(() => {
  fetchPendingRequests();
  fetchAppNotifications();
  const interval = setInterval(() => {
    fetchPendingRequests();
    fetchAppNotifications();
  }, 30000);
  onUnmounted(() => clearInterval(interval));
});

/** 未読のアクティビティ通知件数（バッジ表示用）。 */
const unreadAppCount = computed(() => appNotifications.value.filter(n => !n.read).length);

/**
 * 【関数の役割】 フレンド申請を承認する。
 * @param id 申請 ID
 */
const handleAccept = async (id: number) => {
  isActionLoading.value = id;
  try {
    await acceptRequest(id);
  } finally {
    isActionLoading.value = null;
  }
};

/**
 * 【関数の役割】 フレンド申請を拒否する。
 * @param id 申請 ID
 */
const handleReject = async (id: number) => {
  isActionLoading.value = id;
  try {
    await rejectRequest(id);
  } finally {
    isActionLoading.value = null;
  }
};

/** 【関数の役割】 未読のアクティビティ通知をまとめて既読化する。 */
const handleMarkAllRead = async () => {
  await markAllNotificationsRead();
};

/**
 * 【関数の役割】 通知種別から絵文字アイコンを決定する。
 * @param type 通知タイプ（SCORE_BEAT / FRIEND_RANK_UP / LEAGUE_START / その他）
 */
const notificationIcon = (type: string) => {
  if (type === 'SCORE_BEAT') return '⚡';
  if (type === 'FRIEND_RANK_UP') return '🏆';
  if (type === 'LEAGUE_START') return '🏁';
  if (type === 'LEAGUE_RESULT') return '🎖️';
  if (type === 'LEAGUE_DEADLINE') return '⏰';
  if (type === 'LEAGUE_OVERTAKEN') return '📉';
  if (type === 'LEAGUE_GROUP_UPDATE') return '👥';
  return '🔔';
};

/**
 * 【関数の役割】 通知種別からアイコン背景色のクラスを決定する。
 * @param type 通知タイプ
 */
const notificationIconClass = (type: string) => {
  if (type === 'SCORE_BEAT' || type === 'LEAGUE_OVERTAKEN') return 'bg-red-100 dark:bg-red-900/30';
  if (type === 'LEAGUE_DEADLINE') return 'bg-orange-100 dark:bg-orange-900/30';
  if (type === 'LEAGUE_GROUP_UPDATE') return 'bg-slate-100 dark:bg-slate-700/50';
  if (type.startsWith('LEAGUE_')) return 'bg-indigo-100 dark:bg-indigo-900/30';
  return 'bg-amber-100 dark:bg-amber-900/30';
};
</script>

<template>
  <v-card v-if="isOpen" elevation="8" class="fixed top-16 right-2 w-80 max-w-[calc(100vw-1rem)] mt-2 z-50 animate-in slide-in-from-top-2 duration-200">
    <div class="p-4 border-b border-slate-100 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-900/50">
      <h4 class="text-sm font-bold text-slate-800 dark:text-white">通知</h4>
      <div class="flex items-center gap-2">
        <v-btn
          v-if="activeTab === 'app' && unreadAppCount > 0"
          variant="text"
          size="small"
          color="info"
          @click="handleMarkAllRead"
        >
          全て既読
        </v-btn>
        <v-btn icon variant="text" size="small" @click="emit('close')">
          <v-icon :icon="mdiClose" />
        </v-btn>
      </div>
    </div>

    <!-- タブ切替（フレンド申請 / アクティビティ通知） -->
    <v-tabs v-model="activeTab" grow color="primary">
      <v-tab value="friend">
        フレンド申請
        <v-badge v-if="pendingRequests.length > 0" inline color="info" :content="pendingRequests.length" class="ml-1" />
      </v-tab>
      <v-tab value="app">
        アクティビティ
        <v-badge v-if="unreadAppCount > 0" inline color="error" :content="unreadAppCount" class="ml-1" />
      </v-tab>
    </v-tabs>
    <v-divider />

    <div class="max-h-96 overflow-y-auto">
      <!-- フレンド申請タブの中身 -->
      <template v-if="activeTab === 'friend'">
        <div v-if="pendingRequests.length === 0" class="p-8 text-center">
          <div class="w-12 h-12 bg-slate-50 dark:bg-slate-900 rounded-full flex items-center justify-center mx-auto mb-3">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-slate-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
          </div>
          <p class="text-xs text-slate-400 dark:text-slate-500 font-bold">フレンド申請はありません</p>
        </div>
        <div v-else class="divide-y divide-slate-100 dark:divide-slate-700">
          <div v-for="req in pendingRequests" :key="`req-${req.id}`" class="p-4 hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors">
            <div class="flex flex-col gap-3">
              <div class="flex items-start gap-3">
                <div class="w-8 h-8 bg-blue-100 dark:bg-blue-900/50 rounded-full flex items-center justify-center text-blue-600 dark:text-blue-400 text-xs font-bold">
                  {{ req.senderName?.charAt(0) || 'U' }}
                </div>
                <div class="flex-1 min-w-0">
                  <p class="text-xs text-slate-600 dark:text-slate-300 leading-normal">
                    <span class="font-bold text-slate-900 dark:text-white">{{ req.senderName || 'ユーザー' }}</span> さんからフレンド申請が届いています。
                  </p>
                  <div v-if="req.message" class="mt-2 p-2 bg-blue-50 dark:bg-blue-900/30 rounded-lg border border-blue-100 dark:border-blue-800 relative">
                    <div class="absolute -top-1 left-3 w-2 h-2 bg-blue-50 dark:bg-blue-900/30 border-t border-l border-blue-100 dark:border-blue-800 rotate-45"></div>
                    <p class="text-[10px] text-blue-800 dark:text-blue-300 italic">"{{ req.message }}"</p>
                  </div>
                </div>
              </div>
              <div class="flex gap-2 pl-11">
                <v-btn color="primary" size="small" class="flex-1" :disabled="isActionLoading === req.id" @click="handleAccept(req.id)">
                  承認
                </v-btn>
                <v-btn variant="tonal" size="small" class="flex-1" :disabled="isActionLoading === req.id" @click="handleReject(req.id)">
                  拒否
                </v-btn>
              </div>
            </div>
          </div>
        </div>
      </template>

      <!-- アプリ内通知タブ（スコア抜かれ・フレンド昇格など） -->
      <template v-if="activeTab === 'app'">
        <div v-if="appNotifications.length === 0" class="p-8 text-center">
          <div class="w-12 h-12 bg-slate-50 dark:bg-slate-900 rounded-full flex items-center justify-center mx-auto mb-3">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-slate-300" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9" />
            </svg>
          </div>
          <p class="text-xs text-slate-400 dark:text-slate-500 font-bold">新しい通知はありません</p>
        </div>
        <div v-else class="divide-y divide-slate-100 dark:divide-slate-700">
          <div v-for="notif in appNotifications" :key="`notif-${notif.id}`"
            class="p-4 transition-colors"
            :class="notif.read ? 'hover:bg-slate-50 dark:hover:bg-slate-700/20' : 'bg-blue-50/30 dark:bg-blue-900/10 hover:bg-blue-50/60 dark:hover:bg-blue-900/20'">
            <div class="flex items-start gap-3">
              <div class="w-8 h-8 rounded-full flex items-center justify-center text-sm shrink-0"
                   :class="notificationIconClass(notif.type)">
                {{ notificationIcon(notif.type) }}
              </div>
              <div class="flex-1 min-w-0">
                <p class="text-xs text-slate-700 dark:text-slate-300 leading-relaxed">{{ notif.message }}</p>
                <p class="text-[10px] text-slate-400 dark:text-slate-500 mt-0.5">
                  {{ formatJstShortDateTime(notif.createdAt) }}
                </p>
              </div>
              <div v-if="!notif.read" class="w-2 h-2 bg-blue-500 rounded-full shrink-0 mt-1"></div>
            </div>
          </div>
        </div>
      </template>
    </div>
  </v-card>
</template>
