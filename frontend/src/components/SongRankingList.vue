<script setup lang="ts">
/**
 * 【コンポーネントの役割】 曲別プレイ人口ランキング（「みんなやってる曲 / みんなやってない曲」）を一覧表示する。
 * - useSongRanking コンポーザブルから ranking（降順）と leastRanking（昇順）を取得
 * - sortMode でソート方向を切り替え（most = 人気順 / least = マイナー順）
 * - 表示項目: 順位、曲名、難易度（ANOTHER / LEGGENDARIA）、非公式難易度、平均 BeatPt、プレイ人数
 * - レスポンシブ対応：SP では曲名下に難易度と非公式ランクをインライン表示、PC では個別カラム
 */
import { ref, onMounted } from 'vue';
import { useSongRanking } from '../composables/useSongRanking';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

// サーバから取得するランキング（多い順 / 少ない順）とローディング状態。
const { ranking, leastRanking, isLoading, error, totalUsers, fetchSongRanking } = useSongRanking();
/** ソート方向（most = プレイ人口降順, least = 昇順）。 */
const sortMode = ref<'most' | 'least'>('most');

// マウント時に一度だけ集計 API を呼び出す（タブ切り替えで再生成されるためキャッシュは不要）。
onMounted(() => {
    fetchSongRanking();
});
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
      <p class="text-slate-500 dark:text-slate-400 text-sm font-medium">
        {{ t('ranking.songRankingDesc') }}
      </p>
      <div class="flex items-center gap-3 shrink-0">
        <span v-if="!isLoading && totalUsers > 0" class="text-xs text-slate-400 dark:text-slate-500">
          {{ t('ranking.totalUserCount', { n: totalUsers }) }}
        </span>
        <div class="flex gap-1 p-1 bg-slate-100 dark:bg-slate-700/50 rounded-md">
          <button
            @click="sortMode = 'most'"
            class="px-3 py-1 rounded-lg text-xs font-bold transition-all"
            :class="sortMode === 'most'
              ? 'bg-white dark:bg-slate-600 text-blue-600 dark:text-blue-400'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >{{ t('ranking.sortMost') }}</button>
          <button
            @click="sortMode = 'least'"
            class="px-3 py-1 rounded-lg text-xs font-bold transition-all"
            :class="sortMode === 'least'
              ? 'bg-white dark:bg-slate-600 text-orange-500 dark:text-orange-400'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >{{ t('ranking.sortLeast') }}</button>
        </div>
      </div>
    </div>

    <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
      <div class="w-12 h-12 border-4 border-blue-100 dark:border-slate-700 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('ranking.songRankingAggregating') }}</p>
    </div>

    <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-md text-center font-bold">
      {{ error }}
    </div>

    <div v-else-if="ranking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-md" >
      <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('ranking.empty') }}</p>
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full">
        <thead>
          <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
            <th class="pb-4 pl-4 text-xs font-bold text-slate-400 w-14">{{ t('ranking.colRank') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400">{{ t('ranking.colSongName') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400 w-24 text-center hidden sm:table-cell">{{ t('ranking.colDifficulty') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400 w-20 text-center hidden md:table-cell">{{ t('ranking.colInformalRank') }}</th>
            <th class="pb-4 text-xs font-bold text-slate-400 text-right hidden lg:table-cell">{{ t('ranking.colAvgBeatPt') }}</th>
            <th class="pb-4 text-xs font-bold" :class="sortMode === 'most' ? 'text-blue-500' : 'text-orange-500'" style="text-align:right; padding-right:1rem;">{{ t('ranking.colUserCount') }}</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
          <tr
            v-for="(entry, index) in (sortMode === 'most' ? ranking : leastRanking)"
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
              <span class="font-bold text-slate-800 dark:text-slate-100 text-sm group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">
                {{ entry.title }}
              </span>
              <!-- Mobile: show difficulty/rank inline -->
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
                {{ entry.avgBeatPt.toFixed(1) }}
              </span>
            </td>
            <td class="py-3 text-right pr-4">
              <div class="flex items-baseline justify-end gap-1">
                <span class="text-lg font-bold tabular-nums"
                  :class="sortMode === 'most' ? 'text-slate-800 dark:text-slate-100' : 'text-orange-600 dark:text-orange-400'">
                  {{ entry.userCount }}
                </span>
                <span class="text-[10px] font-bold text-slate-400">{{ t('ranking.unitPersons') }}</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
