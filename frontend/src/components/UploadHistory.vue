<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';
import { getRankInfo } from '../utils/beatTier';
import UploadResultModal from './UploadResultModal.vue';
import RankIcon from './RankIcon.vue';
import type { UploadDiffResult } from '../types/UploadDiff';
import { useRateTierVisibility } from '../composables/useRateTierVisibility';

const { isLoggedIn, authHeaders } = useAuth();
const { showRateTier } = useRateTierVisibility();
const props = defineProps<{
  viewingUserId?: number | null;
}>();

const historyList = ref<any[]>([]);
const isLoading = ref(false);
const errorMsg = ref('');

const selectedDiff = ref<UploadDiffResult | null>(null);
const isModalOpen = ref(false);

const openDiffModal = (item: any) => {
  if (!item.diffJson || item.diffJson === '[]') return;

  try {
    const updatedSongs = JSON.parse(item.diffJson);
    const oldTotal = Math.max(0, item.totalBeatPt - item.beatPtIncrease);

    selectedDiff.value = {
      oldTotalBeatPt: oldTotal,
      newTotalBeatPt: item.totalBeatPt,
      totalBeatPtIncrease: item.beatPtIncrease,
      oldTier: getRankInfo(oldTotal),
      newTier: getRankInfo(item.totalBeatPt),
      updatedSongs: updatedSongs,
      oldTotalRatePt: 0,
      newTotalRatePt: 0,
      oldRateTier: null,
      newRateTier: null
    };
    isModalOpen.value = true;
  } catch (err) {
    console.error('Failed to parse diffJson', err);
  }
};

const fetchHistory = async () => {
  if (!isLoggedIn.value) return;

  isLoading.value = true;
  errorMsg.value = '';

  try {
    const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
    const endpoint = props.viewingUserId
        ? `${API_BASE}/api/admin/users/${props.viewingUserId}/history`
        : `${API_BASE}/api/scores/history`;

    const res = await fetch(endpoint, {
        headers: authHeaders()
    });

    if (!res.ok) throw new Error('履歴の取得に失敗しました');
    const data = await res.json();

    // Sort descending by date
    const sortedData = data.sort((a: any, b: any) => new Date(b.date).getTime() - new Date(a.date).getTime());

    historyList.value = sortedData.map((item: any, idx: number) => {
      const beatPt = item.totalBeatPt || 0;
      const ratePt = item.totalRatePt || 0;
      
      const prevItem = idx < sortedData.length - 1 ? sortedData[idx + 1] : null;
      let calcBeatPtIncrease = item.beatPtIncrease || 0;
      let calcRatePtIncrease = 0;

      if (item.updatedCount === 0) {
          // System recalculation (difficulty revision)
          if (prevItem) {
              calcBeatPtIncrease = beatPt - (prevItem.totalBeatPt || 0);
              calcRatePtIncrease = ratePt - (prevItem.totalRatePt || 0);
          } else {
              calcBeatPtIncrease = beatPt;
              calcRatePtIncrease = ratePt;
          }
      } else {
          // Normal upload
          if (prevItem) {
              calcRatePtIncrease = ratePt - (prevItem.totalRatePt || 0);
          } else {
              calcRatePtIncrease = ratePt;
          }
      }

      const tierInfo = getRankInfo(beatPt);
      return {
        ...item,
        totalBeatPt: beatPt,
        totalRatePt: ratePt,
        beatPtIncrease: calcBeatPtIncrease,
        ratePtIncrease: calcRatePtIncrease,
        tierInfo: tierInfo
      };
    });
  } catch (err: any) {
    errorMsg.value = err.message;
  } finally {
    isLoading.value = false;
  }
};

const formatDate = (dateStr: string) => {
  const zDateStr = dateStr.endsWith('Z') ? dateStr : `${dateStr}Z`;
  const d = new Date(zDateStr);
  return d.toLocaleString('ja-JP', {
    timeZone: 'Asia/Tokyo',
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  });
};

onMounted(() => {
  fetchHistory();
});
</script>

<template>
  <div class="w-full max-w-6xl animate-fade-in bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-100 dark:border-slate-700 transition-colors duration-200">
    <div class="flex items-center justify-between mb-6">
      <h2 class="text-xl font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-500 dark:text-indigo-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        アップロード履歴
      </h2>
      <button @click="fetchHistory" class="p-2 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 bg-slate-50 dark:bg-slate-700 hover:bg-slate-100 dark:hover:bg-slate-600 rounded-lg transition-colors focus:outline-none" title="更新">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z" clip-rule="evenodd" />
        </svg>
      </button>
    </div>

    <div v-if="isLoading" class="py-12 flex justify-center">
      <div class="w-8 h-8 border-4 border-slate-200 dark:border-slate-700 border-t-indigo-600 dark:border-t-indigo-500 rounded-full animate-spin"></div>
    </div>

    <div v-else-if="errorMsg" class="py-8 text-center text-red-500 dark:text-red-400">
      {{ errorMsg }}
    </div>

    <div v-else-if="historyList.length === 0" class="py-12 text-center text-slate-500 dark:text-slate-400">
      履歴データがありません
    </div>

    <div v-else class="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <table class="w-full text-left border-collapse whitespace-nowrap">
        <thead>
          <tr class="bg-slate-50 dark:bg-slate-800/80 border-b border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-sm transition-colors duration-200">
            <th class="p-4 font-semibold text-center w-16">BEAT-TIER</th>
            <th class="p-4 font-semibold text-center">アップロード日時</th>
            <th class="p-4 font-semibold text-center">更新種別</th>
            <th class="p-4 font-semibold text-center w-36">BEAT-PT</th>
            <th v-if="showRateTier" class="p-4 font-semibold text-center w-36">RATE-PT</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-sm text-slate-700 dark:text-slate-200 transition-colors duration-200">
          <tr
            v-for="item in historyList"
            :key="item.snapshotId"
            class="hover:bg-slate-50/50 dark:hover:bg-slate-700/50 transition-colors group"
            :class="item.diffJson && item.diffJson !== '[]' ? 'cursor-pointer' : ''"
            @click="openDiffModal(item)"
          >
            <!-- Beat-Tier Icon -->
            <td class="p-4 text-center align-middle">
              <div class="flex justify-center translate-x-2">
                <RankIcon
                  :rankName="item.tierInfo?.name || 'Unranked'"
                  :tier="item.tierInfo?.tier"
                  size="md"
                  class="shrink-0 drop-shadow-sm"
                />
              </div>
            </td>

            <!-- Upload Date -->
            <td class="p-4 font-medium text-slate-800 dark:text-slate-100 text-center align-middle">
              {{ formatDate(item.date) }}
            </td>

            <!-- Updated Count -->
            <td class="p-4 text-center align-middle font-black">
              <span v-if="item.updatedCount > 0" class="px-3 py-1 bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-400 rounded-full text-base">
                {{ item.updatedCount }} 曲
              </span>
              <span v-else class="px-3 py-1 bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-400 rounded-full text-sm">
                難易度改訂
              </span>
            </td>

            <!-- Beat-PT -->
            <td class="p-4 text-center align-middle w-36">
              <div class="font-black text-slate-700 dark:text-slate-200 text-lg">
                {{ item.totalBeatPt.toFixed(1) }} <span class="text-xs font-bold text-slate-400">pt</span>
              </div>
              <div v-if="item.beatPtIncrease >= 0.1" class="text-sm font-bold text-indigo-500 dark:text-indigo-400 mt-0.5">
                +{{ item.beatPtIncrease.toFixed(1) }} pt
              </div>
              <div v-else-if="item.beatPtIncrease <= -0.1" class="text-sm font-bold text-red-500 dark:text-red-400 mt-0.5">
                {{ item.beatPtIncrease.toFixed(1) }} pt
              </div>
            </td>

            <!-- Rate-PT -->
            <td v-if="showRateTier" class="p-4 text-center align-middle w-36">
              <div class="font-black text-slate-700 dark:text-slate-200 text-lg">
                {{ item.totalRatePt.toFixed(1) }} <span class="text-xs font-bold text-slate-400">pt</span>
              </div>
              <div v-if="item.ratePtIncrease >= 0.1" class="text-sm font-bold text-indigo-500 dark:text-indigo-400 mt-0.5">
                +{{ item.ratePtIncrease.toFixed(1) }} pt
              </div>
              <div v-else-if="item.ratePtIncrease <= -0.1" class="text-sm font-bold text-red-500 dark:text-red-400 mt-0.5">
                {{ item.ratePtIncrease.toFixed(1) }} pt
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Upload Result Diff Modal -->
    <UploadResultModal
      :is-open="isModalOpen"
      :diff-data="selectedDiff"
      @close="isModalOpen = false"
    />
  </div>
</template>
