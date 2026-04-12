<script setup lang="ts">
import { ref, onMounted, watch, computed, nextTick } from 'vue';
import RankIcon from './RankIcon.vue';
import { getRankInfo, getRateTierRankInfo } from '../utils/beatTier';
import { useAuth } from '../composables/useAuth';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';
import { useI18n } from '../composables/useI18n';
import { diffTable as diffTableRanks } from '../composables/useGameData';

interface BeatRankingEntry {
  displayName: string;
  iidxId: string;
  totalBeatPt: number;
  rankChange: number | null;
  lastUpdatedAt: string | null;
}

interface RateRankingEntry {
  displayName: string;
  iidxId: string;
  totalRatePt: number;
  rankChange: number | null;
  lastUpdatedAt: string | null;
}

interface SimulationEntry {
  displayName: string;
  iidxId: string;
  currentBeatPt: number;
  simulatedBeatPt: number;
  ptDelta: number;
  currentRank: number;
  simulatedRank: number;
  rankDelta: number;
}

function formatLastUpdated(dateStr: string | null): string {
  if (!dateStr) return '-';
  const now = new Date();
  const date = new Date(dateStr);
  const diffDays = Math.floor((now.getTime() - date.getTime()) / (1000 * 60 * 60 * 24));
  if (diffDays === 0) return t('common.today');
  if (diffDays === 1) return t('common.yesterday');
  if (diffDays === 2) return t('common.dayBeforeYesterday');
  if (diffDays <= 6) return t('common.daysAgo', { days: diffDays });
  return t('common.moreThanAWeekAgo');
}

const { t } = useI18n();

const { user, authHeaders } = useAuth();
const { showRateTier } = useRateTierVisibility();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const isAdmin = computed(() => user.value?.iidxId === '5787-1145');

const viewMode = ref<'beat' | 'rate' | 'simulation'>('beat');
watch(showRateTier, (val) => {
    if (!val && viewMode.value === 'rate') viewMode.value = 'beat';
});
const beatRanking = ref<BeatRankingEntry[]>([]);
const rateRanking = ref<RateRankingEntry[]>([]);
const isLoading = ref(true);
const error = ref('');

// Pagination
const PAGE_SIZE = 50;
const beatPage = ref(1);
const ratePage = ref(1);

const beatTotalPages = computed(() => Math.max(1, Math.ceil(beatRanking.value.length / PAGE_SIZE)));
const rateTotalPages = computed(() => Math.max(1, Math.ceil(rateRanking.value.length / PAGE_SIZE)));

const paginatedBeatRanking = computed(() => {
    const start = (beatPage.value - 1) * PAGE_SIZE;
    return beatRanking.value.slice(start, start + PAGE_SIZE);
});
const paginatedRateRanking = computed(() => {
    const start = (ratePage.value - 1) * PAGE_SIZE;
    return rateRanking.value.slice(start, start + PAGE_SIZE);
});

const beatPageStartIndex = computed(() => (beatPage.value - 1) * PAGE_SIZE);
const ratePageStartIndex = computed(() => (ratePage.value - 1) * PAGE_SIZE);

function goToMyRank() {
    if (!user.value) return;
    const list = viewMode.value === 'rate' ? rateRanking.value : beatRanking.value;
    const idx = list.findIndex(e => e.iidxId === user.value!.iidxId);
    if (idx === -1) return;
    const page = Math.floor(idx / PAGE_SIZE) + 1;
    if (viewMode.value === 'rate') {
        ratePage.value = page;
    } else {
        beatPage.value = page;
    }
    // Scroll to the row after DOM updates
    nextTick(() => {
        const row = document.getElementById(`ranking-row-${user.value!.iidxId}`);
        if (row) row.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
}

// Reset page when switching modes
watch(viewMode, () => {
    beatPage.value = 1;
    ratePage.value = 1;
});

// Simulation state
const simulationData = ref<SimulationEntry[]>([]);
const isSimulationLoading = ref(false);
const simulationError = ref('');
const draftDiffChanges = ref<{ title: string; oldRank: string; newRank: string }[]>([]);
const promotionChanges = computed(() => draftDiffChanges.value.filter(c => parseFloat(c.newRank) > parseFloat(c.oldRank)));
const demotionChanges = computed(() => draftDiffChanges.value.filter(c => parseFloat(c.newRank) < parseFloat(c.oldRank)));

async function fetchBeatRanking() {
    const res = await fetch(`${API_BASE}/api/scores/ranking`);
    if (res.ok) beatRanking.value = await res.json();
    else throw new Error('beat');
}

async function fetchRateRanking() {
    const res = await fetch(`${API_BASE}/api/scores/rate-ranking`);
    if (res.ok) rateRanking.value = await res.json();
    else throw new Error('rate');
}



async function fetchSimulationData() {
    isSimulationLoading.value = true;
    simulationError.value = '';
    try {
        // Ensure the regular ranking is loaded to set current ranks
        if (beatRanking.value.length === 0) {
            await fetchBeatRanking();
        }

        const [simRes, draftRes, activeDiffRes] = await Promise.all([
            fetch(`${API_BASE}/api/admin/scores/simulation-aggregate`, { headers: authHeaders() }),
            fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, { headers: authHeaders() }),
            fetch(`${API_BASE}/api/game-data/difficulty-table`),
        ]);
        if (!simRes.ok) throw new Error('シミュレーションの取得に失敗しました');
        if (!draftRes.ok) throw new Error('ドラフト難易度表の取得に失敗しました');

        const simEntries: Omit<SimulationEntry, 'currentRank' | 'simulatedRank' | 'rankDelta'>[] = await simRes.json();
        const draftTable: { ranks: { rank: string; songs: string[] }[] } = await draftRes.json();
        const activeDiff: { ranks: { rank: string; songs: string[] }[] } = activeDiffRes.ok
            ? await activeDiffRes.json()
            : { ranks: diffTableRanks.value };

        // Compute draft changes for display
        const activeMap = new Map<string, string>();
        activeDiff.ranks.forEach(r => r.songs.forEach(s => activeMap.set(s, r.rank)));
        const changes: { title: string; oldRank: string; newRank: string }[] = [];
        draftTable.ranks.forEach(r => {
            r.songs.forEach(s => {
                const activeRank = activeMap.get(s);
                if (activeRank !== undefined && activeRank !== r.rank) {
                    changes.push({ title: s, oldRank: activeRank, newRank: r.rank });
                }
            });
        });
        draftDiffChanges.value = changes;

        // Sort by simulated BEAT-PT descending
        const sortedBySim = [...simEntries].sort((a, b) => b.simulatedBeatPt - a.simulatedBeatPt);

        // Current rank = position in the regular ranking
        const currentRankMap = new Map<string, number>();
        beatRanking.value.forEach((e, i) => currentRankMap.set(e.iidxId, i + 1));

        const simRankMap = new Map<string, number>();
        sortedBySim.forEach((e, i) => simRankMap.set(e.iidxId, i + 1));

        simulationData.value = sortedBySim.map(e => ({
            ...e,
            currentRank: currentRankMap.get(e.iidxId) ?? 0,
            simulatedRank: simRankMap.get(e.iidxId) ?? 0,
            rankDelta: (currentRankMap.get(e.iidxId) ?? 0) - (simRankMap.get(e.iidxId) ?? 0),
        }));
    } catch (e: any) {
        simulationError.value = e.message || 'シミュレーションの取得に失敗しました';
    } finally {
        isSimulationLoading.value = false;
    }
}

onMounted(async () => {
    try {
        await fetchBeatRanking();
    } catch (e) {
        console.error(e);
        error.value = t('ranking.error');
    } finally {
        isLoading.value = false;
    }
});

watch(viewMode, async (mode) => {
    if (mode === 'rate' && rateRanking.value.length === 0) {
        isLoading.value = true;
        error.value = '';
        try {
            await fetchRateRanking();
        } catch (e) {
            console.error(e);
            error.value = t('ranking.error');
        } finally {
            isLoading.value = false;
        }
    }
    if (mode === 'simulation' && simulationData.value.length === 0) {
        fetchSimulationData();
    }
});
</script>

<template>
  <div class="w-full max-w-4xl space-y-6 animate-fade-in">
    <div class="bg-white dark:bg-slate-800 p-6 sm:p-8 rounded-3xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors">
      <!-- Header -->
      <div class="flex items-center gap-4 mb-6">
        <div class="p-3 rounded-2xl bg-amber-100 dark:bg-amber-900/30 transition-colors">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-amber-600 dark:text-amber-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4M7.835 4.697a3.42 3.42 0 001.946-.806 3.42 3.42 0 014.438 0 3.42 3.42 0 001.946.806 3.42 3.42 0 013.138 3.138 3.42 3.42 0 00.806 1.946 3.42 3.42 0 010 4.438 3.42 3.42 0 00-.806 1.946 3.42 3.42 0 01-3.138 3.138 3.42 3.42 0 00-1.946.806 3.42 3.42 0 01-4.438 0 3.42 3.42 0 00-1.946-.806 3.42 3.42 0 01-3.138-3.138 3.42 3.42 0 00-.806-1.946 3.42 3.42 0 010-4.438 3.42 3.42 0 00.806-1.946 3.42 3.42 0 013.138-3.138z" />
          </svg>
        </div>
        <div>
          <h2 class="text-2xl font-black text-slate-800 dark:text-slate-100">{{ t('ranking.title') }}</h2>
          <p class="text-slate-500 dark:text-slate-400 font-medium text-sm">{{ t('ranking.subtitle') }}</p>
        </div>
      </div>


      <!-- Find My Rank Button + Mode Toggle -->
      <div class="flex items-center gap-3 mb-6 flex-wrap">
        <button
          v-if="user && (viewMode === 'beat' || viewMode === 'rate')"
          @click="goToMyRank"
          class="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 border border-blue-200 dark:border-blue-700 hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" /></svg>
          {{ t('ranking.findMyRank') }}
        </button>
      </div>

      <!-- Mode Toggle -->
      <div class="flex gap-1 p-1 bg-slate-100 dark:bg-slate-700/50 rounded-xl mb-6 w-fit">
        <button
          @click="viewMode = 'beat'"
          class="px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
          :class="viewMode === 'beat'
            ? 'bg-white dark:bg-slate-600 text-blue-600 dark:text-blue-400 shadow-sm'
            : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
        >Beat-Tier</button>
        <button
          v-if="showRateTier"
          @click="viewMode = 'rate'"
          class="px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
          :class="viewMode === 'rate'
            ? 'bg-white dark:bg-slate-600 text-emerald-600 dark:text-emerald-400 shadow-sm'
            : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
        >Rate-Tier</button>
        <button
          v-if="isAdmin"
          @click="viewMode = 'simulation'"
          class="px-4 py-1.5 rounded-lg text-xs font-black uppercase tracking-widest transition-all"
          :class="viewMode === 'simulation'
            ? 'bg-white dark:bg-slate-600 text-amber-600 dark:text-amber-400 shadow-sm'
            : 'text-slate-400 dark:text-slate-500 hover:text-slate-600 dark:hover:text-slate-300'"
        >難易度シミュ</button>
      </div>

      <!-- Loading / Error / Empty -->
      <div v-if="isLoading" class="flex flex-col items-center justify-center py-20">
        <div class="w-12 h-12 border-4 border-blue-100 dark:border-slate-700 border-t-blue-600 dark:border-t-blue-500 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('ranking.loading') }}</p>
      </div>

      <div v-else-if="error" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
        {{ t('ranking.error') }}
      </div>

      <template v-else>
        <!-- Beat-Tier ranking -->
        <div v-if="viewMode === 'beat'">
          <div v-if="beatRanking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-3xl">
            <p class="text-slate-500 dark:text-slate-400 font-bold">{{ t('ranking.empty') }}</p>
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-4 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-28">{{ t('ranking.colRank') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('ranking.colPlayer') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-20 text-center">{{ t('ranking.colTier') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right">{{ t('ranking.colPoints', { type: 'BEAT' }) }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right pr-4">{{ t('ranking.colUpdatedAt') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="(entry, idx) in paginatedBeatRanking" :key="entry.iidxId"
                  :id="`ranking-row-${entry.iidxId}`"
                  class="group transition-colors"
                  :class="user && entry.iidxId === user.iidxId
                    ? 'bg-blue-50 dark:bg-blue-900/20 border-l-4 border-l-blue-500'
                    : 'hover:bg-slate-50 dark:hover:bg-slate-700/30'">
                  <td class="py-3 pl-4">
                    <div class="flex items-center gap-2">
                      <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                        :class="[
                          beatPageStartIndex + idx === 0 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                          beatPageStartIndex + idx === 1 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                          beatPageStartIndex + idx === 2 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                          user && entry.iidxId === user.iidxId ? 'bg-blue-500 text-white' :
                          'text-slate-400 border border-slate-100 dark:border-slate-700'
                        ]">
                        {{ beatPageStartIndex + idx + 1 }}
                      </div>
                      <span v-if="entry.rankChange === null" class="text-[10px] font-bold text-blue-500">NEW</span>
                      <span v-else-if="entry.rankChange > 0" class="text-[10px] font-bold text-emerald-500">▲{{ entry.rankChange }}</span>
                      <span v-else-if="entry.rankChange < 0" class="text-[10px] font-bold text-red-500">▼{{ Math.abs(entry.rankChange) }}</span>
                      <span v-else class="text-[10px] font-bold text-slate-300 dark:text-slate-600">-</span>
                    </div>
                  </td>
                  <td class="py-3">
                    <div class="flex items-center gap-2">
                      <span class="font-bold text-base transition-colors"
                        :class="user && entry.iidxId === user.iidxId
                          ? 'text-blue-700 dark:text-blue-300'
                          : 'text-slate-800 dark:text-slate-100 group-hover:text-blue-600 dark:group-hover:text-blue-400'">
                        {{ entry.displayName || 'Unnamed Player' }}
                      </span>
                      <span v-if="user && entry.iidxId === user.iidxId"
                        class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-blue-500 text-white">{{ t('ranking.you') }}</span>
                    </div>
                  </td>
                  <td class="py-3 px-2 text-center">
                    <div class="flex justify-center">
                      <RankIcon :rank-name="getRankInfo(entry.totalBeatPt).name" :tier="getRankInfo(entry.totalBeatPt).tier" size="md" disable-party :is-supporter="entry.isSupporter" />
                    </div>
                  </td>
                  <td class="py-3 text-right">
                    <div class="flex items-baseline justify-end gap-1">
                      <span class="text-xl font-black tabular-nums"
                        :class="user && entry.iidxId === user.iidxId ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">
                        {{ entry.totalBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                      </span>
                      <span class="text-[9px] font-bold text-slate-400 uppercase tracking-widest">BEAT-PT</span>
                    </div>
                  </td>
                  <td class="py-3 text-right pr-4">
                    <span class="text-xs font-medium tabular-nums"
                      :class="formatLastUpdated(entry.lastUpdatedAt) === t('common.today') ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'">
                      {{ formatLastUpdated(entry.lastUpdatedAt) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
            <!-- Beat Pagination -->
            <div v-if="beatTotalPages > 1" class="flex items-center justify-center gap-2 mt-6 pt-4 border-t border-slate-100 dark:border-slate-700/50">
              <button @click="beatPage = 1" :disabled="beatPage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &laquo;
              </button>
              <button @click="beatPage--" :disabled="beatPage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &lsaquo;
              </button>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 px-2 tabular-nums">
                {{ beatPage }} / {{ beatTotalPages }}
              </span>
              <button @click="beatPage++" :disabled="beatPage === beatTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &rsaquo;
              </button>
              <button @click="beatPage = beatTotalPages" :disabled="beatPage === beatTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &raquo;
              </button>
            </div>
          </div>
        </div>

        <!-- Rate-Tier ranking -->
        <div v-else-if="viewMode === 'rate'">
          <div v-if="rateRanking.length === 0" class="text-center py-20 border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-3xl">
            <p class="text-slate-500 dark:text-slate-400 font-bold" v-html="t('ranking.emptyRate')"></p>
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-4 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-28">{{ t('ranking.colRank') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest">{{ t('ranking.colPlayer') }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest w-20 text-center">{{ t('ranking.colTier') }}</th>
                  <th class="pb-4 text-xs font-black text-emerald-500 uppercase tracking-widest text-right">{{ t('ranking.colPoints', { type: 'RATE' }) }}</th>
                  <th class="pb-4 text-xs font-black text-slate-400 uppercase tracking-widest text-right pr-4">{{ t('ranking.colUpdatedAt') }}</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="(entry, idx) in paginatedRateRanking" :key="entry.iidxId"
                  :id="`ranking-row-${entry.iidxId}`"
                  class="group transition-colors"
                  :class="user && entry.iidxId === user.iidxId
                    ? 'bg-emerald-50 dark:bg-emerald-900/20 border-l-4 border-l-emerald-500'
                    : 'hover:bg-slate-50 dark:hover:bg-slate-700/30'">
                  <td class="py-3 pl-4">
                    <div class="flex items-center gap-2">
                      <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                        :class="[
                          ratePageStartIndex + idx === 0 ? 'bg-amber-100 text-amber-700 dark:bg-amber-500 dark:text-white' :
                          ratePageStartIndex + idx === 1 ? 'bg-slate-200 text-slate-700 dark:bg-slate-400 dark:text-white' :
                          ratePageStartIndex + idx === 2 ? 'bg-orange-100 text-orange-700 dark:bg-orange-400 dark:text-white' :
                          user && entry.iidxId === user.iidxId ? 'bg-emerald-500 text-white' :
                          'text-slate-400 border border-slate-100 dark:border-slate-700'
                        ]">
                        {{ ratePageStartIndex + idx + 1 }}
                      </div>
                      <span v-if="entry.rankChange === null" class="text-[10px] font-bold text-blue-500">NEW</span>
                      <span v-else-if="entry.rankChange > 0" class="text-[10px] font-bold text-emerald-500">▲{{ entry.rankChange }}</span>
                      <span v-else-if="entry.rankChange < 0" class="text-[10px] font-bold text-red-500">▼{{ Math.abs(entry.rankChange) }}</span>
                      <span v-else class="text-[10px] font-bold text-slate-300 dark:text-slate-600">-</span>
                    </div>
                  </td>
                  <td class="py-3">
                    <div class="flex items-center gap-2">
                      <span class="font-bold text-base transition-colors"
                        :class="user && entry.iidxId === user.iidxId
                          ? 'text-emerald-700 dark:text-emerald-300'
                          : 'text-slate-800 dark:text-slate-100 group-hover:text-emerald-600 dark:group-hover:text-emerald-400'">
                        {{ entry.displayName || 'Unnamed Player' }}
                      </span>
                      <span v-if="user && entry.iidxId === user.iidxId"
                        class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-emerald-500 text-white">{{ t('ranking.you') }}</span>
                    </div>
                  </td>
                  <td class="py-3 px-2 text-center">
                    <div class="flex justify-center">
                      <RankIcon :rank-name="getRateTierRankInfo(entry.totalRatePt).name" :tier="getRateTierRankInfo(entry.totalRatePt).tier" size="md" disable-party :is-supporter="entry.isSupporter" />
                    </div>
                  </td>
                  <td class="py-3 text-right">
                    <div class="flex items-baseline justify-end gap-1">
                      <span class="text-xl font-black tabular-nums"
                        :class="user && entry.iidxId === user.iidxId ? 'text-emerald-700 dark:text-emerald-300' : 'text-slate-800 dark:text-slate-100'">
                        {{ entry.totalRatePt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                      </span>
                      <span class="text-[9px] font-bold text-emerald-500 uppercase tracking-widest">RATE-PT</span>
                    </div>
                  </td>
                  <td class="py-3 text-right pr-4">
                    <span class="text-xs font-medium tabular-nums"
                      :class="formatLastUpdated(entry.lastUpdatedAt) === t('common.today') ? 'text-emerald-600 dark:text-emerald-400' : 'text-slate-400 dark:text-slate-500'">
                      {{ formatLastUpdated(entry.lastUpdatedAt) }}
                    </span>
                  </td>
                </tr>
              </tbody>
            </table>
            <!-- Rate Pagination -->
            <div v-if="rateTotalPages > 1" class="flex items-center justify-center gap-2 mt-6 pt-4 border-t border-slate-100 dark:border-slate-700/50">
              <button @click="ratePage = 1" :disabled="ratePage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &laquo;
              </button>
              <button @click="ratePage--" :disabled="ratePage === 1"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &lsaquo;
              </button>
              <span class="text-xs font-bold text-slate-500 dark:text-slate-400 px-2 tabular-nums">
                {{ ratePage }} / {{ rateTotalPages }}
              </span>
              <button @click="ratePage++" :disabled="ratePage === rateTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &rsaquo;
              </button>
              <button @click="ratePage = rateTotalPages" :disabled="ratePage === rateTotalPages"
                class="px-2 py-1 rounded-lg text-xs font-bold transition-colors disabled:opacity-30 disabled:cursor-not-allowed text-slate-500 hover:bg-slate-100 dark:hover:bg-slate-700">
                &raquo;
              </button>
            </div>
          </div>
        </div>
        <!-- Simulation tab (admin only) -->
        <div v-if="viewMode === 'simulation' && isAdmin">
          <!-- Draft changes summary -->
          <div v-if="draftDiffChanges.length > 0" class="mb-4 space-y-2">
            <div v-if="promotionChanges.length > 0" class="p-3 bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800/50 rounded-xl">
              <p class="text-xs font-bold text-emerald-700 dark:text-emerald-400 mb-2">▲ 昇格 ({{ promotionChanges.length }}件)</p>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="c in promotionChanges" :key="c.title"
                  class="text-[11px] px-2 py-0.5 bg-white dark:bg-slate-800 border border-emerald-200 dark:border-emerald-700 rounded-full text-slate-700 dark:text-slate-300">
                  {{ c.title.length > 20 ? c.title.slice(0, 18) + '…' : c.title }}
                  <span class="line-through text-slate-400">{{ c.oldRank }}</span>→<span class="font-bold text-emerald-600 dark:text-emerald-400">{{ c.newRank }}</span>
                </span>
              </div>
            </div>
            <div v-if="demotionChanges.length > 0" class="p-3 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800/50 rounded-xl">
              <p class="text-xs font-bold text-red-700 dark:text-red-400 mb-2">▼ 降格 ({{ demotionChanges.length }}件)</p>
              <div class="flex flex-wrap gap-1.5">
                <span v-for="c in demotionChanges" :key="c.title"
                  class="text-[11px] px-2 py-0.5 bg-white dark:bg-slate-800 border border-red-200 dark:border-red-700 rounded-full text-slate-700 dark:text-slate-300">
                  {{ c.title.length > 20 ? c.title.slice(0, 18) + '…' : c.title }}
                  <span class="line-through text-slate-400">{{ c.oldRank }}</span>→<span class="font-bold text-red-600 dark:text-red-400">{{ c.newRank }}</span>
                </span>
              </div>
            </div>
          </div>
          <div v-else-if="!isSimulationLoading && !simulationError" class="mb-4 p-3 bg-slate-50 dark:bg-slate-800/50 border border-slate-200 dark:border-slate-700 rounded-xl text-xs text-slate-500 dark:text-slate-400">
            難易度表のドラフト変更がありません。
          </div>

          <div v-if="isSimulationLoading" class="flex flex-col items-center justify-center py-20">
            <div class="w-12 h-12 border-4 border-amber-100 dark:border-slate-700 border-t-amber-500 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 dark:text-slate-400 font-bold">シミュレーション計算中...</p>
          </div>
          <div v-else-if="simulationError" class="p-6 bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400 rounded-2xl text-center font-bold">
            {{ simulationError }}
          </div>
          <div v-else class="overflow-x-auto">
            <table class="w-full text-sm">
              <thead>
                <tr class="text-left border-b border-slate-100 dark:border-slate-700/50">
                  <th class="pb-3 pl-4 text-xs font-black text-slate-400 uppercase tracking-widest w-24">順位変動</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest">プレイヤー</th>
                  <th class="pb-3 text-xs font-black text-slate-400 uppercase tracking-widest text-right">現在 BEAT-PT</th>
                  <th class="pb-3 text-xs font-black text-amber-500 uppercase tracking-widest text-right pr-4">適用後 BEAT-PT</th>
                </tr>
              </thead>
              <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
                <tr v-for="entry in simulationData" :key="entry.iidxId"
                  class="group transition-colors hover:bg-slate-50 dark:hover:bg-slate-700/30">
                  <td class="py-3 pl-4">
                    <div class="flex items-center gap-2">
                      <div class="flex items-center justify-center w-7 h-7 rounded-lg font-black text-xs"
                        :class="entry.iidxId === user?.iidxId
                          ? 'bg-blue-500 text-white'
                          : 'text-slate-400 border border-slate-100 dark:border-slate-700'">
                        {{ entry.simulatedRank }}
                      </div>
                      <span v-if="entry.rankDelta > 0" class="text-[10px] font-bold text-emerald-500">▲{{ entry.rankDelta }}</span>
                      <span v-else-if="entry.rankDelta < 0" class="text-[10px] font-bold text-red-500">▼{{ Math.abs(entry.rankDelta) }}</span>
                      <span v-else class="text-[10px] font-bold text-slate-300 dark:text-slate-600">-</span>
                    </div>
                  </td>
                  <td class="py-3">
                    <div class="flex items-center gap-2">
                      <span class="font-bold text-sm text-slate-800 dark:text-slate-100">{{ entry.displayName || 'Unnamed' }}</span>
                      <span v-if="entry.iidxId === user?.iidxId"
                        class="text-[9px] font-black uppercase tracking-wider px-1.5 py-0.5 rounded bg-blue-500 text-white">YOU</span>
                    </div>
                    <div class="text-[10px] text-slate-400 mt-0.5">{{ entry.iidxId }} / 現在{{ entry.currentRank }}位</div>
                  </td>
                  <td class="py-3 text-right">
                    <span class="text-base font-bold tabular-nums text-slate-500 dark:text-slate-400">
                      {{ entry.currentBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                    </span>
                  </td>
                  <td class="py-3 text-right pr-4">
                    <div class="flex flex-col items-end">
                      <span class="text-base font-black tabular-nums text-amber-600 dark:text-amber-400">
                        {{ entry.simulatedBeatPt.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                      </span>
                      <span class="text-[10px] font-bold tabular-nums"
                        :class="entry.ptDelta > 0 ? 'text-emerald-500' : entry.ptDelta < 0 ? 'text-red-500' : 'text-slate-400'">
                        {{ entry.ptDelta > 0 ? '+' : '' }}{{ entry.ptDelta.toLocaleString(undefined, { minimumFractionDigits: 1, maximumFractionDigits: 1 }) }}
                      </span>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
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
