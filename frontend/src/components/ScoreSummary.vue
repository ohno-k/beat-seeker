<template>
  <div class="w-full mx-auto space-y-6 animate-fade-in relative">
    <div class="bg-white p-6 rounded-2xl shadow-sm border border-slate-200 flex flex-col xl:flex-row xl:items-center justify-between gap-4">
      <div>
        <h2 class="text-2xl font-bold text-slate-800">楽曲スコア詳細</h2>
        <p class="text-slate-500 mt-1">取得データ: <span class="font-semibold text-slate-700">{{ filteredScores.length }}</span> 件</p>
      </div>
      <div class="flex flex-col md:flex-row items-center gap-3 w-full xl:w-auto">
        <div class="flex items-center gap-4 w-full md:w-auto">


          <div class="flex items-center gap-2">
            <!-- Level Filter -->
          <div class="relative w-full md:w-28">
            <button 
              @click.stop="toggleDropdown('level')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 rounded-xl bg-slate-50 text-xs sm:text-sm font-medium text-slate-700 transition-colors hover:bg-white shadow-sm"
            >
              <span class="truncate">レベル{{ filterLevel.length > 0 ? ` (${filterLevel.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'level'" class="absolute z-20 mt-1 w-48 bg-white border border-slate-200 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="l in 12" :key="l" class="flex items-center px-4 py-2 hover:bg-slate-50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterLevel, l.toString())"
                  @change="toggleFilterValue(filterLevel, l.toString())"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 focus:ring-blue-500 transition-all cursor-pointer"
                >
                <span class="ml-3 text-sm font-medium text-slate-600 group-hover:text-slate-900 transition-colors">☆{{ l }}</span>
              </label>
            </div>
          </div>

          <!-- Difficulty Filter -->
          <div class="relative w-full md:w-44">
            <button 
              @click.stop="toggleDropdown('difficulty')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 rounded-xl bg-slate-50 text-xs sm:text-sm font-medium text-slate-700 transition-colors hover:bg-white shadow-sm"
            >
              <span class="truncate">難易度{{ filterDifficulty.length > 0 ? ` (${filterDifficulty.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'difficulty'" class="absolute z-20 mt-1 w-48 bg-white border border-slate-200 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="d in ['BEGINNER', 'NORMAL', 'HYPER', 'ANOTHER', 'LEGGENDARIA']" :key="d" class="flex items-center px-4 py-2 hover:bg-slate-50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterDifficulty, d)"
                  @change="toggleFilterValue(filterDifficulty, d)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 focus:ring-blue-500 transition-all cursor-pointer"
                >
                <span class="ml-3 text-sm font-bold text-slate-600 group-hover:text-slate-900 transition-colors">{{ d }}</span>
              </label>
            </div>
          </div>

          <!-- DJ Level Filter -->
          <div class="relative w-full md:w-32">
            <button 
              @click.stop="toggleDropdown('djLevel')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 rounded-xl bg-slate-50 text-xs sm:text-sm font-medium text-slate-700 transition-colors hover:bg-white shadow-sm"
            >
              <span class="truncate">ランク{{ filterDjLevel.length > 0 ? ` (${filterDjLevel.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'djLevel'" class="absolute z-20 mt-1 w-48 bg-white border border-slate-200 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="lvl in ['AAA', 'AA', 'A', 'B', 'C', 'D', 'E', 'F']" :key="lvl" class="flex items-center px-4 py-2 hover:bg-slate-50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterDjLevel, lvl)"
                  @change="toggleFilterValue(filterDjLevel, lvl)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 focus:ring-blue-500 transition-all cursor-pointer"
                >
                <span class="ml-3 text-sm font-black text-slate-600 group-hover:text-slate-900 transition-colors">{{ lvl }}</span>
              </label>
            </div>
          </div>
          </div>
        </div>
        <!-- Search -->
        <div class="relative w-full md:w-64">
          <div class="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
            <svg class="h-4 w-4 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
            </svg>
          </div>
          <input 
            v-model="searchQuery"
            type="text" 
            class="block w-full pl-9 pr-3 py-2 border border-slate-200 rounded-xl leading-5 bg-slate-50 placeholder-slate-400 focus:outline-none focus:bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500 sm:text-sm transition-colors" 
            placeholder="曲名、アーティストなど..."
          >
        </div>
      </div>
    </div>

    <!-- Data Table -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-200 overflow-hidden">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm text-slate-600">
          <thead class="bg-slate-50/80 border-b border-slate-200 text-slate-700 font-semibold h-12">
            <tr>
              <th class="px-2 sm:px-6 py-4 text-left text-xs font-black text-slate-500 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 transition-colors w-4/12 sm:w-4/12" @click="toggleSort('title')">
                <div class="flex items-center gap-1">
                  曲名 <span class="hidden md:inline">(Title)</span>
                  <span class="text-slate-400 group-hover:text-blue-500" v-if="sortKey === 'title'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300">↕</span>
                </div>
              </th>
              <th class="px-2 sm:px-6 py-4 text-left text-xs font-black text-slate-700 uppercase tracking-wider w-2/12 sm:w-2/12 group cursor-pointer hover:bg-slate-100 transition-colors" @click="toggleSort('informalRank')">
                <div class="flex items-center gap-1">
                  難易度 <span class="hidden lg:inline">(Diff)</span>
                  <span class="text-slate-400 group-hover:text-blue-500" v-if="sortKey === 'informalRank'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                </div>
              </th>
              <th class="px-2 sm:px-6 py-4 text-left text-xs font-black text-slate-500 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 transition-colors w-2/12 sm:w-2/12" @click="toggleSort('scoreRate')">
                <div class="flex items-center gap-1">
                  スコア <span class="hidden md:inline">(Score)</span>
                  <span class="text-slate-400 group-hover:text-blue-500" v-if="sortKey === 'scoreRate'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300">↕</span>
                </div>
              </th>
              <th class="px-2 sm:px-6 py-4 text-left text-xs font-black text-slate-500 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 transition-colors w-3/12 sm:w-2/12" @click="toggleSort('beatTierPoints')">
                <div class="flex items-center gap-1">
                  Beat-PT
                  <span class="text-slate-400 group-hover:text-blue-500" v-if="sortKey === 'beatTierPoints'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                </div>
              </th>
              <th class="px-2 sm:px-4 py-4 text-left text-xs font-black text-slate-500 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 transition-colors w-1/12 sm:w-2/12" @click="toggleSort('djLevel')">
                <div class="flex items-center gap-1">
                  ランク <span class="hidden xl:inline">(Rank)</span>
                  <span class="text-slate-400 group-hover:text-blue-500" v-if="sortKey === 'djLevel'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100">
            <tr 
              v-for="(record, index) in displayScores" 
              :key="index" 
              @click="openDetailModal(record)"
              class="hover:bg-blue-50/70 cursor-pointer transition-colors h-14 w-full"
            >
              <td class="px-2 sm:px-6 py-2 font-medium text-slate-800 max-w-[100px] sm:max-w-[200px] lg:max-w-md xl:max-w-lg truncate" :title="record.title">
                {{ record.title }}
              </td>
              <td class="px-2 sm:px-6 py-2 whitespace-nowrap">
                <div class="flex flex-col gap-0.5 sm:gap-1">
                  <span :class="['px-1 sm:px-2 py-0.5 rounded text-[8px] sm:text-[10px] font-bold whitespace-nowrap inline-block w-fit', record.difficultyColor]">
                    {{ record.difficultyName.charAt(0) }}<span class="hidden sm:inline">{{ record.difficultyName.slice(1) }}</span> {{ record.difficultyLevel || '' }}
                  </span>
                  <span v-if="record.informalRank" class="text-[8px] sm:text-[10px] font-bold text-slate-400">
                    {{ record.informalRank }}
                  </span>
                </div>
              </td>
              <td class="px-2 sm:px-6 py-2">
                <div class="flex flex-col gap-0.5">
                  <span class="font-black text-[8px] sm:text-[10px] truncate max-w-[40px] sm:max-w-none" :class="getClearTypeColor(record.clearType)">
                    {{ record.clearType === 'FULLCOMBO CLEAR' ? 'FC' : record.clearType === 'EX HARD CLEAR' ? 'EXH' : record.clearType === 'HARD CLEAR' ? 'H' : record.clearType === 'CLEAR' ? 'C' : record.clearType === 'EASY CLEAR' ? 'E' : record.clearType === 'ASSIST CLEAR' ? 'AC' : 'F' }}<span class="hidden sm:inline">{{ record.clearType.includes('CLE') ? record.clearType.replace(' CLEAR', '') : '' }}</span>
                  </span>
                  <div class="flex items-center gap-1">
                     <span class="font-black text-slate-800 text-[10px] sm:text-xs">{{ record.score }}</span>
                     <span class="text-[8px] sm:text-[10px] font-bold text-slate-500 hidden sm:inline" v-if="record.scoreRate >= 0">{{ record.scoreRate.toFixed(1) }}%</span>
                  </div>
                </div>
              </td>
              
              <td class="px-2 sm:px-6 py-2 whitespace-nowrap transition-colors" :class="[
                top100Keys.has(record.title + '|' + record.difficultyName) ? 'bg-blue-50/80' : '',
                ((!record.informalRank && record.difficultyLevel && record.difficultyLevel <= 10) || (record.difficultyName === 'HYPER' && record.difficultyLevel && record.difficultyLevel >= 11)) ? 'bg-slate-900' : ''
              ]">
                <div class="flex flex-col sm:flex-row sm:items-center gap-0.5 sm:gap-2" v-if="(record.informalRank || (record.difficultyLevel && record.difficultyLevel > 10)) && !(record.difficultyName === 'HYPER' && record.difficultyLevel && record.difficultyLevel >= 11)">
                  <div class="flex items-center gap-1">
                    <span class="font-black" :class="top100Keys.has(record.title + '|' + record.difficultyName) ? 'text-blue-700 text-xs sm:text-base' : 'text-slate-800 text-[10px] sm:text-sm'">
                      {{ record.beatTierPoints.toFixed(1) }}
                    </span>
                    <span v-if="top100Keys.has(record.title + '|' + record.difficultyName)" class="px-1 py-0.5 rounded bg-blue-100 text-blue-600 text-[6px] sm:text-[8px] font-black uppercase border border-blue-200 shadow-sm">
                      TOP
                    </span>
                  </div>
                  <span class="text-[7px] sm:text-[10px] font-bold text-slate-400">/{{ record.maxBeatTierPoints.toFixed(0) }}</span>
                </div>
                <div v-else class="flex items-center justify-center">
                  <span class="text-[10px] font-black text-slate-700 italic">N/A</span>
                </div>
              </td>
              <td class="px-2 sm:px-4 py-2 whitespace-nowrap">
                <div class="flex flex-col items-center">
                  <span class="font-black text-xs sm:text-sm" :class="getDjLevelColor(record.djLevel)">{{ record.djLevel !== '---' ? record.djLevel : '' }}</span>
                </div>
              </td>
            </tr>
            <tr v-if="displayScores.length === 0">
              <td colspan="5" class="px-6 py-12 text-center text-slate-500 w-full">
                条件に一致するスコアがありません。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <!-- Pagination -->
      <div v-if="totalPages > 1" class="px-6 py-4 border-t border-slate-200 bg-slate-50/50 flex flex-col sm:flex-row items-center justify-between gap-4">
        <div class="text-sm text-slate-500">
          <span class="font-medium text-slate-800">{{ (currentPage - 1) * itemsPerPage + 1 }} - {{ Math.min(currentPage * itemsPerPage, filteredScores.length) }}</span> / <span class="font-medium text-slate-800">{{ filteredScores.length }}</span> 件表示中
        </div>
        <div class="flex items-center gap-2">
          <button 
            @click="prevPage" 
            :disabled="currentPage === 1"
            class="px-3 py-1.5 rounded-lg border border-slate-200 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 transition-colors bg-white text-slate-700 shadow-sm"
          >
            前へ
          </button>
          <span class="text-sm font-medium text-slate-600 px-2 min-w-[3rem] text-center">{{ currentPage }} / {{ totalPages }}</span>
          <button 
            @click="nextPage" 
            :disabled="currentPage === totalPages"
            class="px-3 py-1.5 rounded-lg border border-slate-200 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 transition-colors bg-white text-slate-700 shadow-sm"
          >
            次へ
          </button>
        </div>
      </div>
    </div>

    <!-- Fullscreen Detail Modal -->
    <Teleport to="body">
      <div v-if="selectedRecord" class="fixed inset-0 z-[100] bg-slate-50 flex flex-col animate-fade-in" @click.self="closeDetailModal">
      
      <!-- Sticky Header -->
      <div class="px-6 py-5 border-b border-slate-200 shadow-sm flex justify-between items-center bg-white sticky top-0 z-10 w-full">
        <div class="flex flex-col pr-4 max-w-full overflow-hidden">
          <h3 class="text-2xl sm:text-3xl font-black text-slate-800 leading-tight mb-1 truncate" :title="selectedRecord.title">{{ selectedRecord.title }}</h3>
          <p class="text-sm sm:text-base font-medium text-slate-500 truncate" :title="`${selectedRecord.artist} • ${selectedRecord.genre}`">{{ selectedRecord.artist }} • {{ selectedRecord.genre }}</p>
        </div>
        <button @click="closeDetailModal" class="flex-shrink-0 text-slate-400 hover:text-slate-700 bg-slate-50 hover:bg-slate-200 rounded-full transition-colors p-3 shadow-sm border border-slate-200">
          <svg class="w-6 h-6 sm:w-8 sm:h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
          </svg>
        </button>
      </div>
      
      <!-- Scrollable Body -->
      <div class="flex-1 overflow-y-auto p-4 sm:p-8 lg:p-12 pb-32">
        <div class="max-w-4xl mx-auto space-y-8">
          
          <div class="flex flex-col items-center sm:items-start gap-4">
            <div class="flex flex-wrap gap-2 justify-center sm:justify-start">
              <span :class="['px-4 py-1.5 rounded-full text-sm font-black tracking-wide shadow-sm', selectedRecord.difficultyColor]">
                {{ selectedRecord.difficultyName }} {{ selectedRecord.difficultyLevel ? `☆${selectedRecord.difficultyLevel}` : '' }}
              </span>
              <span v-if="selectedRecord.informalRank" class="px-4 py-1.5 rounded-full text-sm font-black tracking-wide shadow-sm bg-slate-100 text-slate-700 border border-slate-200">
                非公式難易度: {{ selectedRecord.informalRank }}
              </span>
            </div>
            <h3 class="text-4xl sm:text-5xl font-black text-slate-800 tracking-tight text-center sm:text-left leading-tight">
              {{ selectedRecord.title }}
            </h3>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-3">
            <span class="text-sm font-bold text-slate-500 border border-slate-300 px-4 py-2 rounded-lg bg-white shadow-sm">
              最終プレイ: <span class="text-slate-700 font-black">{{ selectedRecord.lastPlayTime || '不明' }}</span>
            </span>
          </div>

          <div class="grid grid-cols-2 gap-6">
            <div class="bg-white p-6 rounded-2xl border border-slate-200 shadow-sm flex flex-col items-center justify-center relative overflow-hidden">
              <div class="absolute top-0 left-0 w-full h-2" :class="getClearTypeBgColor(selectedRecord.clearType)"></div>
              <p class="text-sm font-bold text-slate-400 uppercase tracking-widest mb-2 mt-2">クリアタイプ</p>
              <p class="text-2xl sm:text-4xl font-black text-center" :class="getClearTypeColor(selectedRecord.clearType)">
                {{ selectedRecord.clearType }}
              </p>
            </div>
            <div class="bg-white p-6 rounded-2xl border border-slate-200 shadow-sm flex flex-col items-center justify-center relative overflow-hidden">
              <div class="absolute top-0 left-0 w-full h-2" :class="getDjLevelBgColor(selectedRecord.djLevel)"></div>
              <p class="text-sm font-bold text-slate-400 uppercase tracking-widest mb-2 mt-2">DJレベル</p>
              <div class="flex flex-col items-center">
                <p class="text-5xl sm:text-6xl font-black text-center" :class="getDjLevelColor(selectedRecord.djLevel)">
                  {{ selectedRecord.djLevel }}
                </p>
              </div>
            </div>
            
            <div class="bg-slate-800 p-8 rounded-2xl shadow-md flex flex-col items-center justify-center col-span-2 sm:col-span-1">
              <p class="text-sm font-bold text-slate-400 uppercase tracking-widest mb-2">EXスコア</p>
              <div class="flex items-baseline gap-2">
                <p class="text-6xl font-black text-white tracking-tight">
                  {{ selectedRecord.score }}
                </p>
                <p v-if="selectedRecord.maxScore > 0" class="text-xl font-bold text-slate-500">/ {{ selectedRecord.maxScore }}</p>
              </div>
            </div>
            <div 
               class="p-8 rounded-2xl flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors relative"
               :class="selectedRecord.scoreRate >= 0 ? 'bg-blue-50 border-4 border-blue-200' : 'bg-slate-100 border-dashed border-4 border-slate-300 group cursor-help'"
               :title="selectedRecord.scoreRate >= 0 ? '' : '正確なノーツ数定義データがないため計算できません'"
            >
              <p class="text-sm font-bold uppercase tracking-widest mb-2" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-500' : 'text-slate-500'">単独スコアレート</p>
              <p class="text-6xl font-black tracking-tight flex items-baseline" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-600' : 'text-slate-300 transition-colors group-hover:text-slate-400'">
                 <template v-if="selectedRecord.scoreRate >= 0">
                    {{ selectedRecord.scoreRate.toFixed(2) }}
                 </template>
                 <template v-else>
                    ---
                 </template>
                <span class="text-3xl font-bold ml-2">%</span>
              </p>
            </div>
          </div>

          <div class="border border-slate-200 rounded-2xl overflow-hidden shadow-sm bg-white">
            <div class="bg-slate-100 px-6 py-4 border-b border-slate-200 flex items-center justify-between">
              <p class="text-sm font-bold text-slate-600 uppercase tracking-widest">判定詳細</p>
            </div>
            <div class="grid grid-cols-3 divide-x divide-slate-200">
              <div class="p-6 sm:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-amber-50/50 to-white">
                <span class="text-sm text-amber-500 font-bold tracking-widest mb-2">PGREAT</span>
                <span class="text-4xl sm:text-5xl font-black text-slate-800">{{ selectedRecord.pgreat }}</span>
              </div>
              <div class="p-6 sm:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-yellow-50/50 to-white">
                <span class="text-sm text-yellow-500 font-bold tracking-widest mb-2">GREAT</span>
                <span class="text-4xl sm:text-5xl font-black text-slate-800">{{ selectedRecord.great }}</span>
              </div>
              <div class="p-6 sm:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-red-50/50 to-white">
                <span class="text-sm text-red-400 font-bold tracking-widest mb-2">MISS</span>
                <span class="text-4xl sm:text-5xl font-black text-slate-800">{{ selectedRecord.missCount !== null ? selectedRecord.missCount : '-' }}</span>
              </div>
            </div>
          </div>
          
        </div>
      </div>
      
      <!-- Sticky Footer -->
      <div class="sticky bottom-0 bg-white border-t border-slate-200 p-4 sm:p-6 shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.05)] w-full flex justify-center z-10">
         <button @click="closeDetailModal" class="w-full max-w-md px-8 py-4 bg-blue-600 hover:bg-blue-700 text-white text-lg font-bold rounded-2xl shadow-lg transition-colors flex items-center justify-center gap-2">
           <svg class="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
           </svg>
           一覧に戻る
         </button>
      </div>
    </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue';
import type { ScoreData } from '../types/ScoreData';
import { flattenScores, type ScoreRecord } from '../utils/scoreData';

const props = defineProps<{
  scores: ScoreData[];
}>();

// Emits are handled below after totalBeatTierPoints definition

const searchQuery = ref('');
const filterDifficulty = ref<string[]>([]);
const filterLevel = ref<string[]>([]);
const filterDjLevel = ref<string[]>([]);

const openDropdown = ref<string | null>(null);

const toggleDropdown = (name: string) => {
  openDropdown.value = openDropdown.value === name ? null : name;
};

const toggleFilterValue = (arr: string[], value: string) => {
  const index = arr.indexOf(value);
  if (index === -1) {
    arr.push(value);
  } else {
    arr.splice(index, 1);
  }
};

const isSelected = (arr: string[], value: string) => {
  return arr.includes(value);
};

const currentPage = ref(1);
const itemsPerPage = 50;

type SortKey = 'title' | 'clearType' | 'scoreRate' | 'informalRank' | 'djLevel' | 'beatTierPoints' | null;
type SortOrder = 'asc' | 'desc';

const sortKey = ref<SortKey>('informalRank');
const sortOrder = ref<SortOrder>('desc');

const clearTypeRankings: Record<string, number> = {
  'FULLCOMBO CLEAR': 7,
  'EX HARD CLEAR': 6,
  'HARD CLEAR': 5,
  'CLEAR': 4,
  'EASY CLEAR': 3,
  'ASSIST CLEAR': 2,
  'FAILED': 1,
  'NO PLAY': 0,
  '---': 0
};

const allRecords = computed<ScoreRecord[]>(() => {
  return flattenScores(props.scores);
});

const emit = defineEmits<{
  (e: 'reset'): void;
  (e: 'update:totalPoints', points: number): void;
}>();

const totalBeatTierPoints = computed(() => {
    // Sort all records by beatTierPoints descending and take top 100
    const sorted = [...allRecords.value].sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100 = sorted.slice(0, 100);
    return top100.reduce((acc, curr) => acc + curr.beatTierPoints, 0);
});

watch(totalBeatTierPoints, (newVal) => {
    emit('update:totalPoints', newVal);
}, { immediate: true });

// Top 100 status for highlighting
const top100Keys = computed(() => {
    const sorted = [...allRecords.value].sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    return new Set(sorted.slice(0, 100).map(r => `${r.title}|${r.difficultyName}`));
});

// Modal state
const selectedRecord = ref<ScoreRecord | null>(null);

const openDetailModal = (record: ScoreRecord) => {
  selectedRecord.value = record;
  document.body.style.overflow = 'hidden'; 
};

const closeDetailModal = () => {
  selectedRecord.value = null;
  document.body.style.overflow = '';
};

const handleClickOutside = (event: MouseEvent) => {
  if (openDropdown.value && !(event.target as Element).closest('.relative')) {
    openDropdown.value = null;
  }
};

onMounted(() => {
  window.addEventListener('click', handleClickOutside);
});

onUnmounted(() => {
  window.removeEventListener('click', handleClickOutside);
  document.body.style.overflow = '';
});

// Reset page when search or filters change
watch([searchQuery, filterDifficulty, filterLevel, filterDjLevel, sortKey, sortOrder], () => {
  currentPage.value = 1;
}, { deep: true });

const toggleSort = (key: SortKey) => {
  if (sortKey.value === key) {
    if (sortOrder.value === 'desc') {
        sortKey.value = null; // Clear sort on third click
        sortOrder.value = 'asc';
    } else {
        sortOrder.value = 'desc';
    }
  } else {
    sortKey.value = key;
    // Set default order for specific keys
    if (key === 'scoreRate' || key === 'informalRank' || key === 'beatTierPoints') {
        sortOrder.value = 'desc';
    } else {
        sortOrder.value = 'asc';
    }
  }
};

const filteredScores = computed(() => {
  let result = [...allRecords.value];

  if (filterDifficulty.value.length > 0) {
    result = result.filter(r => filterDifficulty.value.includes(r.difficultyName));
  }

  if (filterLevel.value.length > 0) {
    result = result.filter(r => filterLevel.value.includes(r.difficultyLevel?.toString() || ''));
  }

  if (filterDjLevel.value.length > 0) {
    result = result.filter(r => filterDjLevel.value.includes(r.djLevel));
  }

  const query = searchQuery.value.toLowerCase().trim();
  if (query) {
    result = result.filter(record => 
      record.title.toLowerCase().includes(query) ||
      record.artist.toLowerCase().includes(query) ||
      record.genre.toLowerCase().includes(query) ||
      record.clearType.toLowerCase().includes(query)
    );
  }

  // Sorting
  if (sortKey.value === 'informalRank') {
    result.sort((a, b) => {
      // Sort by informal rank primarily (treating undefined/0 as lowest)
      const valA = parseFloat(a.informalRank || '0');
      const valB = parseFloat(b.informalRank || '0');
      if (valA !== valB) return sortOrder.value === 'asc' ? valA - valB : valB - valA;
      
      // Secondary: typical level (1-12)
      const levelA = a.difficultyLevel || 0;
      const levelB = b.difficultyLevel || 0;
      if (levelA !== levelB) return sortOrder.value === 'asc' ? levelA - levelB : levelB - levelA;

      // Tertiary: title
      return a.title.localeCompare(b.title);
    });
  } else if (sortKey.value === 'title') {
    result.sort((a, b) => {
      const cmp = a.title.localeCompare(b.title);
      return sortOrder.value === 'asc' ? cmp : -cmp;
    });
  } else if (sortKey.value === 'beatTierPoints') {
    result.sort((a, b) => {
      const valA = a.beatTierPoints || 0;
      const valB = b.beatTierPoints || 0;
      return sortOrder.value === 'asc' ? valA - valB : valB - valA;
    });
  } else if (sortKey.value === 'clearType') {
    result.sort((a, b) => {
      const rankA = clearTypeRankings[a.clearType] || 0;
      const rankB = clearTypeRankings[b.clearType] || 0;
      const diff = rankA - rankB;
      if (diff !== 0) {
          return sortOrder.value === 'asc' ? diff : -diff;
      }
      return 0;
    });
  } else if (sortKey.value === 'scoreRate') {
    result.sort((a, b) => {
      const rateA = a.scoreRate >= 0 ? a.scoreRate : -2;
      const rateB = b.scoreRate >= 0 ? b.scoreRate : -2;
      const diff = rateA - rateB;
      if (diff !== 0) {
          return sortOrder.value === 'asc' ? diff : -diff;
      }
      return 0;
    });

  } else if (sortKey.value === 'djLevel') {
    result.sort((a, b) => {
      const levelMap: Record<string, number> = { 'AAA': 8, 'AA': 7, 'A': 6, 'B': 5, 'C': 4, 'D': 3, 'E': 2, 'F': 1, '---': 0 };
      const valA = levelMap[a.djLevel] || 0;
      const valB = levelMap[b.djLevel] || 0;
      return sortOrder.value === 'asc' ? valA - valB : valB - valA;
    });
  }

  return result;
});

const totalPages = computed(() => Math.ceil(filteredScores.value.length / itemsPerPage) || 1);

const displayScores = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage;
  const end = start + itemsPerPage;
  return filteredScores.value.slice(start, end);
});

const prevPage = () => {
  if (currentPage.value > 1) currentPage.value--;
};

const nextPage = () => {
  if (currentPage.value < totalPages.value) currentPage.value++;
};

// Utility for colors
const getClearTypeColor = (ct: string) => {
  if (ct === 'FULLCOMBO CLEAR') return 'text-emerald-500';
  if (ct === 'EX HARD CLEAR') return 'text-amber-500';
  if (ct === 'HARD CLEAR') return 'text-red-500';
  if (ct === 'CLEAR') return 'text-blue-500';
  if (ct === 'EASY CLEAR') return 'text-emerald-400';
  if (ct === 'ASSIST CLEAR') return 'text-purple-500';
  if (ct === 'FAILED') return 'text-slate-400';
  return 'text-slate-600';
};

const getClearTypeBgColor = (ct: string) => {
  if (ct === 'FULLCOMBO CLEAR') return 'bg-emerald-500';
  if (ct === 'EX HARD CLEAR') return 'bg-amber-500';
  if (ct === 'HARD CLEAR') return 'bg-red-500';
  if (ct === 'CLEAR') return 'bg-blue-500';
  if (ct === 'EASY CLEAR') return 'bg-emerald-400';
  if (ct === 'ASSIST CLEAR') return 'bg-purple-500';
  if (ct === 'FAILED') return 'bg-slate-400';
  return 'bg-slate-200';
};

const getDjLevelColor = (lvl: string) => {
  if (lvl === 'AAA') return 'text-amber-400';
  if (lvl === 'AA') return 'text-slate-400';
  if (lvl === 'A') return 'text-emerald-500';
  if (['B','C','D','E','F'].includes(lvl)) return 'text-blue-500';
  return 'text-slate-500';
};

const getDjLevelBgColor = (lvl: string) => {
  if (lvl === 'AAA') return 'bg-amber-400';
  if (lvl === 'AA') return 'bg-slate-400';
  if (lvl === 'A') return 'bg-emerald-500';
  if (['B','C','D','E','F'].includes(lvl)) return 'bg-blue-500';
  return 'bg-slate-200';
};
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.15s ease-out forwards;
}

@keyframes fadeIn {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>
