<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ScoreRecord } from '../utils/scoreData';
import { getFolderRankInfo, getNextFolderRankInfo } from '../utils/beatTier';
import RankIcon from './RankIcon.vue';

const props = defineProps<{
  scores: ScoreRecord[];
}>();

const expandedRanks = ref<Set<string>>(new Set());

const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
};

// Group scores by informalRank, filter out those without one or the ones we want to hide
const groupedByRank = computed(() => {
  const groups: Record<string, ScoreRecord[]> = {};
  
  props.scores.forEach(s => {
    // We only care about levels 11 and 12
    if (s.difficultyLevel !== 11 && s.difficultyLevel !== 12) {
      return;
    }
    
    // We only care about scores that have an informal rank assigned and exclude 'Uncategorized(other)'
    if (s.informalRank && !s.informalRank.includes('Uncategorized')) {
      if (!groups[s.informalRank]) {
        groups[s.informalRank] = [];
      }
      groups[s.informalRank].push(s);
    }
  });

  return groups;
});

// Calculate statistics for each rank and sort them descending (e.g., 12.9 down to 11.6)
const tableData = computed(() => {
  const ranks = Object.keys(groupedByRank.value);
  
  // Sort ranks as numbers descending, treating "12.x" strings as floats
  ranks.sort((a, b) => parseFloat(b) - parseFloat(a));

  return ranks.map(rank => {
    const songs = groupedByRank.value[rank];
    let totalScore = 0;
    let totalMaxScore = 0;
    let totalBeatPoints = 0;
    let maxBeatPoints = 0;
    
    // Sort songs: highest score rate first
    songs.sort((a, b) => b.scoreRate - a.scoreRate);

    songs.forEach(s => {
      // Points accumulation
      totalBeatPoints += s.beatTierPoints;
      maxBeatPoints += s.maxBeatTierPoints;

      // Only include songs with a max score (i.e. we have note data for them)
      if (s.maxScore > 0 && s.score > 0) {
        totalScore += s.score;
        totalMaxScore += s.maxScore;
      }
    });

    const averageRate = totalMaxScore > 0 ? (totalScore / totalMaxScore) * 100 : 0;
    const playCount = songs.filter(s => s.score > 0).length;
    
    const rankInfo = getFolderRankInfo(totalBeatPoints, maxBeatPoints);
    const nextRankInfo = getNextFolderRankInfo(totalBeatPoints, maxBeatPoints);

    return {
      rank,
      songs,
      totalScore,
      totalMaxScore,
      totalBeatPoints,
      maxBeatPoints,
      averageRate,
      playCount,
      totalCount: songs.length,
      rankInfo,
      nextRankInfo
    };
  });
});
</script>

<template>
  <div class="w-full bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden mt-6 animate-fade-in flex flex-col transition-colors duration-200">
    <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-slate-50/50 dark:bg-slate-800/50 transition-colors duration-200">
      <h3 class="font-bold text-slate-800 dark:text-slate-100 text-lg flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-500 dark:text-indigo-400 shrink-0" viewBox="0 0 20 20" fill="currentColor">
          <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
        </svg>
        非公式難易度表サマリー
      </h3>
    </div>
    
    <div class="overflow-x-auto">
      <table class="w-full text-left border-collapse">
        <thead>
          <tr class="bg-slate-100 dark:bg-slate-800/80 text-slate-600 dark:text-slate-300 text-[10px] sm:text-sm border-b border-slate-200 dark:border-slate-700 transition-colors duration-200">
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold w-auto sm:w-24">難易度</th>
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-center w-auto sm:w-32">平均RATE</th>
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-right w-auto sm:w-48">合計 PT</th>
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-center w-auto sm:w-24">プレイ済</th>
            <th class="py-2 px-1 sm:py-3 sm:px-4 w-auto sm:w-12 text-center"></th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-xs sm:text-sm text-slate-700 dark:text-slate-200 transition-colors duration-200">
          <template v-for="data in tableData" :key="data.rank">
            <tr 
              @click="toggleRank(data.rank)"
              class="hover:bg-indigo-50/50 dark:hover:bg-slate-700/50 transition-colors cursor-pointer group"
              :class="{ 'bg-slate-50 dark:bg-slate-800/80': expandedRanks.has(data.rank) }"
            >
              <td class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-slate-800 dark:text-slate-100 whitespace-nowrap">
                <span class="inline-block w-2 h-2 rounded-full mr-1 sm:mr-2" :class="parseFloat(data.rank) >= 12.5 ? 'bg-purple-500 dark:bg-purple-400' : 'bg-blue-500 dark:bg-blue-400'"></span>
                {{ data.rank }}
              </td>
              <td class="py-2 px-2 sm:py-3 sm:px-4 text-center cursor-pointer">
                <div class="flex flex-col items-center">
                  <span class="font-black text-sm sm:text-base whitespace-nowrap" :class="data.averageRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : data.averageRate >= 88.88 ? 'text-amber-500 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400'">
                    {{ data.averageRate > 0 ? data.averageRate.toFixed(2) + '%' : '-' }}
                  </span>
                  <div v-if="data.averageRate > 0" class="hidden sm:block w-full max-w-[5rem] h-1 mt-1 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden mx-auto">
                    <div class="h-full bg-indigo-500 dark:bg-indigo-400" :style="{ width: `${data.averageRate}%` }"></div>
                  </div>
                </div>
              </td>
              <td class="py-2 px-2 sm:py-3 sm:px-4 text-right cursor-pointer" @click.stop="toggleRank(data.rank)">
                <div class="flex items-center justify-end gap-1 sm:gap-2">
                  <div class="flex flex-col text-right">
                    <span class="font-black text-xs sm:text-sm whitespace-nowrap" :class="data.rankInfo.color">
                      {{ data.totalBeatPoints.toFixed(1) }} <span class="text-[8px] sm:text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase">pt</span>
                    </span>
                    <span class="text-[8px] sm:text-[10px] text-slate-400 dark:text-slate-500 font-bold whitespace-nowrap">MAX: {{ data.maxBeatPoints.toFixed(1) }}</span>
                  </div>
                  <RankIcon class="block sm:hidden shrink-0" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="xs" />
                  <RankIcon class="hidden sm:block shrink-0" :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="sm" />
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
            <tr v-if="expandedRanks.has(data.rank)" class="bg-slate-50/80 dark:bg-slate-800/40 border-t-0 shadow-inner">
              <td colspan="5" class="px-6 py-4">
                <!-- Summary Board -->
                <div class="mb-4 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4 transition-colors duration-200">
                  <div class="flex flex-col">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">フォルダ内 BEAT-TIER</span>
                    <div class="flex items-center gap-3">
                      <RankIcon :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="lg" />
                      <div class="flex flex-col">
                        <span class="text-2xl font-black tracking-tight" :class="data.rankInfo.color">
                          {{ data.totalBeatPoints.toFixed(1) }} <span class="text-sm text-slate-400 dark:text-slate-500 font-bold uppercase">pt</span>
                        </span>
                        <span class="text-xs font-bold text-slate-400 dark:text-slate-500">
                          MAX: {{ data.maxBeatPoints.toFixed(1) }} pt
                        </span>
                      </div>
                    </div>
                  </div>
                  
                  <div class="flex-1 max-w-md w-full bg-slate-50 dark:bg-slate-900/50 rounded-xl p-3 border border-slate-100 dark:border-slate-700 flex flex-col justify-center transition-colors duration-200">
                    <div v-if="data.nextRankInfo.nextRank" class="flex flex-col gap-2">
                      <div class="flex justify-between items-end">
                        <span class="text-xs font-bold text-slate-500 dark:text-slate-400">NEXT RANK</span>
                        <div class="flex items-center gap-1.5">
                          <span class="text-sm font-bold" :class="data.nextRankInfo.nextRank.color">
                            {{ data.nextRankInfo.nextRank.name }} {{ data.nextRankInfo.nextRank.tier || '' }}
                          </span>
                          <span class="text-xs font-bold text-slate-400 dark:text-slate-500">
                            (あと{{ (data.nextRankInfo.nextRank.minPoints - data.totalBeatPoints).toFixed(1) }} pt)
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
                  <div v-for="song in data.songs" :key="song.title + song.difficultyName" class="bg-white dark:bg-slate-800 p-3 rounded-xl border border-slate-200 dark:border-slate-700 flex flex-col shadow-sm transition-colors duration-200">
                    <div class="flex items-start justify-between mb-2">
                       <h4 class="font-bold text-slate-800 dark:text-slate-200 text-xs line-clamp-2 pr-2" :title="song.title">{{ song.title }}</h4>
                       <span class="text-[9px] font-bold px-1.5 py-0.5 rounded uppercase tracking-wider shrink-0" :class="song.difficultyColor">
                         {{ song.difficultyName.substring(0, 3) }}
                       </span>
                    </div>
                    
                    <div class="flex items-end justify-between mt-auto">
                      <div class="flex flex-col">
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 font-bold">RATE</span>
                        <span class="font-black text-sm" :class="song.scoreRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : song.scoreRate >= 88.88 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-700 dark:text-slate-300'">
                          {{ song.scoreRate > 0 ? song.scoreRate.toFixed(2) + '%' : '-' }}
                        </span>
                      </div>
                      <div class="flex flex-col text-right">
                        <span class="text-[10px] text-slate-500 dark:text-slate-400 font-bold">PT</span>
                        <span class="font-mono text-sm text-slate-700 dark:text-slate-300 font-bold">{{ song.beatTierPoints > 0 ? song.beatTierPoints.toFixed(1) : '-' }}</span>
                      </div>
                    </div>
                  </div>
                </div>
              </td>
            </tr>
          </template>
          
          <tr v-if="tableData.length === 0">
            <td colspan="5" class="py-12 text-center text-slate-500 dark:text-slate-400">
              非公式難易度データが見つかりません。対象レベル（☆11、☆12）が含まれているか確認してください。
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
