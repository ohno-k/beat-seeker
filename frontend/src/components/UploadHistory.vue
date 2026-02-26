<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';

const { isLoggedIn } = useAuth();
const historyList = ref<any[]>([]);
const isLoading = ref(false);
const errorMsg = ref('');

const fetchHistory = async () => {
  if (!isLoggedIn.value) return;
  
  isLoading.value = true;
  errorMsg.value = '';
  
  try {
    const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';
    const res = await fetch(`${API_BASE}/api/scores/history`, { credentials: 'login' === 'login' ? 'include' : 'include' });
    
    if (!res.ok) throw new Error('履歴の取得に失敗しました');
    const data = await res.json();
    
    // Sort descending by date
    const sortedData = data.sort((a: any, b: any) => new Date(b.date).getTime() - new Date(a.date).getTime());
    
    // Calculate differences from the previous upload
    for (let i = 0; i < sortedData.length; i++) {
      const current = sortedData[i];
      const previous = i < sortedData.length - 1 ? sortedData[i + 1] : null;

      current.diffs = {
        totalScore: previous ? current.totalScore - previous.totalScore : 0,
        fcCount: previous ? current.fcCount - previous.fcCount : 0,
        exhCount: previous ? current.exhCount - previous.exhCount : 0,
        aaaCount: previous ? current.aaaCount - previous.aaaCount : 0,
        aaCount: previous ? current.aaCount - previous.aaCount : 0,
        aCount: previous ? current.aCount - previous.aCount : 0,
      };
    }
    
    historyList.value = sortedData;
  } catch (err: any) {
    errorMsg.value = err.message;
  } finally {
    isLoading.value = false;
  }
};

const deleteSnapshot = async (snapshotId: string) => {
  if (!confirm('このアップロード履歴を削除してもよろしいですか？\n※復元することはできません。')) {
    return;
  }
  
  try {
    const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080';
    const res = await fetch(`${API_BASE}/api/scores/snapshot/${snapshotId}`, {
      method: 'DELETE',
      credentials: 'include'
    });
    
    if (!res.ok) throw new Error('削除に失敗しました');
    
    // Refresh the list after successful deletion
    await fetchHistory();
  } catch (err: any) {
    alert(err.message);
  }
};

const formatDate = (dateStr: string) => {
  // Add 'Z' to treat as UTC if the server returns no timezone info,
  // then format it into JST
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
            <th class="p-4 font-semibold">アップロード日時</th>
            <th class="p-4 font-semibold text-right">EX SCORE 合計</th>
            <th class="p-4 font-semibold text-center">FULLCOMBO</th>
            <th class="p-4 font-semibold text-center">EX HARD</th>
            <th class="p-4 font-semibold text-center">AAA/AA/A</th>
            <th class="p-4 font-semibold text-right">操作</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50 text-sm text-slate-700 dark:text-slate-200 transition-colors duration-200">
          <tr v-for="(item, index) in historyList" :key="item.snapshotId" class="hover:bg-slate-50/50 dark:hover:bg-slate-700/50 transition-colors group">
            <td class="p-4 font-medium text-slate-800 dark:text-slate-100">{{ formatDate(item.date) }}</td>
            <td class="p-4 text-right font-mono">
              <div>{{ item.totalScore.toLocaleString() }}</div>
              <div v-if="index < historyList.length - 1" :class="item.diffs.totalScore >= 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-red-500 dark:text-red-400'" class="text-xs font-bold transition-colors">
                {{ item.diffs.totalScore >= 0 ? '+' : '' }}{{ item.diffs.totalScore.toLocaleString() }}
              </div>
            </td>
            <td class="p-4 text-center">
              <span class="px-2 py-0.5 bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-400 rounded-md font-bold text-xs transition-colors">{{ item.fcCount }}</span>
              <div v-if="index < historyList.length - 1" :class="item.diffs.fcCount >= 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-red-500 dark:text-red-400'" class="text-xs font-bold mt-1 transition-colors">
                {{ item.diffs.fcCount >= 0 ? '+' : '' }}{{ item.diffs.fcCount }}
              </div>
            </td>
            <td class="p-4 text-center">
              <span class="px-2 py-0.5 text-yellow-500 dark:text-yellow-400 font-bold border border-yellow-200 dark:border-yellow-700/50 bg-yellow-50 dark:bg-yellow-900/30 rounded-md text-xs transition-colors">{{ item.exhCount }}</span>
              <div v-if="index < historyList.length - 1" :class="item.diffs.exhCount >= 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-red-500 dark:text-red-400'" class="text-xs font-bold mt-1 transition-colors">
                {{ item.diffs.exhCount >= 0 ? '+' : '' }}{{ item.diffs.exhCount }}
              </div>
            </td>
            <td class="p-4 text-center text-xs">
              <div class="mb-1">
                <span class="text-slate-500 dark:text-slate-400 font-bold">AAA:</span> {{ item.aaaCount }} / 
                <span class="text-slate-400 dark:text-slate-500 font-bold">AA:</span> {{ item.aaCount }} / 
                <span class="text-slate-400 dark:text-slate-500 font-bold">A:</span> {{ item.aCount }}
              </div>
              <div v-if="index < historyList.length - 1" class="space-x-2">
                <span :class="item.diffs.aaaCount >= 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-red-500 dark:text-red-400'" class="font-bold transition-colors">
                  {{ item.diffs.aaaCount >= 0 ? '+' : '' }}{{ item.diffs.aaaCount }}
                </span>
                <span :class="item.diffs.aaCount >= 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-red-500 dark:text-red-400'" class="font-bold transition-colors">
                  {{ item.diffs.aaCount >= 0 ? '+' : '' }}{{ item.diffs.aaCount }}
                </span>
                <span :class="item.diffs.aCount >= 0 ? 'text-emerald-500 dark:text-emerald-400' : 'text-red-500 dark:text-red-400'" class="font-bold transition-colors">
                  {{ item.diffs.aCount >= 0 ? '+' : '' }}{{ item.diffs.aCount }}
                </span>
              </div>
            </td>
            <td class="p-4 text-right">
              <button @click="deleteSnapshot(item.snapshotId)" 
                class="px-3 py-1.5 bg-red-50 dark:bg-red-900/20 hover:bg-red-500 dark:hover:bg-red-600 text-red-600 dark:text-red-400 hover:text-white dark:hover:text-white rounded-lg text-xs font-semibold transition-colors duration-200 border border-red-100 dark:border-red-900/50 hover:border-red-500 dark:hover:border-red-600 outline-none focus:ring-2 focus:ring-red-200 dark:focus:ring-red-900">
                削除
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
