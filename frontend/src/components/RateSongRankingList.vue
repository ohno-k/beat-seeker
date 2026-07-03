<script setup lang="ts">
/**
 * 【コンポーネントの役割】 Rate-Pt 用の曲別プレイ人口ランキング表示。
 * - Rate-Pt 対象難易度（概ね Lv7 以下 ANOTHER/LEGGENDARIA 等）での「みんなやってる / やってない」を表示
 * - SongRankingList.vue（Beat-Pt 版）と対の関係。集計元コンポーザブルとメトリクスが異なる
 *   - Beat-Pt 版: avgBeatPt（整数換算）
 *   - Rate-Pt 版: avgRatePt（小数点 2 桁）
 * - sortMode で人気順 / マイナー順を切替
 */
import { ref, onMounted } from 'vue';
import { useRateSongRanking } from '../composables/useRateSongRanking';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

// Rate-Pt 曲別集計（多い順・少ない順・総ユーザー数）。
const { mostRanking, leastRanking, isLoading, error, totalUsers, fetchRateSongRanking } = useRateSongRanking();
/** ソート方向（most = プレイ人口降順, least = 昇順）。 */
const sortMode = ref<'most' | 'least'>('most');

// マウント時に 1 回だけサーバから集計結果を取得する。
onMounted(() => {
    fetchRateSongRanking();
});
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
      <p class="text-slate-500 dark:text-slate-400 text-sm font-medium">
        {{ t('songRanking.rateDesc') }}
      </p>
      <div class="flex items-center gap-3 shrink-0">
        <span v-if="!isLoading && totalUsers > 0" class="text-xs text-slate-400 dark:text-slate-500">
          {{ t('songRanking.totalUsers', { n: totalUsers }) }}
        </span>
        <div class="flex gap-1 p-1 bg-slate-100 dark:bg-slate-700/50 rounded-md">
          <button
            @click="sortMode = 'most'"
            class="px-3 py-1 rounded-lg text-xs font-bold transition-all"
            :class="sortMode === 'most'
              ? 'bg-white dark:bg-slate-600 text-emerald-600 dark:text-emerald-400'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >{{ t('songRanking.sortMost') }}</button>
          <button
            @click="sortMode = 'least'"
            class="px-3 py-1 rounded-lg text-xs font-bold transition-all"
            :class="sortMode === 'least'
              ? 'bg-white dark:bg-slate-600 text-orange-500 dark:text-orange-400'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >{{ t('songRanking.sortLeast') }}</button>
        </div>
      </div>
    </div>

    <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
      <div class="w-12 h-12 border-4 border-emerald-100 dark:border-slate-700 border-t-emerald-600 dark:border-t-emerald-500 rounded-full animate-spin mb-4"></div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">楽曲ランキングを集計中...</p>
    </div>

    <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-center font-bold">
      {{ error }}
    </div>

    <div v-else-if="mostRanking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-md">
      <p class="text-slate-500 dark:text-slate-400 font-bold">表示できるデータがありません。</p>
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full">
        <thead>
          <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
            <th class="pb-4 pl-4 text-xs font-bold text-slate-400 w-14">{{ t('songRanking.colRank') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400">{{ t('songRanking.colTitle') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400 w-24 text-center hidden sm:table-cell">{{ t('songRanking.colDifficulty') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400 w-20 text-center hidden md:table-cell">{{ t('songRanking.colInformalRank') }}</th>
            <th class="pb-4 text-xs font-bold text-emerald-500 text-right hidden lg:table-cell">{{ t('songRanking.colAvgRatePt') }}</th>
            <th class="pb-4 text-xs font-bold" :class="sortMode === 'most' ? 'text-emerald-500' : 'text-orange-500'" style="text-align:right; padding-right:1rem;">{{ t('songRanking.colUserCount') }}</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
          <tr
            v-for="(entry, index) in (sortMode === 'most' ? mostRanking : leastRanking)"
            :key="`${entry.title}_${entry.difficultyName}`"
            class="group hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
          >
            <td class="py-3 pl-4">
              <div
                class="flex items-center justify-center w-7 h-7 rounded-lg font-bold text-xs"
                :class="sortMode === 'most' ? [
                  index === 0 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                  index === 1 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                  index === 2 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                  'text-slate-400 border border-slate-100 dark:border-slate-700'
                ] : 'text-slate-400 border border-slate-100 dark:border-slate-700'"
              >
                {{ index + 1 }}
              </div>
            </td>
            <td class="py-3 pr-2">
              <span class="font-bold text-slate-800 dark:text-slate-100 text-sm group-hover:text-emerald-600 dark:group-hover:text-emerald-400 transition-colors">
                {{ entry.title }}
              </span>
              <div class="flex items-center gap-2 mt-0.5 sm:hidden">
                <span
                  class="text-[10px] font-bold px-1.5 py-0.5 rounded"
                  :class="entry.difficultyName === 'LEGGENDARIA'
                    ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
                    : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'"
                >
                  {{ entry.difficultyName === 'LEGGENDARIA' ? 'LEGGEN' : 'ANO' }}
                </span>
                <span v-if="entry.informalRank" class="text-[10px] font-bold text-slate-400">☆{{ entry.informalRank }}</span>
              </div>
            </td>
            <td class="py-3 px-2 text-center hidden sm:table-cell">
              <span
                class="text-xs font-bold px-2 py-1 rounded-lg"
                :class="entry.difficultyName === 'LEGGENDARIA'
                  ? 'bg-purple-100 text-purple-700 dark:bg-purple-900/40 dark:text-purple-300'
                  : 'bg-red-100 text-red-700 dark:bg-red-900/40 dark:text-red-300'"
              >
                {{ entry.difficultyName === 'LEGGENDARIA' ? 'LEGGEN' : 'ANOTHER' }}
              </span>
            </td>
            <td class="py-3 px-2 text-center hidden md:table-cell">
              <span v-if="entry.informalRank" class="text-xs font-bold text-slate-600 dark:text-slate-300 tabular-nums">
                ☆{{ entry.informalRank }}
              </span>
              <span v-else class="text-xs text-slate-400">-</span>
            </td>
            <td class="py-3 text-right hidden lg:table-cell">
              <span class="text-sm font-bold text-slate-600 dark:text-slate-300 tabular-nums">
                {{ entry.avgRatePt.toFixed(2) }}
              </span>
            </td>
            <td class="py-3 text-right pr-4">
              <div class="flex items-baseline justify-end gap-1">
                <span class="text-lg font-bold tabular-nums"
                  :class="sortMode === 'most' ? 'text-slate-800 dark:text-slate-100' : 'text-orange-600 dark:text-orange-400'">
                  {{ entry.userCount }}
                </span>
                <span class="text-[10px] font-bold text-slate-400">{{ t('songRanking.unitPersons') }}</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
