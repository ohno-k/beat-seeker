<script setup lang="ts">
import { ref, onMounted } from 'vue';
import RankIcon from './RankIcon.vue';
import SongRankingList from './SongRankingList.vue';
import { getRankInfo } from '../utils/beatTier';

interface RankingEntry {
  displayName: string;
  iidxId: string;
  totalBeatPt: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
const ranking = ref<RankingEntry[]>([]);
const isLoading = ref(true);
const error = ref('');
const activeTab = ref<'player' | 'song'>('player');

onMounted(async () => {
    try {
        const res = await fetch(`${API_BASE}/api/scores/ranking`);
        if (res.ok) {
            ranking.value = await res.json();
        } else {
            error.value = 'ランキングの取得に失敗しました。';
        }
    } catch (e) {
        console.error(e);
        error.value = '通信エラーが発生しました。';
    } finally {
        isLoading.value = false;
    }
});
</script>

<template>
  <div class="w-full max-w-4xl space-y-6 animate-fade-in">
    <div class="bg-white dark:bg-slate-800 p-6 sm:p-8 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors">
      <div class="flex items-center gap-4 mb-6">
        <div class="bg-amber-100 dark:bg-amber-900/30 p-3 rounded-2xl">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-amber-600 dark:text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z" />
          </svg>
        </div>
        <div>
          <h2 class="text-2xl font-black text-slate-800 dark:text-slate-100">ランキング</h2>
          <p class="text-slate-500 dark:text-slate-400 font-medium text-sm">全プレイヤーの BEAT-PT 集計結果</p>
        </div>
      </div>

      <!-- Tab Switcher -->
      <div class="flex gap-1 bg-slate-100 dark:bg-slate-700/50 p-1 rounded-xl mb-6">
        <button
          @click="activeTab = 'player'"
          class="flex-1 py-2 px-4 rounded-lg text-sm font-bold transition-all"
          :class="activeTab === 'player'
            ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
            : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        >
          プレイヤーランキング
        </button>
        <button
          @click="activeTab = 'song'"
          class="flex-1 py-2 px-4 rounded-lg text-sm font-bold transition-all"
          :class="activeTab === 'song'
            ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm'
            : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        >
          楽曲ランキング
        </button>
      </div>

      <!-- Player Ranking -->
      <template v-if="activeTab === 'player'">
        <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
          <div class="w-12 h-12 border-4 border-blue-100 dark:border-slate-700 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
          <p class="text-slate-500 dark:text-slate-400 font-bold">ランキングを集計中...</p>
        </div>

        <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
          {{ error }}
        </div>

        <div v-else-if="ranking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-3xl">
          <p class="text-slate-500 dark:text-slate-400 font-bold">表示できるデータがありません。</p>
        </div>

        <div v-else class="overflow-x-auto">
          <table class="w-full">
            <thead>
              <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                <th class="pb-4 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-16">順位</th>
                <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest">プレイヤー</th>
                <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-20 text-center">ランク</th>
                <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right pr-4">総 BEAT-PT</th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
              <tr v-for="(entry, index) in ranking" :key="entry.iidxId"
                class="group hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors">
                <td class="py-3 pl-4">
                  <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                    :class="[
                      index === 0 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                      index === 1 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                      index === 2 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                      'text-slate-400 border border-slate-100 dark:border-slate-700'
                    ]">
                    {{ index + 1 }}
                  </div>
                </td>
                <td class="py-3">
                  <span class="font-bold text-slate-800 dark:text-slate-100 text-base group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors">
                    {{ entry.displayName || 'Unnamed Player' }}
                  </span>
                </td>
                <td class="py-3 px-2 text-center">
                  <div class="flex justify-center">
                    <RankIcon
                      :rank-name="getRankInfo(entry.totalBeatPt).name"
                      :tier="getRankInfo(entry.totalBeatPt).tier"
                      size="md"
                    />
                  </div>
                </td>
                <td class="py-3 text-right pr-4">
                  <div class="flex items-baseline justify-end gap-1">
                    <span class="text-xl font-black text-slate-800 dark:text-slate-100 tabular-nums">
                      {{ entry.totalBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                    </span>
                    <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest">BEAT-PT</span>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <!-- Song Ranking -->
      <template v-else>
        <SongRankingList />
      </template>
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
