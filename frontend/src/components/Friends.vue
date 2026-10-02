<script setup lang="ts">
/**
 * 【コンポーネントの役割】 フレンド一覧 + バーチャルライバル（地域 TOP）一覧のページ。
 *
 * 機能:
 *  - 通常フレンドとバーチャルライバル（versionNum×prefectureFileNum のトップ）をカードで並べる
 *  - 「比較する」ボタンで FriendComparisonModal を開く
 *  - 名前クリックで親に `view-user` / `view-top-ranker` を emit してダッシュボード遷移
 *  - フレンド検索モーダル（FriendSearchModal）も内包
 *
 * emits:
 *  - view-user: 通常ユーザープロフの閲覧要求
 *  - view-top-ranker: バーチャルライバル（地域 TOP）プロフの閲覧要求
 */
import { ref, onMounted, computed } from 'vue';
import { useFriends } from '../composables/useFriends';
import type { Friend, VirtualRival } from '../composables/useFriends';
import FriendSearchModal from './FriendSearchModal.vue';
import FriendComparisonModal from './FriendComparisonModal.vue';
import RankIcon from './RankIcon.vue';
import { getRankInfo, previousTierFrame } from '../utils/beatTier';
import { formatJstDateTime } from '../utils/jstTime';
import { mdiPlus, mdiAccountMinusOutline, mdiChartBar } from '@mdi/js';

const emit = defineEmits<{
  'view-user': [user: { id: number; displayName: string; iidxId: string }],
  'view-top-ranker': [area: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }]
}>();

const { friends, fetchFriends, removeFriend, fetchVirtualRivals, removeVirtualRival } = useFriends();
/** フレンド検索モーダルの開閉。 */
const isSearchModalOpen = ref(false);
/** スコア比較モーダルの開閉。 */
const isComparisonModalOpen = ref(false);
/** 比較モーダルへ渡すフレンド（バーチャルライバル時はダミー Friend 形で渡す）。 */
const selectedFriend = ref<Friend | null>(null);
/** バーチャルライバル時のみ有効。どの地域 TOP か特定するキー。 */
const selectedVirtualArea = ref<{ versionNum: number; prefectureFileNum: number } | null>(null);
/** 削除中のフレンド ID（ボタンをスピナー化）。 */
const removingId = ref<number | null>(null);

/** バーチャルライバル一覧（地域 TOP プレイヤー）。 */
const virtualRivals = ref<VirtualRival[]>([]);
/** 削除中のバーチャルライバル ID。 */
const removingVirtualId = ref<number | null>(null);
/** 初回ロード完了フラグ（空表示用のプレースホルダ切替）。 */
const isLoaded = ref(false);

/** フレンドかバーチャルライバルが 1 人以上いるか。 */
const hasAnyRival = computed(() => friends.value.length > 0 || virtualRivals.value.length > 0);

/** 通常フレンドとの比較モーダルを開く。 */
const openComparison = (friend: Friend) => {
  selectedFriend.value = friend;
  selectedVirtualArea.value = null;
  isComparisonModalOpen.value = true;
};

/**
 * 【関数の役割】 バーチャルライバル（地域 TOP）との比較モーダルを開く。
 * 比較モーダルは Friend 型を受け取るため、ID を負数にしたダミー Friend を構築して渡す。
 */
const openVirtualComparison = (rival: VirtualRival) => {
  console.log('[Friends] openVirtualComparison', rival);
  selectedFriend.value = {
    id: -rival.id,
    displayName: `${rival.versionName} ${rival.prefectureName} TOP`,
    iidxId: '',
    lastUploadedAt: null,
    totalBeatPt: rival.totalBeatPt,
  };
  selectedVirtualArea.value = { versionNum: rival.versionNum, prefectureFileNum: rival.prefectureFileNum };
  isComparisonModalOpen.value = true;
  console.log('[Friends] openVirtualComparison set', {
    selectedFriend: selectedFriend.value,
    selectedVirtualArea: selectedVirtualArea.value,
    isOpen: isComparisonModalOpen.value,
  });
};

/** 【関数の役割】 フレンド削除。confirm ダイアログで確認してから実行。 */
const handleRemoveFriend = async (friend: Friend) => {
  if (!confirm(`${friend.displayName} さんをフレンドから削除しますか？`)) return;
  removingId.value = friend.id;
  try {
    await removeFriend(friend.id);
  } finally {
    removingId.value = null;
  }
};

/** 【関数の役割】 バーチャルライバルリストを再取得する。削除後や初期表示で使う。 */
const refreshVirtualRivals = async () => {
  virtualRivals.value = await fetchVirtualRivals();
};

/** 【関数の役割】 バーチャルライバル解除。解除後にリストを再フェッチして UI を更新。 */
const handleRemoveVirtualRival = async (rival: VirtualRival) => {
  if (!confirm(`${rival.versionName} ${rival.prefectureName} TOP をライバルから解除しますか？`)) return;
  removingVirtualId.value = rival.id;
  try {
    await removeVirtualRival(rival.versionNum, rival.prefectureFileNum);
    await refreshVirtualRivals();
  } finally {
    removingVirtualId.value = null;
  }
};

// マウント時にフレンドとバーチャルライバルを並列取得。どちらかが失敗しても空表示は抜ける。
onMounted(async () => {
  try {
    await Promise.all([fetchFriends(), refreshVirtualRivals()]);
  } finally {
    isLoaded.value = true;
  }
});

/** 【関数の役割】 ISO 日時を「YYYY/MM/DD HH:mm」形式（JST 固定）で整形。未指定時は「未アップロード」。 */
const formatDate = (dateStr: string | null) => {
  if (!dateStr) return '未アップロード';
  return formatJstDateTime(dateStr);
};

/** 相手のプライバシーレベルが 2（完全非公開）でない限り、ダッシュボードを覗ける。 */
const canViewDashboard = (friend: Friend) => (friend.privacyLevel ?? 0) !== 2;

/** 名前クリックで親に閲覧イベント発火。非公開ユーザーの場合は無反応。 */
const handleNameClick = (friend: Friend) => {
  if (!canViewDashboard(friend)) return;
  emit('view-user', { id: friend.id, displayName: friend.displayName, iidxId: friend.iidxId });
};

/** バーチャルライバル（地域 TOP）名クリック時のハンドラ。 */
const handleVirtualNameClick = (rival: VirtualRival) => {
  emit('view-top-ranker', {
    versionNum: rival.versionNum,
    versionName: rival.versionName,
    prefectureFileNum: rival.prefectureFileNum,
    prefectureName: rival.prefectureName,
  });
};
</script>

<template>
  <div class="space-y-6">
    <v-card>
      <v-card-item>
        <v-card-title>フレンド一覧</v-card-title>
        <v-card-subtitle>ライバルの進捗を確認しましょう</v-card-subtitle>
        <template #append>
          <v-btn
            @click="isSearchModalOpen = true"
            color="primary"
            :prepend-icon="mdiPlus"
          >
            フレンド追加
          </v-btn>
        </template>
      </v-card-item>
    </v-card>

    <v-card v-if="!isLoaded">
      <v-card-text class="flex flex-col items-center justify-center py-12">
        <v-progress-circular indeterminate size="40" width="4" class="mb-4" />
        <p class="text-slate-500 dark:text-slate-400">読み込み中...</p>
      </v-card-text>
    </v-card>

    <v-card v-else-if="!hasAnyRival">
     <v-card-text class="flex flex-col items-center justify-center py-12">
      <div class="w-16 h-16 bg-slate-100 dark:bg-slate-700 rounded-full flex items-center justify-center text-slate-400 mb-4">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
        </svg>
      </div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">フレンドがまだいません</p>
      <p class="text-slate-400 dark:text-slate-500 text-sm mt-1">右上のボタンからフレンドを探してみましょう！</p>
     </v-card-text>
    </v-card>

    <div v-else class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4">
      <v-card v-for="friend in friends" :key="'u-' + friend.id">
       <v-card-text>
        <div class="flex items-center gap-4 mb-4">
          <div class="w-12 h-12 bg-blue-600 rounded-full flex items-center justify-center text-white text-lg font-bold shrink-0">
            {{ friend.displayName?.charAt(0) || 'U' }}
          </div>
          <div class="flex-1 min-w-0">
            <h3
              class="font-bold text-slate-900 dark:text-white truncate transition-colors"
              :class="canViewDashboard(friend) ? 'cursor-pointer hover:text-blue-600' : 'cursor-default'"
              @click="handleNameClick(friend)"
            >
              {{ friend.displayName }}
              <span v-if="!canViewDashboard(friend)" class="ml-1 text-xs text-slate-400 font-normal">🔒</span>
            </h3>
          </div>
          <v-btn
            icon
            variant="text"
            size="small"
            @click="handleRemoveFriend(friend)"
            :disabled="removingId === friend.id"
            :loading="removingId === friend.id"
            class="shrink-0"
            title="フレンドを削除"
            aria-label="フレンドを削除"
          >
            <v-icon :icon="mdiAccountMinusOutline" size="18" />
          </v-btn>
        </div>

        <div class="space-y-3">
          <div class="flex items-center justify-between p-3 bg-slate-50 dark:bg-slate-900/50 rounded-md">
            <div class="flex flex-col">
              <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500">BEAT-PT</span>
              <span class="text-lg font-bold text-blue-600 dark:text-blue-400">{{ friend.totalBeatPt.toLocaleString() }} <span class="text-xs font-normal">pt</span></span>
            </div>
            <div class="flex items-center gap-2">
              <RankIcon
                :rank-name="getRankInfo(friend.totalBeatPt).name"
                :tier="getRankInfo(friend.totalBeatPt).tier"
                size="sm"
                v-bind="previousTierFrame(friend.previousBeatPt, 'beat')"
              />
              <div class="flex flex-col items-end">
                <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500">TIER</span>
                <div :class="[getRankInfo(friend.totalBeatPt).color, 'font-bold text-sm']">
                  {{ getRankInfo(friend.totalBeatPt).name }} {{ getRankInfo(friend.totalBeatPt).tier }}
                </div>
              </div>
            </div>
          </div>

          <div class="flex gap-2">
            <v-btn
              v-if="canViewDashboard(friend)"
              @click="openComparison(friend)"
              variant="tonal"
              color="primary"
              size="small"
              :prepend-icon="mdiChartBar"
              class="flex-1"
            >
              比較する
            </v-btn>
            <div class="flex items-center justify-between text-[10px] text-slate-500 dark:text-slate-400 px-1 flex-1">
              <span>最終更新: {{ formatDate(friend.lastUploadedAt) }}</span>
            </div>
          </div>
        </div>
       </v-card-text>
      </v-card>

      <v-card v-for="rival in virtualRivals" :key="'v-' + rival.id">
       <v-card-text>
        <div class="flex items-center gap-4 mb-4">
          <div class="w-12 h-12 bg-amber-500 rounded-full flex items-center justify-center text-white text-lg font-bold shrink-0">
            👑
          </div>
          <div class="flex-1 min-w-0">
            <h3
              class="font-bold text-slate-900 dark:text-white truncate cursor-pointer hover:text-amber-600 transition-colors"
              @click="handleVirtualNameClick(rival)"
              :title="`${rival.versionName} ${rival.prefectureName} TOP`"
            >
              {{ rival.prefectureName }} TOP
            </h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 truncate">{{ rival.versionName }}</p>
          </div>
          <v-btn
            icon
            variant="text"
            size="small"
            @click="handleRemoveVirtualRival(rival)"
            :disabled="removingVirtualId === rival.id"
            :loading="removingVirtualId === rival.id"
            class="shrink-0"
            title="ライバルを解除"
            aria-label="ライバルを解除"
          >
            <v-icon :icon="mdiAccountMinusOutline" size="18" />
          </v-btn>
        </div>

        <div class="space-y-3">
          <div class="flex items-center justify-between p-3 bg-slate-50 dark:bg-slate-900/50 rounded-md">
            <div class="flex flex-col">
              <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500">BEAT-PT</span>
              <span class="text-lg font-bold text-blue-600 dark:text-blue-400">{{ rival.totalBeatPt.toLocaleString() }} <span class="text-xs font-normal">pt</span></span>
            </div>
            <div class="flex items-center gap-2">
              <RankIcon
                :rank-name="getRankInfo(rival.totalBeatPt).name"
                :tier="getRankInfo(rival.totalBeatPt).tier"
                size="sm"
              />
              <div class="flex flex-col items-end">
                <span class="text-[10px] font-bold text-slate-400 dark:text-slate-500">TIER</span>
                <div :class="[getRankInfo(rival.totalBeatPt).color, 'font-bold text-sm']">
                  {{ getRankInfo(rival.totalBeatPt).name }} {{ getRankInfo(rival.totalBeatPt).tier }}
                </div>
              </div>
            </div>
          </div>

          <div class="flex gap-2">
            <v-btn
              @click="openVirtualComparison(rival)"
              variant="tonal"
              color="warning"
              size="small"
              :prepend-icon="mdiChartBar"
              class="flex-1"
            >
              比較する
            </v-btn>
            <div class="flex items-center justify-between text-[10px] text-slate-500 dark:text-slate-400 px-1 flex-1">
              <span class="text-amber-600 dark:text-amber-400 font-bold">TOPランカー</span>
            </div>
          </div>
        </div>
       </v-card-text>
      </v-card>
    </div>

    <FriendSearchModal
      :is-open="isSearchModalOpen"
      @close="isSearchModalOpen = false"
      @request-sent="fetchFriends"
    />

    <FriendComparisonModal
      v-if="isComparisonModalOpen && selectedFriend"
      :is-open="isComparisonModalOpen"
      :friend="selectedFriend"
      :virtual-area="selectedVirtualArea"
      @close="isComparisonModalOpen = false"
    />
  </div>
</template>
