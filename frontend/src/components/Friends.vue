<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useFriends } from '../composables/useFriends';
import type { Friend, VirtualRival } from '../composables/useFriends';
import FriendSearchModal from './FriendSearchModal.vue';
import FriendComparisonModal from './FriendComparisonModal.vue';
import RankIcon from './RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';

const emit = defineEmits<{
  'view-user': [user: { id: number; displayName: string; iidxId: string }],
  'view-top-ranker': [area: { versionNum: number; versionName: string; prefectureFileNum: number; prefectureName: string }]
}>();

const { friends, fetchFriends, removeFriend, fetchVirtualRivals, removeVirtualRival } = useFriends();
const isSearchModalOpen = ref(false);
const isComparisonModalOpen = ref(false);
const selectedFriend = ref<Friend | null>(null);
const selectedVirtualArea = ref<{ versionNum: number; prefectureFileNum: number } | null>(null);
const removingId = ref<number | null>(null);

const virtualRivals = ref<VirtualRival[]>([]);
const removingVirtualId = ref<number | null>(null);
const isLoaded = ref(false);

const hasAnyRival = computed(() => friends.value.length > 0 || virtualRivals.value.length > 0);

const openComparison = (friend: Friend) => {
  selectedFriend.value = friend;
  selectedVirtualArea.value = null;
  isComparisonModalOpen.value = true;
};

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

const handleRemoveFriend = async (friend: Friend) => {
  if (!confirm(`${friend.displayName} さんをフレンドから削除しますか？`)) return;
  removingId.value = friend.id;
  try {
    await removeFriend(friend.id);
  } finally {
    removingId.value = null;
  }
};

const refreshVirtualRivals = async () => {
  virtualRivals.value = await fetchVirtualRivals();
};

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

onMounted(async () => {
  try {
    await Promise.all([fetchFriends(), refreshVirtualRivals()]);
  } finally {
    isLoaded.value = true;
  }
});

const formatDate = (dateStr: string | null) => {
  if (!dateStr) return '未アップロード';
  const date = new Date(dateStr);
  return date.toLocaleString('ja-JP', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  });
};

const canViewDashboard = (friend: Friend) => (friend.privacyLevel ?? 0) !== 2;

const handleNameClick = (friend: Friend) => {
  if (!canViewDashboard(friend)) return;
  emit('view-user', { id: friend.id, displayName: friend.displayName, iidxId: friend.iidxId });
};

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
    <div class="flex justify-between items-center bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <div>
        <h2 class="text-2xl font-bold text-slate-900 dark:text-white">フレンド一覧</h2>
        <p class="text-slate-500 dark:text-slate-400 text-sm mt-1">ライバルの進捗を確認しましょう</p>
      </div>
      <button
        @click="isSearchModalOpen = true"
        class="p-3 bg-blue-600 hover:bg-blue-700 text-white rounded-xl shadow-lg shadow-blue-500/20 transition-all flex items-center gap-2 font-bold"
      >
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4" />
        </svg>
        フレンド追加
      </button>
    </div>

    <div v-if="!isLoaded" class="flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700">
      <div class="w-10 h-10 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
      <p class="text-slate-500 dark:text-slate-400">読み込み中...</p>
    </div>

    <div v-else-if="!hasAnyRival" class="flex flex-col items-center justify-center p-12 bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 border-dashed">
      <div class="w-16 h-16 bg-slate-100 dark:bg-slate-700 rounded-full flex items-center justify-center text-slate-400 mb-4">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4.354a4 4 0 110 5.292M15 21H3v-1a6 6 0 0112 0v1zm0 0h6v-1a6 6 0 00-9-5.197M13 7a4 4 0 11-8 0 4 4 0 018 0z" />
        </svg>
      </div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">フレンドがまだいません</p>
      <p class="text-slate-400 dark:text-slate-500 text-sm mt-1">右上のボタンからフレンドを探してみましょう！</p>
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      <div v-for="friend in friends" :key="'u-' + friend.id"
        class="bg-white dark:bg-slate-800 p-5 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 hover:shadow-md transition-all group"
      >
        <div class="flex items-center gap-4 mb-4">
          <div class="w-12 h-12 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-full flex items-center justify-center text-white text-lg font-bold shadow-md shrink-0">
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
          <button
            @click="handleRemoveFriend(friend)"
            :disabled="removingId === friend.id"
            class="shrink-0 p-1.5 text-slate-300 hover:text-red-500 dark:text-slate-600 dark:hover:text-red-400 transition-colors rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20"
            title="フレンドを削除"
          >
            <svg v-if="removingId === friend.id" class="w-4 h-4 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
            </svg>
            <svg v-else xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M13 7a4 4 0 11-8 0 4 4 0 018 0zM9 14a6 6 0 00-6 6v1h12v-1a6 6 0 00-6-6zM21 12h-6" />
            </svg>
          </button>
        </div>

        <div class="space-y-3">
          <div class="flex items-center justify-between p-3 bg-slate-50 dark:bg-slate-900/50 rounded-xl">
            <div class="flex flex-col">
              <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-wider">BEAT-PT</span>
              <span class="text-lg font-black text-blue-600 dark:text-blue-400">{{ friend.totalBeatPt.toLocaleString() }} <span class="text-xs font-normal">pt</span></span>
            </div>
            <div class="flex items-center gap-2">
              <RankIcon
                :rank-name="getRankInfo(friend.totalBeatPt).name"
                :tier="getRankInfo(friend.totalBeatPt).tier"
                size="sm"
              />
              <div class="flex flex-col items-end">
                <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-wider">TIER</span>
                <div :class="[getRankInfo(friend.totalBeatPt).color, 'font-black text-sm']">
                  {{ getRankInfo(friend.totalBeatPt).name }} {{ getRankInfo(friend.totalBeatPt).tier }}
                </div>
              </div>
            </div>
          </div>

          <div class="flex gap-2">
            <button
              v-if="canViewDashboard(friend)"
              @click="openComparison(friend)"
              class="flex-1 py-2 bg-indigo-50 hover:bg-indigo-100 dark:bg-indigo-900/20 dark:hover:bg-indigo-900/40 text-indigo-600 dark:text-indigo-400 rounded-xl text-xs font-black transition-all flex items-center justify-center gap-1.5"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              比較する
            </button>
            <div class="flex items-center justify-between text-[10px] text-slate-500 dark:text-slate-400 px-1 flex-1">
              <span>最終更新: {{ formatDate(friend.lastUploadedAt) }}</span>
            </div>
          </div>
        </div>
      </div>

      <div v-for="rival in virtualRivals" :key="'v-' + rival.id"
        class="bg-white dark:bg-slate-800 p-5 rounded-2xl shadow-sm border border-amber-200/60 dark:border-amber-800/40 hover:shadow-md transition-all group"
      >
        <div class="flex items-center gap-4 mb-4">
          <div class="w-12 h-12 bg-gradient-to-br from-amber-400 to-orange-500 rounded-full flex items-center justify-center text-white text-lg font-bold shadow-md shrink-0">
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
          <button
            @click="handleRemoveVirtualRival(rival)"
            :disabled="removingVirtualId === rival.id"
            class="shrink-0 p-1.5 text-slate-300 hover:text-red-500 dark:text-slate-600 dark:hover:text-red-400 transition-colors rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20"
            title="ライバルを解除"
          >
            <svg v-if="removingVirtualId === rival.id" class="w-4 h-4 animate-spin" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
            </svg>
            <svg v-else xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M13 7a4 4 0 11-8 0 4 4 0 018 0zM9 14a6 6 0 00-6 6v1h12v-1a6 6 0 00-6-6zM21 12h-6" />
            </svg>
          </button>
        </div>

        <div class="space-y-3">
          <div class="flex items-center justify-between p-3 bg-slate-50 dark:bg-slate-900/50 rounded-xl">
            <div class="flex flex-col">
              <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-wider">BEAT-PT</span>
              <span class="text-lg font-black text-blue-600 dark:text-blue-400">{{ rival.totalBeatPt.toLocaleString() }} <span class="text-xs font-normal">pt</span></span>
            </div>
            <div class="flex items-center gap-2">
              <RankIcon
                :rank-name="getRankInfo(rival.totalBeatPt).name"
                :tier="getRankInfo(rival.totalBeatPt).tier"
                size="sm"
              />
              <div class="flex flex-col items-end">
                <span class="text-[10px] font-black text-slate-400 dark:text-slate-500 uppercase tracking-wider">TIER</span>
                <div :class="[getRankInfo(rival.totalBeatPt).color, 'font-black text-sm']">
                  {{ getRankInfo(rival.totalBeatPt).name }} {{ getRankInfo(rival.totalBeatPt).tier }}
                </div>
              </div>
            </div>
          </div>

          <div class="flex gap-2">
            <button
              @click="openVirtualComparison(rival)"
              class="flex-1 py-2 bg-amber-50 hover:bg-amber-100 dark:bg-amber-900/20 dark:hover:bg-amber-900/40 text-amber-700 dark:text-amber-300 rounded-xl text-xs font-black transition-all flex items-center justify-center gap-1.5"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              比較する
            </button>
            <div class="flex items-center justify-between text-[10px] text-slate-500 dark:text-slate-400 px-1 flex-1">
              <span class="text-amber-600 dark:text-amber-400 font-black uppercase tracking-wider">TOPランカー</span>
            </div>
          </div>
        </div>
      </div>
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
