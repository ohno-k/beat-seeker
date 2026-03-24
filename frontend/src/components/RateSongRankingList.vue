<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useRateSongRanking } from '../composables/useRateSongRanking';

const { mostRanking, leastRanking, isLoading, error, totalUsers, fetchRateSongRanking } = useRateSongRanking();
const sortMode = ref<'most' | 'least'>('most');

onMounted(() => {
    fetchRateSongRanking();
});
</script>

<template>
  <div class="space-y-4">
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
      <p class="text-slate-500 dark:text-slate-400 text-sm font-medium">
        全ユーザーの総合RATE-PT算出に使われているTop100楽曲を集計したランキングです。
      </p>
      <div class="flex items-center gap-3 shrink-0">
        <span v-if="!isLoading && totalUsers > 0" class="text-xs text-slate-400 dark:text-slate-500">
          {{ totalUsers }}人のデータを集計
        </span>
        <div class="flex gap-1 p-1 bg-slate-100 dark:bg-slate-700/50 rounded-xl">
          <button
            @click="sortMode = 'most'"
            class="px-3 py-1 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
            :class="sortMode === 'most'
              ? 'bg-white dark:bg-slate-600 text-emerald-600 dark:text-emerald-400 shadow-sm'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >多い順</button>
          <button
            @click="sortMode = 'least'"
            class="px-3 py-1 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
            :class="sortMode === 'least'
              ? 'bg-white dark:bg-slate-600 text-orange-500 dark:text-orange-400 shadow-sm'
              : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
          >少ない順</button>
        </div>
      </div>
    </div>

    <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
      <div class="w-12 h-12 border-4 border-emerald-100 dark:border-slate-700 border-t-emerald-600 dark:border-t-emerald-500 rounded-full animate-spin mb-4"></div>
      <p class="text-slate-500 dark:text-slate-400 font-bold">楽曲ランキングを集計中...</p>
    </div>

    <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
      {{ error }}
    </div>

    <div v-else-if="mostRanking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-3xl">
      <p class="text-slate-500 dark:text-slate-400 font-bold">表示できるデータがありません。</p>
    </div>

    <div v-else class="overflow-x-auto">
      <table class="w-full">
        <thead>
          <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
            <th class="pb-4 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-14">順位</th>
            <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest">楽曲名</th>
            <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-24 text-center hidden sm:table-cell">難易度</th>
            <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-20 text-center hidden md:table-cell">ランク</th>
            <th class="pb-4 text-xs font-black text-emerald-500 uppercase tracking-widest text-right hidden lg:table-cell">平均RATE-PT</th>
            <th class="pb-4 text-xs font-black" :class="sortMode === 'most' ? 'text-emerald-500' : 'text-orange-500'" style="text-align:right; padding-right:1rem;">採用人数</th>
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
                class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
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
                <span class="text-lg font-black tabular-nums"
                  :class="sortMode === 'most' ? 'text-slate-800 dark:text-slate-100' : 'text-orange-600 dark:text-orange-400'">
                  {{ entry.userCount }}
                </span>
                <span class="text-[10px] font-bold text-slate-400 uppercase tracking-widest">人</span>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
