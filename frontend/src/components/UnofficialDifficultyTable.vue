<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ScoreRecord } from '../utils/scoreData';
import { getFolderRankInfoByRate, getNextFolderRankInfoByRate, getLegendPtPerSong, getFolderLegendRate, FOLDER_RANK_DEFS, getMaxPoints } from '../utils/beatTier';
import songDataRaw from '../data/song_data.json';
import RankIcon from './RankIcon.vue';
import difficultyData from '../data/difficulty_table.json';

const props = defineProps<{
  scores: ScoreRecord[];
}>();

const expandedRanks = ref<Set<string>>(new Set());
const showInfo = ref(false);
const showRateTable = ref(false);

// All folders ☆11.0 ~ ☆13.0
const allFolders: string[] = [];
for (let i = 0; i <= 20; i++) allFolders.push((11.0 + i * 0.1).toFixed(1));

// Rate table: all ranks × all folders
const rateTableRows = computed(() => {
  return FOLDER_RANK_DEFS.map(def => {
    const label = def.tier ? `${def.name} ${def.tier}` : def.name;
    const rates = allFolders.map(f => {
      const rate = getFolderLegendRate(f) - def.offset;
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

const toggleRank = (rank: string) => {
  if (expandedRanks.value.has(rank)) {
    expandedRanks.value.delete(rank);
  } else {
    expandedRanks.value.add(rank);
  }
};

// Build song definition lookup by "title_difficultyCode"
const songDict = new Map<string, any>();
if (songDataRaw && Array.isArray(songDataRaw.body)) {
  songDataRaw.body.forEach(s => {
    songDict.set(`${s.title}_${s.difficulty}`, s);
  });
}

// Group all songs by informalRank, including unplayed ones from the difficulty table
const groupedByRank = computed(() => {
  const groups: Record<string, ScoreRecord[]> = {};

  // Build lookup from played scores by title + difficultyName
  const scoreMap = new Map<string, ScoreRecord>();
  props.scores.forEach(s => {
    if (s.informalRank && !s.informalRank.includes('Uncategorized')) {
      scoreMap.set(`${s.title}_${s.difficultyName}`, s);
    }
  });

  // Iterate all songs defined in the difficulty table
  difficultyData.ranks.forEach(r => {
    const rank = r.rank;
    if (!groups[rank]) groups[rank] = [];

    r.songs.forEach(songTitle => {
      const isLeggendaria = songTitle.endsWith('[L]');
      const baseTitle = isLeggendaria ? songTitle.slice(0, -3) : songTitle;
      const diffName = isLeggendaria ? 'LEGGENDARIA' : 'ANOTHER';
      const diffCode = isLeggendaria ? '10' : '4';

      const scoreKey = `${baseTitle}_${diffName}`;
      if (scoreMap.has(scoreKey)) {
        // Played song – use existing score record
        groups[rank].push(scoreMap.get(scoreKey)!);
      } else {
        // Unplayed song – create placeholder using song definition data
        const def = songDict.get(`${baseTitle}_${diffCode}`);
        const maxScore = def ? def.notes * 2 : 0;
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
          memo: undefined,
        });
      }
    });
  });

  return groups;
});

// Calculate total songs per rank from the difficulty table
const rankSongCounts = computed(() => {
  const counts: Record<string, number> = {};
  difficultyData.ranks.forEach(r => {
    counts[r.rank] = r.songs.length;
  });
  return counts;
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

      // Only include songs with a max score (i.e. we have note data for them)
      if (s.maxScore > 0 && s.score > 0) {
        totalScore += s.score;
        totalMaxScore += s.maxScore;
      }
    });

    const averageRate = totalMaxScore > 0 ? (totalScore / totalMaxScore) * 100 : 0;
    const playCount = songs.filter(s => s.score > 0).length;

    const totalCount = rankSongCounts.value[rank] || songs.length;

    // Legend threshold for display (MAX reference)
    const legendPtPerSong = getLegendPtPerSong(rank);
    maxBeatPoints = legendPtPerSong > 0 ? legendPtPerSong * totalCount : 0;

    const rankInfo = getFolderRankInfoByRate(averageRate, rank);
    const nextRankInfo = getNextFolderRankInfoByRate(averageRate, rank);

    // レートベースのランクは既にプレイ済み曲の平均レートで算出されるため playedRankInfo は同値
    const playedRankInfo = rankInfo;

    return {
      rank,
      songs,
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
  <div class="w-full bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden mt-6 animate-fade-in flex flex-col transition-colors duration-200">
    <div class="px-6 py-4 border-b border-slate-100 dark:border-slate-700/50 flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-slate-50/50 dark:bg-slate-800/50 transition-colors duration-200">
      <h3 class="font-bold text-slate-800 dark:text-slate-100 text-lg flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-indigo-500 dark:text-indigo-400 shrink-0" viewBox="0 0 20 20" fill="currentColor">
          <path d="M5 4a1 1 0 00-2 0v7.268a2 2 0 000 3.464V16a1 1 0 102 0v-1.268a2 2 0 000-3.464V4zM11 4a1 1 0 10-2 0v1.268a2 2 0 000 3.464V16a1 1 0 102 0V8.732a2 2 0 000-3.464V4zM16 3a1 1 0 011 1v7.268a2 2 0 010 3.464V16a1 1 0 11-2 0v-1.268a2 2 0 010-3.464V4a1 1 0 011-1z" />
        </svg>
        非公式難易度表サマリー
        <div class="relative">
          <button
            @click.stop="showInfo = !showInfo"
            class="w-5 h-5 rounded-full bg-slate-200 dark:bg-slate-600 hover:bg-indigo-200 dark:hover:bg-indigo-700 text-slate-500 dark:text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-300 text-[10px] font-black flex items-center justify-center transition-colors"
            title="このサマリーについて"
          >?</button>
          <div
            v-if="showInfo"
            class="absolute z-20 top-7 -left-16 sm:left-0 w-[280px] sm:w-72 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-600 shadow-xl p-4 text-xs text-slate-700 dark:text-slate-300 font-normal"
          >
            <div class="flex items-center justify-between mb-2">
              <span class="font-bold text-sm text-slate-800 dark:text-slate-100">フォルダランクについて</span>
              <button @click="showInfo = false" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 font-bold text-base leading-none">×</button>
            </div>
            <p class="mb-2 text-slate-600 dark:text-slate-400">各フォルダの全楽曲の合計BEAT-PTをもとに、フォルダランクを算出します。</p>
            <div class="border-t border-slate-100 dark:border-slate-700 pt-2 mt-2 space-y-1">
              <p class="font-bold text-slate-700 dark:text-slate-200">■ LEGENDラインの基準</p>
              <p>LEGENDに必要な平均スコアレートは難易度ごとに異なります。☆11.0では約99.6%、☆13.0では約94.44%（MAX-越え）が基準で、その間は曲線で補間されます。</p>
              <p class="font-bold text-slate-700 dark:text-slate-200 mt-2">■ 各ランクの基準</p>
              <p>LEGENDラインから0.25%ずつ必要スコアレートが下がるごとに、ランクが1段階下がります。</p>
              <p class="font-bold text-slate-700 dark:text-slate-200 mt-2">■ 薄いランクアイコン</p>
              <p>プレイ済みの曲のみで算出したランクです。全曲プレイ済みの場合は表示されません。</p>
            </div>
            <button
              @click.stop="showRateTable = true; showInfo = false"
              class="mt-3 w-full py-1.5 bg-indigo-500 hover:bg-indigo-600 text-white text-xs font-bold rounded-lg transition-colors"
            >📊 必要スコアレート表を見る</button>
          </div>
        </div>
      </h3>
    </div>
    <!-- Click-outside backdrop for info tooltip -->
    <div v-if="showInfo" class="fixed inset-0 z-10" @click="showInfo = false"></div>

    <!-- Rate Table Modal -->
    <Teleport to="body">
      <div v-if="showRateTable" class="fixed inset-0 z-50 flex items-center justify-center p-2 sm:p-4">
        <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" @click="showRateTable = false"></div>
        <div class="relative z-10 bg-white dark:bg-slate-800 rounded-xl sm:rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 w-full max-w-[95vw] max-h-[90vh] flex flex-col">
          <!-- Header -->
          <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
            <div>
              <h3 class="font-bold text-sm sm:text-base text-slate-800 dark:text-slate-100">📊 必要スコアレート表</h3>
            </div>
            <button @click="showRateTable = false" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors shrink-0 ml-2">×</button>
          </div>
          <!-- Scrollable table -->
          <div class="overflow-auto flex-1">
            <table class="text-[10px] sm:text-xs border-collapse">
              <thead class="sticky top-0 z-20">
                <tr class="bg-slate-100 dark:bg-slate-900 text-slate-500 dark:text-slate-400">
                  <th class="py-1.5 px-2 text-left font-bold sticky left-0 z-30 bg-slate-100 dark:bg-slate-900 min-w-[80px] sm:min-w-[110px]">ランク</th>
                  <th v-for="f in allFolders" :key="f" class="py-1.5 px-1 sm:px-2 text-center font-bold whitespace-nowrap">☆{{ f }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in rateTableRows" :key="row.label" :class="idx % 5 === 0 ? 'border-t border-slate-200 dark:border-slate-700' : ''">
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
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold w-auto sm:w-24">難易度</th>
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-center w-auto sm:w-32">平均RATE</th>
            <th class="py-2 px-2 sm:py-3 sm:px-4 font-bold text-right w-auto sm:w-48">合計PT（予測ランク）</th>
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
            <tr v-if="expandedRanks.has(data.rank)" class="bg-slate-50/80 dark:bg-slate-800/40 border-t-0 shadow-inner">
              <td colspan="5" class="px-6 py-4">
                <!-- Summary Board -->
                <div class="mb-4 bg-white dark:bg-slate-800 rounded-xl border border-slate-200 dark:border-slate-700 p-4 shadow-sm flex flex-col md:flex-row md:items-center justify-between gap-4 transition-colors duration-200">
                  <div class="flex flex-col">
                    <span class="text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">フォルダ内 BEAT-TIER</span>
                    <div class="flex items-center gap-3">
                      <RankIcon :rank-name="data.rankInfo.name" :tier="data.rankInfo.tier" size="lg" :class="data.playCount < data.totalCount ? 'opacity-30' : ''" />
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
