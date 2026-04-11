<script setup lang="ts">
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { songData as songDataBody } from '../composables/useGameData';
import type { SongDataEntry } from '../composables/useGameData';

const { t } = useI18n();

const DIFF_MAP: Record<string, { name: string; color: string; bg: string }> = {
  '1': { name: 'BEGINNER', color: 'text-emerald-600 dark:text-emerald-400', bg: 'bg-emerald-100 dark:bg-emerald-900/40' },
  '2': { name: 'NORMAL', color: 'text-blue-600 dark:text-blue-400', bg: 'bg-blue-100 dark:bg-blue-900/40' },
  '3': { name: 'HYPER', color: 'text-amber-600 dark:text-amber-400', bg: 'bg-amber-100 dark:bg-amber-900/40' },
  '4': { name: 'ANOTHER', color: 'text-red-600 dark:text-red-400', bg: 'bg-red-100 dark:bg-red-900/40' },
  '10': { name: 'LEGGENDARIA', color: 'text-purple-600 dark:text-purple-400', bg: 'bg-purple-100 dark:bg-purple-900/40' },
};

const searchQuery = ref('');
const selectedDifficulty = ref<string>('');
const selectedLevel = ref<number | ''>('');
const sortKey = ref<'title' | 'level' | 'notes' | 'bpm'>('level');
const sortDir = ref<'asc' | 'desc'>('desc');
const currentPage = ref(1);
const PAGE_SIZE = 50;

const parseBpmNum = (bpm: string): number => {
  if (!bpm) return 0;
  const nums = bpm.match(/\d+/g);
  if (!nums) return 0;
  return Math.max(...nums.map(Number));
};

const availableLevels = computed(() => {
  const levels = new Set<number>();
  for (const s of songDataBody.value) {
    if (s.level) levels.add(s.level);
  }
  return [...levels].sort((a, b) => a - b);
});

const filtered = computed(() => {
  let rows = songDataBody.value;

  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase();
    rows = rows.filter(s => s.title.toLowerCase().includes(q) || s.artist.toLowerCase().includes(q));
  }

  if (selectedDifficulty.value) {
    rows = rows.filter(s => s.difficulty === selectedDifficulty.value);
  }

  if (selectedLevel.value !== '') {
    rows = rows.filter(s => s.level === selectedLevel.value);
  }

  return rows;
});

const sorted = computed(() => {
  const arr = [...filtered.value];
  const dir = sortDir.value === 'asc' ? 1 : -1;

  arr.sort((a, b) => {
    switch (sortKey.value) {
      case 'title':
        return dir * a.title.localeCompare(b.title, 'ja');
      case 'level':
        return dir * ((a.level || 0) - (b.level || 0));
      case 'notes':
        return dir * ((a.notes || 0) - (b.notes || 0));
      case 'bpm':
        return dir * (parseBpmNum(a.bpm) - parseBpmNum(b.bpm));
      default:
        return 0;
    }
  });

  return arr;
});

const totalPages = computed(() => Math.max(1, Math.ceil(sorted.value.length / PAGE_SIZE)));

const paged = computed(() => {
  const start = (currentPage.value - 1) * PAGE_SIZE;
  return sorted.value.slice(start, start + PAGE_SIZE);
});

const toggleSort = (key: typeof sortKey.value) => {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    sortDir.value = key === 'title' ? 'asc' : 'desc';
  }
  currentPage.value = 1;
};

const sortIcon = (key: string) => {
  if (sortKey.value !== key) return '';
  return sortDir.value === 'asc' ? '\u25B2' : '\u25BC';
};

const getDiff = (d: string) => DIFF_MAP[d] || { name: d, color: 'text-slate-600', bg: 'bg-slate-100' };

const textageUrl = (s: SongDataEntry) => {
  if (!s.textage) return '';
  return `https://textage.cc/score/${s.textage}`;
};
</script>

<template>
  <div class="space-y-6">
    <!-- Header -->
    <div>
      <h1 class="text-2xl font-black text-slate-900 dark:text-white">{{ t('chartList.title') }}</h1>
      <p class="text-sm text-slate-500 dark:text-slate-400 mt-1">{{ t('chartList.subtitle') }}</p>
    </div>

    <!-- Filters -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 p-4 shadow-sm">
      <div class="flex flex-wrap gap-3 items-center">
        <!-- Search -->
        <div class="relative flex-1 min-w-[200px]">
          <svg xmlns="http://www.w3.org/2000/svg" class="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
          </svg>
          <input
            v-model="searchQuery"
            type="text"
            :placeholder="t('chartList.searchPlaceholder')"
            class="w-full pl-10 pr-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent"
            @input="currentPage = 1"
          />
        </div>

        <!-- Difficulty Filter -->
        <select
          v-model="selectedDifficulty"
          class="px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          @change="currentPage = 1"
        >
          <option value="">{{ t('chartList.allDifficulties') }}</option>
          <option v-for="(info, code) in DIFF_MAP" :key="code" :value="code">{{ info.name }}</option>
        </select>

        <!-- Level Filter -->
        <select
          v-model="selectedLevel"
          class="px-4 py-2.5 rounded-xl border border-slate-200 dark:border-slate-600 bg-slate-50 dark:bg-slate-900/50 text-sm text-slate-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
          @change="currentPage = 1"
        >
          <option value="">{{ t('chartList.allLevels') }}</option>
          <option v-for="lv in availableLevels" :key="lv" :value="lv">Lv.{{ lv }}</option>
        </select>

        <!-- Count -->
        <span class="text-xs font-bold text-slate-400 dark:text-slate-500 whitespace-nowrap">
          {{ filtered.length.toLocaleString() }} {{ t('chartList.charts') }}
        </span>
      </div>
    </div>

    <!-- Table -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-sm">
          <thead>
            <tr class="border-b border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-900/30">
              <th
                class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                @click="toggleSort('title')"
              >
                {{ t('chartList.colTitle') }} {{ sortIcon('title') }}
              </th>
              <th class="px-4 py-3 text-left font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                {{ t('chartList.colArtist') }}
              </th>
              <th class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                {{ t('chartList.colDifficulty') }}
              </th>
              <th
                class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                @click="toggleSort('level')"
              >
                {{ t('chartList.colLevel') }} {{ sortIcon('level') }}
              </th>
              <th
                class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                @click="toggleSort('notes')"
              >
                {{ t('chartList.colNotes') }} {{ sortIcon('notes') }}
              </th>
              <th
                class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 cursor-pointer hover:text-blue-600 dark:hover:text-blue-400 select-none whitespace-nowrap"
                @click="toggleSort('bpm')"
              >
                BPM {{ sortIcon('bpm') }}
              </th>
              <th class="px-3 py-3 text-center font-bold text-slate-600 dark:text-slate-400 whitespace-nowrap">
                {{ t('chartList.colTextage') }}
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-50 dark:divide-slate-700/50">
            <tr
              v-for="(song, i) in paged"
              :key="`${song.title}-${song.difficulty}-${i}`"
              class="hover:bg-slate-50 dark:hover:bg-slate-700/30 transition-colors"
            >
              <td class="px-4 py-3 font-medium text-slate-900 dark:text-white max-w-[300px] truncate">
                {{ song.title }}
              </td>
              <td class="px-4 py-3 text-slate-500 dark:text-slate-400 max-w-[200px] truncate">
                {{ song.artist }}
              </td>
              <td class="px-3 py-3 text-center">
                <span
                  :class="[getDiff(song.difficulty).color, getDiff(song.difficulty).bg]"
                  class="inline-block px-2 py-0.5 text-[10px] font-black rounded-md uppercase tracking-wider"
                >
                  {{ getDiff(song.difficulty).name }}
                </span>
              </td>
              <td class="px-3 py-3 text-center font-bold text-slate-700 dark:text-slate-300">
                {{ song.level }}
              </td>
              <td class="px-3 py-3 text-center font-mono text-slate-600 dark:text-slate-400">
                {{ song.notes?.toLocaleString() || '-' }}
              </td>
              <td class="px-3 py-3 text-center font-mono text-slate-600 dark:text-slate-400">
                {{ song.bpm || '-' }}
              </td>
              <td class="px-3 py-3 text-center">
                <a
                  v-if="textageUrl(song)"
                  :href="textageUrl(song)"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-1 text-blue-500 hover:text-blue-600 dark:text-blue-400 dark:hover:text-blue-300 transition-colors"
                >
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10 6H6a2 2 0 00-2 2v10a2 2 0 002 2h10a2 2 0 002-2v-4M14 4h6m0 0v6m0-6L10 14" />
                  </svg>
                </a>
                <span v-else class="text-slate-300 dark:text-slate-600">-</span>
              </td>
            </tr>
            <tr v-if="paged.length === 0">
              <td colspan="7" class="px-4 py-12 text-center text-slate-400 dark:text-slate-500 font-medium">
                {{ t('chartList.noResults') }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      <div v-if="totalPages > 1" class="flex items-center justify-between px-4 py-3 border-t border-slate-100 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-900/20">
        <button
          :disabled="currentPage <= 1"
          @click="currentPage--"
          class="px-3 py-1.5 text-xs font-bold rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
        >
          {{ t('chartList.prev') }}
        </button>
        <span class="text-xs font-bold text-slate-500 dark:text-slate-400">
          {{ currentPage }} / {{ totalPages }}
        </span>
        <button
          :disabled="currentPage >= totalPages"
          @click="currentPage++"
          class="px-3 py-1.5 text-xs font-bold rounded-lg border border-slate-200 dark:border-slate-600 text-slate-600 dark:text-slate-400 hover:bg-white dark:hover:bg-slate-700 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
        >
          {{ t('chartList.next') }}
        </button>
      </div>
    </div>
  </div>
</template>
