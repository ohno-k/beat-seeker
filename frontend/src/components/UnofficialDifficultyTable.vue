<script setup lang="ts">
/**
 * 【コンポーネントの役割】 非公式難易度表（☆11.0〜☆13.0）を折返し可能な行で一覧表示する。
 * - 難易度表に定義された全曲を、ユーザーのプレイ済みスコア / 未プレイのプレースホルダに分けて集約
 * - 各難易度ランクごとに平均スコアレート・獲得 Beat-PT を集計し、フォルダランク（A/AA/AAA 等）を算出
 * - 展開/折りたたみで曲一覧を表示、情報モーダルとレート早見表モーダルを内包
 *
 * @prop scores プレイ済みスコアレコード配列。
 */
import { computed, ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import type { ScoreRecord } from '../utils/scoreData';
import { getFolderRankInfoByRate, getNextFolderRankInfoByRate, getLegendPtPerSong, getFolderLegendRate, getFolderRankOffsetMax, FOLDER_RANK_DEFS, getMaxPoints } from '../utils/beatTier';
import { songData as songDataBodyRef, diffTable as diffTableRanksRef, getDifficultyCode } from '../composables/useGameData';
import RankIcon from './RankIcon.vue';
import DifficultyRankingModal from './DifficultyRankingModal.vue';
import RankGrowthChartModal from './RankGrowthChartModal.vue';

const props = defineProps<{
  scores: ScoreRecord[];
}>();

const { t } = useI18n();
/** 展開中のランクキー集合（Set でトグル）。 */
const expandedRanks = ref<Set<string>>(new Set());
/** 「フォルダランクとは？」ツールチップ表示フラグ。 */
const showInfo = ref(false);
/** レート早見表モーダル表示フラグ。 */
const showRateTable = ref(false);
/** 難易度別ランキングモーダルの対象難易度（null なら非表示）。 */
const rankingModalRank = ref<{ rank: string; totalCount: number } | null>(null);
/** 成長グラフモーダルの対象難易度（null なら非表示）。 */
const growthChartRank = ref<{ rank: string; songCount: number; currentTotalBeatPoints: number } | null>(null);

// ☆11.0 〜 ☆13.1 までの 0.1 刻みラベル配列を生成（レート早見表の列）。
const allFolders: string[] = [];
for (let i = 0; i <= 21; i++) allFolders.push((11.0 + i * 0.1).toFixed(1));

/**
 * 【computed の役割】 レート早見表の行データを生成する。
 * 各フォルダランク定義 × 各難易度ランクの組合せで「必要レート」を算出し、
 * 66.67% 以下は "-"、AAA/MAX-/MAX 帯域は色分けする。
 */
const rateTableRows = computed(() => {
  return FOLDER_RANK_DEFS.map(def => {
    const label = def.tier ? `${def.name} ${def.tier}` : def.name;
    const rates = allFolders.map(f => {
      const rate = getFolderLegendRate(f) - def.offset * getFolderRankOffsetMax(f);
      if (rate <= 66.666) return { text: '-', color: 'text-slate-400 dark:text-slate-500' };

      let rateColor = 'text-slate-600 dark:text-slate-300';
      if (rate >= 94.45) rateColor = 'text-purple-600 dark:text-purple-400 font-bold';
      else if (rate >= 88.88) rateColor = 'text-amber-500 dark:text-amber-400 font-bold';
      else if (rate >= 77.77) rateColor = 'text-emerald-600 dark:text-emerald-400 font-bold';

      return { text: rate.toFixed(2) + '%', color: rateColor };
    });
    return { label, color: def.color, rates };
  });
});

/** 【関数の役割】 ランク行の展開状態を反転する（展開済みなら閉じる、そうでなければ開く）。 */
const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
};

// 曲定義 (songData) を "title_difficultyCode" キーで検索可能にするルックアップ。
// ANOTHER=4 / LEGGENDARIA=10 という難易度コードで引くため事前 Map 化する。
const songDict = new Map<string, any>();
if (songDataBodyRef.value && Array.isArray(songDataBodyRef.value)) {
  songDataBodyRef.value.forEach((s: any) => {
    songDict.set(`${s.title}_${s.difficulty}`, s);
  });
}

/**
 * 【computed の役割】 非公式ランク別に曲をグループ化する。
 *   - プレイ済み曲は既存 ScoreRecord をそのまま採用
 *   - 未プレイ曲は songDict から情報を引き、scoreRate=-1 のプレースホルダを生成
 *   - "Uncategorized" ランクは除外
 * 難易度表の順にイテレートするので、同じランク内の表示順はテーブル定義順になる。
 */
const groupedByRank = computed(() => {
  const groups: Record<string, ScoreRecord[]> = {};

  // プレイ済みスコアを "title_difficultyName" でマップ化（O(1) ルックアップ用）。
  const scoreMap = new Map<string, ScoreRecord>();
  props.scores.forEach(s => {
    if (s.informalRank && !s.informalRank.includes('Uncategorized')) {
      scoreMap.set(`${s.title}_${s.difficultyName}`, s);
    }
  });

  // 難易度表の全曲を走査し、ランク別グループを構築。
  (diffTableRanksRef.value || []).forEach((r: any) => {
    const rank = r.rank;
    if (rank.includes('Uncategorized')) return;
    if (!groups[rank]) groups[rank] = [];

    r.songs.forEach((songTitle: string) => {
      const isLeggendaria = songTitle.endsWith('[L]');
      const baseTitle = isLeggendaria ? songTitle.slice(0, -3) : songTitle;
      const diffName = isLeggendaria ? 'LEGGENDARIA' : 'ANOTHER';
      // 難易度名 → コードの変換は useGameData の getDifficultyCode に集約済み。
      const diffCode = String(getDifficultyCode(diffName));

      const scoreKey = `${baseTitle}_${diffName}`;
      if (scoreMap.has(scoreKey)) {
        // プレイ済み: 既存の ScoreRecord をそのまま採用。
        groups[rank].push(scoreMap.get(scoreKey)!);
      } else {
        // 未プレイ: 曲定義から notes（ノーツ数 × 2 = 満点）を引いてプレースホルダ生成。
        const def = songDict.get(`${baseTitle}_${diffCode}`);
        if (!def) return; // 定義が無ければスキップ（uncategorized）
        const maxScore = def.notes * 2;
        groups[rank].push({
          title: baseTitle,
          artist: def?.artist ?? '',
          genre: def?.genre ?? '',
          difficultyName: diffName,
          difficultyColor: isLeggendaria
            ? 'text-purple-700 bg-purple-100 border border-purple-300'
            : 'text-red-700 bg-red-100 border border-red-300',
          difficultyLevel: def?.level ?? null,
          clearType: 'NO PLAY',
          score: 0,
          scoreRate: -1,
          maxScore,
          informalRank: rank,
          djLevel: '-',
          pgreat: 0,
          great: 0,
          missCount: null,
          playCount: 0,
          lastPlayTime: '',
          beatTierPoints: 0,
          maxBeatTierPoints: getMaxPoints(rank),
          options: undefined,
        });
      }
    });
  });

  return groups;
});

/** 【computed の役割】 非公式ランクごとの曲数を集計（難易度表定義の総数、プレイ有無不問）。 */
const rankSongCounts = computed(() => {
  const counts: Record<string, number> = {};
  (diffTableRanksRef.value || []).forEach((r: any) => {
    counts[r.rank] = r.songs.length;
  });
  return counts;
});

/**
 * 【computed の役割】 ランクごとの集計行データ（平均レート、合計 pt、フォルダランク等）を構築。
 * ランク順は数値降順（☆12.9 → ☆11.0）。各ランク内の曲もスコアレート降順に並べる。
 * 「MAX 基準 pt」は legendPtPerSong × totalCount。レート算出は プレイ済み曲のみを対象とする。
 */
const tableData = computed(() => {
  const ranks = Object.keys(groupedByRank.value);

  // "12.x" 文字列を数値として比較し、降順ソート。
  ranks.sort((a, b) => parseFloat(b) - parseFloat(a));

  return ranks.map(rank => {
    const songs = groupedByRank.value[rank];
    let totalScore = 0;
    let totalMaxScore = 0;
    let totalBeatPoints = 0;
    let maxBeatPoints = 0;

    // スコアレート降順（未プレイ曲は scoreRate=-1 なので末尾にまとまる）。
    songs.sort((a, b) => b.scoreRate - a.scoreRate);

    songs.forEach(s => {
      // Beat-PT は未プレイ曲でも 0 として累積（全 playthrough の合計値）。
      totalBeatPoints += s.beatTierPoints;

      // 平均レートは「プレイ済みかつ maxScore がある曲」だけで計算する。
      if (s.maxScore > 0 && s.score > 0) {
        totalScore += s.score;
        totalMaxScore += s.maxScore;
      }
    });

    const averageRate = totalMaxScore > 0 ? (totalScore / totalMaxScore) * 100 : 0;
    const playCount = songs.filter(s => s.score > 0).length;

    const totalCount = rankSongCounts.value[rank] || songs.length;

    // MAX 参照値（Legend 到達時の理論 pt = 1 曲あたり pt × 総曲数）
    const legendPtPerSong = getLegendPtPerSong(rank);
    maxBeatPoints = legendPtPerSong > 0 ? legendPtPerSong * totalCount : 0;

    const rankInfo = getFolderRankInfoByRate(averageRate, rank);
    const nextRankInfo = getNextFolderRankInfoByRate(averageRate, rank);

    // レートベースのランクは既にプレイ済み曲の平均レートで算出されるため playedRankInfo は同値
    const playedRankInfo = rankInfo;

    // 単曲ごとのランク（必要スコアレート表に対応）。未プレイは null。
    const songsWithRank = songs.map(s => ({
      song: s,
      songRank: s.scoreRate > 0 ? getFolderRankInfoByRate(s.scoreRate, rank) : null,
    }));

    return {
      rank,
      songs,
      songsWithRank,
      totalScore,
      totalMaxScore,
      totalBeatPoints,
      maxBeatPoints,
      averageRate,
      playCount,
      totalCount,
      rankInfo,
      nextRankInfo,
      playedRankInfo
    };
  });
});
</script>

<template>
  <div class="w-full bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden mt-6 animate-fade-in flex flex-col transition-colors duration-200">
    <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-slate-50/50 dark:bg-slate-800/50 transition-colors duration-200">
      <h3 class="font-bold text-slate-800 dark:text-slate-100 text-lg flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-500 dark:text-indigo-400 shrink-0" viewBox="0 0 20 20" fill="currentColor">
          <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
        </svg>
        {{ t('table.unofficialSummary') }}
        <div class="relative">
          <button
            @click.stop="showInfo = !showInfo"
            :aria-label="t('table.aboutSummary')"
            :aria-expanded="showInfo"
            class="w-5 h-5 rounded-full bg-slate-200 dark:bg-slate-600 hover:bg-indigo-200 dark:hover:bg-indigo-700 text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-300 text-[10px] font-bold flex items-center justify-center transition-colors"
            :title="t('table.aboutSummary')"
          >?</button>
          <div
            v-if="showInfo"
            class="absolute z-20 top-7 -left-16 sm:left-0 w-[280px] sm:w-72 bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-600 shadow-xl p-4 text-xs text-slate-700 dark:text-slate-300 font-normal"
          >
            <div class="flex items-center justify-between mb-2">
               <span class="font-bold text-sm text-slate-800 dark:text-slate-100">{{ t('table.aboutFolderRank') }}</span>
              <button @click="showInfo = false" aria-label="閉じる" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 font-bold text-base leading-none">×</button>
            </div>
            <p class="mb-2 text-slate-600 dark:text-slate-400">{{ t('table.folderRankExplanation') }}</p>
            <div class="border-t border-slate-100 dark:border-slate-700 pt-2 mt-2 space-y-1">
              <p class="font-bold text-slate-700 dark:text-slate-200">{{ t('table.legendCriteria') }}</p>
              <p>{{ t('table.legendExplanation') }}</p>
              <p class="font-bold text-slate-700 dark:text-slate-200 mt-2">{{ t('table.rankCriteria') }}</p>
              <p>{{ t('table.rankExplanation') }}</p>
              <p class="font-bold text-slate-700 dark:text-slate-200 mt-2">{{ t('table.paleIconCriteria') }}</p>
              <p>{{ t('table.paleIconExplanation') }}</p>
            </div>
            <button
              @click.stop="showRateTable = true; showInfo = false"
              class="mt-3 w-full py-1.5 bg-indigo-500 hover:bg-indigo-600 text-white text-xs font-bold rounded-lg transition-colors"
            >{{ t('table.viewRateTable') }}</button>
          </div>
        </div>
      </h3>
    </div>
    <!-- Click-outside backdrop for info tooltip -->
    <div v-if="showInfo" class="fixed inset-0 z-10" @click="showInfo = false"></div>

    <!-- Difficulty Ranking Modal -->
    <DifficultyRankingModal
      v-if="rankingModalRank"
      :rank="rankingModalRank.rank"
      :total-count="rankingModalRank.totalCount"
      @close="rankingModalRank = null"
    />

    <!-- Rank Growth Chart Modal -->
    <RankGrowthChartModal
      v-if="growthChartRank"
      :rank="growthChartRank.rank"
      :song-count="growthChartRank.songCount"
      :current-total-beat-points="growthChartRank.currentTotalBeatPoints"
      @close="growthChartRank = null"
    />

    <!-- Rate Table Modal -->
    <Teleport to="body">
      <div v-if="showRateTable" class="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4">
        <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" @click="showRateTable = false"></div>
        <div class="relative z-10 bg-white dark:bg-slate-800 rounded-md shadow-xl border border-slate-200 dark:border-slate-700 w-full max-w-[95vw] max-h-[90vh] flex flex-col">
          <!-- Header -->
          <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
            <div>
              <h3 class="font-bold text-sm sm:text-base text-slate-800 dark:text-slate-100">{{ t('table.rateTableTitle') }}</h3>
            </div>
            <button @click="showRateTable = false" aria-label="閉じる" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors shrink-0 ml-2">×</button>
          </div>
          <!-- Scrollable table -->
          <div class="overflow-auto flex-1">
            <table class="text-[10px] sm:text-xs border-collapse">
              <thead class="sticky top-0 z-20">
                <tr class="bg-slate-100 dark:bg-slate-900 text-slate-500 dark:text-slate-400">
                  <th scope="col" class="py-1.5 px-2 text-left font-bold sticky left-0 z-30 bg-slate-100 dark:bg-slate-900 min-w-[80px] sm:min-w-[110px]">{{ t('table.colRank') }}</th>
                  <th v-for="f in allFolders" :key="f" scope="col" class="py-1.5 px-1 sm:px-2 text-center font-bold whitespace-nowrap">☆{{ f }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in rateTableRows" :key="row.label" :class="idx % 5 === 1 ? 'border-t border-slate-200 dark:border-slate-700' : ''">
                  <td class="py-1 px-2 font-bold whitespace-nowrap sticky left-0 z-10 bg-white dark:bg-slate-800" :class="row.color">{{ row.label }}</td>
                  <td v-for="(rate, i) in row.rates" :key="i" class="py-1 px-1 sm:px-2 text-center font-mono whitespace-nowrap" :class="rate.color">{{ rate.text }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </Teleport>
    
    <div class="overflow-x-auto">
      <table class="w-full text-left border-collapse">
        <thead>
          <tr class="bg-slate-100 dark:bg-slate-800/80 text-slate-600 dark:text-slate-300 text-[10px] sm:text-sm border-b border-slate-200 dark:border-slate-700 transition-colors duration-200">
            <th scope="col" class="py-2 px-2 sm:py-3 sm:px-4 font-bold w-auto sm:w-24">{{ t('table.colDifficulty') }}</th>
            <th scope="col" class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-center w-auto sm:w-32">{{ t('table.colAvgRate') }}</th>
            <th scope="col" class="py-2 px-1 sm:py-3 sm:px-2 font-bold text-center w-auto sm:w-24"><span class="sr-only">{{ t('table.colRanking') }}</span></th>
            <th scope="col" class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-right w-auto sm:w-48">{{ t('table.colTotalPt') }}</th>
            <th scope="col" class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-center w-auto sm:w-24">{{ t('table.colPlayed') }}</th>
            <th scope="col" class="py-2 px-1 sm:py-3 sm:px-4 w-auto sm:w-12 text-center"><span class="sr-only">{{ t('diffTable.expandAll') }}</span></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-xs sm:text-sm text-slate-700 dark:text-slate-200 transition-colors duration-200">
          <template v-for="data in tableData" :key="data.rank">
            <tr
              @click="toggleRank(data.rank)"
              @keydown.enter.prevent="toggleRank(data.rank)"
              @keydown.space.prevent="toggleRank(data.rank)"
              role="button"
              tabindex="0"
              :aria-expanded="expandedRanks.has(data.rank)"
              :aria-controls="`unofficial-rank-panel-${data.rank}`"
              class="hover:bg-indigo-50/50 dark:hover:bg-slate-700/50 transition-colors cursor-pointer group"
              :class="{ 'bg-slate-50 dark:bg-slate-800/80': expandedRanks.has(data.rank) }"
            >
              <td class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-slate-800 dark:text-slate-100 whitespace-nowrap">
                <span class="inline-block w-2 h-2 rounded-full mr-1 sm:mr-2" :class="parseFloat(data.rank) >= 12.5 ? 'bg-purple-500 dark:bg-purple-400' : 'bg-blue-500 dark:bg-blue-400'"></span>
                {{ data.rank }}
              </td>
              <td class="py-2 px-2 sm:py-3 sm:px-4 text-center cursor-pointer">
                <div class="flex flex-col items-center">
                  <span class="font-bold text-sm sm:text-base whitespace-nowrap" :class="data.averageRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : data.averageRate >= 88.88 ? 'text-amber-500 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400'">
                    {{ data.averageRate > 0 ? data.averageRate.toFixed(2) + '%' : '-' }}
                  </span>
                  <div v-if="data.averageRate > 0" class="hidden sm:block w-full max-w-[5rem] h-1 mt-1 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden mx-auto">
                    <div class="h-full bg-indigo-500 dark:bg-indigo-400" :style="{ width: `${data.averageRate}%` }"></div>
                  </div>
                </div>
              </td>
              <td class="py-2 px-1 sm:py-3 sm:px-2 text-center">
                <div class="inline-flex items-center gap-1">
                  <button
                    type="button"
                    @click.stop="rankingModalRank = { rank: data.rank, totalCount: data.totalCount }"
                    :title="t('table.viewDifficultyRanking')"
                    :aria-label="t('table.viewDifficultyRanking')"
                    class="inline-flex items-center justify-center w-7 h-7 sm:w-8 sm:h-8 rounded-lg bg-amber-50 hover:bg-amber-100 dark:bg-amber-900/30 dark:hover:bg-amber-900/50 text-amber-600 dark:text-amber-400 border border-amber-200 dark:border-amber-700 transition-colors"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 sm:h-4 sm:w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z" />
                    </svg>
                  </button>
                  <button
                    v-if="data.playCount >= data.totalCount && data.totalCount > 0"
                    type="button"
                    @click.stop="growthChartRank = { rank: data.rank, songCount: data.totalCount, currentTotalBeatPoints: data.totalBeatPoints }"
                    :title="t('table.viewGrowthChart')"
                    :aria-label="t('table.viewGrowthChart')"
                    class="inline-flex items-center justify-center w-7 h-7 sm:w-8 sm:h-8 rounded-lg bg-indigo-50 hover:bg-indigo-100 dark:bg-indigo-900/30 dark:hover:bg-indigo-900/50 text-indigo-600 dark:text-indigo-400 border border-indigo-200 dark:border-indigo-700 transition-colors"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 sm:h-4 sm:w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M3 17l5-5 4 4 7-7M14 9h6v6" />
                    </svg>
                  </button>
                </div>
              </td>
              <td class="py-2 px-2 sm:py-3 sm:px-4 text-right cursor-pointer" @click.stop="toggleRank(data.rank)">
                <div class="flex items-center justify-end gap-1 sm:gap-2">
                  <div class="flex flex-col text-right">
                    <span class="font-bold text-xs sm:text-sm whitespace-nowrap" :class="data.rankInfo.color">
                      {{ data.totalBeatPoints.toFixed(1) }} <span class="text-[8px] sm:text-[10px] text-slate-400 dark:text-slate-500 font-bold">pt</span>
                    </span>
                    <span class="text-[8px] sm:text-[10px] text-slate-400 dark:text-slate-500 font-bold whitespace-nowrap">MAX: {{ data.maxBeatPoints.toFixed(1) }}</span>
                  </div>
                  <div class="flex items-center gap-0.5">
                    <template v-if="data.playCount >= data.totalCount">
                      <RankIcon class="block sm:hidden shrink-0" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="xs" />
                      <RankIcon class="hidden sm:block shrink-0" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="sm" />
                    </template>
                    <template v-else>
                      <RankIcon class="block sm:hidden shrink-0 opacity-30" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="xs" />
                      <RankIcon class="hidden sm:block shrink-0 opacity-30" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="sm" />
                    </template>
                  </div>
                </div>
              </td>
              <td class="py-2 px-2 sm:py-3 sm:px-4 text-center font-bold text-slate-600 dark:text-slate-300">
                <div class="flex flex-col sm:block whitespace-nowrap">
                  <span>{{ data.playCount }}</span>
                  <span class="text-[10px] sm:text-xs text-slate-400 dark:text-slate-500 font-normal sm:ml-1">/ {{ data.totalCount }}</span>
                </div>
              </td>
              <td class="py-2 px-1 sm:py-3 sm:px-4 text-center text-slate-400 dark:text-slate-500">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 sm:h-5 sm:w-5 transform transition-transform duration-200 group-hover:text-indigo-500 dark:group-hover:text-indigo-400 mx-auto" :class="{ 'rotate-180 text-indigo-500 dark:text-indigo-400': expandedRanks.has(data.rank) }" viewBox="0 0 20 20" fill="currentColor">
                  <path fill-rule="evenodd" d="M5.293 7.293a1 1 0 011.414 0L10 10.586l3.293-3.293a1 1 0 111.414 1.414l-4 4a1 1 0 01-1.414 0l-4-4a1 1 0 010-1.414z" clip-rule="evenodd" />
                </svg>
              </td>
            </tr>
            <!-- Expanded Details Row -->
            <tr v-if="expandedRanks.has(data.rank)" :id="`unofficial-rank-panel-${data.rank}`" class="bg-slate-50/80 dark:bg-slate-800/40 border-t-0 shadow-inner">
              <td colspan="6" class="px-6 py-4">
                <!-- Summary Board -->
                <div class="mb-4 bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-4 flex flex-col md:flex-row md:items-center justify-between gap-4 transition-colors duration-200">
                  <div class="flex flex-col">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">{{ t('table.folderBeatTier') }}</span>
                    <div class="flex items-center gap-3">
                      <RankIcon :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="lg" :class="data.playCount < data.totalCount ? 'opacity-30' : ''" />
                      <div class="flex flex-col">
                        <span class="text-2xl font-bold tracking-tight" :class="data.rankInfo.color">
                          {{ data.totalBeatPoints.toFixed(1) }} <span class="text-sm text-slate-400 dark:text-slate-500 font-bold">pt</span>
                        </span>
                        <span class="text-xs font-bold text-slate-400 dark:text-slate-500">
                          MAX: {{ data.maxBeatPoints.toFixed(1) }} pt
                        </span>
                      </div>
                    </div>
                  </div>
                  
                  <div class="flex-1 max-w-md w-full bg-slate-50 dark:bg-slate-900/50 rounded-md p-3 border border-slate-100 dark:border-slate-700 flex flex-col justify-center transition-colors duration-200">
                    <div v-if="data.nextRankInfo.nextRank" class="flex flex-col gap-2">
                      <div class="flex justify-between items-end">
                        <span class="text-xs font-bold text-slate-500 dark:text-slate-400">NEXT RANK</span>
                        <div class="flex items-center gap-1.5">
                          <span class="text-sm font-bold" :class="data.nextRankInfo.nextRank.color">
                            {{ data.nextRankInfo.nextRank.name }} {{ data.nextRankInfo.nextRank.tier || '' }}
                          </span>
                          <span class="text-xs font-bold text-slate-400 dark:text-slate-500">
                            (あと{{ (data.nextRankInfo.nextRank.minRate - data.averageRate).toFixed(2) }}%)
                          </span>
                        </div>
                      </div>
                      <div class="w-full h-2 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden">
                        <div class="h-full bg-indigo-500 dark:bg-indigo-400 rounded-full transition-all duration-500" :style="{ width: `${data.nextRankInfo.progress}%` }"></div>
                      </div>
                    </div>
                    <div v-else class="flex items-center justify-center py-2">
                      <span class="text-sm font-bold text-amber-500 dark:text-amber-400 animate-pulse">✨ HIGHEST TIER ACHIEVED ✨</span>
                    </div>
                  </div>
                </div>

                <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                  <div v-for="entry in data.songsWithRank" :key="entry.song.title + entry.song.difficultyName" class="bg-white dark:bg-slate-800 p-3 rounded-md border border-slate-200 dark:border-slate-700 flex flex-col transition-colors duration-200">
                    <div class="flex items-start justify-between mb-2">
                       <h4 class="font-bold text-slate-800 dark:text-slate-200 text-xs line-clamp-2 pr-2" :title="entry.song.title">{{ entry.song.title }}</h4>
                       <span class="text-[9px] font-bold px-1.5 py-0.5 rounded shrink-0" :class="entry.song.difficultyColor">
                         {{ entry.song.difficultyName.substring(0, 3) }}
                       </span>
                    </div>

                    <div class="flex items-end justify-between mt-auto gap-2">
                      <div class="flex flex-col">
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 font-bold">RATE</span>
                        <span class="font-bold text-sm" :class="entry.song.scoreRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : entry.song.scoreRate >= 88.88 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-700 dark:text-slate-300'">
                          {{ entry.song.scoreRate > 0 ? entry.song.scoreRate.toFixed(2) + '%' : '-' }}
                        </span>
                      </div>
                      <div class="flex flex-col items-center">
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 font-bold">RANK</span>
                        <div v-if="entry.songRank" class="flex items-center gap-1">
                          <RankIcon :rank-name="entry.songRank.name" :tier="entry.songRank.tier" size="xs" />
                          <span class="text-[10px] font-bold whitespace-nowrap" :class="entry.songRank.color">
                            {{ entry.songRank.name }}<template v-if="entry.songRank.tier"> {{ entry.songRank.tier }}</template>
                          </span>
                        </div>
                        <span v-else class="text-sm font-bold text-slate-400 dark:text-slate-500">-</span>
                      </div>
                      <div class="flex flex-col text-right">
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 font-bold">PT</span>
                        <span class="font-mono text-sm text-slate-700 dark:text-slate-300 font-bold">{{ entry.song.beatTierPoints > 0 ? entry.song.beatTierPoints.toFixed(1) : '-' }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          
          <tr v-if="tableData.length === 0">
            <td colspan="6" class="py-12 text-center text-slate-500 dark:text-slate-400">
              {{ t('table.noUnofficialData') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
