<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useI18n } from '../composables/useI18n';
import { getRankInfo, getRateTierRankInfo } from '../utils/beatTier';

const { t, currentLang } = useI18n();
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
const groupByDay = ref(false);

const selectedDiff = ref<UploadDiffResult | null>(null);
const isModalOpen = ref(false);

const getDateKey = (dateStr: string) => {
  const zDateStr = dateStr.endsWith('Z') ? dateStr : `${dateStr}Z`;
  const d = new Date(zDateStr);
  const locale = currentLang.value === 'ko' ? 'ko-KR' : (currentLang.value === 'en' ? 'en-US' : 'ja-JP');
  return d.toLocaleDateString(locale, { timeZone: 'Asia/Tokyo', year: 'numeric', month: '2-digit', day: '2-digit' });
};

/** 同日エントリをまとめたリストを返す */
const groupedList = computed(() => {
  if (!groupByDay.value) return historyList.value;

  const dayMap = new Map<string, any[]>();
  for (const item of historyList.value) {
    const key = getDateKey(item.date);
    if (!dayMap.has(key)) dayMap.set(key, []);
    dayMap.get(key)!.push(item);
  }

  return Array.from(dayMap.entries()).map(([dateKey, items]) => {
    // items は降順ソート済み (fetchHistory で sortedData)
    const latest = items[0];
    // 当日の最初アップロード前の totalBeatPt = 最後の item の (totalBeatPt - beatPtIncrease)
    // 複数ある場合: 前日最終エントリの totalBeatPt が基準
    const dayBeforeLast = historyList.value[historyList.value.indexOf(items[items.length - 1]) + 1];
    const prevBeatPt = dayBeforeLast ? (dayBeforeLast.totalBeatPt || 0) : 0;
    const prevRatePt = dayBeforeLast ? (dayBeforeLast.totalRatePt || 0) : 0;

    // 当日の更新曲を全てまとめる (diffJson をマージ、title+difficulty で重複排除)
    const songMap = new Map<string, any>();
    for (const item of [...items].reverse()) {
      if (!item.diffJson || item.diffJson === '[]') continue;
      try {
        const songs = JSON.parse(item.diffJson);
        for (const s of songs) {
          const key = `${s.title}_${s.difficulty || s.difficultyName}`;
          // 後から上書き → より新しいスコアが残る
          songMap.set(key, s);
        }
      } catch (_) { /* ignore */ }
    }
    const mergedSongs = Array.from(songMap.values());

    return {
      ...latest,
      _isGrouped: true,
      _dateKey: dateKey,
      _itemCount: items.length,
      updatedCount: items.reduce((sum: number, i: any) => sum + (i.updatedCount || 0), 0),
      beatPtIncrease: latest.totalBeatPt - prevBeatPt,
      ratePtIncrease: latest.totalRatePt - prevRatePt,
      _mergedDiffJson: JSON.stringify(mergedSongs),
    };
  });
});

const openDiffModal = (item: any) => {
  const diffJson = item._isGrouped ? item._mergedDiffJson : item.diffJson;
  if (!diffJson || diffJson === '[]') return;

  try {
    const updatedSongs = JSON.parse(diffJson);
    const oldTotal = Math.max(0, item.totalBeatPt - item.beatPtIncrease);

    selectedDiff.value = {
      oldTotalBeatPt: oldTotal,
      newTotalBeatPt: item.totalBeatPt,
      totalBeatPtIncrease: item.beatPtIncrease,
      oldTier: getRankInfo(oldTotal),
      newTier: getRankInfo(item.totalBeatPt),
      updatedSongs: updatedSongs,
      oldTotalRatePt: Math.max(0, item.totalRatePt - item.ratePtIncrease),
      newTotalRatePt: item.totalRatePt,
      oldRateTier: getRateTierRankInfo(Math.max(0, item.totalRatePt - item.ratePtIncrease)),
      newRateTier: getRateTierRankInfo(item.totalRatePt)
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

    if (!res.ok) throw new Error(t('history.error'));
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
      const rateTierInfo = getRateTierRankInfo(ratePt);
      return {
        ...item,
        totalBeatPt: beatPt,
        totalRatePt: ratePt,
        beatPtIncrease: calcBeatPtIncrease,
        ratePtIncrease: calcRatePtIncrease,
        tierInfo: tierInfo,
        rateTierInfo: rateTierInfo
      };
    }).filter((item: any) => {
      if ((item.updatedCount || 0) > 0) return true;
      // updatedCount=0 でも BEAT-PT か RATE-PT が変動していれば難易度改訂として表示
      return Math.abs(item.beatPtIncrease) >= 0.1 || Math.abs(item.ratePtIncrease) >= 0.1;
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
  const locale = currentLang.value === 'ko' ? 'ko-KR' : (currentLang.value === 'en' ? 'en-US' : 'ja-JP');
  return d.toLocaleString(locale, {
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
        {{ t('history.title') }}
      </h2>
      <div class="flex items-center gap-2">
        <button
          @click="groupByDay = !groupByDay"
          :class="groupByDay ? 'bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300 border-indigo-300 dark:border-indigo-600' : 'bg-slate-50 dark:bg-slate-700 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600'"
          class="px-3 py-1.5 text-xs font-semibold border rounded-lg transition-colors focus:outline-none"
          :title="t('history.groupByDayHint')"
        >{{ t('history.groupByDay') }}</button>
        <button @click="fetchHistory" class="p-2 text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200 bg-slate-50 dark:bg-slate-700 hover:bg-slate-100 dark:hover:bg-slate-600 rounded-lg transition-colors focus:outline-none" :title="t('history.refresh')">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M4 2a1 1 0 011 1v2.101a7.002 7.002 0 0111.601 2.566 1 1 0 11-1.885.666A5.002 5.002 0 005.999 7H9a1 1 0 010 2H4a1 1 0 01-1-1V3a1 1 0 011-1zm.008 9.057a1 1 0 011.276.61A5.002 5.002 0 0014.001 13H11a1 1 0 110-2h5a1 1 0 011 1v5a1 1 0 11-2 0v-2.101a7.002 7.002 0 01-11.601-2.566 1 1 0 01.61-1.276z" clip-rule="evenodd" />
          </svg>
        </button>
      </div>
    </div>

    <div v-if="isLoading" class="py-12 flex justify-center">
      <div class="w-8 h-8 border-4 border-slate-200 dark:border-slate-700 border-t-indigo-600 dark:border-t-indigo-500 rounded-full animate-spin"></div>
    </div>

    <div v-else-if="errorMsg" class="py-8 text-center text-red-500 dark:text-red-400">
      {{ errorMsg }}
    </div>

    <div v-else-if="historyList.length === 0" class="py-12 text-center text-slate-500 dark:text-slate-400">
      {{ t('history.empty') }}
    </div>

    <div v-else class="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <table class="w-full text-left border-collapse whitespace-nowrap">
        <thead>
          <tr class="bg-slate-50 dark:bg-slate-800/80 border-b border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-300 text-sm transition-colors duration-200">
            <th class="p-4 font-semibold text-center w-16">{{ t('history.colTier') }}</th>
            <th class="p-4 font-semibold text-center">{{ t('history.colDate') }}</th>
            <th class="p-4 font-semibold text-center">{{ t('history.colType') }}</th>
            <th class="p-4 font-semibold text-center w-36">{{ t('history.colBeatPt') }}</th>
            <th v-if="showRateTier" class="p-4 font-semibold text-center w-36">{{ t('history.colRatePt') }}</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-sm text-slate-700 dark:text-slate-200 transition-colors duration-200">
          <tr
            v-for="item in groupedList"
            :key="item._isGrouped ? item._dateKey : item.snapshotId"
            class="hover:bg-slate-50/50 dark:hover:bg-slate-700/50 transition-colors group"
            :class="(item._isGrouped ? item._mergedDiffJson !== '[]' : (item.diffJson && item.diffJson !== '[]')) ? 'cursor-pointer' : ''"
            @click="openDiffModal(item)"
          >
            <!-- Beat-Tier Icon (+ Rate-Tier Icon) -->
            <td class="p-4 text-center align-middle">
              <div class="flex justify-center items-center gap-1">
                <RankIcon
                  :rankName="item.tierInfo?.name || 'Unranked'"
                  :tier="item.tierInfo?.tier"
                  size="md"
                  class="shrink-0 drop-shadow-sm"
                />
                <RankIcon
                  v-if="showRateTier"
                  :rankName="item.rateTierInfo?.name || 'Unranked'"
                  :tier="item.rateTierInfo?.tier"
                  size="md"
                  class="shrink-0 drop-shadow-sm"
                />
              </div>
            </td>

            <!-- Upload Date -->
            <td class="p-4 font-medium text-slate-800 dark:text-slate-100 text-center align-middle">
              <template v-if="item._isGrouped">
                {{ item._dateKey }}
                <span class="ml-1 text-xs text-slate-400 dark:text-slate-500">{{ t('history.times', { n: item._itemCount }) }}</span>
              </template>
              <template v-else>
                {{ formatDate(item.date) }}
              </template>
            </td>

            <!-- Updated Count -->
            <td class="p-4 text-center align-middle font-black">
              <span v-if="item.updatedCount > 0" class="px-3 py-1 bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-400 rounded-full text-base">
                {{ item.updatedCount }} {{ t('history.unitSongs') }}
              </span>
              <span v-else class="px-3 py-1 bg-amber-100 dark:bg-amber-900/40 text-amber-700 dark:text-amber-400 rounded-full text-sm">
                {{ t('history.revision') }}
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
