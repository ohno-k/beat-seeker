<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useGameData } from '../composables/useGameData';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
const { diffTableRanks } = useGameData();
const { authHeaders, isLoggedIn } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

interface ScoreRateEntry {
  title: string;
  difficultyName: string;
  avgScoreRate: number;
  playerCount: number;
  maxMinusRate: number;
  maxMinusCount: number;
}

const isLoading = ref(true);
const errorMsg = ref('');
const scoreRates = ref<ScoreRateEntry[]>([]);
const sortKey = ref<'avgScoreRate' | 'title' | 'playerCount' | 'rank' | 'maxMinusRate'>('maxMinusRate');
const sortDir = ref<'asc' | 'desc'>('asc');

// 難易度表から曲→ランクのマップを構築
const rankMap = computed(() => {
  const map = new Map<string, string>();
  for (const rank of diffTableRanks.value) {
    for (const song of rank.songs) {
      map.set(song, rank.rank);
    }
  }
  return map;
});

function getRank(row: ScoreRateEntry): string {
  const entry = row.difficultyName === 'LEGGENDARIA' ? row.title + '[L]' : row.title;
  return rankMap.value.get(entry) || '-';
}

// ドラフト難易度表: 曲→ドラフトランク
const draftRankMap = ref(new Map<string, string>());

function getDraftRank(row: ScoreRateEntry): string {
  const entry = row.difficultyName === 'LEGGENDARIA' ? row.title + '[L]' : row.title;
  return draftRankMap.value.get(entry) || rankMap.value.get(entry) || '-';
}

// active と draft の差分を表示
function getDraft(row: ScoreRateEntry): string {
  const entry = row.difficultyName === 'LEGGENDARIA' ? row.title + '[L]' : row.title;
  const draftRank = draftRankMap.value.get(entry);
  if (!draftRank) return '';
  const activeRank = rankMap.value.get(entry);
  if (!activeRank) return `新規 ${draftRank}`;
  if (activeRank === draftRank) return '';
  return `${activeRank} → ${draftRank}`;
}

onMounted(async () => {
  try {
    const res = await fetch(`${API_BASE}/api/scores/song-avg-score-rates`, {
      headers: authHeaders(),
    });
    if (res.ok) {
      scoreRates.value = await res.json();
    } else {
      errorMsg.value = `APIエラー: ${res.status}`;
    }
  } catch (e: any) {
    errorMsg.value = `通信エラー: ${e.message}`;
  } finally {
    isLoading.value = false;
  }

  // ドラフト難易度表を取得（ログイン時のみ、失敗しても無視）
  if (isLoggedIn.value) {
    try {
      const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
        headers: authHeaders(),
      });
      if (res.ok) {
        const data: { ranks: { rank: string; songs: string[] }[] } = await res.json();
        const map = new Map<string, string>();
        for (const r of data.ranks) {
          for (const song of r.songs) {
            map.set(song, r.rank);
          }
        }
        draftRankMap.value = map;
      }
    } catch {}
  }
});

function toggleSort(key: typeof sortKey.value) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    sortDir.value = 'asc';
  }
}

// MAX-割合の昇順で固定の順位を割り当て
const fixedRankMap = computed(() => {
  const map = new Map<string, number>();
  const sorted = [...scoreRates.value].sort((a, b) => a.maxMinusRate - b.maxMinusRate);
  sorted.forEach((row, idx) => {
    map.set(`${row.title}_${row.difficultyName}`, idx + 1);
  });
  return map;
});

function getFixedRank(row: ScoreRateEntry): number {
  return fixedRankMap.value.get(`${row.title}_${row.difficultyName}`) || 0;
}

const sortedData = computed(() => {
  const data = [...scoreRates.value];
  const dir = sortDir.value === 'asc' ? 1 : -1;
  data.sort((a, b) => {
    let primary = 0;
    switch (sortKey.value) {
      case 'avgScoreRate':
        primary = (a.avgScoreRate - b.avgScoreRate) * dir;
        break;
      case 'title':
        primary = a.title.localeCompare(b.title) * dir;
        break;
      case 'playerCount':
        primary = (a.playerCount - b.playerCount) * dir;
        break;
      case 'rank':
        primary = ((parseFloat(getRank(a)) || 0) - (parseFloat(getRank(b)) || 0)) * dir;
        break;
      case 'maxMinusRate':
        primary = (a.maxMinusRate - b.maxMinusRate) * dir;
        break;
    }
    if (primary !== 0) return primary;
    return a.maxMinusRate - b.maxMinusRate;
  });
  return data;
});

function hasRedLine(idx: number): boolean {
  if (idx === 0) return false;
  if (sortKey.value === 'rank') {
    return getRank(sortedData.value[idx - 1]) !== getRank(sortedData.value[idx]);
  }
  return false;
}

function sortIcon(key: string): string {
  if (sortKey.value !== key) return '↕';
  return sortDir.value === 'asc' ? '↑' : '↓';
}
</script>

<template>
  <div class="space-y-6">
    <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-6 shadow-sm">
      <h2 class="text-xl font-black text-slate-900 dark:text-white mb-2">曲別平均スコアレート</h2>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-6">
        全プレイヤーの平均スコアレート（ANOTHER+LEGGENDARIA、☆11+☆12）
      </p>

      <div v-if="isLoading" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-sm text-slate-500">読み込み中...</span>
      </div>

      <div v-else-if="errorMsg" class="text-red-500 text-sm py-4">{{ errorMsg }}</div>

      <template v-else>
        <p class="text-sm text-slate-500 mb-4">{{ scoreRates.length }}曲</p>

        <div class="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table class="w-full text-sm">
            <thead>
              <tr class="bg-slate-50 dark:bg-slate-700/50 border-b border-slate-200 dark:border-slate-600">
                <th class="text-left px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-8">#</th>
                <th
                  class="text-left px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('title')"
                >曲名 {{ sortIcon('title') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-20 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('rank')"
                >難易度 {{ sortIcon('rank') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-28 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('avgScoreRate')"
                >平均レート {{ sortIcon('avgScoreRate') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-black text-amber-600 dark:text-amber-400 w-32 cursor-pointer hover:text-amber-700 dark:hover:text-amber-300 select-none bg-amber-50/50 dark:bg-amber-900/20"
                  @click="toggleSort('maxMinusRate')"
                >MAX- {{ sortIcon('maxMinusRate') }}</th>
                <th
                  class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-20 cursor-pointer hover:text-blue-600 select-none"
                  @click="toggleSort('playerCount')"
                >人数 {{ sortIcon('playerCount') }}</th>
                <th class="text-center px-3 py-2.5 font-bold text-slate-600 dark:text-slate-300 w-28">ドラフト</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(row, idx) in sortedData"
                :key="`${row.title}_${row.difficultyName}`"
                class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
                :class="hasRedLine(idx) ? 'border-t-2 border-t-red-500 border-b border-b-slate-100 dark:border-b-slate-700/50' : 'border-b border-slate-100 dark:border-slate-700/50'"
              >
                <td class="px-3 py-2 text-slate-400 text-xs">{{ getFixedRank(row) }}</td>
                <td class="px-3 py-2">
                  <span class="font-medium text-slate-800 dark:text-slate-200">{{ row.title }}</span>
                  <span v-if="row.difficultyName === 'LEGGENDARIA'" class="ml-1 text-[10px] font-bold text-orange-500 bg-orange-50 dark:bg-orange-900/30 px-1 rounded">[L]</span>
                </td>
                <td class="px-3 py-2 text-center font-mono text-slate-700 dark:text-slate-300">{{ getRank(row) }}</td>
                <td class="px-3 py-2 text-center font-mono font-bold text-slate-700 dark:text-slate-300">{{ row.avgScoreRate.toFixed(2) }}%</td>
                <td class="px-3 py-2 text-center font-mono font-bold bg-amber-50/50 dark:bg-amber-900/20 whitespace-nowrap"
                    :class="row.maxMinusRate >= 50 ? 'text-amber-600 dark:text-amber-400' : row.maxMinusRate >= 20 ? 'text-amber-500 dark:text-amber-500' : 'text-slate-500 dark:text-slate-400'"
                >{{ row.maxMinusRate.toFixed(1) }}% <span class="text-slate-400 dark:text-slate-500 font-normal">({{ row.maxMinusCount }})</span></td>
                <td class="px-3 py-2 text-center text-slate-500 dark:text-slate-400">{{ row.playerCount }}</td>
                <td class="px-3 py-2 text-center text-xs">
                  <span v-if="getDraft(row)" class="px-1.5 py-0.5 rounded bg-purple-50 dark:bg-purple-900/30 text-purple-600 dark:text-purple-300 font-bold">{{ getDraft(row) }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </div>
  </div>
</template>
