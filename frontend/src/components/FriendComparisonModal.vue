<script setup lang="ts">
/**
 * 【コンポーネントの役割】 フレンド（または都道府県 TOP ランカー＝バーチャルライバル）とスコア比較を行うモーダル。
 *
 * 機能:
 *  - 自分と相手のスコアを取得し、ANOTHER/LEGGENDARIA 譜面に絞って EX-SCORE を突き合わせる
 *  - 集計対象は Lv.10以下 / Lv.11 / Lv.12 の 3 バケット（チェックボックスで個別 ON/OFF 可能）
 *  - Lv.10以下はデフォルト OFF（既存ユーザーの体験を変えないため）
 *  - WIN / LOSS / DRAW / YOU Only / FRIEND Only をカウント
 *  - 非公式難易度（例: 12.2, 12.A+）別に集計し、クリックで詳細楽曲リストを展開
 *  - レベル表示トグル・両者プレイ済みのみトグルでフィルタ可能
 *
 * props:
 *  - friend: 比較相手（バーチャル時は負の ID のダミー Friend）
 *  - virtualArea: バーチャルライバル時の取得元（versionNum + prefectureFileNum）
 *  - isOpen: モーダル開閉
 * emits:
 *  - close: 閉じる
 */
import { ref, computed, onMounted, watch } from 'vue';
import { useFriends, type Friend } from '../composables/useFriends';
import { useScores } from '../composables/useScores';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';
import type { ScoreData } from '../types/ScoreData';
import FriendComparisonChartModal from './FriendComparisonChartModal.vue';

/** グラフモーダルが対象とする集計単位の指定。 'rank:12.0' で非公式難易度を指定。 */
type ChartScope = 'overall' | 'lv10minus' | 'lv11' | 'lv12' | `rank:${string}`;

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

/** 自分の ScoreRecord（flatten 済み）。 */
const myProcessedScores = ref<ScoreRecord[]>([]);
/** 相手（フレンド or バーチャルライバル）の ScoreRecord（flatten 済み）。 */
const friendProcessedScores = ref<ScoreRecord[]>([]);
/** 取得エラー文言。存在すれば赤帯表示。 */
const error = ref<string | null>(null);

/** 自分 or 相手どちらかが取得中なら true。スピナー表示用。 */
const isLoading = computed(() => isFriendLoading.value || isMyLoading.value);

/**
 * 【関数の役割】 自分と相手のスコアを並列取得し、flatten 済み配列に格納する。
 * virtualArea が指定されていれば TOP ランカープロフィール API、そうでなければフレンド用 API を叩く。
 * フレンド API は生 flat 形式なので groupScores() で ScoreData 風に整形してから flatten し直す。
 */
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

// マウント時に既に isOpen なら即ロード（親が v-if ではなく :isOpen で切り替える場合に備える）。
onMounted(() => {
  console.log('[FriendComparisonModal] onMounted', {
    isOpen: props.isOpen,
    virtualArea: props.virtualArea,
    friendId: props.friend.id,
  });
  if (props.isOpen) loadData();
});

// モーダル開閉・比較相手変更・バーチャルエリア変更のいずれかで再取得する。
watch(() => [props.isOpen, props.friend.id, props.virtualArea?.versionNum, props.virtualArea?.prefectureFileNum], ([open]) => {
  console.log('[FriendComparisonModal] watch triggered', {
    isOpen: props.isOpen,
    virtualArea: props.virtualArea,
    friendId: props.friend.id,
  });
  if (open) loadData();
});

/**
 * 【関数の役割】 フレンド API の flat 形式スコア配列を ScoreData[]（楽曲単位にまとめた形式）に変換する。
 * flattenScores() に再度渡して差分比較可能な ScoreRecord[] に戻すための橋渡し関数。
 * 難易度キーは difficultyName を小文字化したもの（beginner/normal/hyper/another/leggendaria）。
 */
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

/** 楽曲 1 行分の比較結果。WIN/LOSS/DRAW 表の詳細セクションで使用。 */
interface SongComparison {
  title: string;
  difficultyName: string;
  myScore: number;
  friendScore: number;
  diff: number;
  myClearType: string;
  friendClearType: string;
}

/** 集計結果（カウント + 各カテゴリの楽曲リスト）。overall/lv11/lv12/非公式ランク毎に持つ。 */
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

/**
 * 【computed の役割】 自分と相手のスコアから全集計結果を一気に算出する。
 * 返り値:
 *  - summary: overall / lv10minus / lv11 / lv12 のカード表示用結果
 *  - unofficial: [非公式ランク文字列, 結果] のタプル配列（降順ソート）
 * 集計対象: ANOTHER / LEGGENDARIA 譜面に限定。レベル表示トグルで Lv.10以下 / Lv.11 / Lv.12 を個別除外可。
 */
const comparisonStats = computed(() => {
  const initStats = (): ComparisonResult => ({
    win: 0, loss: 0, draw: 0, myOnly: 0, friendOnly: 0, total: 0,
    winSongs: [], lossSongs: [], drawSongs: [], myOnlySongs: [], friendOnlySongs: []
  });
  const res: Record<string, ComparisonResult> = {
    overall: initStats(),
    lv10minus: initStats(),
    lv11: initStats(),
    lv12: initStats()
  };

  const unofficialRanks: Record<string, ComparisonResult> = {};

  // 両者スコアの「曲+難易度」キーを統合。myMap/friendMap で O(1) アクセス用に保持。
  const allKeys = new Set<string>();
  const myMap = new Map<string, ScoreRecord>();
  const friendMap = new Map<string, ScoreRecord>();

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

  myProcessedScores.value.forEach(s => {
    if (!isTargetRecord(s)) return;
    const key = `${s.title}_${s.difficultyName}`;
    allKeys.add(key);
    myMap.set(key, s);
  });

  friendProcessedScores.value.forEach(s => {
    if (!isTargetRecord(s)) return;
    const key = `${s.title}_${s.difficultyName}`;
    allKeys.add(key);
    friendMap.set(key, s);
  });

  allKeys.forEach(key => {
    const myScore = myMap.get(key);
    const friendScore = friendMap.get(key);

    const s = myScore || friendScore; // タイトル等の取得用の代表レコード
    if (!s) return;

    const myPlay = myScore && myScore.score > 0;
    const friendPlay = friendScore && friendScore.score > 0;

    if (!myPlay && !friendPlay) return; // 両者未プレイはスキップ
    if (showBothPlayedOnly.value && !(myPlay && friendPlay)) return; // 「両者プレイ済みのみ」トグル有効時は片方だけプレイも除外

    const lv = s.difficultyLevel ?? 0;
    const lvKey = lv <= 10 ? 'lv10minus' : lv === 11 ? 'lv11' : 'lv12';
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

  // 楽曲リストを並び替える: WIN は差が大きい順、LOSS は差の値が小さい順（例: -10, -50, -100）、それ以外はタイトル順。
  const sortSongs = (stats: ComparisonResult) => {
    stats.winSongs.sort((a, b) => b.diff - a.diff);
    stats.lossSongs.sort((a, b) => b.diff - a.diff);
    stats.drawSongs.sort((a, b) => a.title.localeCompare(b.title));
    stats.myOnlySongs.sort((a, b) => a.title.localeCompare(b.title));
    stats.friendOnlySongs.sort((a, b) => a.title.localeCompare(b.title));
  };

  sortSongs(res.overall);
  sortSongs(res.lv10minus);
  sortSongs(res.lv11);
  sortSongs(res.lv12);
  Object.values(unofficialRanks).forEach(sortSongs);

  // 非公式ランクを数値降順に並べる（12.A+ → 12.A → 12.B のように強い順）。
  const sortedUnofficial = Object.entries(unofficialRanks)
    .sort(([a], [b]) => parseFloat(b) - parseFloat(a));

  return {
    summary: res,
    unofficial: sortedUnofficial
  };
});

/** 現在展開している非公式ランクのセット。クリックで行詳細を出し入れする。 */
const expandedRanks = ref<Set<string>>(new Set());
/** 【関数の役割】 対象ランクの展開状態を反転させる。 */
const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
};

/** 「両者プレイ済みのみ」表示トグル。true なら YOU Only / FRIEND Only を除外。 */
const showBothPlayedOnly = ref(false);
/** Lv.10 以下 (ANOTHER/LEGGENDARIA) を集計に含めるか。デフォルト OFF。 */
const showLv10Minus = ref(false);
/** Lv.11 を集計に含めるか。 */
const showLv11 = ref(true);
/** Lv.12 を集計に含めるか。 */
const showLv12 = ref(true);

/** 勝敗変遷グラフモーダルの開閉。 */
const isChartModalOpen = ref(false);
/** グラフ対象の scope（'overall' | 'lv11' | 'lv12' | `rank:12.0` 等）。 */
const chartScope = ref<ChartScope>('overall');
/** グラフモーダルヘッダに出す表示用ラベル。 */
const chartScopeLabel = ref<string>('');

/** サマリーカード用に scope に対応するラベルを返す。 */
const summaryLabel = (key: string) => {
  if (key === 'overall') return '全体';
  if (key === 'lv10minus') return 'レベル 10 以下';
  if (key === 'lv11') return 'レベル 11';
  return 'レベル 12';
};

/** サマリーカードのグラフボタン押下。クリックバブリングを防いで対象 scope でモーダルを開く。 */
const openSummaryChart = (key: string, ev: Event) => {
  ev.stopPropagation();
  chartScope.value = (key as ChartScope);
  chartScopeLabel.value = summaryLabel(key);
  isChartModalOpen.value = true;
};

/** 非公式難易度行のグラフボタン押下。行の展開トグルとは独立に動かしたいので stopPropagation。 */
const openRankChart = (rank: string, ev: Event) => {
  ev.stopPropagation();
  chartScope.value = (`rank:${rank}` as ChartScope);
  chartScopeLabel.value = `☆${rank}`;
  isChartModalOpen.value = true;
};
</script>

<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[9999] flex items-start justify-center p-2 sm:p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in overflow-y-auto">
      <div class="bg-white dark:bg-slate-800 w-full max-w-5xl my-3 sm:my-12 rounded-md shadow-xl overflow-hidden flex flex-col border border-slate-200 dark:border-slate-700">
        <!-- ヘッダー（タイトル + 相手表示名 + ×ボタン） -->
        <div class="p-4 sm:p-6 border-b border-slate-100 dark:border-slate-700 flex justify-between items-center bg-slate-50 dark:bg-slate-800/50">
          <div class="min-w-0">
            <h2 class="text-lg sm:text-2xl font-bold text-slate-900 dark:text-white flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 sm:h-8 sm:w-8 text-blue-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" />
              </svg>
              スコア比較
            </h2>
            <p class="text-slate-500 dark:text-slate-400 text-xs sm:text-sm font-bold mt-1 truncate">
              vs <span class="text-blue-600 dark:text-blue-400 font-bold">{{ friend.displayName }}</span>
            </p>
          </div>
          <button @click="emit('close')" class="p-2 shrink-0 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors text-slate-400 hover:text-slate-600">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- コンテンツ本体（ローディング/エラー/本文 を状態で切替） -->
        <div class="flex-1 p-3 sm:p-6 space-y-6 sm:space-y-8">
          <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
            <div class="w-12 h-12 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 font-bold">データを集計中...</p>
          </div>

          <div v-else-if="error" class="bg-red-50 dark:bg-red-900/20 p-6 rounded-md border border-red-100 dark:border-red-900/30 text-center">
            <p class="text-red-600 dark:text-red-400 font-bold">{{ error }}</p>
          </div>

          <div v-else class="space-y-6 sm:space-y-8">
            <!-- フィルタートグル群（レベルチェック + 両者プレイ済み）。狭幅では行ごとに折り返してタップ領域を確保 -->
            <div class="flex flex-wrap items-center justify-start sm:justify-end gap-x-3 gap-y-2 sm:gap-4">
              <!-- レベル選択チェックボックス (ANOTHER/LEGGENDARIA 譜面のみ集計) -->
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
              <!-- 仕切り線（狭幅時は折り返しが入るので装飾は sm: 以上のみ） -->
              <span class="hidden sm:block w-px h-5 bg-slate-200 dark:bg-slate-600"></span>
              <!-- 両者プレイ済みのみ表示トグル -->
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

            <!-- サマリーカード（overall / lv10minus / lv11 / lv12 の最大 4 カード並び、WIN/DRAW/LOSS の大数字 + 進捗バー）。
                 iPhone は 1 列、Z Fold3 展開時 (sm:) で 2 列、lg 以上で 4 列 -->
            <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4">
              <div v-for="(stats, key) in comparisonStats.summary" :key="key"
                v-show="key === 'overall' || (key === 'lv10minus' && showLv10Minus) || (key === 'lv11' && showLv11) || (key === 'lv12' && showLv12)"
                class="bg-slate-100/50 dark:bg-slate-900/50 p-3 sm:p-5 rounded-md border border-slate-200 dark:border-slate-800 transition-all">
                <div class="flex items-center justify-between mb-3">
                  <h3 class="text-xs font-bold text-slate-400 dark:text-slate-500">
                    {{ summaryLabel(key) }}
                  </h3>
                  <button
                    type="button"
                    @click="openSummaryChart(key, $event)"
                    class="p-1 rounded-md text-slate-400 hover:text-indigo-500 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors"
                    title="勝敗の変遷をグラフで見る"
                    aria-label="勝敗の変遷をグラフで見る"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M3 3v18h18M7 14l3-3 4 4 5-7" />
                    </svg>
                  </button>
                </div>
                <div class="font-bold text-center" :class="showBothPlayedOnly ? 'grid grid-cols-3 gap-1' : 'grid grid-cols-5 gap-1'">
                  <div class="flex flex-col">
                    <span class="text-xl sm:text-2xl text-blue-600 dark:text-blue-400">{{ stats.win }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">WIN</span>
                  </div>
                  <div v-if="!showBothPlayedOnly" class="flex flex-col bg-blue-50 dark:bg-blue-900/20 rounded-lg py-1">
                    <span class="text-base sm:text-lg text-blue-500/80">{{ stats.myOnly }}</span>
                    <span class="text-[8px] text-blue-400 dark:text-blue-500">YOU</span>
                  </div>
                  <div class="flex flex-col">
                    <span class="text-xl sm:text-2xl text-slate-400 dark:text-slate-500">{{ stats.draw }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">DRAW</span>
                  </div>
                  <div v-if="!showBothPlayedOnly" class="flex flex-col bg-red-50 dark:bg-red-900/20 rounded-lg py-1">
                    <span class="text-base sm:text-lg text-red-400/80">{{ stats.friendOnly }}</span>
                    <span class="text-[8px] text-red-400 dark:text-red-500">FRIEND</span>
                  </div>
                  <div class="flex flex-col">
                    <span class="text-xl sm:text-2xl text-red-500 dark:text-red-400">{{ stats.loss }}</span>
                    <span class="text-[8px] text-slate-400 dark:text-slate-500">LOSS</span>
                  </div>
                </div>
                <!-- 進捗バー（割合の帯グラフ。WIN→YOU→DRAW→FRIEND→LOSS の順）。total=0 のときは 0% で描画して NaN を避ける -->
                <div class="mt-4 h-2 w-full bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden flex">
                  <div class="h-full bg-blue-500" :style="{ width: `${stats.total > 0 ? (stats.win/stats.total)*100 : 0}%` }"></div>
                  <div v-if="!showBothPlayedOnly" class="h-full bg-blue-300" :style="{ width: `${stats.total > 0 ? (stats.myOnly/stats.total)*100 : 0}%` }"></div>
                  <div class="h-full bg-slate-400" :style="{ width: `${stats.total > 0 ? (stats.draw/stats.total)*100 : 0}%` }"></div>
                  <div v-if="!showBothPlayedOnly" class="h-full bg-red-300" :style="{ width: `${stats.total > 0 ? (stats.friendOnly/stats.total)*100 : 0}%` }"></div>
                  <div class="h-full bg-red-400" :style="{ width: `${stats.total > 0 ? (stats.loss/stats.total)*100 : 0}%` }"></div>
                </div>
                <p class="mt-2 text-[10px] text-right text-slate-400 font-bold">{{ stats.total }} 曲対象</p>
              </div>
            </div>

            <!-- 非公式難易度別テーブル（行クリックで詳細リストを展開） -->
            <div>
              <h3 class="text-base sm:text-xl font-bold text-slate-800 dark:text-white mb-3 sm:mb-4 flex items-center gap-2">
                <span class="w-1.5 h-5 sm:h-6 bg-indigo-500 rounded-full"></span>
                非公式難易度別 勝敗 <span class="text-xs sm:text-sm text-slate-500 dark:text-slate-400 font-bold">(クリックで詳細)</span>
              </h3>
              <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-100 dark:border-slate-700 overflow-hidden">
                <table class="w-full text-left border-collapse table-fixed">
                  <thead class="bg-slate-50 dark:bg-slate-900/80 text-[10px] sm:text-sm font-bold text-slate-500">
                    <tr>
                      <!-- ランク列はアイコン2つ + 文字を入れる必要があるので、最低 5em 確保 -->
                      <th class="p-2 sm:p-4 w-[5.5rem] sm:w-32">ランク</th>
                      <th class="p-2 sm:p-4 text-center">WIN</th>
                      <th v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center bg-blue-50/50 dark:bg-blue-900/10">YOU</th>
                      <th class="p-2 sm:p-4 text-center">DRAW</th>
                      <th v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center bg-red-50/50 dark:bg-red-900/10">FRIEND</th>
                      <th class="p-2 sm:p-4 text-center">LOSS</th>
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
                            <button
                              type="button"
                              @click="openRankChart(rank, $event)"
                              class="ml-auto p-1 rounded-md text-slate-400 hover:text-indigo-500 hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors shrink-0"
                              title="このランクの勝敗の変遷をグラフで見る"
                              aria-label="このランクの勝敗の変遷をグラフで見る"
                            >
                              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 sm:h-4 sm:w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                                <path stroke-linecap="round" stroke-linejoin="round" d="M3 3v18h18M7 14l3-3 4 4 5-7" />
                              </svg>
                            </button>
                          </div>
                        </td>
                        <td class="p-2 sm:p-4 text-center font-bold text-blue-600 dark:text-blue-400">{{ stats.win }}</td>
                        <td v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center font-bold text-blue-500/80 bg-blue-50/30 dark:bg-blue-900/5">{{ stats.myOnly }}</td>
                        <td class="p-2 sm:p-4 text-center font-bold text-slate-400">{{ stats.draw }}</td>
                        <td v-if="!showBothPlayedOnly" class="p-2 sm:p-4 text-center font-bold text-red-500/80 bg-red-50/30 dark:bg-red-900/5">{{ stats.friendOnly }}</td>
                        <td class="p-2 sm:p-4 text-center font-bold text-red-500 dark:text-red-400">{{ stats.loss }}</td>
                      </tr>
                      <!-- 展開時の詳細内訳（WIN/LOSS/DRAW/YOU Only/FRIEND Only を楽曲単位で列挙） -->
                      <tr v-if="expandedRanks.has(rank)">
                        <td :colspan="showBothPlayedOnly ? 4 : 6" class="p-0 bg-slate-50/50 dark:bg-slate-900/20">
                          <div class="p-3 sm:p-4 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3 sm:gap-4">
                            <!-- WIN セクション（差分が大きい順） -->
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
                            <!-- LOSS セクション（負け幅が大きい順） -->
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
                            <!-- DRAW セクション（完全同点） -->
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
                            <!-- YOU Only セクション（自分だけがプレイ済み） -->
                            <div v-if="stats.myOnlySongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-blue-400 flex items-center gap-1">
                                <span class="w-1 h-3 bg-blue-300 rounded-full"></span>
                                YOU Only ({{ stats.myOnlySongs.length }})
                              </h4>
                              <div class="space-y-1">
                                <div v-for="s in stats.myOnlySongs" :key="s.title" class="flex justify-between items-center bg-white dark:bg-slate-800 p-2 rounded-lg border border-blue-50 dark:border-blue-900/10 text-xs">
                                  <span class="font-bold truncate text-slate-500">{{ s.title }}</span>
                                </div>
                              </div>
                            </div>
                            <!-- FRIEND Only セクション（相手だけがプレイ済み） -->
                            <div v-if="stats.friendOnlySongs.length > 0" class="space-y-2">
                              <h4 class="text-[10px] font-bold text-red-400 flex items-center gap-1">
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

            <!-- 注意書き（集計ルールの補足表示） -->
            <div class="flex items-start gap-2 sm:gap-3 p-3 sm:p-4 bg-blue-50 dark:bg-blue-900/20 rounded-md border border-blue-100 dark:border-blue-900/30">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 sm:h-6 sm:w-6 text-blue-600 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <div class="text-[11px] sm:text-xs text-blue-700 dark:text-blue-300 font-bold leading-relaxed min-w-0">
                <p>・集計対象は ANOTHER / LEGGENDARIA 譜面のみ。BEGINNER / NORMAL / HYPER は除外しています。</p>
                <p>・WIN/DRAW/LOSS: 両者がプレイ済みの楽曲のEX-SCORE比較</p>
                <p>・YOU Only: 自分のみプレイ済み / FRIEND Only: 相手のみプレイ済み</p>
                <p>・両者未プレイの楽曲は集計から除外して表示しています。</p>
              </div>
            </div>
          </div>
        </div>

        <!-- フッター（閉じるボタン） -->
        <div class="p-3 sm:p-6 border-t border-slate-100 dark:border-slate-700 bg-white dark:bg-slate-800 text-right">
          <button @click="emit('close')" class="w-full sm:w-auto px-8 sm:px-12 py-3 sm:py-4 bg-slate-900 hover:bg-black text-white font-bold rounded-md transition-all active:scale-95 text-base sm:text-lg">
            閉じる
          </button>
        </div>
      </div>
    </div>
  </Teleport>

  <!-- 勝敗の変遷グラフ（サマリーカード / 非公式難易度行のグラフボタンから開く） -->
  <FriendComparisonChartModal
    :is-open="isChartModalOpen"
    :friend="friend"
    :virtual-area="virtualArea"
    :scope="chartScope"
    :scope-label="chartScopeLabel"
    :my-current-scores="myProcessedScores"
    :friend-current-scores="friendProcessedScores"
    @close="isChartModalOpen = false"
  />
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
