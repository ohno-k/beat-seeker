<template>
  <div class="w-full mx-auto space-y-6 animate-fade-in relative">
    <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 flex flex-col xl:flex-row xl:items-center justify-between gap-4 transition-colors duration-200">
      <div>
        <h2 class="text-2xl font-bold text-slate-800 dark:text-slate-100">楽曲スコア詳細</h2>
        <p class="text-slate-500 dark:text-slate-400 mt-1">取得データ: <span class="font-semibold text-slate-700 dark:text-slate-200">{{ filteredScores.length }}</span> 件</p>
      </div>
      <div class="flex flex-col md:flex-row items-start md:items-center justify-end gap-3 w-full xl:w-auto">
        <div class="flex flex-col sm:flex-row items-start sm:items-center gap-3 sm:gap-4 w-full md:w-auto">
          <!-- Hide Zero Score Toggle -->
          <label class="flex items-center gap-2 cursor-pointer group whitespace-nowrap">
            <div class="relative inline-flex items-center">
              <input type="checkbox" v-model="hideZeroScore" class="sr-only peer">
              <div class="w-9 h-5 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-blue-300 dark:peer-focus:ring-blue-800 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white dark:peer-checked:after:border-slate-800 after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white dark:after:bg-slate-800 after:border-slate-300 dark:after:border-slate-600 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-blue-600"></div>
            </div>
            <span class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400 group-hover:text-slate-800 dark:group-hover:text-slate-200 transition-colors">スコア0を非表示</span>
          </label>

          <div class="flex items-center gap-2 w-full sm:w-auto">
            <!-- Level Filter -->
          <div class="relative w-full md:w-36">
            <button 
              @click.stop="toggleDropdown('level')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
              :title="filterLevel.length > 0 ? filterLevel.map(l => '☆'+l).join(', ') : 'レベル'"
            >
              <span class="truncate">{{ filterLevel.length > 0 ? filterLevel.map(l => '☆'+l).join(', ') : 'レベル' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0 ml-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'level'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="l in [11, 12]" :key="l" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterLevel, l.toString())"
                  @change="toggleFilterValue(filterLevel, l.toString())"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-medium text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">☆{{ l }}</span>
              </label>
            </div>
          </div>

          <!-- Difficulty Filter -->
          <div class="relative w-full md:w-36">
            <button 
              @click.stop="toggleDropdown('difficulty')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
              :title="filterDifficulty.length > 0 ? filterDifficulty.map(d => d.substring(0,3)).join(', ') : '難易度'"
            >
              <span class="truncate">{{ filterDifficulty.length > 0 ? filterDifficulty.map(d => d.substring(0,3)).join(', ') : '難易度' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0 ml-1" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'difficulty'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="d in ['ANOTHER', 'LEGGENDARIA']" :key="d" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterDifficulty, d)"
                  @change="toggleFilterValue(filterDifficulty, d)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-bold text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ d }}</span>
              </label>
            </div>
          </div>

          <!-- DJ Level Filter -->
          <div class="relative w-full md:w-32">
            <button 
              @click.stop="toggleDropdown('djLevel')"
              class="flex items-center justify-between w-full px-3 py-1.5 sm:py-2 border border-slate-200 dark:border-slate-700 rounded-xl bg-slate-50 dark:bg-slate-900 text-xs sm:text-sm font-medium text-slate-700 dark:text-slate-200 transition-colors hover:bg-white dark:hover:bg-slate-800 shadow-sm"
            >
              <span class="truncate">ランク{{ filterDjLevel.length > 0 ? ` (${filterDjLevel.length})` : '' }}</span>
              <svg class="h-4 w-4 text-slate-400 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7" />
              </svg>
            </button>
            <div v-if="openDropdown === 'djLevel'" class="absolute z-20 mt-1 w-48 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg py-2 max-h-64 overflow-y-auto animate-fade-in">
              <label v-for="lvl in ['AAA', 'AA', 'A', 'B', 'C', 'D', 'E', 'F']" :key="lvl" class="flex items-center px-4 py-2 hover:bg-slate-50 dark:hover:bg-slate-700/50 cursor-pointer group">
                <input 
                  type="checkbox" 
                  :checked="isSelected(filterDjLevel, lvl)"
                  @change="toggleFilterValue(filterDjLevel, lvl)"
                  class="h-4 w-4 text-blue-600 rounded border-slate-300 dark:border-slate-600 focus:ring-blue-500 dark:focus:ring-blue-600 transition-all cursor-pointer bg-white dark:bg-slate-900"
                >
                <span class="ml-3 text-sm font-black text-slate-600 dark:text-slate-300 group-hover:text-slate-900 dark:group-hover:text-white transition-colors">{{ lvl }}</span>
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
            class="block w-full pl-9 pr-3 py-2 border border-slate-200 dark:border-slate-700 rounded-xl leading-5 bg-slate-50 dark:bg-slate-900 text-slate-700 dark:text-slate-200 placeholder-slate-400 dark:placeholder-slate-500 focus:outline-none focus:bg-white dark:focus:bg-slate-800 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 sm:text-sm transition-colors" 
            placeholder="曲名、アーティストなど..."
          >
        </div>
      </div>
    </div>

    <!-- Data Table -->
    <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
      <div class="overflow-x-auto">
        <table class="w-full text-left text-[10px] sm:text-sm text-slate-600 dark:text-slate-300">
          <thead class="bg-slate-50/80 dark:bg-slate-800/80 border-b border-slate-200 dark:border-slate-700 text-slate-700 dark:text-slate-200 font-semibold h-10 sm:h-12">
            <tr>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-4/12" @click="toggleSort('title')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  曲名 <span class="hidden md:inline">(Title)</span>
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'title'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-700 dark:text-slate-200 uppercase tracking-wider w-auto sm:w-1/12 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors" @click="toggleSort('difficultyLevel')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  公式 <span class="hidden lg:inline">(Official)</span>
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'difficultyLevel'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-700 dark:text-slate-200 uppercase tracking-wider w-auto sm:w-1/12 group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors" @click="toggleSort('informalRank')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  非公式 <span class="hidden lg:inline">(Informal)</span>
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'informalRank'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-2/12" @click="toggleSort('clearType')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  スコア <span class="hidden md:inline">/ ランク</span>
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'clearType'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-4 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-1/12" @click="toggleSort('scoreRate')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  <span class="hidden sm:inline">スコア</span>レート
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'scoreRate'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
              <th class="px-1 sm:px-6 py-2 sm:py-4 text-left text-[9px] sm:text-xs font-black text-slate-500 dark:text-slate-400 uppercase tracking-wider group cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors w-auto sm:w-2/12" @click="toggleSort('beatTierPoints')">
                <div class="flex items-center gap-0.5 sm:gap-1">
                  Beat-PT
                  <span class="text-slate-400 dark:text-slate-500 group-hover:text-blue-500 dark:group-hover:text-blue-400" v-if="sortKey === 'beatTierPoints'">
                    {{ sortOrder === 'asc' ? '▲' : '▼' }}
                  </span>
                  <span v-else class="text-slate-300 dark:text-slate-600">↕</span>
                </div>
              </th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-700/50">
            <tr 
              v-for="(record, index) in displayScores" 
              :key="index" 
              @click="openDetailModal(record)"
              class="hover:bg-blue-50/70 dark:hover:bg-slate-700/50 cursor-pointer transition-colors h-12 sm:h-14 w-full"
            >
              <td class="px-1 sm:px-6 py-1.5 sm:py-2 font-medium text-slate-800 dark:text-slate-200 max-w-[80px] sm:max-w-[200px] lg:max-w-md xl:max-w-lg truncate" :title="record.title">
                {{ record.title }}
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <div class="flex flex-col gap-0.5 sm:gap-1">
                  <span :class="['px-1 sm:px-2 py-0.5 rounded text-[8px] sm:text-[10px] font-bold whitespace-nowrap inline-block w-fit', record.difficultyColor]">
                    {{ record.difficultyName.charAt(0) }}<span class="hidden sm:inline">{{ record.difficultyName.slice(1) }}</span> {{ record.difficultyLevel || '' }}
                  </span>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                  <span v-if="record.informalRank" class="text-[8px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">
                    {{ record.informalRank }}
                  </span>
              </td>
              <td class="px-1 sm:px-6 py-1.5 sm:py-2">
                <div class="flex flex-col gap-0.5">
                  <div class="flex items-center gap-1 sm:gap-2">
                    <span class="font-black text-[8px] sm:text-[10px] truncate max-w-[36px] sm:max-w-none" :class="getClearTypeColor(record.clearType)">
                      {{ record.clearType === 'FULLCOMBO CLEAR' ? 'FC' : record.clearType === 'EX HARD CLEAR' ? 'EXH' : record.clearType === 'HARD CLEAR' ? 'H' : record.clearType === 'CLEAR' ? 'C' : record.clearType === 'EASY CLEAR' ? 'E' : record.clearType === 'ASSIST CLEAR' ? 'AC' : 'F' }}
                    </span>
                    <span class="font-black text-[10px] sm:text-sm" :class="getDjLevelColor(record.djLevel)">{{ record.djLevel !== '---' ? record.djLevel : '' }}</span>
                  </div>
                  <div class="flex items-center gap-0.5 sm:gap-1">
                     <span class="font-black text-slate-800 dark:text-slate-200 text-[9px] sm:text-xs">{{ record.score }}</span>
                  </div>
                </div>
              </td>
              <td class="px-1 sm:px-4 py-1.5 sm:py-2 whitespace-nowrap">
                <span v-if="record.scoreRate >= 0" class="font-bold text-[9px] sm:text-xs" :class="record.scoreRate >= 94.45 ? 'text-purple-600 dark:text-purple-400' : record.scoreRate >= 88.89 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-600 dark:text-slate-400'">{{ record.scoreRate.toFixed(2) }}%</span>
                <span v-else class="text-[9px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">---</span>
              </td>
              
              <td class="px-1 sm:px-6 py-1.5 sm:py-2 whitespace-nowrap transition-colors" :class="[
                top100Keys.has(record.title + '|' + record.difficultyName) ? 'bg-blue-50/80 dark:bg-blue-900/20' : '',
                ((!record.informalRank && record.difficultyLevel && record.difficultyLevel <= 10) || (record.difficultyName === 'HYPER' && record.difficultyLevel && record.difficultyLevel >= 11)) ? 'bg-slate-900' : ''
              ]">
                <div class="flex flex-col sm:flex-row sm:items-center gap-px sm:gap-2" v-if="(record.informalRank || (record.difficultyLevel && record.difficultyLevel > 10)) && !(record.difficultyName === 'HYPER' && record.difficultyLevel && record.difficultyLevel >= 11)">
                  <div class="flex items-center gap-0.5 sm:gap-1">
                    <span class="font-black" :class="top100Keys.has(record.title + '|' + record.difficultyName) ? 'text-blue-700 dark:text-blue-400 text-[10px] sm:text-base' : 'text-slate-800 dark:text-slate-200 text-[9px] sm:text-sm'">
                      {{ record.beatTierPoints.toFixed(1) }}
                    </span>
                    <span v-if="top100Keys.has(record.title + '|' + record.difficultyName)" class="hidden sm:inline-block px-1 py-0.5 rounded bg-blue-100 dark:bg-blue-900/50 text-blue-600 dark:text-blue-400 text-[6px] sm:text-[8px] font-black uppercase border border-blue-200 dark:border-blue-800 shadow-sm">
                      TOP
                    </span>
                  </div>
                  <span class="text-[7px] sm:text-[10px] font-bold text-slate-400 dark:text-slate-500">/{{ record.maxBeatTierPoints.toFixed(1) }}</span>
                </div>
                <div v-else class="flex items-center justify-center">
                  <span class="text-[9px] sm:text-[10px] font-black text-slate-700 dark:text-slate-500 italic">N/A</span>
                </div>
              </td>
            </tr>
            <tr v-if="displayScores.length === 0">
              <td colspan="6" class="px-6 py-12 text-center text-slate-500 dark:text-slate-400 w-full">
                条件に一致するスコアがありません。
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      
      <!-- Pagination -->
      <div v-if="filteredScores.length > 0" class="px-6 py-4 border-t border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/50 flex flex-col sm:flex-row items-center justify-between gap-4 transition-colors duration-200">
        <div class="flex flex-col sm:flex-row items-center gap-4">
          <div class="text-sm text-slate-500 dark:text-slate-400">
            <span class="font-medium text-slate-800 dark:text-slate-200">{{ (currentPage - 1) * itemsPerPage + 1 }} - {{ Math.min(currentPage * itemsPerPage, filteredScores.length) }}</span> / <span class="font-medium text-slate-800 dark:text-slate-200">{{ filteredScores.length }}</span> 件表示中
          </div>
          <div class="flex items-center gap-2">
            <span class="text-sm text-slate-500 dark:text-slate-400">表示件数:</span>
            <select v-model="itemsPerPage" class="text-sm border border-slate-200 dark:border-slate-700 rounded-lg bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 px-2 py-1 outline-none focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-600 transition-colors shadow-sm cursor-pointer">
              <option :value="10">10件</option>
              <option :value="25">25件</option>
              <option :value="50">50件</option>
              <option :value="100">100件</option>
            </select>
          </div>
        </div>
        <div class="flex items-center gap-2">
          <button 
            @click="prevPage" 
            :disabled="currentPage === 1"
            class="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 shadow-sm"
          >
            前へ
          </button>
          <span class="text-sm font-medium text-slate-600 dark:text-slate-400 px-2 min-w-[3rem] text-center">{{ currentPage }} / {{ totalPages }}</span>
          <button 
            @click="nextPage" 
            :disabled="currentPage === totalPages"
            class="px-3 py-1.5 rounded-lg border border-slate-200 dark:border-slate-700 text-sm font-medium disabled:opacity-50 disabled:cursor-not-allowed hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-300 shadow-sm"
          >
            次へ
          </button>
        </div>
      </div>
    </div>

    <!-- Fullscreen Detail Modal -->
    <Teleport to="body">
      <div v-if="selectedRecord" class="fixed inset-0 z-[100] bg-slate-50 dark:bg-slate-900 flex flex-col animate-fade-in transition-colors duration-200" @click.self="closeDetailModal">
      
      <!-- Sticky Header -->
      <div class="bg-white dark:bg-slate-900 sticky top-0 z-10 w-full shadow-sm border-b border-slate-200 dark:border-slate-800 transition-colors duration-200">
        <div class="px-4 py-3 sm:px-6 sm:py-5 flex justify-between items-center">
          <div class="flex flex-col pr-4 max-w-full overflow-hidden">
            <h3 class="text-xl sm:text-3xl font-black text-slate-800 dark:text-slate-100 leading-tight mb-0.5 sm:mb-1 truncate" :title="selectedRecord.title">{{ selectedRecord.title }}</h3>
            <p class="text-xs sm:text-base font-medium text-slate-500 dark:text-slate-400 truncate" :title="`${selectedRecord.artist} • ${selectedRecord.genre}`">{{ selectedRecord.artist }} • {{ selectedRecord.genre }}</p>
          </div>
          <button @click="closeDetailModal" class="flex-shrink-0 text-slate-400 dark:text-slate-500 hover:text-slate-700 dark:hover:text-slate-300 bg-slate-50 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700 rounded-full transition-colors p-2 sm:p-3 shadow-sm border border-slate-200 dark:border-slate-700">
            <svg class="w-5 h-5 sm:w-8 sm:h-8" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>
        <!-- Modal Tabs -->
        <div class="flex border-t border-slate-100 dark:border-slate-800">
          <button
            @click="modalTab = 'detail'"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors"
            :class="modalTab === 'detail' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >詳細</button>
          <button
            v-if="isLoggedIn"
            @click="handleRivalTabClick"
            class="flex-1 py-2 text-sm font-bold border-b-2 transition-colors flex items-center justify-center gap-1"
            :class="modalTab === 'rivals' ? 'border-blue-600 text-blue-600' : 'border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300'"
          >
            ライバル
            <span v-if="rivalList.length > 0" class="text-xs bg-blue-100 dark:bg-blue-900/40 text-blue-600 dark:text-blue-400 rounded-full px-1.5">{{ rivalList.length }}</span>
          </button>
        </div>
      </div>
      
      <!-- Scrollable Body -->
      <div class="flex-1 overflow-y-auto p-3 sm:p-8 lg:p-12 pb-24">

        <!-- Rivals Tab -->
        <div v-if="modalTab === 'rivals'" class="w-full">
          <div v-if="isLoadingRivals" class="flex flex-col items-center justify-center py-20">
            <div class="w-10 h-10 border-4 border-blue-100 border-t-blue-600 rounded-full animate-spin mb-4"></div>
            <p class="text-slate-500 dark:text-slate-400">読み込み中...</p>
          </div>
          <div v-else-if="rivalList.length === 0" class="flex flex-col items-center justify-center py-20 text-slate-400 dark:text-slate-500">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-12 w-12 mb-3 opacity-40" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z" />
            </svg>
            <p class="font-bold">スコアを登録しているライバルがいません</p>
          </div>
          <div v-else class="space-y-2">
            <div
              v-for="(rival, index) in rivalList"
              :key="rival.id"
              class="rounded-xl border px-4 py-3 flex items-center gap-4"
              :class="rival.isSelf
                ? 'bg-blue-50 dark:bg-blue-900/20 border-blue-300 dark:border-blue-700'
                : 'bg-white dark:bg-slate-800 border-slate-200 dark:border-slate-700'"
            >
              <!-- Rank number -->
              <div class="w-10 text-center shrink-0">
                <span v-if="rival.score != null" class="text-base font-black text-slate-500 dark:text-slate-400">#{{ index + 1 }}</span>
                <span v-else class="text-sm font-bold text-slate-400 dark:text-slate-500">-</span>
              </div>

              <!-- Avatar + Name -->
              <div class="flex items-center gap-3 flex-1 min-w-0">
                <div
                  class="w-10 h-10 rounded-full flex items-center justify-center text-white font-bold text-base shrink-0"
                  :class="rival.isSelf ? 'bg-blue-500' : 'bg-gradient-to-br from-slate-400 to-slate-600'"
                >
                  {{ rival.displayName?.charAt(0) || 'U' }}
                </div>
                <p class="text-sm font-bold truncate" :class="rival.isSelf ? 'text-blue-700 dark:text-blue-300' : 'text-slate-800 dark:text-slate-100'">{{ rival.displayName }}</p>
              </div>

              <!-- Private: lock icon -->
              <div v-if="!rival.isSelf && rival.privacyLevel === 2" class="flex items-center gap-1.5 text-slate-400 dark:text-slate-500 shrink-0">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
                </svg>
                <span class="text-sm font-bold">非公開</span>
              </div>

              <template v-else-if="rival.score != null">
                <!-- Clear Lamp -->
                <div class="text-center shrink-0 w-14">
                  <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider mb-0.5">CLEAR</p>
                  <p class="text-sm font-black" :class="getClearTypeColor(rival.clearType)">
                    {{ rival.clearType === 'FULLCOMBO CLEAR' ? 'FC' : rival.clearType === 'EX HARD CLEAR' ? 'EXH' : rival.clearType === 'HARD CLEAR' ? 'H' : rival.clearType === 'CLEAR' ? 'C' : rival.clearType === 'EASY CLEAR' ? 'E' : rival.clearType === 'ASSIST CLEAR' ? 'AC' : 'F' }}
                  </p>
                </div>

                <!-- DJ Level -->
                <div class="text-center shrink-0 w-14">
                  <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider mb-0.5">DJ LV</p>
                  <p class="text-base font-black" :class="getDjLevelColor(rival.djLevel)">{{ rival.djLevel !== '---' ? rival.djLevel : '-' }}</p>
                </div>

                <!-- Score -->
                <div class="text-center shrink-0 w-24">
                  <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider mb-0.5">SCORE</p>
                  <p class="text-base font-black text-slate-800 dark:text-slate-100">{{ rival.score.toLocaleString() }}</p>
                </div>

                <!-- Score Rate -->
                <div class="text-center shrink-0 w-20">
                  <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider mb-0.5">RATE</p>
                  <p class="text-sm font-bold" :class="selectedRecord && selectedRecord.maxScore > 0 && (rival.score / selectedRecord.maxScore * 100) >= 94.45 ? 'text-purple-600 dark:text-purple-400' : selectedRecord && selectedRecord.maxScore > 0 && (rival.score / selectedRecord.maxScore * 100) >= 88.89 ? 'text-amber-500 dark:text-amber-400' : 'text-slate-600 dark:text-slate-300'">
                    {{ selectedRecord && selectedRecord.maxScore > 0 ? (rival.score / selectedRecord.maxScore * 100).toFixed(2) + '%' : '---' }}
                  </p>
                </div>

                <!-- BEAT-PT -->
                <div class="text-center shrink-0 w-20">
                  <p class="text-[10px] text-slate-400 dark:text-slate-500 font-bold uppercase tracking-wider mb-0.5">BEAT-PT</p>
                  <p class="text-base font-black text-indigo-600 dark:text-indigo-400">
                    {{ selectedRecord && selectedRecord.maxScore > 0 && selectedRecord.informalRank
                      ? calculatePoints(rival.score / selectedRecord.maxScore * 100, selectedRecord.informalRank).toFixed(1)
                      : '---' }}
                  </p>
                </div>
              </template>

              <div v-else-if="rival.isSelf || rival.privacyLevel !== 2" class="text-sm text-slate-400 dark:text-slate-500 font-bold shrink-0">未プレイ</div>
            </div>
          </div>
        </div>

        <div v-else class="max-w-4xl mx-auto space-y-4 sm:space-y-8">
          
          <div class="flex flex-col items-center sm:items-start gap-2 sm:gap-4">
            <div class="flex flex-wrap gap-2 justify-center sm:justify-start">
              <span :class="['px-3 py-1 sm:px-4 sm:py-1.5 rounded-full text-xs sm:text-sm font-black tracking-wide shadow-sm', selectedRecord.difficultyColor]">
                {{ selectedRecord.difficultyName }} {{ selectedRecord.difficultyLevel ? `☆${selectedRecord.difficultyLevel}` : '' }}
              </span>
              <span v-if="selectedRecord.informalRank" class="px-3 py-1 sm:px-4 sm:py-1.5 rounded-full text-xs sm:text-sm font-black tracking-wide shadow-sm bg-slate-100 dark:bg-slate-800 text-slate-700 dark:text-slate-300 border border-slate-200 dark:border-slate-700">
                非公式難易度: {{ selectedRecord.informalRank }}
              </span>
            </div>
            <h3 class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-100 tracking-tight text-center sm:text-left leading-tight mt-1 sm:mt-0">
              {{ selectedRecord.title }}
            </h3>
          </div>
          <div class="flex flex-wrap items-center justify-between gap-3">
            <span class="text-xs sm:text-sm font-bold text-slate-500 dark:text-slate-400 border border-slate-300 dark:border-slate-600 px-3 sm:px-4 py-1.5 sm:py-2 rounded-lg bg-white dark:bg-slate-800 shadow-sm transition-colors">
              最終プレイ: <span class="text-slate-700 dark:text-slate-200 font-black">{{ selectedRecord.lastPlayTime || '不明' }}</span>
            </span>
          </div>

          <div class="grid grid-cols-2 gap-3 sm:gap-6">
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-6 rounded-xl sm:rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
              <div class="absolute top-0 left-0 w-full h-1 sm:h-2" :class="getClearTypeBgColor(selectedRecord.clearType)"></div>
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-1 mt-1 sm:mb-2 sm:mt-2">クリアタイプ</p>
              <p class="text-lg sm:text-4xl font-black text-center" :class="getClearTypeColor(selectedRecord.clearType)">
                {{ selectedRecord.clearType }}
              </p>
            </div>
            <div class="bg-white dark:bg-slate-800 p-3 sm:p-6 rounded-xl sm:rounded-2xl border border-slate-200 dark:border-slate-700 shadow-sm flex flex-col items-center justify-center relative overflow-hidden transition-colors duration-200">
              <div class="absolute top-0 left-0 w-full h-1 sm:h-2" :class="getDjLevelBgColor(selectedRecord.djLevel)"></div>
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-500 uppercase tracking-widest mb-1 mt-1 sm:mb-2 sm:mt-2">DJレベル</p>
              <div class="flex flex-col items-center">
                <p class="text-4xl sm:text-6xl font-black text-center" :class="getDjLevelColor(selectedRecord.djLevel)">
                  {{ selectedRecord.djLevel }}
                </p>
              </div>
            </div>
            
            <div class="bg-indigo-900/10 dark:bg-indigo-900/20 p-4 sm:p-8 rounded-xl sm:rounded-2xl shadow-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors duration-200 border border-indigo-100 dark:border-indigo-800/50">
              <p class="text-xs sm:text-sm font-bold text-indigo-500 dark:text-indigo-400 uppercase tracking-widest mb-1 sm:mb-2">単独BEAT-PT</p>
              <div class="flex items-baseline gap-1 sm:gap-2">
                <p class="text-4xl sm:text-6xl font-black text-indigo-700 dark:text-indigo-300 tracking-tight">
                  {{ selectedRecord.beatTierPoints.toFixed(1) }}
                </p>
                <p v-if="selectedRecord.maxBeatTierPoints > 0" class="text-sm sm:text-xl font-bold text-indigo-400 dark:text-indigo-500">/ {{ selectedRecord.maxBeatTierPoints.toFixed(1) }}</p>
              </div>
            </div>
            
            <div class="bg-slate-800 dark:bg-slate-700 p-4 sm:p-8 rounded-xl sm:rounded-2xl shadow-md flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-slate-400 dark:text-slate-300 uppercase tracking-widest mb-1 sm:mb-2">EXスコア</p>
              <div class="flex items-baseline gap-1 sm:gap-2">
                <p class="text-4xl sm:text-6xl font-black text-white tracking-tight">
                  {{ selectedRecord.score }}
                </p>
                <p v-if="selectedRecord.maxScore > 0" class="text-sm sm:text-xl font-bold text-slate-500 dark:text-slate-400">/ {{ selectedRecord.maxScore }}</p>
              </div>
            </div>
            <div 
               class="p-4 sm:p-8 rounded-xl sm:rounded-2xl flex flex-col items-center justify-center col-span-2 sm:col-span-1 transition-colors relative duration-200"
               :class="selectedRecord.scoreRate >= 0 ? 'bg-blue-50 dark:bg-slate-800 border-4 border-blue-200 dark:border-slate-700' : 'bg-slate-100 dark:bg-slate-800/50 border-dashed border-4 border-slate-300 dark:border-slate-600 group cursor-help'"
               :title="selectedRecord.scoreRate >= 0 ? '' : '正確なノーツ数定義データがないため計算できません'"
            >
              <p class="text-xs sm:text-sm font-bold uppercase tracking-widest mb-1 sm:mb-2" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-500 dark:text-blue-400' : 'text-slate-500 dark:text-slate-400'">単独スコアレート</p>
              <p class="text-4xl sm:text-6xl font-black tracking-tight flex items-baseline" :class="selectedRecord.scoreRate >= 0 ? 'text-blue-600 dark:text-blue-300' : 'text-slate-300 dark:text-slate-600 transition-colors group-hover:text-slate-400 dark:group-hover:text-slate-500'">
                 <template v-if="selectedRecord.scoreRate >= 0">
                    {{ selectedRecord.scoreRate.toFixed(2) }}
                 </template>
                 <template v-else>
                    ---
                 </template>
                <span class="text-xl sm:text-3xl font-bold ml-1 sm:ml-2">%</span>
              </p>
            </div>
          </div>

          <div class="border border-slate-200 dark:border-slate-700 rounded-xl sm:rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 transition-colors duration-200">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-4 sm:px-6 py-2 sm:py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-slate-600 dark:text-slate-400 uppercase tracking-widest">判定詳細</p>
            </div>
            <div class="grid grid-cols-3 divide-x divide-slate-200 dark:divide-slate-700">
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-amber-50/50 dark:from-slate-800/50 to-white dark:to-slate-800 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-amber-500 dark:text-amber-400 font-bold tracking-widest mb-1 sm:mb-2">PGREAT</span>
                <span class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-200">{{ selectedRecord.pgreat }}</span>
              </div>
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-yellow-50/50 dark:from-slate-800/50 to-white dark:to-slate-800 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-yellow-500 dark:text-yellow-400 font-bold tracking-widest mb-1 sm:mb-2">GREAT</span>
                <span class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-200">{{ selectedRecord.great }}</span>
              </div>
              <div class="p-3 sm:p-6 lg:p-8 flex flex-col items-center justify-center bg-gradient-to-b from-red-50/50 dark:from-slate-800/50 to-white dark:to-slate-800 transition-colors duration-200">
                <span class="text-[10px] sm:text-sm text-red-400 dark:text-red-500 font-bold tracking-widest mb-1 sm:mb-2">MISS</span>
                <span class="text-2xl sm:text-5xl font-black text-slate-800 dark:text-slate-200">{{ selectedRecord.missCount !== null ? selectedRecord.missCount : '-' }}</span>
              </div>
            </div>
          </div>

          <!-- Option Vote Section -->
          <div class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-emerald-50 dark:bg-emerald-900/30 px-4 sm:px-6 py-3 sm:py-4 border-b border-emerald-100 dark:border-emerald-800/50 flex items-center justify-between transition-colors duration-200">
              <p class="text-xs sm:text-sm font-bold text-emerald-700 dark:text-emerald-400 uppercase tracking-widest flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
                </svg>
                おすすめオプション投票
              </p>
              <span v-if="voteData.totalVotes > 0" class="text-[10px] sm:text-xs font-bold text-emerald-500 dark:text-emerald-400 bg-emerald-100 dark:bg-emerald-900/50 px-2 py-0.5 rounded-full">{{ voteData.totalVotes }}票</span>
            </div>
            <div class="p-4 sm:p-6">
              <div class="mb-4 p-3 bg-slate-50 dark:bg-slate-800/50 rounded-xl border border-slate-200 dark:border-slate-700/50">
                <p class="text-[11px] sm:text-xs text-slate-600 dark:text-slate-400 leading-relaxed font-medium">
                  💡 2P側でプレイされている方の投票は、自動的に1P側の配置に反転（ミラーリング）されて集計されます。
                  そのため、<span class="font-bold text-emerald-600 dark:text-emerald-500">ご自身のプレイサイドで当たりだったオプションをそのまま投票</span>してください。
                </p>
              </div>
              
              <!-- Vote Buttons -->
              <div v-if="isLoggedIn" class="flex flex-wrap gap-2 mb-4">
                <button
                  v-for="opt in optionTypes"
                  :key="opt.value"
                  @click="castVote(opt.value)"
                  :disabled="isVoting"
                  class="px-3 py-2 rounded-xl text-xs sm:text-sm font-bold border-2 transition-all flex items-center gap-1.5 disabled:opacity-50"
                  :class="voteData.myVote === opt.value 
                    ? `${opt.activeBg} ${opt.activeText} ${opt.activeBorder} shadow-sm` 
                    : 'bg-slate-50 dark:bg-slate-900 text-slate-600 dark:text-slate-400 border-slate-200 dark:border-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800'"
                >
                  <span>{{ opt.icon }}</span>
                  {{ opt.label }}
                  <span v-if="voteData.myVote === opt.value" class="text-[10px]">✔</span>
                </button>
              </div>
              <div v-else class="mb-4 p-3 bg-slate-50 dark:bg-slate-900 rounded-xl text-sm text-slate-500 dark:text-slate-400 italic text-center">
                ※ログインすると投票できます。
              </div>
              
              <!-- Vote Results Bar Chart -->
              <div v-if="voteData.totalVotes > 0" class="space-y-2">
                <div v-for="opt in optionTypes" :key="opt.value" class="flex items-center gap-2">
                  <span class="text-[10px] sm:text-xs font-bold w-20 sm:w-24 text-right shrink-0" :class="opt.labelColor">{{ opt.label }}</span>
                  <div class="flex-1 h-6 sm:h-7 bg-slate-100 dark:bg-slate-700 rounded-lg overflow-hidden relative">
                    <div
                      class="h-full rounded-lg transition-all duration-500 flex items-center justify-end pr-2"
                      :class="opt.barColor"
                      :style="{ width: `${getVotePercent(opt.value)}%`, minWidth: (voteData.counts[opt.value] || 0) > 0 ? '24px' : '0px' }"
                    >
                      <span v-if="(voteData.counts[opt.value] || 0) > 0" class="text-[10px] sm:text-xs font-black text-white drop-shadow-sm">{{ voteData.counts[opt.value] }}</span>
                    </div>
                    <span v-if="(voteData.counts[opt.value] || 0) === 0" class="absolute left-2 top-1/2 -translate-y-1/2 text-[10px] font-bold text-slate-400 dark:text-slate-500">0</span>
                  </div>
                  <span class="text-[10px] sm:text-xs font-bold text-slate-400 dark:text-slate-500 w-10 text-right">{{ getVotePercent(opt.value).toFixed(0) }}%</span>
                </div>
              </div>
              <div v-else class="text-center py-4 text-sm text-slate-400 dark:text-slate-500">
                まだ投票がありません。最初の投票者になりましょう！
              </div>
            </div>
          </div>

          <!-- BEAT-PT Target Calculator -->
          <div v-if="selectedRecord.maxScore > 0 && selectedRecord.maxBeatTierPoints > 0 && selectedRecord.beatTierPoints < selectedRecord.maxBeatTierPoints" class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-indigo-50 dark:bg-indigo-900/30 px-6 py-4 border-b border-indigo-100 dark:border-indigo-800/50 flex items-center justify-between transition-colors duration-200">
              <p class="text-sm font-bold text-indigo-700 dark:text-indigo-400 uppercase tracking-widest flex items-center gap-2">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 7h8m0 0v8m0-8l-8 8-4-4-6 6" />
                </svg>
                目標BEAT-PTシミュレーター
              </p>
            </div>
            <div class="p-6">
              <div class="flex flex-col gap-4">
                <div class="flex justify-between items-end">
                  <span class="text-sm font-bold text-slate-600 dark:text-slate-400">あと何pt稼ぐ？</span>
                  <div class="flex flex-col items-end">
                    <span class="text-2xl font-black text-indigo-600 dark:text-indigo-400">+{{ targetBeatPtSlider.toFixed(1) }} <span class="text-sm">pt</span></span>
                    <span v-if="targetBeatPtSlider > 0" class="text-xs font-bold text-indigo-500 dark:text-indigo-500/80">
                      (合計: {{ (selectedRecord.beatTierPoints + targetBeatPtSlider).toFixed(1) }} pt)
                    </span>
                  </div>
                </div>
                <input 
                  type="range" 
                  min="0" 
                  :max="selectedRecord.maxBeatTierPoints - selectedRecord.beatTierPoints" 
                  step="0.1" 
                  v-model.number="targetBeatPtSlider"
                  class="w-full h-2 bg-slate-200 rounded-lg appearance-none cursor-pointer dark:bg-slate-700 accent-indigo-600"
                >
                <div class="flex justify-between text-xs font-medium text-slate-400 dark:text-slate-500">
                  <span>0</span>
                  <span>最大: +{{ (selectedRecord.maxBeatTierPoints - selectedRecord.beatTierPoints).toFixed(1) }}</span>
                </div>
                
                <div v-if="targetBeatPtSlider > 0" class="mt-4 p-4 bg-indigo-50 dark:bg-indigo-900/20 rounded-xl border border-indigo-100 dark:border-indigo-800/50 flex flex-col items-center justify-center text-center">
                  <p class="text-sm font-bold text-slate-600 dark:text-slate-300 mb-2">目標達成に必要なスコアの伸び</p>
                  <p class="text-4xl font-black text-indigo-700 dark:text-indigo-400 flex items-baseline gap-1">
                    +{{ targetScoreNeeded }} <span class="text-lg font-bold">点</span>
                  </p>
                  <p class="text-xs font-medium text-indigo-500 dark:text-indigo-400 mt-2">
                    目標スコア: {{ selectedRecord.score + targetScoreNeeded }} / {{ selectedRecord.maxScore }} ({{ (((selectedRecord.score + targetScoreNeeded) / selectedRecord.maxScore) * 100).toFixed(2) }}%)
                  </p>
                </div>
              </div>
            </div>
          </div>

          <!-- Memo Section -->
          <div v-if="selectedRecord.id || !isLoggedIn" class="border border-slate-200 dark:border-slate-700 rounded-2xl overflow-hidden shadow-sm bg-white dark:bg-slate-800 mt-6 transition-colors duration-200">
            <div class="bg-slate-100 dark:bg-slate-900/50 px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center justify-between transition-colors duration-200">
              <p class="text-sm font-bold text-slate-600 dark:text-slate-400 uppercase tracking-widest">メモ</p>
              <button @click="isEditingMemo = true" v-if="!isEditingMemo && isLoggedIn" class="text-blue-600 dark:text-blue-400 hover:text-blue-700 dark:hover:text-blue-300 text-sm font-bold flex items-center gap-1 transition-colors">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                  <path d="M13.586 3.586a2 2 0 112.828 2.828l-.793.793-2.828-2.828.793-.793zM11.379 5.793L3 14.172V17h2.828l8.38-8.379-2.83-2.828z" />
                </svg>
                編集
              </button>
            </div>
            <div class="p-6">
              <template v-if="!isLoggedIn">
                <div class="text-slate-500 dark:text-slate-400 italic text-sm text-center py-2">
                  ※ログインすると、各楽曲にメモを残せるようになります。
                </div>
              </template>
              <template v-else-if="isEditingMemo">
                <textarea v-model="editMemoText" rows="4" class="w-full border border-slate-300 dark:border-slate-600 rounded-xl p-3 bg-white dark:bg-slate-900 focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-600 focus:border-blue-500 dark:focus:border-blue-500 text-slate-700 dark:text-slate-200 resize-y transition-colors duration-200" placeholder="オプション（RANDOMなど）や攻略のメモを残せます..."></textarea>
                <div class="flex justify-end gap-3 mt-4">
                  <button @click="isEditingMemo = false" class="px-4 py-2 text-sm font-bold text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-xl transition-colors">キャンセル</button>
                  <button @click="saveMemo" :disabled="isSavingMemo" class="px-6 py-2 bg-blue-600 dark:bg-blue-600 hover:bg-blue-700 dark:hover:bg-blue-700 text-white text-sm font-bold rounded-xl transition-colors shadow-sm disabled:opacity-50">
                    {{ isSavingMemo ? '保存中...' : '保存' }}
                  </button>
                </div>
              </template>
              <template v-else>
                <div v-if="selectedRecord.memo" class="text-slate-700 dark:text-slate-300 whitespace-pre-wrap leading-relaxed">{{ selectedRecord.memo }}</div>
                <div v-else class="text-slate-400 dark:text-slate-500 italic text-sm">メモはありません。</div>
              </template>
            </div>
          </div>
          
        </div>
      </div>
      
      <!-- Sticky Footer -->
      <div class="sticky bottom-0 bg-white dark:bg-slate-900 border-t border-slate-200 dark:border-slate-800 p-4 sm:p-6 shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.05)] dark:shadow-[0_-10px_20px_-10px_rgba(0,0,0,0.2)] w-full flex justify-center z-10 transition-colors duration-200">
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
import diffTableRaw from '../data/difficulty_table.json';
import { calculatePoints, getMaxPoints } from '../utils/beatTier';
import { useScores } from '../composables/useScores';
import { useDarkMode } from '../composables/useDarkMode';
import { useAuth } from '../composables/useAuth';
import songDataRaw from '../data/song_data.json';

const { updateMemo } = useScores();
const { isDarkMode } = useDarkMode();
const { isLoggedIn, authHeaders } = useAuth();

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const props = defineProps<{
  scores: ScoreData[];
}>();

// Emits are handled below after totalBeatTierPoints definition

const searchQuery = ref('');
const filterDifficulty = ref<string[]>([]);
const filterLevel = ref<string[]>([]);
const filterDjLevel = ref<string[]>([]);
const hideZeroScore = ref(false);

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
const itemsPerPage = ref(50);

type SortKey = 'title' | 'clearType' | 'scoreRate' | 'informalRank' | 'difficultyLevel' | 'djLevel' | 'beatTierPoints' | null;
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
  // First, get the user's played scores
  const playedRecords = flattenScores(props.scores).filter(r => 
    r.difficultyLevel &&
    r.difficultyLevel >= 11 &&
    ['ANOTHER', 'LEGGENDARIA'].includes(r.difficultyName)
  );

  // Build a map for fast lookup
  const playedMap = new Map<string, ScoreRecord>();
  playedRecords.forEach(r => playedMap.set(`${r.title}|${r.difficultyName}`, r));

  // Index informal difficulty table for unplayed songs
  const informalDict = new Map<string, string>();
  if (diffTableRaw && Array.isArray(diffTableRaw.ranks)) {
      diffTableRaw.ranks.forEach(r => {
          r.songs.forEach(songTitle => {
              if (songTitle.endsWith('[L]')) {
                  const baseTitle = songTitle.slice(0, -3);
                  informalDict.set(`${baseTitle}_LEGGENDARIA`, r.rank);
              } else {
                  informalDict.set(`${songTitle}_ANOTHER`, r.rank);
              }
          });
      });
  }

  // Create empty records for songs in song_data.json that the user hasn't played
  const difMap: Record<string, string> = { "4": "ANOTHER", "10": "LEGGENDARIA" };
  const baseRecords: ScoreRecord[] = (songDataRaw.body as any[])
    .filter(s => s.level >= 11 && (s.difficulty === "4" || s.difficulty === "10"))
    .map(s => {
      const diffName = difMap[s.difficulty];
      const key = `${s.title}|${diffName}`;
      
      if (playedMap.has(key)) {
        return playedMap.get(key)!;
      }

      // Look up informal rank
      const informalKey = `${s.title}_${diffName}`;
      let informalRank = informalDict.get(informalKey);
      if (!informalRank && diffName === 'ANOTHER') {
          informalRank = informalDict.get(`${s.title}_ANOTHER`);
      }

      // Generate a default empty record
      return {
        id: undefined,
        playStyle: 'SP',
        title: s.title,
        genre: s.genre,
        artist: s.artist,
        playCount: 0,
        difficultyName: diffName,
        difficultyLevel: s.level,
        score: 0,
        pgreat: 0,
        great: 0,
        missCount: 0,
        clearType: 'NO PLAY',
        djLevel: '---',
        maxScore: s.notes ? s.notes * 2 : 0,
        scoreRate: 0,
        informalRank: informalRank,
        beatTierPoints: 0,
        maxBeatTierPoints: getMaxPoints(informalRank),
        memo: undefined,
        difficultyColor: diffName === 'LEGGENDARIA' ? 'text-purple-700 bg-purple-100 border border-purple-300' : 'text-red-700 bg-red-100 border border-red-300',
        lastPlayTime: ''
      } as ScoreRecord;
    });

  return baseRecords;
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
const modalTab = ref<'detail' | 'rivals'>('detail');

const isEditingMemo = ref(false);
const editMemoText = ref('');
const isSavingMemo = ref(false);

const targetBeatPtSlider = ref(0);

// Rival scores state
interface RivalScore {
  id: number;
  displayName: string;
  iidxId: string;
  score: number | null;
  clearType: string;
  djLevel: string;
  pgreat: number;
  great: number;
  missCount: number | null;
  isSelf?: boolean;
  privacyLevel?: number;
}
const rivalScores = ref<RivalScore[]>([]);

const rivalList = computed(() => {
  if (!selectedRecord.value) return [];
  const rec = selectedRecord.value;

  // Build self entry
  const selfEntry: RivalScore = {
    id: -1,
    displayName: 'あなた',
    iidxId: '',
    score: rec.score > 0 ? rec.score : null,
    clearType: rec.clearType,
    djLevel: rec.djLevel,
    pgreat: rec.pgreat,
    great: rec.great,
    missCount: rec.missCount,
    isSelf: true
  };

  const all: RivalScore[] = [selfEntry, ...rivalScores.value];

  // Sort: scored first (desc), unplayed last
  return all.sort((a, b) => {
    if (a.score == null && b.score == null) return 0;
    if (a.score == null) return 1;
    if (b.score == null) return -1;
    return b.score - a.score;
  });
});
const isLoadingRivals = ref(false);

const fetchRivalScores = async () => {
  if (!selectedRecord.value) return;
  isLoadingRivals.value = true;
  try {
    const params = new URLSearchParams({
      title: selectedRecord.value.title,
      difficultyName: selectedRecord.value.difficultyName
    });
    const res = await fetch(`${API_BASE}/api/friends/scores?${params}`, {
      headers: authHeaders()
    });
    if (res.ok) {
      rivalScores.value = await res.json();
    }
  } catch {
    // Silently fail
  } finally {
    isLoadingRivals.value = false;
  }
};

const handleRivalTabClick = () => {
  modalTab.value = 'rivals';
  if (rivalScores.value.length === 0 && !isLoadingRivals.value) {
    fetchRivalScores();
  }
};

// Reset target slider when a new record is selected
watch(() => selectedRecord.value?.title, () => {
  targetBeatPtSlider.value = 0;
  rivalScores.value = [];
  // Fetch votes for the new record
  if (selectedRecord.value) {
    fetchVotes(selectedRecord.value.title, selectedRecord.value.difficultyName);
  }
});

// Option Vote System
interface VoteDataType {
  counts: Record<string, number>;
  totalVotes: number;
  myVote: string | null;
}

const voteData = ref<VoteDataType>({
  counts: { REGULAR: 0, MIRROR: 0, RANDOM: 0, 'R-RANDOM': 0, 'S-RANDOM': 0 },
  totalVotes: 0,
  myVote: null
});
const isVoting = ref(false);

const optionTypes = [
  { value: 'REGULAR', label: '正規', icon: '▶', activeBg: 'bg-blue-50 dark:bg-blue-900/30', activeText: 'text-blue-700 dark:text-blue-400', activeBorder: 'border-blue-300 dark:border-blue-700', barColor: 'bg-blue-500', labelColor: 'text-blue-600 dark:text-blue-400' },
  { value: 'MIRROR', label: 'MIRROR', icon: '◀', activeBg: 'bg-purple-50 dark:bg-purple-900/30', activeText: 'text-purple-700 dark:text-purple-400', activeBorder: 'border-purple-300 dark:border-purple-700', barColor: 'bg-purple-500', labelColor: 'text-purple-600 dark:text-purple-400' },
  { value: 'RANDOM', label: 'RANDOM', icon: '🎲', activeBg: 'bg-emerald-50 dark:bg-emerald-900/30', activeText: 'text-emerald-700 dark:text-emerald-400', activeBorder: 'border-emerald-300 dark:border-emerald-700', barColor: 'bg-emerald-500', labelColor: 'text-emerald-600 dark:text-emerald-400' },
  { value: 'R-RANDOM', label: 'R-RAN', icon: '🔀', activeBg: 'bg-amber-50 dark:bg-amber-900/30', activeText: 'text-amber-700 dark:text-amber-400', activeBorder: 'border-amber-300 dark:border-amber-700', barColor: 'bg-amber-500', labelColor: 'text-amber-600 dark:text-amber-400' },
  { value: 'S-RANDOM', label: 'S-RAN', icon: '🎰', activeBg: 'bg-rose-50 dark:bg-rose-900/30', activeText: 'text-rose-700 dark:text-rose-400', activeBorder: 'border-rose-300 dark:border-rose-700', barColor: 'bg-rose-500', labelColor: 'text-rose-600 dark:text-rose-400' },
];

const getVotePercent = (optionValue: string): number => {
  if (voteData.value.totalVotes === 0) return 0;
  return ((voteData.value.counts[optionValue] || 0) / voteData.value.totalVotes) * 100;
};

const fetchVotes = async (title: string, difficultyName: string) => {
  try {
    const params = new URLSearchParams({ title, difficultyName });
    const res = await fetch(`${API_BASE}/api/votes?${params}`, {
      headers: authHeaders(),
    });
    if (res.ok) {
      const data = await res.json();
      voteData.value = data;
    }
  } catch {
    // Silently fail
  }
};

const castVote = async (optionType: string) => {
  if (!selectedRecord.value) return;
  isVoting.value = true;
  try {
    // If already voted for this option, delete the vote
    if (voteData.value.myVote === optionType) {
      const params = new URLSearchParams({
        title: selectedRecord.value.title,
        difficultyName: selectedRecord.value.difficultyName
      });
      await fetch(`${API_BASE}/api/votes?${params}`, {
        method: 'DELETE',
        headers: authHeaders(),
      });
    } else {
      await fetch(`${API_BASE}/api/votes`, {
        method: 'POST',
        headers: { ...authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: selectedRecord.value.title,
          difficultyName: selectedRecord.value.difficultyName,
          optionType
        })
      });
    }
    // Refresh vote data
    await fetchVotes(selectedRecord.value.title, selectedRecord.value.difficultyName);
  } catch {
    // Silently fail
  } finally {
    isVoting.value = false;
  }
};

const targetScoreNeeded = computed(() => {
  if (!selectedRecord.value || selectedRecord.value.maxScore <= 0 || selectedRecord.value.maxBeatTierPoints <= 0) return 0;
  
  const currentPt = selectedRecord.value.beatTierPoints;
  const targetPt = currentPt + targetBeatPtSlider.value;
  
  if (targetPt <= currentPt) return 0;

  let low = selectedRecord.value.score;
  let high = selectedRecord.value.maxScore;
  let bestScore = selectedRecord.value.maxScore;
  
  const informalRank = selectedRecord.value.informalRank || (selectedRecord.value.difficultyLevel?.toFixed(1) ?? '12.0');

  while (low <= high) {
    let mid = Math.floor((low + high) / 2);
    let midRate = (mid / selectedRecord.value.maxScore) * 100;
    let midPt = calculatePoints(midRate, informalRank);
    
    if (midPt >= targetPt) {
      bestScore = mid;
      high = mid - 1; 
    } else {
      low = mid + 1;
    }
  }

  return Math.max(0, bestScore - selectedRecord.value.score);
});

const openDetailModal = (record: ScoreRecord) => {
  selectedRecord.value = record;
  modalTab.value = 'detail';
  isEditingMemo.value = false;
  editMemoText.value = record.memo || '';
  document.body.style.overflow = 'hidden';
};

const saveMemo = async () => {
    if (!selectedRecord.value?.id) return;
    isSavingMemo.value = true;
    try {
        await updateMemo(selectedRecord.value.id, editMemoText.value);
        selectedRecord.value.memo = editMemoText.value;
        isEditingMemo.value = false;
    } catch (e) {
        alert('メモの保存に失敗しました');
    } finally {
        isSavingMemo.value = false;
    }
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
watch([searchQuery, filterDifficulty, filterLevel, filterDjLevel, sortKey, sortOrder, hideZeroScore, itemsPerPage], () => {
  currentPage.value = 1;
}, { deep: true });

const toggleSort = (key: SortKey) => {
  if (sortKey.value === key) {
    // Toggle between asc and desc
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc';
  } else {
    sortKey.value = key;
    // Set default order for specific keys
    if (key === 'scoreRate' || key === 'informalRank' || key === 'beatTierPoints' || key === 'clearType' || key === 'djLevel') {
        sortOrder.value = 'desc';
    } else {
        sortOrder.value = 'asc';
    }
  }
};

const filteredScores = computed(() => {
  let result = [...allRecords.value];

  if (hideZeroScore.value) {
    result = result.filter(r => r.score > 0);
  }

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
    const getNumericRank = (rank: string | undefined): number => {
      if (!rank) return -1;
      const match = rank.match(/(\d+\.\d+)/);
      return match ? parseFloat(match[1]) : -1;
    };

    result.sort((a, b) => {
        const valA = getNumericRank(a.informalRank);
        const valB = getNumericRank(b.informalRank);
        
        if (valA !== valB) {
            return sortOrder.value === 'asc' ? valA - valB : valB - valA;
        }

        // Secondary: difficultyLevel
        const levelA = a.difficultyLevel || 0;
        const levelB = b.difficultyLevel || 0;
        if (levelA !== levelB) {
            return sortOrder.value === 'asc' ? levelA - levelB : levelB - levelA;
        }
        
        return a.title.localeCompare(b.title);
    });
  } else if (sortKey.value === 'difficultyLevel') {
    result.sort((a, b) => {
        const levelA = a.difficultyLevel || 0;
        const levelB = b.difficultyLevel || 0;
        if (levelA !== levelB) return sortOrder.value === 'asc' ? levelA - levelB : levelB - levelA;
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

const totalPages = computed(() => Math.ceil(filteredScores.value.length / itemsPerPage.value) || 1);

const displayScores = computed(() => {
  const start = (currentPage.value - 1) * itemsPerPage.value;
  const end = start + itemsPerPage.value;
  return filteredScores.value.slice(start, end);
});

const prevPage = () => {
  if (currentPage.value > 1) currentPage.value--;
};

const nextPage = () => {
  if (currentPage.value < totalPages.value) currentPage.value++;
};

// Utility for colors
const getClearTypeColor = (clearType: string) => {
  switch (clearType) {
    case 'FULLCOMBO CLEAR': return isDarkMode.value ? 'text-cyan-400' : 'text-cyan-600';
    case 'EX HARD CLEAR': return isDarkMode.value ? 'text-yellow-400' : 'text-yellow-600';
    case 'HARD CLEAR': return isDarkMode.value ? 'text-red-400' : 'text-red-500';
    case 'CLEAR': return isDarkMode.value ? 'text-blue-400' : 'text-blue-500';
    case 'EASY CLEAR': return isDarkMode.value ? 'text-green-400' : 'text-green-500';
    case 'ASSIST CLEAR': return isDarkMode.value ? 'text-purple-400' : 'text-purple-500';
    default: return isDarkMode.value ? 'text-slate-500' : 'text-slate-400';
  }
};

const getClearTypeBgColor = (clearType: string) => {
  switch (clearType) {
    case 'FULLCOMBO CLEAR': return 'bg-cyan-500';
    case 'EX HARD CLEAR': return 'bg-yellow-500';
    case 'HARD CLEAR': return 'bg-red-500';
    case 'CLEAR': return 'bg-blue-500';
    case 'EASY CLEAR': return 'bg-green-500';
    case 'ASSIST CLEAR': return 'bg-purple-500';
    default: return isDarkMode.value ? 'bg-slate-700' : 'bg-slate-200';
  }
};

const getDjLevelColor = (djLevel: string) => {
  switch (djLevel) {
    case 'AAA': return isDarkMode.value ? 'text-amber-400' : 'text-amber-500';
    case 'AA': return isDarkMode.value ? 'text-yellow-400' : 'text-yellow-500';
    case 'A': return isDarkMode.value ? 'text-emerald-400' : 'text-emerald-500';
    default: return isDarkMode.value ? 'text-slate-500' : 'text-slate-400';
  }
};

const getDjLevelBgColor = (djLevel: string) => {
  switch (djLevel) {
    case 'AAA': return 'bg-amber-500';
    case 'AA': return 'bg-yellow-500';
    case 'A': return 'bg-emerald-500';
    default: return isDarkMode.value ? 'bg-slate-700' : 'bg-slate-200';
  }
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
