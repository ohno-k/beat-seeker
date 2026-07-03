<script setup lang="ts">
/**
 * 【コンポーネントの役割】 管理者が任意の 2 ユーザー間のスコアを突き合わせるモーダル。
 *
 * FriendComparisonModal を「自分 vs 相手」から「ユーザー A vs ユーザー B」に置き換えた管理者用バリエーション。
 * UI / 集計ロジック / 表記 (WIN/LOSS/DRAW + A Only / B Only) は元モーダルとそろえている。
 *
 * 機能:
 *  - 2 ユーザーのスコアを並列取得し、Lv.11/Lv.12 に絞って EX-SCORE を突き合わせる
 *  - WIN / LOSS / DRAW / A Only / B Only をカウント (A の視点で WIN/LOSS を判定)
 *  - 非公式難易度 (例: 12.2, 12.A+) 別に集計し、クリックで詳細楽曲リストを展開
 *  - レベル表示トグル・両者プレイ済みのみトグルでフィルタ可能
 *
 * props:
 *  - userA / userB: 比較対象の AdminUserSummary (管理者 API `/api/admin/users` の戻り値要素)
 *  - isOpen: モーダル開閉
 * emits:
 *  - close: 閉じる
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useScores } from '../composables/useScores';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';

/** 管理者用ユーザー一覧 API のレスポンス要素 (id / displayName / iidxId などの軽量サマリ)。 */
export interface AdminUserSummary {
  id: number;
  displayName: string;
  iidxId: string;
}

const props = defineProps<{
  userA: AdminUserSummary;
  userB: AdminUserSummary;
  isOpen: boolean;
}>();

const emit = defineEmits<{
  'close': []
}>();

const { fetchUserScores, isFetching } = useScores();

/** ユーザー A の ScoreRecord (flatten 済み)。 */
const aProcessedScores = ref<ScoreRecord[]>([]);
/** ユーザー B の ScoreRecord (flatten 済み)。 */
const bProcessedScores = ref<ScoreRecord[]>([]);
/** 取得エラー文言。存在すれば赤帯表示。 */
const error = ref<string | null>(null);

const isLoading = computed(() => isFetching.value);

/**
 * 【関数の役割】 ユーザー A と B のスコアを並列取得し、flatten 済み配列に格納する。
 * 管理者 API (`/api/admin/users/{id}/scores`) は ScoreData[] 形式で返るので flattenScores に直接渡せる。
 */
const loadData = async () => {
  if (!props.isOpen) return;
  error.value = null;
  try {
    const [aRaw, bRaw] = await Promise.all([
      fetchUserScores(props.userA.id, 'admin'),
      fetchUserScores(props.userB.id, 'admin')
    ]);
    aProcessedScores.value = flattenScores(aRaw);
    bProcessedScores.value = flattenScores(bRaw);
  } catch (e: any) {
    console.error('[AdminComparisonModal] loadData error', e);
    error.value = e.message;
  }
};

onMounted(() => {
  if (props.isOpen) loadData();
});

// 開閉 or 比較対象変更で再取得する。
watch(() => [props.isOpen, props.userA.id, props.userB.id], ([open]) => {
  if (open) loadData();
});

/** 楽曲 1 行分の比較結果。WIN/LOSS/DRAW 表の詳細セクションで使用。 */
interface SongComparison {
  title: string;
  difficultyName: string;
  aScore: number;
  bScore: number;
  diff: number;
  aClearType: string;
  bClearType: string;
}

/** 集計結果 (カウント + 各カテゴリの楽曲リスト)。overall/lv11/lv12/非公式ランク毎に持つ。 */
interface ComparisonResult {
  win: number;
  loss: number;
  draw: number;
  aOnly: number;
  bOnly: number;
  total: number;
  winSongs: SongComparison[];
  lossSongs: SongComparison[];
  drawSongs: SongComparison[];
  aOnlySongs: SongComparison[];
  bOnlySongs: SongComparison[];
}

/**
 * 【computed の役割】 ユーザー A・B のスコアから全集計結果を一気に算出する。
 * WIN/LOSS は A 視点 (A が勝てば WIN、B が勝てば LOSS) で判定する。
 */
const comparisonStats = computed(() => {
  const initStats = (): ComparisonResult => ({
    win: 0, loss: 0, draw: 0, aOnly: 0, bOnly: 0, total: 0,
    winSongs: [], lossSongs: [], drawSongs: [], aOnlySongs: [], bOnlySongs: []
  });
  const res: Record<string, ComparisonResult> = {
    overall: initStats(),
    lv10minus: initStats(),
    lv11: initStats(),
    lv12: initStats()
  };

  const unofficialRanks: Record<string, ComparisonResult> = {};

  const allKeys = new Set<string>();
  const aMap = new Map<string, ScoreRecord>();
  const bMap = new Map<string, ScoreRecord>();

  // ANOTHER / LEGGENDARIA かつ、現在 ON になっているレベル帯のみ採用する。
  const isTargetRecord = (s: ScoreRecord): boolean => {
    if (s.difficultyName !== 'ANOTHER' && s.difficultyName !== 'LEGGENDARIA') return false;
    const lv = s.difficultyLevel;
    if (lv == null) return false;
    if (lv <= 10) return showLv10Minus.value;
    if (lv === 11) return showLv11.value;
    if (lv === 12) return showLv12.value;
    return false;
  };

  aProcessedScores.value.forEach(s => {
    if (!isTargetRecord(s)) return;
    const key = `${s.title}_${s.difficultyName}`;
    allKeys.add(key);
    aMap.set(key, s);
  });

  bProcessedScores.value.forEach(s => {
    if (!isTargetRecord(s)) return;
    const key = `${s.title}_${s.difficultyName}`;
    allKeys.add(key);
    bMap.set(key, s);
  });

  allKeys.forEach(key => {
    const aScore = aMap.get(key);
    const bScore = bMap.get(key);

    const s = aScore || bScore;
    if (!s) return;

    const aPlay = aScore && aScore.score > 0;
    const bPlay = bScore && bScore.score > 0;

    if (!aPlay && !bPlay) return;
    if (showBothPlayedOnly.value && !(aPlay && bPlay)) return;

    const lv = s.difficultyLevel ?? 0;
    const lvKey = lv <= 10 ? 'lv10minus' : lv === 11 ? 'lv11' : 'lv12';
    const rank = s.informalRank && !s.informalRank.includes('Uncategorized') ? s.informalRank : null;

    const update = (stats: ComparisonResult) => {
      stats.total++;
      const songInfo: SongComparison = {
        title: s.title,
        difficultyName: s.difficultyName,
        aScore: aScore?.score || 0,
        bScore: bScore?.score || 0,
        diff: (aScore?.score || 0) - (bScore?.score || 0),
        aClearType: aScore?.clearType || 'NO PLAY',
        bClearType: bScore?.clearType || 'NO PLAY'
      };

      if (aPlay && bPlay) {
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
      } else if (aPlay) {
        stats.aOnly++;
        stats.aOnlySongs.push(songInfo);
      } else if (bPlay) {
        stats.bOnly++;
        stats.bOnlySongs.push(songInfo);
      }
    };

    update(res.overall);
    update(res[lvKey]);
    if (rank) {
      if (!unofficialRanks[rank]) unofficialRanks[rank] = initStats();
      update(unofficialRanks[rank]);
    }
  });

  const sortSongs = (stats: ComparisonResult) => {
    stats.winSongs.sort((a, b) => b.diff - a.diff);
    stats.lossSongs.sort((a, b) => b.diff - a.diff);
    stats.drawSongs.sort((a, b) => a.title.localeCompare(b.title));
    stats.aOnlySongs.sort((a, b) => a.title.localeCompare(b.title));
    stats.bOnlySongs.sort((a, b) => a.title.localeCompare(b.title));
  };

  sortSongs(res.overall);
  sortSongs(res.lv10minus);
  sortSongs(res.lv11);
  sortSongs(res.lv12);
  Object.values(unofficialRanks).forEach(sortSongs);

  const sortedUnofficial = Object.entries(unofficialRanks)
    .sort(([a], [b]) => parseFloat(b) - parseFloat(a));

  return {
    summary: res,
    unofficial: sortedUnofficial
  };
});

/** 現在展開している非公式ランクのセット。 */
const expandedRanks = ref<Set<string>>(new Set());
const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
};

const showBothPlayedOnly = ref(false);
/** Lv.10 以下 (ANOTHER/LEGGENDARIA) を集計に含めるか。デフォルト OFF。 */
const showLv10Minus = ref(false);
const showLv11 = ref(true);
const showLv12 = ref(true);
</script>

<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[9999] flex items-start justify-center p-2 sm:p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in overflow-y-auto">
      <div class="bg-white dark:bg-slate-800 w-full max-w-5xl my-3 sm:my-12 rounded-md shadow-xl overflow-hidden flex flex-col border border-slate-200 dark:border-slate-700">
        <!-- ヘッダー -->
        <div class="p-4 sm:p-6 border-b border-slate-100 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-800/50">
          <div class="min-w-0">
            <h2 class="text-lg sm:text-2xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 sm:h-8 sm:w-8 text-blue-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              スコア比較 (管理者)
            </h2>
            <p class="text-slate-500 dark:text-slate-400 text-xs sm:text-sm font-bold mt-1 truncate">
              <span class="text-blue-600 dark:text-blue-400 font-bold">{{ userA.displayName }}</span>
              <span class="mx-1.5 text-slate-400">vs</span>
              <span class="text-red-500 dark:text-red-400 font-bold">{{ userB.displayName }}</span>
            </p>
          </div>
          <button @click="emit('close')" class="p-2 shrink-0 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors text-slate-400 hover:text-slate-600">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- コンテンツ本体 -->
        <div class="flex-1 p-3 sm:p-6 space-y-6 sm:space-y-8">
          <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
            <div class="w-12 h-12 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 font-bold">データを集計中...</p>
          </div>

          <div v-else-if="error" class="bg-red-50 dark:bg-red-900/20 p-6 rounded-md border border-red-100 dark:border-red-900/30 text-center">
            <p class="text-red-600 dark:text-red-400 font-bold">{{ error }}</p>
          </div>

          <div v-else class="space-y-6 sm:space-y-8">
            <!-- フィルタートグル群 -->
            <div class="flex flex-wrap items-center justify-start sm:justify-end gap-x-3 gap-y-2 sm:gap-4">
              <div class="flex items-center gap-2 sm:gap-3">
                <span class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-300">公式レベル</span>
                <label class="flex items-center gap-1.5 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    v-model="showLv10Minus"
                    class="w-4 h-4 rounded accent-indigo-500 cursor-pointer"
                  />
                  <span class="text-xs sm:text-sm font-bold text-indigo-600 dark:text-indigo-400">Lv.10以下</span>
                </label>
                <label class="flex items-center gap-1.5 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    v-model="showLv11"
                    class="w-4 h-4 rounded accent-indigo-500 cursor-pointer"
                  />
                  <span class="text-xs sm:text-sm font-bold text-indigo-600 dark:text-indigo-400">Lv.11</span>
                </label>
                <label class="flex items-center gap-1.5 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    v-model="showLv12"
                    class="w-4 h-4 rounded accent-indigo-500 cursor-pointer"
                  />
                  <span class="text-xs sm:text-sm font-bold text-indigo-600 dark:text-indigo-400">Lv.12</span>
                </label>
              </div>
              <span class="hidden sm:block w-px h-5 bg-slate-200 dark:bg-slate-600"></span>
              <div class="flex items-center gap-2 sm:gap-3">
                <span class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-300">両者プレイ済みのみ</span>
                <button
                  @click="showBothPlayedOnly = !showBothPlayedOnly"
                  class="relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:outline-none shrink-0"
                  :class="showBothPlayedOnly ? 'bg-blue-600' : 'bg-slate-300 dark:bg-slate-600'"
                >
                  <span
                    class="inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform"
                    :class="showBothPlayedOnly ? 'translate-x-6' : 'translate-x-1'"
                  ></span>
                </button>
              </div>
            </div>

            <!-- サマリーカード -->
            <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
              <div v-for="(stats, key) in comparisonStats.summary" :key="key"
                v-show="key === 'overall' || (key === 'lv10minus' && showLv10Minus) || (key === 'lv11' && showLv11) || (key === 'lv12' && showLv12)"
                class="bg-slate-100/50 dark:bg-slate-900/50 p-3 sm:p-5 rounded-md border border-slate-200 dark:border-slate-800 transition-all">
                <h3 class="text-xs font-bold text-slate-400 dark:text-slate-500 mb-3">
                  {{ key === 'overall' ? '全体' : key === 'lv10minus' ? 'レベル 10 以下' : key === 'lv11' ? 'レベル 11' : 'レベル 12' }}
                </h3>
                <div class="font-bold text-center" :class="showBothPlayedOnly ? 'grid grid-cols-3 gap-1' : 'grid grid-cols-5 gap-1'">
                  <div class="flex flex-col">
                    <span class="text-xl sm:text-2xl text-blue-600 dark:text-blue-400">{{ stats.win }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">WIN (A)</span>
                  </div>
                  <div v-if="!showBothPlayedOnly" class="flex flex-col bg-blue-50 dark:bg-blue-900/20 rounded-lg py-1">
                    <span class="text-base sm:text-lg text-blue-500/80">{{ stats.aOnly }}</span>
                    <span class="text-[8px] text-blue-400 dark:text-blue-500">A Only</span>
                  </div>
                  <div class="flex flex-col">
                    <span class="text-xl sm:text-2xl text-slate-400 dark:text-slate-500">{{ stats.draw }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">DRAW</span>
                  </div>
                  <div v-if="!showBothPlayedOnly" class="flex flex-col bg-red-50 dark:bg-red-900/20 rounded-lg py-1">
                    <span class="text-base sm:text-lg text-red-400/80">{{ stats.bOnly }}</span>
                    <span class="text-[8px] text-red-400 dark:text-red-500">B Only</span>
                  </div>
                  <div class="flex flex-col">
                    <span class="text-xl sm:text-2xl text-red-500 dark:text-red-400">{{ stats.loss }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">LOSS (A)</span>
                  </div>
                </div>
                <!-- 進捗バー: total=0 で NaN を出さないようガード -->
                <div class="mt-4 h-2 w-full bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden flex">
                  <div class="h-full bg-blue-500" :style="{ width: `${stats.total > 0 ? (stats.win/stats.total)*100 : 0}%` }"></div>
                  <div v-if="!showBothPlayedOnly" class="h-full bg-blue-300" :style="{ width: `${stats.total > 0 ? (stats.aOnly/stats.total)*100 : 0}%` }"></div>
                  <div class="h-full bg-slate-400" :style="{ width: `${stats.total > 0 ? (stats.draw/stats.total)*100 : 0}%` }"></div>
                  <div v-if="!showBothPlayedOnly" class="h-full bg-red-300" :style="{ width: `${stats.total > 0 ? (stats.bOnly/stats.total)*100 : 0}%` }"></div>
                  <div class="h-full bg-red-400" :style="{ width: `${stats.total > 0 ? (stats.loss/stats.total)*100 : 0}%` }"></div>
                </div>
                <p class="mt-2 text-[10px] text-right text-slate-400 font-bold">{{ stats.total }} 曲対象</p>
              </div>
            </div>

            <!-- 非公式難易度別テーブル -->
            <div>
              <h3 class="text-base sm:text-xl font-bold text-slate-800 dark:text-white mb-3 sm:mb-4 flex items-center gap-2">
                <span class="w-1.5 h-5 sm:h-6 bg-indigo-500 rounded-full"></span>
                非公式難易度別 勝敗 <span class="text-xs sm:text-sm text-slate-500 dark:text-slate-400 font-bold">(クリックで詳細)</span>
              </h3>
              <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-100 dark:border-slate-700 overflow-hidden">
                <table class="w-full text-left border-collapse table-fixed">
                  <thead class="bg-slate-50 dark:bg-slate-900/80 text-[10px] sm:text-sm font-bold text-slate-500">
                    <tr>
                      <th class="p-2 sm:p-4 w-14 sm:w-24">ランク</th>
                      <th class="p-2 sm:p-4 text-center">WIN (A)</th>
                      <th v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center bg-blue-50/50 dark:bg-blue-900/10">A Only</th>
                      <th class="p-2 sm:p-4 text-center">DRAW</th>
                      <th v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center bg-red-50/50 dark:bg-red-900/10">B Only</th>
                      <th class="p-2 sm:p-4 text-center">LOSS (A)</th>
                    </tr>
                  </thead>
                  <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-sm sm:text-lg">
                    <template v-for="[rank, stats] in comparisonStats.unofficial" :key="rank">
                      <tr
                        @click="toggleRank(rank)"
                        class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors cursor-pointer select-none"
                      >
                        <td class="p-2 sm:p-4 font-bold">
                          <div class="flex items-center gap-1 sm:gap-2">
                            <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 sm:h-4 sm:w-4 transition-transform text-slate-400 shrink-0" :class="{ 'rotate-90': expandedRanks.has(rank) }" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
                            </svg>
                            <span class="text-slate-800 dark:text-slate-200 whitespace-nowrap">{{ rank }}</span>
                          </div>
                        </td>
                        <td class="p-2 sm:p-4 text-center font-bold text-blue-600 dark:text-blue-400">{{ stats.win }}</td>
                        <td v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center font-bold text-blue-500/80 bg-blue-50/30 dark:bg-blue-900/5">{{ stats.aOnly }}</td>
                        <td class="p-2 sm:p-4 text-center font-bold text-slate-400">{{ stats.draw }}</td>
                        <td v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center font-bold text-red-500/80 bg-red-50/30 dark:bg-red-900/5">{{ stats.bOnly }}</td>
                        <td class="p-2 sm:p-4 text-center font-bold text-red-500 dark:text-red-400">{{ stats.loss }}</td>
                      </tr>
                      <tr v-if="expandedRanks.has(rank)">
                        <td :colspan="showBothPlayedOnly ? 4 : 6" class="p-0 bg-slate-50/50 dark:bg-slate-900/20">
                          <div class="p-3 sm:p-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4">
                            <div v-if="stats.winSongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-blue-600 dark:text-blue-400 flex items-center gap-1">
                                <span class="w-1 h-3 bg-blue-500 rounded-full"></span>
                                WIN ({{ stats.winSongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.winSongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-blue-100 dark:border-blue-900/30 text-xs">
                                  <span class="font-bold truncate max-w-[60%] text-slate-700 dark:text-slate-200">{{ s.title }}</span>
                                  <span class="font-bold text-blue-600">+{{ s.diff }}</span>
                                </div>
                              </div>
                            </div>
                            <div v-if="stats.lossSongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-red-500 dark:text-red-400 flex items-center gap-1">
                                <span class="w-1 h-3 bg-red-500 rounded-full"></span>
                                LOSS ({{ stats.lossSongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.lossSongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-red-100 dark:border-red-900/30 text-xs">
                                  <span class="font-bold truncate max-w-[60%] text-slate-700 dark:text-slate-200">{{ s.title }}</span>
                                  <span class="font-bold text-red-500">{{ s.diff }}</span>
                                </div>
                              </div>
                            </div>
                            <div v-if="stats.drawSongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-slate-400 flex items-center gap-1">
                                <span class="w-1 h-3 bg-slate-400 rounded-full"></span>
                                DRAW ({{ stats.drawSongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.drawSongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-slate-200 dark:border-slate-700 text-xs">
                                  <span class="font-bold truncate max-w-[60%] text-slate-700 dark:text-slate-200">{{ s.title }}</span>
                                  <span class="font-bold text-slate-400">±0</span>
                                </div>
                              </div>
                            </div>
                            <div v-if="stats.aOnlySongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-blue-400 flex items-center gap-1">
                                <span class="w-1 h-3 bg-blue-300 rounded-full"></span>
                                A Only ({{ stats.aOnlySongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.aOnlySongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-blue-50 dark:border-blue-900/10 text-xs">
                                  <span class="font-bold truncate text-slate-500">{{ s.title }}</span>
                                </div>
                              </div>
                            </div>
                            <div v-if="stats.bOnlySongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-red-400 flex items-center gap-1">
                                <span class="w-1 h-3 bg-red-300 rounded-full"></span>
                                B Only ({{ stats.bOnlySongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.bOnlySongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-red-50 dark:border-red-900/10 text-xs text-slate-500">
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

            <!-- 注意書き -->
            <div class="flex items-start gap-2 sm:gap-3 p-3 sm:p-4 bg-blue-50 dark:bg-blue-900/20 rounded-md border border-blue-100 dark:border-blue-900/30">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 sm:h-6 sm:w-6 text-blue-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <div class="text-[11px] sm:text-xs text-blue-700 dark:text-blue-300 font-bold leading-relaxed min-w-0">
                <p>・集計対象は ANOTHER / LEGGENDARIA 譜面のみ。BEGINNER / NORMAL / HYPER は除外しています。</p>
                <p>・WIN/DRAW/LOSS: 両者がプレイ済みの楽曲のEX-SCORE比較 (A 視点)</p>
                <p>・A Only: ユーザー A のみプレイ済み / B Only: ユーザー B のみプレイ済み</p>
                <p>・両者未プレイの楽曲は集計から除外して表示しています。</p>
              </div>
            </div>
          </div>
        </div>

        <!-- フッター -->
        <div class="p-3 sm:p-6 border-t border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800 text-right">
          <button @click="emit('close')" class="w-full sm:w-auto px-8 sm:px-12 py-3 sm:py-4 bg-slate-900 hover:bg-black text-white font-bold rounded-md transition-all active:scale-95 text-base sm:text-lg">
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
