<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue';
import { useFriends, type Friend } from '../composables/useFriends';
import { useScores } from '../composables/useScores';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import type { ScoreData } from '../types/ScoreData';

const props = defineProps<{
  friend: Friend;
  virtualArea?: { versionNum: number; prefectureFileNum: number } | null;
  isOpen: boolean;
}>();

const emit = defineEmits<{
  'close': []
}>();

const { fetchFriendScores, isLoading: isFriendLoading } = useFriends();
const { fetchMyScores, fetchTopRankerProfile, isFetching: isMyLoading } = useScores();

const myProcessedScores = ref<ScoreRecord[]>([]);
const friendProcessedScores = ref<ScoreRecord[]>([]);
const error = ref<string | null>(null);

const isLoading = computed(() => isFriendLoading.value || isMyLoading.value);

const loadData = async () => {
  if (!props.isOpen) return;
  error.value = null;
  console.log('[FriendComparisonModal] loadData called', {
    isOpen: props.isOpen,
    virtualArea: props.virtualArea,
    friendId: props.friend.id,
  });
  try {
    if (props.virtualArea) {
      console.log('[FriendComparisonModal] virtual path', props.virtualArea);
      const [myRaw, topRanker] = await Promise.all([
        fetchMyScores(),
        fetchTopRankerProfile(props.virtualArea.versionNum, props.virtualArea.prefectureFileNum)
      ]);
      console.log('[FriendComparisonModal] virtual fetch done', {
        mySize: myRaw.length,
        topRankerScoresSize: topRanker.scores.length,
      });
      myProcessedScores.value = flattenScores(myRaw);
      friendProcessedScores.value = flattenScores(topRanker.scores);
    } else {
      console.log('[FriendComparisonModal] regular friend path', props.friend.id);
      const [myRaw, friendRaw] = await Promise.all([
        fetchMyScores(),
        fetchFriendScores(props.friend.id)
      ]);
      myProcessedScores.value = flattenScores(myRaw);
      friendProcessedScores.value = flattenScores(groupScores(friendRaw));
    }
  } catch (e: any) {
    console.error('[FriendComparisonModal] loadData error', e);
    error.value = e.message;
  }
};

onMounted(() => {
  console.log('[FriendComparisonModal] onMounted', {
    isOpen: props.isOpen,
    virtualArea: props.virtualArea,
    friendId: props.friend.id,
  });
  if (props.isOpen) loadData();
});

watch(() => [props.isOpen, props.friend.id, props.virtualArea?.versionNum, props.virtualArea?.prefectureFileNum], ([open]) => {
  console.log('[FriendComparisonModal] watch triggered', {
    isOpen: props.isOpen,
    virtualArea: props.virtualArea,
    friendId: props.friend.id,
  });
  if (open) loadData();
});

// Helper to group flat scores into ScoreData[]
function groupScores(flatScores: any[]): ScoreData[] {
  const grouped = new Map<string, any>();
  const emptyDiff = () => ({
    difficulty: null, score: 0, pgreat: 0, great: 0, missCount: null,
    clearType: 'NO PLAY', djLevel: '---'
  });

  flatScores.forEach((s: any) => {
    const title = s.title;
    if (!grouped.has(title)) {
      grouped.set(title, {
        version: '0', title, genre: '', artist: '', playCount: 0,
        lastPlayTime: '', beginner: emptyDiff(), normal: emptyDiff(),
        hyper: emptyDiff(), another: emptyDiff(), leggendaria: emptyDiff()
      });
    }
    const entry = grouped.get(title);
    const diffKey = s.difficultyName.toLowerCase();
    if (entry[diffKey]) {
      entry[diffKey] = {
        id: s.id, difficulty: s.difficultyLevel, score: s.score,
        pgreat: s.pgreat, great: s.great, missCount: s.missCount,
        clearType: s.clearType, djLevel: s.djLevel
      };
    }
  });
  return Array.from(grouped.values());
}

interface SongComparison {
  title: string;
  difficultyName: string;
  myScore: number;
  friendScore: number;
  diff: number;
  myClearType: string;
  friendClearType: string;
}

interface ComparisonResult {
  win: number;
  loss: number;
  draw: number;
  myOnly: number;
  friendOnly: number;
  total: number;
  winSongs: SongComparison[];
  lossSongs: SongComparison[];
  drawSongs: SongComparison[];
  myOnlySongs: SongComparison[];
  friendOnlySongs: SongComparison[];
}

const comparisonStats = computed(() => {
  const initStats = (): ComparisonResult => ({ 
    win: 0, loss: 0, draw: 0, myOnly: 0, friendOnly: 0, total: 0,
    winSongs: [], lossSongs: [], drawSongs: [], myOnlySongs: [], friendOnlySongs: []
  });
  const res: Record<string, ComparisonResult> = {
    overall: initStats(),
    lv11: initStats(),
    lv12: initStats()
  };

  const unofficialRanks: Record<string, ComparisonResult> = {};

  // Create a map for all unique song+difficulty keys that appear in either set
  const allKeys = new Set<string>();
  const myMap = new Map<string, ScoreRecord>();
  const friendMap = new Map<string, ScoreRecord>();

  myProcessedScores.value.forEach(s => {
    if (s.difficultyLevel !== 11 && s.difficultyLevel !== 12) return;
    if (s.difficultyLevel === 11 && !showLv11.value) return;
    if (s.difficultyLevel === 12 && !showLv12.value) return;
    const key = `${s.title}_${s.difficultyName}`;
    allKeys.add(key);
    myMap.set(key, s);
  });

  friendProcessedScores.value.forEach(s => {
    if (s.difficultyLevel !== 11 && s.difficultyLevel !== 12) return;
    if (s.difficultyLevel === 11 && !showLv11.value) return;
    if (s.difficultyLevel === 12 && !showLv12.value) return;
    const key = `${s.title}_${s.difficultyName}`;
    allKeys.add(key);
    friendMap.set(key, s);
  });

  allKeys.forEach(key => {
    const myScore = myMap.get(key);
    const friendScore = friendMap.get(key);

    const s = myScore || friendScore; // Representative record for basic info
    if (!s) return;

    const myPlay = myScore && myScore.score > 0;
    const friendPlay = friendScore && friendScore.score > 0;

    if (!myPlay && !friendPlay) return; // Both 0 -> Skip
    if (showBothPlayedOnly.value && !(myPlay && friendPlay)) return; // Filter: both played only

    const lvKey = s.difficultyLevel === 11 ? 'lv11' : 'lv12';
    const rank = s.informalRank && !s.informalRank.includes('Uncategorized') ? s.informalRank : null;

    const update = (stats: ComparisonResult) => {
      stats.total++;
      const songInfo: SongComparison = {
        title: s.title,
        difficultyName: s.difficultyName,
        myScore: myScore?.score || 0,
        friendScore: friendScore?.score || 0,
        diff: (myScore?.score || 0) - (friendScore?.score || 0),
        myClearType: myScore?.clearType || 'NO PLAY',
        friendClearType: friendScore?.clearType || 'NO PLAY'
      };

      if (myPlay && friendPlay) {
        if (songInfo.diff > 0) {
          stats.win++;
          stats.winSongs.push(songInfo);
        } else if (songInfo.diff < 0) {
          stats.loss++;
          stats.lossSongs.push(songInfo);
        } else {
          stats.draw++;
          stats.drawSongs.push(songInfo);
        }
      } else if (myPlay) {
        stats.myOnly++;
        stats.myOnlySongs.push(songInfo);
      } else if (friendPlay) {
        stats.friendOnly++;
        stats.friendOnlySongs.push(songInfo);
      }
    };

    update(res.overall);
    update(res[lvKey]);
    if (rank) {
      if (!unofficialRanks[rank]) unofficialRanks[rank] = initStats();
      update(unofficialRanks[rank]);
    }
  });

  // Sort song lists
  const sortSongs = (stats: ComparisonResult) => {
    stats.winSongs.sort((a, b) => b.diff - a.diff); // Win: Large gap first (Descending)
    stats.lossSongs.sort((a, b) => b.diff - a.diff); // Loss: Small gap first (Ascending order of value: -10, -50, -100)
    stats.drawSongs.sort((a, b) => a.title.localeCompare(b.title));
    stats.myOnlySongs.sort((a, b) => a.title.localeCompare(b.title));
    stats.friendOnlySongs.sort((a, b) => a.title.localeCompare(b.title));
  };

  sortSongs(res.overall);
  sortSongs(res.lv11);
  sortSongs(res.lv12);
  Object.values(unofficialRanks).forEach(sortSongs);

  // Sort unofficial ranks descending
  const sortedUnofficial = Object.entries(unofficialRanks)
    .sort(([a], [b]) => parseFloat(b) - parseFloat(a));

  return {
    summary: res,
    unofficial: sortedUnofficial
  };
});

const expandedRanks = ref<Set<string>>(new Set());
const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
};

const showBothPlayedOnly = ref(false);
const showLv11 = ref(true);
const showLv12 = ref(true);
</script>

<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[9999] flex items-start justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in overflow-y-auto">
      <div class="bg-white dark:bg-slate-800 w-full max-w-5xl my-12 rounded-3xl shadow-2xl overflow-hidden flex flex-col border border-slate-200 dark:border-slate-700">
        <!-- Header -->
        <div class="p-6 border-b border-slate-100 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-800/50">
          <div>
            <h2 class="text-2xl font-black text-slate-900 dark:text-white flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-blue-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              スコア比較
            </h2>
            <p class="text-slate-500 dark:text-slate-400 text-sm font-bold mt-1">
              vs <span class="text-blue-600 dark:text-blue-400 font-black">{{ friend.displayName }}</span>
            </p>
          </div>
          <button @click="emit('close')" class="p-2 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors text-slate-400 hover:text-slate-600">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- Content -->
        <div class="flex-1 p-4 sm:p-6 space-y-8">
          <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
            <div class="w-12 h-12 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 font-bold">データを集計中...</p>
          </div>

          <div v-else-if="error" class="bg-red-50 dark:bg-red-900/20 p-6 rounded-2xl border border-red-100 dark:border-red-900/30 text-center">
            <p class="text-red-600 dark:text-red-400 font-bold">{{ error }}</p>
          </div>

          <div v-else class="space-y-8">
            <!-- Filter Toggle -->
            <div class="flex flex-wrap items-center justify-end gap-4">
              <!-- Level Checkboxes -->
              <div class="flex items-center gap-3">
                <span class="text-sm font-bold text-slate-600 dark:text-slate-300">公式レベル</span>
                <label class="flex items-center gap-1.5 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    v-model="showLv11"
                    class="w-4 h-4 rounded accent-indigo-500 cursor-pointer"
                  />
                  <span class="text-sm font-black text-indigo-600 dark:text-indigo-400">Lv.11</span>
                </label>
                <label class="flex items-center gap-1.5 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    v-model="showLv12"
                    class="w-4 h-4 rounded accent-indigo-500 cursor-pointer"
                  />
                  <span class="text-sm font-black text-indigo-600 dark:text-indigo-400">Lv.12</span>
                </label>
              </div>
              <!-- Divider -->
              <span class="w-px h-5 bg-slate-200 dark:bg-slate-600"></span>
              <!-- Both Played Toggle -->
              <div class="flex items-center gap-3">
                <span class="text-sm font-bold text-slate-600 dark:text-slate-300">両者プレイ済みのみ表示</span>
                <button
                  @click="showBothPlayedOnly = !showBothPlayedOnly"
                  class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none"
                  :class="showBothPlayedOnly ? 'bg-blue-600' : 'bg-slate-300 dark:bg-slate-600'"
                >
                  <span
                    class="inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform"
                    :class="showBothPlayedOnly ? 'translate-x-6' : 'translate-x-1'"
                  ></span>
                </button>
              </div>
            </div>

            <!-- Summary Cards -->
            <div class="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div v-for="(stats, key) in comparisonStats.summary" :key="key"
                v-show="key === 'overall' || (key === 'lv11' && showLv11) || (key === 'lv12' && showLv12)"
                class="bg-slate-100/50 dark:bg-slate-900/50 p-5 rounded-2xl border border-slate-200 dark:border-slate-800 transition-all hover:shadow-md">
                <h3 class="text-xs font-black text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-3">
                  {{ key === 'overall' ? '全体 (11 & 12)' : key === 'lv11' ? 'レベル 11' : 'レベル 12' }}
                </h3>
                <div class="font-black text-center" :class="showBothPlayedOnly ? 'grid grid-cols-3 gap-1' : 'grid grid-cols-5 gap-1'">
                  <div class="flex flex-col">
                    <span class="text-2xl text-blue-600 dark:text-blue-400">{{ stats.win }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">WIN</span>
                  </div>
                  <div v-if="!showBothPlayedOnly" class="flex flex-col bg-blue-50 dark:bg-blue-900/20 rounded-lg py-1">
                    <span class="text-lg text-blue-500/80">{{ stats.myOnly }}</span>
                    <span class="text-[8px] text-blue-400 dark:text-blue-500">YOU</span>
                  </div>
                  <div class="flex flex-col">
                    <span class="text-2xl text-slate-400 dark:text-slate-500">{{ stats.draw }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">DRAW</span>
                  </div>
                  <div v-if="!showBothPlayedOnly" class="flex flex-col bg-red-50 dark:bg-red-900/20 rounded-lg py-1">
                    <span class="text-lg text-red-400/80">{{ stats.friendOnly }}</span>
                    <span class="text-[8px] text-red-400 dark:text-red-500">FRIEND</span>
                  </div>
                  <div class="flex flex-col">
                    <span class="text-2xl text-red-500 dark:text-red-400">{{ stats.loss }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">LOSS</span>
                  </div>
                </div>
                <!-- Progress Bar -->
                <div class="mt-4 h-2 w-full bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden flex">
                  <div class="h-full bg-blue-500" :style="{ width: `${(stats.win/stats.total)*100}%` }"></div>
                  <div v-if="!showBothPlayedOnly" class="h-full bg-blue-300" :style="{ width: `${(stats.myOnly/stats.total)*100}%` }"></div>
                  <div class="h-full bg-slate-400" :style="{ width: `${(stats.draw/stats.total)*100}%` }"></div>
                  <div v-if="!showBothPlayedOnly" class="h-full bg-red-300" :style="{ width: `${(stats.friendOnly/stats.total)*100}%` }"></div>
                  <div class="h-full bg-red-400" :style="{ width: `${(stats.loss/stats.total)*100}%` }"></div>
                </div>
                <p class="mt-2 text-[10px] text-right text-slate-400 font-bold">{{ stats.total }} 曲対象</p>
              </div>
            </div>

            <!-- Unofficial Rank Table -->
            <div>
              <h3 class="text-xl font-black text-slate-800 dark:text-white mb-4 flex items-center gap-2">
                <span class="w-1.5 h-6 bg-indigo-500 rounded-full"></span>
                非公式難易度別 勝敗 (クリックで詳細)
              </h3>
              <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 overflow-hidden shadow-sm">
                <table class="w-full text-left border-collapse table-fixed">
                  <thead class="bg-slate-50 dark:bg-slate-900/80 text-xs sm:text-sm font-black text-slate-500 uppercase tracking-wider">
                    <tr>
                      <th class="p-4 w-24">ランク</th>
                      <th class="p-4 text-center">WIN</th>
                      <th v-if="!showBothPlayedOnly" class="p-4 text-center bg-blue-50/50 dark:bg-blue-900/10">YOU</th>
                      <th class="p-4 text-center">DRAW</th>
                      <th v-if="!showBothPlayedOnly" class="p-4 text-center bg-red-50/50 dark:bg-red-900/10">FRIEND</th>
                      <th class="p-4 text-center">LOSS</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-sm sm:text-lg">
                    <template v-for="[rank, stats] in comparisonStats.unofficial" :key="rank">
                      <tr 
                        @click="toggleRank(rank)"
                        class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors cursor-pointer select-none"
                      >
                        <td class="p-4 font-black flex items-center gap-2">
                          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 transition-transform text-slate-400" :class="{ 'rotate-90': expandedRanks.has(rank) }" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
                          </svg>
                          <span class="text-slate-800 dark:text-slate-200">{{ rank }}</span>
                        </td>
                        <td class="p-4 text-center font-black text-blue-600 dark:text-blue-400">{{ stats.win }}</td>
                        <td v-if="!showBothPlayedOnly" class="p-4 text-center font-black text-blue-500/80 bg-blue-50/30 dark:bg-blue-900/5">{{ stats.myOnly }}</td>
                        <td class="p-4 text-center font-black text-slate-400">{{ stats.draw }}</td>
                        <td v-if="!showBothPlayedOnly" class="p-4 text-center font-black text-red-500/80 bg-red-50/30 dark:bg-red-900/5">{{ stats.friendOnly }}</td>
                        <td class="p-4 text-center font-black text-red-500 dark:text-red-400">{{ stats.loss }}</td>
                      </tr>
                      <!-- Expanded Breakdown -->
                      <tr v-if="expandedRanks.has(rank)">
                        <td :colspan="showBothPlayedOnly ? 4 : 6" class="p-0 bg-slate-50/50 dark:bg-slate-900/20">
                          <div class="p-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                            <!-- WIN Section -->
                            <div v-if="stats.winSongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-black text-blue-600 dark:text-blue-400 uppercase tracking-widest flex items-center gap-1">
                                <span class="w-1 h-3 bg-blue-500 rounded-full"></span>
                                WIN ({{ stats.winSongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.winSongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-blue-100 dark:border-blue-900/30 text-xs">
                                  <span class="font-bold truncate max-w-[60%] text-slate-700 dark:text-slate-200">{{ s.title }}</span>
                                  <span class="font-black text-blue-600">+{{ s.diff }}</span>
                                </div>
                              </div>
                            </div>
                            <!-- LOSS Section -->
                            <div v-if="stats.lossSongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-black text-red-500 dark:text-red-400 uppercase tracking-widest flex items-center gap-1">
                                <span class="w-1 h-3 bg-red-500 rounded-full"></span>
                                LOSS ({{ stats.lossSongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.lossSongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-red-100 dark:border-red-900/30 text-xs">
                                  <span class="font-bold truncate max-w-[60%] text-slate-700 dark:text-slate-200">{{ s.title }}</span>
                                  <span class="font-black text-red-500">{{ s.diff }}</span>
                                </div>
                              </div>
                            </div>
                            <!-- DRAW Section -->
                            <div v-if="stats.drawSongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-black text-slate-400 uppercase tracking-widest flex items-center gap-1">
                                <span class="w-1 h-3 bg-slate-400 rounded-full"></span>
                                DRAW ({{ stats.drawSongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.drawSongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs">
                                  <span class="font-bold truncate max-w-[60%] text-slate-700 dark:text-slate-200">{{ s.title }}</span>
                                  <span class="font-black text-slate-400">±0</span>
                                </div>
                              </div>
                            </div>
                            <!-- YOU Only Section -->
                            <div v-if="stats.myOnlySongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-black text-blue-400 uppercase tracking-widest flex items-center gap-1">
                                <span class="w-1 h-3 bg-blue-300 rounded-full"></span>
                                YOU Only ({{ stats.myOnlySongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.myOnlySongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-blue-50 dark:border-blue-900/10 text-xs">
                                  <span class="font-bold truncate text-slate-500">{{ s.title }}</span>
                                </div>
                              </div>
                            </div>
                            <!-- FRIEND Only Section -->
                            <div v-if="stats.friendOnlySongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-black text-red-400 uppercase tracking-widest flex items-center gap-1">
                                <span class="w-1 h-3 bg-red-300 rounded-full"></span>
                                FRIEND Only ({{ stats.friendOnlySongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.friendOnlySongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-red-50 dark:border-red-900/10 text-xs text-slate-500">
                                  <span class="font-bold truncate">{{ s.title }}</span>
                                </div>
                              </div>
                            </div>
                          </div>
                        </td>
                      </tr>
                    </template>
                  </tbody>
                </table>
              </div>
            </div>

            <!-- Note -->
            <div class="flex items-start gap-3 p-4 bg-blue-50 dark:bg-blue-900/20 rounded-2xl border border-blue-100 dark:border-blue-900/30">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-blue-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <div class="text-xs text-blue-700 dark:text-blue-300 font-bold leading-relaxed">
                <p>・WIN/DRAW/LOSS: 両者がプレイ済みの楽曲のEX-SCORE比較</p>
                <p>・YOU Only: 自分のみプレイ済み / FRIEND Only: 相手のみプレイ済み</p>
                <p>・両者未プレイの楽曲は集計から除外して表示しています。</p>
              </div>
            </div>
          </div>
        </div>

        <!-- Footer -->
        <div class="p-6 border-t border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800 text-right">
          <button @click="emit('close')" class="w-full sm:w-auto px-12 py-4 bg-slate-900 hover:bg-black text-white font-black rounded-2xl transition-all shadow-lg active:scale-95 text-lg">
            閉じる
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.3s ease-out forwards;
}
@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.98); }
  to { opacity: 1; transform: scale(1); }
}
</style>
