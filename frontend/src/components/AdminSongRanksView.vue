<template>
  <div class="w-full max-w-5xl mx-auto px-4 py-6 animate-fade-in">
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-xl font-black text-slate-800 dark:text-white">曲別順位</h1>
      <button
        @click="load"
        :disabled="isLoading"
        class="flex items-center gap-2 px-4 py-2 text-sm font-bold bg-amber-500 hover:bg-amber-600 text-white rounded-xl transition-colors disabled:opacity-50"
      >
        <svg v-if="isLoading" class="w-4 h-4 animate-spin" fill="none" viewBox="0 0 24 24">
          <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
          <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8z"/>
        </svg>
        <svg v-else xmlns="http://www.w3.org/2000/svg" class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15" />
        </svg>
        更新
      </button>
    </div>

    <!-- Stats summary -->
    <div v-if="rows.length > 0" class="grid grid-cols-3 gap-3 mb-4">
      <div class="bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-100 dark:border-slate-700 text-center">
        <div class="text-2xl font-black text-slate-800 dark:text-white">{{ filteredRows.length }}</div>
        <div class="text-xs text-slate-500 dark:text-slate-400 mt-1">登録曲数</div>
      </div>
      <div class="bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-100 dark:border-slate-700 text-center">
        <div class="text-2xl font-black text-green-600 dark:text-green-400">{{ top3Count }}</div>
        <div class="text-xs text-slate-500 dark:text-slate-400 mt-1">3位以内</div>
      </div>
      <div class="bg-white dark:bg-slate-800 rounded-xl p-4 border border-slate-100 dark:border-slate-700 text-center">
        <div class="text-2xl font-black text-amber-500">{{ firstPlaceCount }}</div>
        <div class="text-xs text-slate-500 dark:text-slate-400 mt-1">1位</div>
      </div>
    </div>

    <!-- Filters -->
    <div v-if="rows.length > 0" class="flex flex-wrap gap-3 mb-4">
      <label class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showLv11" class="w-4 h-4 rounded accent-amber-500" />
        <span class="text-sm font-bold text-slate-700 dark:text-slate-300">Lv.11</span>
      </label>
      <label class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showLv12" class="w-4 h-4 rounded accent-amber-500" />
        <span class="text-sm font-bold text-slate-700 dark:text-slate-300">Lv.12</span>
      </label>
      <div class="w-px bg-slate-200 dark:bg-slate-700 mx-1"></div>
      <label class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showAnother" class="w-4 h-4 rounded accent-red-500" />
        <span class="text-sm font-bold text-red-600 dark:text-red-400">ANOTHER</span>
      </label>
      <label class="flex items-center gap-2 cursor-pointer select-none">
        <input type="checkbox" v-model="showLeggendaria" class="w-4 h-4 rounded accent-purple-500" />
        <span class="text-sm font-bold text-purple-600 dark:text-purple-400">LEGGENDARIA</span>
      </label>
    </div>

    <!-- Loading -->
    <div v-if="isLoading" class="flex justify-center py-20">
      <div class="w-10 h-10 border-4 border-amber-100 border-t-amber-500 rounded-full animate-spin"></div>
    </div>

    <!-- Empty -->
    <div v-else-if="rows.length === 0 && loaded" class="text-center py-20 text-slate-400 dark:text-slate-500">
      データがありません
    </div>

    <!-- Table -->
    <div v-else-if="filteredRows.length > 0" class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-100 dark:border-slate-700 overflow-hidden">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/30">
            <th class="py-3 px-4 text-left font-bold text-slate-500 dark:text-slate-400 w-16">順位</th>
            <th class="py-3 px-4 text-left font-bold text-slate-500 dark:text-slate-400">曲名</th>
            <th class="py-3 px-4 text-center font-bold text-slate-500 dark:text-slate-400 w-32">難易度</th>
            <th class="py-3 px-4 text-right font-bold text-slate-500 dark:text-slate-400 w-32">順位 / 人数</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-slate-50 dark:divide-slate-700/30">
          <tr
            v-for="row in filteredRows"
            :key="`${row.title}_${row.difficultyName}`"
            class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
          >
            <!-- Rank badge -->
            <td class="py-3 px-4">
              <span
                class="inline-flex items-center justify-center w-8 h-8 rounded-full text-sm font-black"
                :class="rankBadgeClass(row.rank)"
              >{{ row.rank }}</span>
            </td>
            <!-- Title -->
            <td class="py-3 px-4">
              <div class="font-semibold text-slate-800 dark:text-white leading-tight">{{ row.title }}</div>
            </td>
            <!-- Difficulty -->
            <td class="py-3 px-4 text-center">
              <span class="inline-block px-2 py-0.5 rounded text-xs font-bold" :class="diffClass(row.difficultyName)">
                Lv.{{ row.difficultyLevel }}
              </span>
              <span class="ml-1 inline-block px-2 py-0.5 rounded text-xs font-bold" :class="diffClass(row.difficultyName)">
                {{ row.difficultyName }}
              </span>
            </td>
            <!-- Rank / Total -->
            <td class="py-3 px-4 text-right font-mono font-bold text-slate-700 dark:text-slate-200">
              {{ row.rank }} <span class="text-xs font-normal text-slate-400 dark:text-slate-500">/ {{ row.total }}人</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Filtered empty -->
    <div v-else-if="rows.length > 0 && filteredRows.length === 0" class="text-center py-20 text-slate-400 dark:text-slate-500">
      条件に一致する曲がありません
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { useAuth } from '../composables/useAuth';

interface SongRankRow {
  title: string;
  difficultyName: string;
  difficultyLevel: number;
  rank: number;
  total: number;
}

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';
const { authHeaders } = useAuth();

const rows = ref<SongRankRow[]>([]);
const isLoading = ref(false);
const loaded = ref(false);

const showLv11 = ref(true);
const showLv12 = ref(true);
const showAnother = ref(true);
const showLeggendaria = ref(true);

const filteredRows = computed(() =>
  rows.value.filter(r =>
    (r.difficultyLevel === 11 ? showLv11.value : true) &&
    (r.difficultyLevel === 12 ? showLv12.value : true) &&
    (r.difficultyName === 'ANOTHER' ? showAnother.value : true) &&
    (r.difficultyName === 'LEGGENDARIA' ? showLeggendaria.value : true)
  )
);

const firstPlaceCount = computed(() => filteredRows.value.filter(r => r.rank === 1).length);
const top3Count = computed(() => filteredRows.value.filter(r => r.rank <= 3).length);

const load = async () => {
  isLoading.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/scores/admin-song-ranks`, { headers: authHeaders() });
    if (res.ok) {
      const data = await res.json();
      rows.value = data.map((d: any) => ({
        title: d.title,
        difficultyName: d.difficultyName,
        difficultyLevel: Number(d.difficultyLevel),
        rank: Number(d.rank),
        total: Number(d.total),
      }));
    }
  } catch {
    // ignore
  } finally {
    isLoading.value = false;
    loaded.value = true;
  }
};

const rankBadgeClass = (rank: number) => {
  if (rank === 1) return 'bg-amber-400 text-white';
  if (rank === 2) return 'bg-slate-300 dark:bg-slate-500 text-white';
  if (rank === 3) return 'bg-amber-700 text-white';
  if (rank <= 10) return 'bg-blue-100 dark:bg-blue-900/40 text-blue-700 dark:text-blue-300';
  return 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';
};

const diffClass = (name: string) => {
  if (name === 'LEGGENDARIA') return 'bg-purple-100 dark:bg-purple-900/40 text-purple-700 dark:text-purple-300';
  if (name === 'ANOTHER') return 'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300';
  return 'bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300';
};

onMounted(load);
</script>
