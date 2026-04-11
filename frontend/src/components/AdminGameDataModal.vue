<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm" @click.self="$emit('close')">
      <div class="bg-white dark:bg-slate-900 w-full max-w-3xl rounded-2xl shadow-2xl flex flex-col overflow-hidden max-h-[90vh] animate-fade-in border border-slate-200 dark:border-slate-800">
        
        <!-- Header -->
        <div class="p-5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between shrink-0">
          <h2 class="text-xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
            </svg>
            ゲームデータ管理
          </h2>
          <div class="flex items-center gap-2">
            <!-- Apply button -->
            <button 
              @click="handleApplyDraft" 
              :disabled="isApplying || (draftSongs.length === 0 && savedDiffChanges.length === 0)"
              class="px-3 py-1.5 bg-emerald-500 hover:bg-emerald-600 text-white rounded-lg text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <svg v-if="isApplying" class="animate-spin h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
              {{ isApplying ? '適用中...' : '適用（公開）' }}
            </button>
            <button @click="$emit('close')" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors p-2 -mr-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
              </svg>
            </button>
          </div>
        </div>

        <!-- Draft status -->
        <div v-if="hasDraftSongs || hasDraftDiffTable" class="px-5 py-2 bg-amber-50 dark:bg-amber-900/20 border-b border-amber-200 dark:border-amber-800/50 flex items-center gap-2 text-sm text-amber-700 dark:text-amber-400">
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L3.27 16.5c-.77.833.192 2.5 1.732 2.5z" />
          </svg>
          <span>未適用のドラフトがあります</span>
          <span v-if="hasDraftSongs" class="px-1.5 py-0.5 bg-amber-200 dark:bg-amber-800/50 rounded text-xs font-bold">楽曲 {{ draftSongs.length }}件</span>
          <span v-if="hasDraftDiffTable" class="px-1.5 py-0.5 bg-amber-200 dark:bg-amber-800/50 rounded text-xs font-bold">難易度表</span>
        </div>

        <!-- Tab bar -->
        <div class="flex border-b border-slate-200 dark:border-slate-800 shrink-0">
          <button 
            @click="activeTab = 'songs'" 
            class="flex-1 py-3 text-sm font-bold transition-colors"
            :class="activeTab === 'songs' ? 'text-indigo-600 dark:text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'"
          >楽曲追加</button>
          <button 
            @click="activeTab = 'difficulty'" 
            class="flex-1 py-3 text-sm font-bold transition-colors"
            :class="activeTab === 'difficulty' ? 'text-indigo-600 dark:text-indigo-400 border-b-2 border-indigo-500' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300'"
          >難易度表</button>
        </div>

        <!-- Status messages -->
        <div v-if="errorMsg" class="mx-5 mt-4 bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 p-3 rounded-xl border border-red-200 dark:border-red-800 text-sm">{{ errorMsg }}</div>
        <div v-if="successMsg" class="mx-5 mt-4 bg-green-50 dark:bg-green-900/20 text-green-700 dark:text-green-400 p-3 rounded-xl border border-green-200 dark:border-green-800 text-sm">{{ successMsg }}</div>

        <!-- Tab content -->
        <div class="flex-1 overflow-y-auto p-5">

          <!-- Songs Tab -->
          <div v-if="activeTab === 'songs'">
            <!-- Add Song Form -->
            <div class="bg-slate-50 dark:bg-slate-800/50 rounded-xl border border-slate-200 dark:border-slate-700 p-4 mb-4">
              <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300 mb-3">新曲追加</h3>
              
              <!-- Basic info -->
              <div class="grid grid-cols-2 gap-3 mb-3">
                <div>
                  <label class="block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">曲名 *</label>
                  <input v-model="form.title" type="text" class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent" placeholder="曲名" />
                </div>
                <div>
                  <label class="block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">アーティスト</label>
                  <input v-model="form.artist" type="text" class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent" placeholder="アーティスト名" />
                </div>
                <div>
                  <label class="block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">ジャンル</label>
                  <input v-model="form.genre" type="text" class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent" placeholder="ジャンル" />
                </div>
                <div>
                  <label class="block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">BPM</label>
                  <input v-model="form.bpm" type="text" class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent" placeholder="150 / 130-180" />
                </div>
              </div>

              <!-- Per-difficulty fields -->
              <div class="space-y-2 mb-3">
                <div v-for="diff in difficultyDefs" :key="diff.code" 
                  class="flex items-center gap-2 p-2 rounded-lg border border-slate-200 dark:border-slate-700"
                  :class="diff.bgClass"
                >
                  <span class="w-20 text-xs font-black uppercase shrink-0 text-center py-1 rounded" :class="diff.labelClass">{{ diff.label }}</span>
                  <div class="flex items-center gap-1.5 flex-1">
                    <label class="text-[10px] font-bold text-slate-500 dark:text-slate-400 shrink-0">ノーツ</label>
                    <input v-model.number="form[diff.notesKey]" type="number" min="0" class="w-20 px-2 py-1 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded text-xs text-slate-800 dark:text-white" placeholder="0" />
                    <label class="text-[10px] font-bold text-slate-500 dark:text-slate-400 shrink-0">☆</label>
                    <input v-model.number="form[diff.levelKey]" type="number" min="1" max="12" class="w-14 px-2 py-1 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded text-xs text-slate-800 dark:text-white" placeholder="0" />
                  </div>
                </div>
              </div>

              <!-- Extra fields for ANOTHER/LEGGENDARIA -->
              <details class="mb-3">
                <summary class="text-xs font-bold text-slate-500 dark:text-slate-400 cursor-pointer hover:text-indigo-500">詳細フィールド (ANOTHER/LEGG用)</summary>
                <div class="grid grid-cols-4 gap-2 mt-2">
                  <div>
                    <label class="block text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-0.5">WR</label>
                    <input v-model.number="form.wr" type="number" class="w-full px-2 py-1 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded text-xs text-slate-800 dark:text-white" />
                  </div>
                  <div>
                    <label class="block text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-0.5">AVG</label>
                    <input v-model.number="form.avg" type="number" class="w-full px-2 py-1 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded text-xs text-slate-800 dark:text-white" />
                  </div>
                  <div>
                    <label class="block text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-0.5">coef</label>
                    <input v-model.number="form.coef" type="number" step="0.01" class="w-full px-2 py-1 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded text-xs text-slate-800 dark:text-white" />
                  </div>
                  <div>
                    <label class="block text-[10px] font-bold text-slate-500 dark:text-slate-400 mb-0.5">textage</label>
                    <input v-model="form.textage" type="text" class="w-full px-2 py-1 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded text-xs text-slate-800 dark:text-white" />
                  </div>
                </div>
              </details>

              <button 
                @click="handleAddSong" 
                :disabled="isSubmitting || !form.title"
                class="w-full py-2 bg-indigo-500 hover:bg-indigo-600 text-white rounded-lg text-sm font-bold transition-colors disabled:opacity-40 disabled:cursor-not-allowed flex items-center justify-center gap-2"
              >
                <svg v-if="isSubmitting" class="animate-spin h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                ドラフトに追加
              </button>
            </div>

            <!-- Draft songs list -->
            <div v-if="draftSongs.length > 0">
              <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300 mb-2 flex items-center gap-2">
                ドラフト楽曲一覧
                <span class="text-xs font-normal text-slate-500 dark:text-slate-400">({{ draftSongs.length }}件)</span>
              </h3>
              <div class="space-y-1">
                <div v-for="song in groupedDraftSongs" :key="song.title" 
                  class="flex items-center justify-between bg-white dark:bg-slate-800 p-3 rounded-lg border border-slate-200 dark:border-slate-700"
                >
                  <div class="flex-1 min-w-0">
                    <div class="font-bold text-sm text-slate-800 dark:text-white truncate">{{ song.title }}</div>
                    <div class="text-xs text-slate-500 dark:text-slate-400">
                      {{ song.artist }} / {{ song.genre }} / {{ song.difficulties.join(', ') }}
                    </div>
                  </div>
                  <button 
                    @click="handleDeleteDraftSong(song.ids)" 
                    class="ml-2 text-red-400 hover:text-red-600 dark:hover:text-red-300 transition-colors shrink-0 p-1"
                    title="削除"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                      <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                    </svg>
                  </button>
                </div>
              </div>
            </div>
            <div v-else class="text-center text-sm text-slate-400 dark:text-slate-500 py-8">
              ドラフト楽曲はありません
            </div>
          </div>

          <!-- Difficulty Table Tab -->
          <div v-if="activeTab === 'difficulty'">
            <div class="mb-3 flex items-center justify-between gap-2">
              <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300 shrink-0">難易度表 GUI 編集</h3>
              <div class="flex items-center gap-2">
                <button
                  @click="generateDraftFromVotes"
                  :disabled="isGeneratingDraft"
                  class="px-3 py-1.5 bg-amber-500 hover:bg-amber-600 text-white rounded-lg text-sm font-bold transition-colors disabled:opacity-40 flex items-center gap-1 whitespace-nowrap"
                >
                  <svg v-if="isGeneratingDraft" class="animate-spin h-3.5 w-3.5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                  投票から生成
                </button>
                <button
                  @click="handleSaveDiffTable"
                  :disabled="isSavingDiff || pendingDiffChanges.length === 0"
                  class="px-3 py-1.5 bg-indigo-500 hover:bg-indigo-600 text-white rounded-lg text-sm font-bold transition-colors disabled:opacity-40 flex items-center gap-1 whitespace-nowrap"
                >
                  <svg v-if="isSavingDiff" class="animate-spin h-3.5 w-3.5" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                  下書き保存 ({{ pendingDiffChanges.length }}件)
                </button>
              </div>
            </div>

            <!-- Level filter checkboxes -->
            <div class="mb-3 flex items-center gap-4">
              <label class="flex items-center gap-1.5 cursor-pointer select-none">
                <input v-model="showLv12" type="checkbox" class="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-indigo-500 focus:ring-indigo-500 cursor-pointer" />
                <span class="text-sm font-bold text-slate-700 dark:text-slate-300">☆12</span>
              </label>
              <label class="flex items-center gap-1.5 cursor-pointer select-none">
                <input v-model="showLv11" type="checkbox" class="w-4 h-4 rounded border-slate-300 dark:border-slate-600 text-indigo-500 focus:ring-indigo-500 cursor-pointer" />
                <span class="text-sm font-bold text-slate-700 dark:text-slate-300">☆11</span>
              </label>
            </div>

            <div class="bg-slate-50 dark:bg-slate-800/50 rounded-xl border border-slate-200 dark:border-slate-700 p-4 mb-4">
              <h4 class="text-xs font-bold text-slate-500 mb-2">楽曲のランク移動</h4>
              <div class="flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
                <select v-model="diffEditSongTitle" class="flex-1 min-w-0 px-3 py-2 bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white">
                  <option value="">曲名を選択...</option>
                  <option v-for="song in effectiveSongsList" :key="song.title" :value="song.title">
                    {{ song.title.length > 40 ? song.title.substring(0, 37) + '...' : song.title }} (現在: {{ song.rank }})
                  </option>
                </select>
                <select v-model="diffEditNewRank" class="sm:w-40 px-3 py-2 bg-white dark:bg-slate-900 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white">
                  <option value="">移動先...</option>
                  <option v-for="r in availableRanks" :key="r" :value="r">
                    {{ r }}
                  </option>
                </select>
                <button @click="handleAddDiffChange" :disabled="!diffEditSongTitle || !diffEditNewRank" class="px-4 py-2 bg-indigo-50 dark:bg-indigo-900/30 text-indigo-600 dark:text-indigo-400 rounded-lg text-sm font-bold disabled:opacity-50 transition-colors whitespace-nowrap">
                  追加
                </button>
              </div>
            </div>

            <!-- Saved draft changes (applied to draft, not yet published) -->
            <div v-if="savedDiffChanges.length > 0" class="mb-3 space-y-2">
              <div v-if="savedPromotions.length > 0">
                <h4 class="text-xs font-bold text-emerald-600 dark:text-emerald-400 mb-1">▲ 昇格 ({{ savedPromotions.length }}件)</h4>
                <div class="space-y-1">
                  <div v-for="change in savedPromotions" :key="change.title" class="flex items-center justify-between bg-emerald-50 dark:bg-emerald-900/20 p-2.5 rounded-lg border border-emerald-200 dark:border-emerald-800/50 min-w-0" @mouseenter="handleSongHover(change.title, $event)" @mouseleave="handleSongLeave()">
                    <div class="text-sm text-slate-700 dark:text-slate-300 truncate flex-1 min-w-0 mr-2" :title="change.title">{{ change.title }}</div>
                    <div class="flex items-center gap-2 text-sm shrink-0">
                      <span class="line-through text-slate-400">{{ change.oldRank }}</span>
                      <span class="text-slate-400">→</span>
                      <span class="text-emerald-600 dark:text-emerald-400 font-bold">{{ change.newRank }}</span>
                    </div>
                    <button @click="handleRevertSavedChange(change)" :disabled="isSavingDiff" class="ml-2 text-red-400 hover:text-red-600 dark:hover:text-red-300 transition-colors shrink-0 p-1 disabled:opacity-40" title="取り消す">
                      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                        <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
              <div v-if="savedDemotions.length > 0">
                <h4 class="text-xs font-bold text-red-600 dark:text-red-400 mb-1">▼ 降格 ({{ savedDemotions.length }}件)</h4>
                <div class="space-y-1">
                  <div v-for="change in savedDemotions" :key="change.title" class="flex items-center justify-between bg-red-50 dark:bg-red-900/20 p-2.5 rounded-lg border border-red-200 dark:border-red-800/50 min-w-0" @mouseenter="handleSongHover(change.title, $event)" @mouseleave="handleSongLeave()">
                    <div class="text-sm text-slate-700 dark:text-slate-300 truncate flex-1 min-w-0 mr-2" :title="change.title">{{ change.title }}</div>
                    <div class="flex items-center gap-2 text-sm shrink-0">
                      <span class="line-through text-slate-400">{{ change.oldRank }}</span>
                      <span class="text-slate-400">→</span>
                      <span class="text-red-600 dark:text-red-400 font-bold">{{ change.newRank }}</span>
                    </div>
                    <button @click="handleRevertSavedChange(change)" :disabled="isSavingDiff" class="ml-2 text-red-400 hover:text-red-600 dark:hover:text-red-300 transition-colors shrink-0 p-1 disabled:opacity-40" title="取り消す">
                      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                        <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
              <div v-if="savedPlacements.length > 0">
                <h4 class="text-xs font-bold text-blue-600 dark:text-blue-400 mb-1">● 配置 ({{ savedPlacements.length }}件)</h4>
                <div class="space-y-1">
                  <div v-for="change in savedPlacements" :key="change.title" class="flex items-center justify-between bg-blue-50 dark:bg-blue-900/20 p-2.5 rounded-lg border border-blue-200 dark:border-blue-800/50 min-w-0" @mouseenter="handleSongHover(change.title, $event)" @mouseleave="handleSongLeave()">
                    <div class="text-sm text-slate-700 dark:text-slate-300 truncate flex-1 min-w-0 mr-2" :title="change.title">{{ change.title }}</div>
                    <div class="flex items-center gap-2 text-sm shrink-0">
                      <span class="text-slate-400 text-xs">{{ change.oldRank.length > 15 ? change.oldRank.substring(0, 12) + '...' : change.oldRank }}</span>
                      <span class="text-slate-400">→</span>
                      <span class="text-blue-600 dark:text-blue-400 font-bold">{{ change.newRank }}</span>
                    </div>
                    <button @click="handleRevertSavedChange(change)" :disabled="isSavingDiff" class="ml-2 text-red-400 hover:text-red-600 dark:hover:text-red-300 transition-colors shrink-0 p-1 disabled:opacity-40" title="取り消す">
                      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                        <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                      </svg>
                    </button>
                  </div>
                </div>
              </div>
            </div>

            <!-- Pending in-memory changes (not yet saved to draft) -->
            <div v-if="pendingDiffChanges.length > 0">
               <h4 class="text-xs font-bold text-slate-500 mb-2">保存前の変更一覧</h4>
               <div class="space-y-1">
                  <div v-for="change in filteredPendingChanges" :key="change.title" class="flex items-center justify-between bg-white dark:bg-slate-800 p-2.5 rounded-lg border border-slate-200 dark:border-slate-700 min-w-0" @mouseenter="handleSongHover(change.title, $event)" @mouseleave="handleSongLeave()">
                     <div class="text-sm font-bold text-slate-800 dark:text-white truncate flex-1 min-w-0 mr-2" :title="change.title">{{ change.title }}</div>
                     <div class="flex items-center gap-2 text-sm shrink-0">
                        <span class="line-through text-slate-400">{{ change.oldRank }}</span>
                        <span class="text-slate-400">→</span>
                        <span class="text-indigo-600 dark:text-indigo-400 font-bold">{{ change.newRank }}</span>
                     </div>
                     <button @click="handleRemoveDiffChange(change.title)" class="text-red-400 hover:text-red-600 dark:hover:text-red-300 p-1" title="元に戻す">
                        <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                          <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                        </svg>
                     </button>
                  </div>
               </div>
            </div>
            <div v-if="savedDiffChanges.length === 0 && pendingDiffChanges.length === 0" class="text-center text-sm py-8 text-slate-400 dark:text-slate-500 border border-dashed border-slate-300 dark:border-slate-700 rounded-xl">
              変更はありません。<br>上のフォームから楽曲を選んでランクを移動してください。
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Comment tooltip -->
    <div
      v-if="isOpen && tooltipSongKey && (tooltipComments.length > 0 || tooltipLoading)"
      class="fixed z-[200] bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-xl shadow-2xl p-3 w-80 overflow-y-auto pointer-events-none"
      :style="{ top: tooltipPosition.top + 'px', left: tooltipPosition.left + 'px', maxHeight: tooltipPosition.maxHeight + 'px' }"
    >
      <div v-if="tooltipLoading" class="text-xs text-slate-400 text-center py-2">読み込み中...</div>
      <div v-else class="space-y-2.5">
        <div v-for="(c, i) in tooltipComments" :key="i" class="flex items-start gap-2">
          <RankIcon v-if="c.totalBeatPt !== undefined" :rank-name="getRankInfo(c.totalBeatPt).name" :tier="getRankInfo(c.totalBeatPt).tier" size="sm" disable-party class="shrink-0 mt-0.5" />
          <div class="text-xs text-slate-700 dark:text-slate-300 whitespace-pre-wrap break-words leading-relaxed">{{ c.content }}</div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, watch, computed } from 'vue';
import { useAuth } from '../composables/useAuth';
import { useGameData } from '../composables/useGameData';
import RankIcon from './RankIcon.vue';
import { getRankInfo } from '../utils/beatTier';

const props = defineProps<{
  isOpen: boolean;
}>();

const emit = defineEmits<{
  (e: 'close'): void;
}>();

const { authHeaders } = useAuth();
const { songDataBody } = useGameData();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

const activeTab = ref<'songs' | 'difficulty'>('songs');
const errorMsg = ref('');
const successMsg = ref('');

// ── Draft status ────────────────────────────────────────
const hasDraftSongs = ref(false);
const hasDraftDiffTable = ref(false);
const draftSongs = ref<any[]>([]);

const difficultyCodeToName: Record<string, string> = {
  '1': 'BEG', '2': 'NOR', '3': 'HYP', '4': 'ANO', '10': 'LEG'
};

const groupedDraftSongs = computed(() => {
  const groups: Record<string, { title: string; artist: string; genre: string; difficulties: string[]; ids: number[] }> = {};
  for (const song of draftSongs.value) {
    if (!groups[song.title]) {
      groups[song.title] = { title: song.title, artist: song.artist || '', genre: song.genre || '', difficulties: [], ids: [] };
    }
    groups[song.title].difficulties.push(difficultyCodeToName[song.difficulty] || song.difficulty);
    groups[song.title].ids.push(song.id);
  }
  return Object.values(groups);
});

// ── Song form ───────────────────────────────────────────
const difficultyDefs = [
  { code: '1', label: 'BEG', notesKey: 'beginnerNotes' as const, levelKey: 'beginnerLevel' as const, bgClass: 'bg-emerald-50/50 dark:bg-emerald-900/10', labelClass: 'text-emerald-700 bg-emerald-100 dark:text-emerald-300 dark:bg-emerald-900/50' },
  { code: '2', label: 'NOR', notesKey: 'normalNotes' as const, levelKey: 'normalLevel' as const, bgClass: 'bg-blue-50/50 dark:bg-blue-900/10', labelClass: 'text-blue-700 bg-blue-100 dark:text-blue-300 dark:bg-blue-900/50' },
  { code: '3', label: 'HYP', notesKey: 'hyperNotes' as const, levelKey: 'hyperLevel' as const, bgClass: 'bg-amber-50/50 dark:bg-amber-900/10', labelClass: 'text-amber-700 bg-amber-100 dark:text-amber-300 dark:bg-amber-900/50' },
  { code: '4', label: 'ANO', notesKey: 'anotherNotes' as const, levelKey: 'anotherLevel' as const, bgClass: 'bg-red-50/50 dark:bg-red-900/10', labelClass: 'text-red-700 bg-red-100 dark:text-red-300 dark:bg-red-900/50' },
  { code: '10', label: 'LEG', notesKey: 'leggendariaNotes' as const, levelKey: 'leggendariaLevel' as const, bgClass: 'bg-purple-50/50 dark:bg-purple-900/10', labelClass: 'text-purple-700 bg-purple-100 dark:text-purple-300 dark:bg-purple-900/50' },
];

interface SongForm {
  title: string;
  artist: string;
  genre: string;
  bpm: string;
  beginnerNotes: number | null;
  beginnerLevel: number | null;
  normalNotes: number | null;
  normalLevel: number | null;
  hyperNotes: number | null;
  hyperLevel: number | null;
  anotherNotes: number | null;
  anotherLevel: number | null;
  leggendariaNotes: number | null;
  leggendariaLevel: number | null;
  wr: number | null;
  avg: number | null;
  coef: number | null;
  textage: string;
  [key: string]: string | number | null;
}

const defaultForm = (): SongForm => ({
  title: '', artist: '', genre: '', bpm: '',
  beginnerNotes: null, beginnerLevel: null,
  normalNotes: null, normalLevel: null,
  hyperNotes: null, hyperLevel: null,
  anotherNotes: null, anotherLevel: null,
  leggendariaNotes: null, leggendariaLevel: null,
  wr: null, avg: null, coef: null, textage: '',
});

const form = ref<SongForm>(defaultForm());

const isSubmitting = ref(false);
const isApplying = ref(false);
const isSavingDiff = ref(false);
const isGeneratingDraft = ref(false);

// Comment tooltip
const tooltipSongKey = ref('');
const tooltipComments = ref<Array<{ totalBeatPt: number; content: string; createdAt: string }>>([]);
const tooltipLoading = ref(false);
const tooltipPosition = ref({ top: 0, left: 0, maxHeight: 240 });
const commentCache = new Map<string, Array<any>>();

const activeDiffTable = ref<{ranks: {rank: string, songs: string[]}[]}>({ranks: []});
const originalDiffTable = ref<{ranks: {rank: string, songs: string[]}[]}>({ranks: []});
const pendingDiffChanges = ref<{title: string, oldRank: string, newRank: string}[]>([]);
const diffEditSongTitle = ref('');
const diffEditNewRank = ref('');

// Level filter (based on official level from song_data)
const showLv12 = ref(true);
const showLv11 = ref(true);

const officialLevelMap = computed(() => {
  const map = new Map<string, number>();
  for (const song of songDataBody.value) {
    if (song.difficulty === '4') map.set(`${song.title}|ANOTHER`, song.level);
    else if (song.difficulty === '10') map.set(`${song.title}|LEGGENDARIA`, song.level);
  }
  return map;
});

const matchesLevelFilter = (songEntry: string): boolean => {
  const parsed = parseSongTitle(songEntry);
  const level = officialLevelMap.value.get(`${parsed.title}|${parsed.difficultyName}`);
  if (level === 12) return showLv12.value;
  if (level === 11) return showLv11.value;
  return showLv12.value || showLv11.value; // unknown → show if either checked
};

const savedDiffChanges = computed(() => {
  if (!activeDiffTable.value?.ranks?.length || !originalDiffTable.value?.ranks?.length) return [];
  const activeMap = new Map<string, string>();
  for (const r of activeDiffTable.value.ranks) {
    for (const s of r.songs) activeMap.set(s, r.rank);
  }
  const changes: {title: string, oldRank: string, newRank: string}[] = [];
  for (const r of originalDiffTable.value.ranks) {
    for (const s of r.songs) {
      const activeRank = activeMap.get(s);
      if (activeRank !== undefined && activeRank !== r.rank) {
        changes.push({ title: s, oldRank: activeRank, newRank: r.rank });
      }
    }
  }
  return changes;
});

const savedPlacements = computed(() => savedDiffChanges.value.filter(c =>
  (isNaN(parseFloat(c.oldRank)) || isNaN(parseFloat(c.newRank))) && matchesLevelFilter(c.title)));
const savedPromotions = computed(() => savedDiffChanges.value.filter(c => {
  const o = parseFloat(c.oldRank), n = parseFloat(c.newRank);
  return !isNaN(o) && !isNaN(n) && n > o && matchesLevelFilter(c.title);
}));
const savedDemotions = computed(() => savedDiffChanges.value.filter(c => {
  const o = parseFloat(c.oldRank), n = parseFloat(c.newRank);
  return !isNaN(o) && !isNaN(n) && n < o && matchesLevelFilter(c.title);
}));

const effectiveSongsList = computed(() => {
  if (!originalDiffTable.value?.ranks) return [];
  const list: {title: string, rank: string}[] = [];
  for (const r of originalDiffTable.value.ranks) {
    for (const s of r.songs) {
       const pending = pendingDiffChanges.value.find(p => p.title === s);
       const effectiveRank = pending ? pending.newRank : r.rank;
       if (!matchesLevelFilter(s)) continue;
       list.push({ title: s, rank: effectiveRank });
    }
  }
  return list.sort((a, b) => a.title.localeCompare(b.title));
});

const filteredPendingChanges = computed(() =>
  pendingDiffChanges.value.filter(c => matchesLevelFilter(c.title))
);

const availableRanks = computed(() => {
  if (!originalDiffTable.value?.ranks) return [];
  return originalDiffTable.value.ranks.map(r => r.rank);
});

const originalRankOf = (title: string) => {
    for (const r of originalDiffTable.value.ranks) {
        if (r.songs.includes(title)) return r.rank;
    }
    return '';
};

const parseSongTitle = (songEntry: string): { title: string; difficultyName: 'ANOTHER' | 'LEGGENDARIA' } => {
  if (songEntry.endsWith('[L]')) {
    return { title: songEntry.slice(0, -3), difficultyName: 'LEGGENDARIA' };
  }
  return { title: songEntry, difficultyName: 'ANOTHER' };
};

const handleSongHover = async (songEntry: string, event: MouseEvent) => {
  const parsed = parseSongTitle(songEntry);
  const key = `${parsed.title}|${parsed.difficultyName}`;
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
  const maxH = Math.min(240, Math.max(100, window.innerHeight - rect.bottom - 20));
  tooltipPosition.value = {
    top: rect.bottom + 4,
    left: Math.min(rect.left, window.innerWidth - 340),
    maxHeight: maxH,
  };
  tooltipSongKey.value = key;

  if (commentCache.has(key)) {
    tooltipComments.value = commentCache.get(key)!;
    return;
  }

  tooltipLoading.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/tier-comments?title=${encodeURIComponent(parsed.title)}&difficultyName=${encodeURIComponent(parsed.difficultyName)}`);
    if (res.ok) {
      const data = await res.json();
      commentCache.set(key, data);
      if (tooltipSongKey.value === key) tooltipComments.value = data;
    }
  } catch { /* ignore */ } finally {
    tooltipLoading.value = false;
  }
};

const handleSongLeave = () => {
  tooltipSongKey.value = '';
  tooltipComments.value = [];
};

const handleAddDiffChange = () => {
    if (!diffEditSongTitle.value || !diffEditNewRank.value) return;
    const currentEffective = effectiveSongsList.value.find(s => s.title === diffEditSongTitle.value)?.rank;
    if (currentEffective === diffEditNewRank.value) return;

    const originalR = originalRankOf(diffEditSongTitle.value);
    
    const existingIndex = pendingDiffChanges.value.findIndex(p => p.title === diffEditSongTitle.value);
    if (existingIndex !== -1) {
        if (originalR === diffEditNewRank.value) {
            pendingDiffChanges.value.splice(existingIndex, 1);
        } else {
            pendingDiffChanges.value[existingIndex].newRank = diffEditNewRank.value;
        }
    } else {
        pendingDiffChanges.value.push({
            title: diffEditSongTitle.value,
            oldRank: originalR,
            newRank: diffEditNewRank.value
        });
    }
    diffEditSongTitle.value = '';
    diffEditNewRank.value = '';
};

const handleRemoveDiffChange = (title: string) => {
    pendingDiffChanges.value = pendingDiffChanges.value.filter(p => p.title !== title);
};

const handleRevertSavedChange = async (change: {title: string, oldRank: string, newRank: string}) => {
    if (!confirm(`「${change.title}」のドラフト変更を取り消しますか？`)) return;

    const newTable = JSON.parse(JSON.stringify(originalDiffTable.value));
    for (const r of newTable.ranks) {
        r.songs = r.songs.filter((s: string) => s !== change.title);
    }
    const targetRank = newTable.ranks.find((r: any) => r.rank === change.oldRank);
    if (targetRank) targetRank.songs.push(change.title);

    isSavingDiff.value = true;
    errorMsg.value = '';
    try {
        const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
            method: 'PUT',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(newTable),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Error');
        originalDiffTable.value = newTable;
        successMsg.value = 'ドラフト変更を取り消しました';
        const statusRes = await fetch(`${API_BASE}/api/admin/game-data/status`, { headers: authHeaders() });
        if (statusRes.ok) {
            const status = await statusRes.json();
            hasDraftDiffTable.value = status.hasDraftDifficultyTable;
        }
    } catch (e: any) {
        errorMsg.value = '取り消しエラー: ' + e.message;
    } finally {
        isSavingDiff.value = false;
    }
};

// ── Load data ───────────────────────────────────────────
const loadData = async () => {
  try {
    // Fetch draft status
    const statusRes = await fetch(`${API_BASE}/api/admin/game-data/status`, { headers: authHeaders() });
    if (statusRes.ok) {
      const status = await statusRes.json();
      hasDraftSongs.value = status.hasDraftSongs;
      hasDraftDiffTable.value = status.hasDraftDifficultyTable;
    }

    // Fetch draft songs
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) {
      draftSongs.value = await songsRes.json();
    }

    // Fetch difficulty table draft
    const diffRes = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, { headers: authHeaders() });
    if (diffRes.ok) {
      originalDiffTable.value = await diffRes.json();
      pendingDiffChanges.value = [];
    }
  } catch (e: any) {
    console.error('Failed to load admin game data:', e);
  }

  // Fetch active difficulty table independently (for diff comparison)
  try {
    const activeRes = await fetch(`${API_BASE}/api/game-data/difficulty-table`);
    if (activeRes.ok) {
      activeDiffTable.value = await activeRes.json();
    }
  } catch (e) {
    console.warn('Failed to fetch active difficulty table for diff:', e);
  }
};

watch(() => props.isOpen, (val) => {
  if (val) {
    errorMsg.value = '';
    successMsg.value = '';
    commentCache.clear();
    loadData();
  }
});

// ── Add song ────────────────────────────────────────────
const handleAddSong = async () => {
  if (!form.value.title) return;
  isSubmitting.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(form.value),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Error');
    
    // Auto-add to Uncategorized(other) if Lv11 or 12
    const addedSongsToDiff: string[] = [];
    if (form.value.anotherLevel === 11 || form.value.anotherLevel === 12) {
        addedSongsToDiff.push(form.value.title);
    }
    if (form.value.leggendariaLevel === 11 || form.value.leggendariaLevel === 12) {
        addedSongsToDiff.push(form.value.title + '[L]');
    }
    
    if (addedSongsToDiff.length > 0 && originalDiffTable.value.ranks) {
        const newTable = JSON.parse(JSON.stringify(originalDiffTable.value));
        const uncatOther = newTable.ranks.find((r: any) => r.rank === 'Uncategorized(other)');
        if (uncatOther) {
            let changed = false;
            for (const s of addedSongsToDiff) {
                let exists = false;
                for (const r of newTable.ranks) {
                    if (r.songs.includes(s)) exists = true;
                }
                if (!exists) {
                    uncatOther.songs.push(s);
                    changed = true;
                }
            }
            if (changed) {
                const diffApiRes = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
                  method: 'PUT',
                  headers: authHeaders({ 'Content-Type': 'application/json' }),
                  body: JSON.stringify(newTable),
                });
                if (diffApiRes.ok) {
                    originalDiffTable.value = newTable;
                    hasDraftDiffTable.value = true;
                }
            }
        }
    }

    successMsg.value = data.message;
    form.value = defaultForm();
    hasDraftSongs.value = true;
    
    // Refresh draft songs
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) draftSongs.value = await songsRes.json();
  } catch (e: any) {
    errorMsg.value = '追加エラー: ' + e.message;
  } finally {
    isSubmitting.value = false;
  }
};

// ── Delete draft song ───────────────────────────────────
const handleDeleteDraftSong = async (ids: number[]) => {
  if (!confirm('このドラフト楽曲を削除しますか？')) return;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    for (const id of ids) {
      const res = await fetch(`${API_BASE}/api/admin/game-data/songs/draft/${id}`, {
        method: 'DELETE',
        headers: authHeaders(),
      });
      if (!res.ok) {
        const data = await res.json();
        throw new Error(data.message || 'Error');
      }
    }
    successMsg.value = '削除しました';
    
    // Refresh
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) draftSongs.value = await songsRes.json();
    hasDraftSongs.value = draftSongs.value.length > 0;
  } catch (e: any) {
    errorMsg.value = '削除エラー: ' + e.message;
  }
};

// ── Generate draft from votes ─────────────────────────
const generateDraftFromVotes = async () => {
  if (!confirm('現在のドラフトを全削除し、投票結果からドラフトを再生成しますか？')) return;

  isGeneratingDraft.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const [activeRes, votesRes] = await Promise.all([
      fetch(`${API_BASE}/api/game-data/difficulty-table`),
      fetch(`${API_BASE}/api/tier-votes/all`),
    ]);
    if (!activeRes.ok || !votesRes.ok) throw new Error('データ取得に失敗しました');

    const activeTable: { ranks: Array<{ rank: string; songs: string[] }> } = await activeRes.json();
    const votesData: Array<Record<string, any>> = await votesRes.json();

    // Build vote map: "title|difficultyName" -> { PROMOTE: n, STAY: n, DEMOTE: n, "12.3": n, ... }
    const voteMap = new Map<string, Record<string, number>>();
    for (const item of votesData) {
      const { title, difficultyName, ...rest } = item;
      const counts: Record<string, number> = {};
      for (const [k, v] of Object.entries(rest)) counts[k] = Number(v) || 0;
      voteMap.set(`${title}|${difficultyName}`, counts);
    }

    const newTable = JSON.parse(JSON.stringify(activeTable));
    const ranks: Array<{ rank: string; songs: string[] }> = newTable.ranks;

    // Identify numeric (non-uncategorized) rank indices
    const numericRankIndices: number[] = [];
    for (let i = 0; i < ranks.length; i++) {
      if (!ranks[i].rank.toLowerCase().includes('uncategorized')) numericRankIndices.push(i);
    }

    // Tier options for uncategorized (11.0-13.0 in 0.1 steps)
    const TIER_OPTIONS: string[] = [];
    for (let i = 110; i <= 130; i++) TIER_OPTIONS.push((i / 10).toFixed(1));

    // Phase 1: Collect all moves (iterate originals, apply later to avoid double-moves)
    const moves: Array<{ song: string; fromIdx: number; toIdx: number }> = [];

    for (let ri = 0; ri < ranks.length; ri++) {
      const rank = ranks[ri];
      const isUncat = rank.rank.toLowerCase().includes('uncategorized');

      for (const songEntry of rank.songs) {
        const parsed = parseSongTitle(songEntry);
        const key = `${parsed.title}|${parsed.difficultyName}`;
        const votes = voteMap.get(key);
        if (!votes) continue;

        if (isUncat) {
          // Uncategorized: place at top-voted tier
          let bestTier: string | null = null;
          let bestCount = 0;
          for (const tier of TIER_OPTIONS) {
            const count = votes[tier] ?? 0;
            if (count > bestCount) { bestTier = tier; bestCount = count; }
          }
          if (bestTier && bestCount > 0) {
            const targetIdx = ranks.findIndex(r => r.rank === bestTier);
            if (targetIdx !== -1) moves.push({ song: songEntry, fromIdx: ri, toIdx: targetIdx });
          }
        } else {
          // Ranked: majority vote (STAY wins if >= both, tie = no change)
          const promote = votes['PROMOTE'] ?? 0;
          const stay = votes['STAY'] ?? 0;
          const demote = votes['DEMOTE'] ?? 0;
          if (promote === 0 && stay === 0 && demote === 0) continue;
          if (stay >= promote && stay >= demote) continue;
          if (promote === demote) continue;

          const curNumIdx = numericRankIndices.indexOf(ri);
          if (curNumIdx === -1) continue;

          if (promote > demote && curNumIdx > 0) {
            moves.push({ song: songEntry, fromIdx: ri, toIdx: numericRankIndices[curNumIdx - 1] });
          } else if (demote > promote && curNumIdx < numericRankIndices.length - 1) {
            moves.push({ song: songEntry, fromIdx: ri, toIdx: numericRankIndices[curNumIdx + 1] });
          }
        }
      }
    }

    // Phase 2: Apply all moves at once
    for (const { song, fromIdx, toIdx } of moves) {
      ranks[fromIdx].songs = ranks[fromIdx].songs.filter((s: string) => s !== song);
      ranks[toIdx].songs.push(song);
    }

    // Save the new draft
    const saveRes = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(newTable),
    });
    const saveData = await saveRes.json();
    if (!saveRes.ok) throw new Error(saveData.message || 'Error');

    originalDiffTable.value = newTable;
    activeDiffTable.value = activeTable;
    pendingDiffChanges.value = [];
    hasDraftDiffTable.value = true;
    successMsg.value = `投票結果から ${moves.length}件 の変更をドラフトに反映しました`;
  } catch (e: any) {
    errorMsg.value = 'ドラフト生成エラー: ' + e.message;
  } finally {
    isGeneratingDraft.value = false;
  }
};

// ── Save difficulty table ───────────────────────────────
const handleSaveDiffTable = async () => {
  isSavingDiff.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const newTable = JSON.parse(JSON.stringify(originalDiffTable.value));
    
    for (const change of pendingDiffChanges.value) {
       for (const r of newTable.ranks) {
          r.songs = r.songs.filter((s: string) => s !== change.title);
       }
       const targetRank = newTable.ranks.find((r: any) => r.rank === change.newRank);
       if (targetRank) {
           targetRank.songs.push(change.title);
       }
    }

    const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
      method: 'PUT',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(newTable),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Error');
    
    originalDiffTable.value = newTable;
    pendingDiffChanges.value = [];
    successMsg.value = data.message;
    hasDraftDiffTable.value = true;
  } catch (e: any) {
    errorMsg.value = '保存エラー: ' + e.message;
  } finally {
    isSavingDiff.value = false;
  }
};

// ── Apply draft ─────────────────────────────────────────
const handleApplyDraft = async () => {
  if (!confirm('ドラフトを適用しますか？全ユーザーのポイント再計算が実行されます。')) return;
  
  isApplying.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/apply`, {
      method: 'POST',
      headers: authHeaders(),
    });
    const data = await res.json();
    if (!res.ok && res.status !== 202) throw new Error(data.message || 'Error');
    
    successMsg.value = data.message;
    hasDraftSongs.value = false;
    hasDraftDiffTable.value = false;
    draftSongs.value = [];
  } catch (e: any) {
    errorMsg.value = '適用エラー: ' + e.message;
  } finally {
    isApplying.value = false;
  }
};
</script>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.2s ease-out forwards;
}
@keyframes fadeIn {
  from { opacity: 0; transform: scale(0.98); }
  to { opacity: 1; transform: scale(1); }
}
</style>
