<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[110] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm" @click.self="$emit('close')">
      <div class="bg-white dark:bg-slate-900 w-full max-w-3xl rounded-md shadow-xl flex flex-col overflow-hidden max-h-[90vh] animate-fade-in border border-slate-200 dark:border-slate-800">
        
        <!-- Header -->
        <div class="p-5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between shrink-0">
          <h2 class="text-xl font-bold text-slate-800 dark:text-white flex items-center gap-2">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-indigo-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19V6l12-3v13M9 19c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zm12-3c0 1.105-1.343 2-3 2s-3-.895-3-2 1.343-2 3-2 3 .895 3 2zM9 10l12-3" />
            </svg>
            ゲームデータ管理
          </h2>
          <div class="flex items-center gap-2">
            <!-- Apply buttons (楽曲と難易度表で独立に適用) -->
            <button
              @click="handleApplyDraftSongs"
              :disabled="isApplyingSongs || isApplyingDiff || draftSongs.length === 0"
              class="px-3 py-1.5 bg-emerald-500 hover:bg-emerald-600 text-white rounded-lg text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <svg v-if="isApplyingSongs" class="animate-spin h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
              {{ isApplyingSongs ? '適用中...' : '楽曲を適用' }}
            </button>
            <button
              @click="handleApplyDraftDiffTable"
              :disabled="isApplyingSongs || isApplyingDiff || savedDiffChanges.length === 0"
              class="px-3 py-1.5 bg-emerald-500 hover:bg-emerald-600 text-white rounded-lg text-sm font-bold flex items-center gap-1 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <svg v-if="isApplyingDiff" class="animate-spin h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
              {{ isApplyingDiff ? '適用中...' : '難易度表を適用' }}
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
        <div v-if="errorMsg" class="mx-5 mt-4 bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-400 p-3 rounded-md border border-red-200 dark:border-red-800 text-sm">{{ errorMsg }}</div>
        <div v-if="successMsg" class="mx-5 mt-4 bg-green-50 dark:bg-green-900/20 text-green-700 dark:text-green-400 p-3 rounded-md border border-green-200 dark:border-green-800 text-sm">{{ successMsg }}</div>

        <!-- Tab content -->
        <div class="flex-1 overflow-y-auto p-5">

          <!-- Songs Tab -->
          <div v-if="activeTab === 'songs'">
            <!-- Edit existing active song -->
            <div class="bg-slate-50 dark:bg-slate-800/50 rounded-md border border-slate-200 dark:border-slate-700 p-4 mb-4">
              <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300 mb-2">既存曲を編集</h3>
              <input
                v-model="activeSearchQuery"
                :disabled="isEditingExistingSong"
                type="text"
                placeholder="曲名で検索..."
                class="w-full px-3 py-2 bg-white dark:bg-slate-800 border border-slate-300 dark:border-slate-600 rounded-lg text-sm text-slate-800 dark:text-white focus:ring-2 focus:ring-indigo-500 focus:border-transparent disabled:opacity-50"
              />
              <div v-if="filteredActiveSongs.length > 0 && !isEditingExistingSong" class="mt-2 max-h-48 overflow-y-auto space-y-1">
                <button
                  v-for="g in filteredActiveSongs"
                  :key="g.title"
                  @click="handleBeginEditActiveSong(g)"
                  :disabled="isPreparingEdit"
                  class="w-full text-left px-3 py-2 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg hover:bg-indigo-50 dark:hover:bg-indigo-900/30 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                >
                  <div class="text-sm font-bold text-slate-800 dark:text-white truncate">{{ g.title }}</div>
                  <div class="text-xs text-slate-500 dark:text-slate-400">
                    {{ g.artist }} / {{ g.genre }} / {{ g.difficulties.join(', ') }}
                  </div>
                </button>
              </div>
              <div v-else-if="activeSearchQuery && !isEditingExistingSong" class="mt-2 text-xs text-slate-400 dark:text-slate-500">
                該当する楽曲が見つかりません
              </div>
              <div v-if="isPreparingEdit" class="mt-2 text-xs text-slate-500 dark:text-slate-400">編集用ドラフトを準備中...</div>
            </div>

            <!-- Add Song Form -->
            <div class="bg-slate-50 dark:bg-slate-800/50 rounded-md border border-slate-200 dark:border-slate-700 p-4 mb-4">
              <div v-if="isEditingExistingSong" class="flex items-center justify-between mb-3 p-2 bg-indigo-50 dark:bg-indigo-900/20 rounded-lg border border-indigo-200 dark:border-indigo-800/50">
                <div class="text-sm text-indigo-700 dark:text-indigo-400">
                  <span class="font-bold">編集中:</span> {{ editingSongTitle }}
                </div>
                <button @click="cancelEditExistingSong" class="text-xs px-2 py-1 bg-white dark:bg-slate-800 text-slate-600 dark:text-slate-300 border border-slate-300 dark:border-slate-600 rounded hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors">
                  キャンセル
                </button>
              </div>
              <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300 mb-3">{{ isEditingExistingSong ? '楽曲情報の編集' : '新曲追加' }}</h3>
              
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
                  <span class="w-20 text-xs font-bold shrink-0 text-center py-1 rounded" :class="diff.labelClass">{{ diff.label }}</span>
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
                @click="isEditingExistingSong ? handleUpdateEditingSong() : handleAddSong()"
                :disabled="isSubmitting || !form.title"
                class="w-full py-2 bg-indigo-500 hover:bg-indigo-600 text-white rounded-lg text-sm font-bold transition-colors disabled:opacity-40 disabled:cursor-not-allowed flex items-center justify-center gap-2"
              >
                <svg v-if="isSubmitting" class="animate-spin h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24"><circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle><path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path></svg>
                {{ isEditingExistingSong ? 'ドラフトを更新' : 'ドラフトに追加' }}
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

            <div class="bg-slate-50 dark:bg-slate-800/50 rounded-md border border-slate-200 dark:border-slate-700 p-4 mb-4">
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
              <div v-if="hasSavedPromotionsOrDemotions" class="flex justify-end">
                <button
                  @click="handleRevertAllPromotionsDemotions"
                  :disabled="isSavingDiff"
                  class="px-3 py-1 bg-amber-50 dark:bg-amber-900/30 text-amber-700 dark:text-amber-400 border border-amber-300 dark:border-amber-800/50 rounded-lg text-xs font-bold hover:bg-amber-100 dark:hover:bg-amber-900/50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                  title="新規配置（Uncategorized との相互移動）はそのまま残し、昇格・降格のみを取り消します"
                >
                  昇格・降格を一括取り消し
                </button>
              </div>
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
            <div v-if="savedDiffChanges.length === 0 && pendingDiffChanges.length === 0" class="text-center text-sm py-8 text-slate-400 dark:text-slate-500 border border-dashed border-slate-300 dark:border-slate-700 rounded-md">
              変更はありません。<br>上のフォームから楽曲を選んでランクを移動してください。
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Comment tooltip -->
    <div
      v-if="isOpen && tooltipSongKey && (tooltipComments.length > 0 || tooltipLoading)"
      class="fixed z-[200] bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-md shadow-xl p-3 w-80 overflow-y-auto pointer-events-none"
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
/**
 * 【コンポーネントの役割】 管理者用のゲームデータ管理モーダル（2 タブ）。
 *
 * 機能:
 *  - [songs] タブ: 新曲をドラフト追加（難易度別ノーツ数 + レベル + ANO/LEGG 用詳細）
 *  - [difficulty] タブ: 非公式難易度表の GUI 編集。曲をドラッグではなくセレクトで移動
 *  - 投票結果からドラフトを自動生成（PROMOTE/STAY/DEMOTE 多数決 + Uncategorized の tier 配置）
 *  - [適用（公開）] ボタンでドラフトを本番公開 → 全ユーザーの PT 再計算がキックされる
 *  - 難易度表の楽曲にホバーすると tier コメントが吹き出しで表示される
 *
 * props:
 *  - isOpen: モーダル開閉
 * emits:
 *  - close: 閉じる
 */
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

/** 現在アクティブなタブ。 */
const activeTab = ref<'songs' | 'difficulty'>('songs');
/** 赤帯で表示するエラー文言。 */
const errorMsg = ref('');
/** 緑帯で表示する成功文言。 */
const successMsg = ref('');

// ── ドラフト状態 ─────────────────────────────────────
/** draft_songs テーブルに未公開楽曲があるか。 */
const hasDraftSongs = ref(false);
/** difficulty_table_draft が本番と乖離しているか。 */
const hasDraftDiffTable = ref(false);
/** ドラフト追加済みの楽曲（難易度単位で複数行の可能性あり）。 */
const draftSongs = ref<any[]>([]);
/** 現在公開中の active 楽曲（既存曲編集の検索対象）。 */
const activeSongs = ref<any[]>([]);

// ── 既存曲編集モード ─────────────────────────────────
/** フォームが「既存曲編集」モードに入っているか。 */
const isEditingExistingSong = ref(false);
/** 編集中の楽曲タイトル（表示用）。 */
const editingSongTitle = ref('');
/** 編集対象の難易度ごとの draft レコード ID マップ（code → draftId）。保存時の PUT 先 ID を決めるために使う。 */
const editingDraftIdByDiff = ref<Record<string, number | null>>({});
/** 既存曲検索の文字列。 */
const activeSearchQuery = ref('');
/** 編集用 draft 作成中（from-active 呼び出し中）のフラグ。 */
const isPreparingEdit = ref(false);

const difficultyCodeToName: Record<string, string> = {
  '1': 'BEG', '2': 'NOR', '3': 'HYP', '4': 'ANO', '10': 'LEG'
};

/**
 * 【computed の役割】 ドラフト楽曲を曲名単位にまとめて、難易度ラベルの配列と id の配列を合成して返す。
 * 1 曲に対して複数難易度が存在する場合、一覧表示で 1 行に統合するための整形。
 */
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

/**
 * 【computed の役割】 active 楽曲を曲名単位にまとめる（既存曲編集の検索候補として表示）。
 * 各グループに生レコード配列を添えておき、選択時にそのまま from-active へ回せるようにする。
 */
const groupedActiveSongs = computed(() => {
  const groups: Record<string, { title: string; artist: string; genre: string; difficulties: string[]; records: any[] }> = {};
  for (const song of activeSongs.value) {
    if (!groups[song.title]) {
      groups[song.title] = { title: song.title, artist: song.artist || '', genre: song.genre || '', difficulties: [], records: [] };
    }
    groups[song.title].difficulties.push(difficultyCodeToName[song.difficulty] || song.difficulty);
    groups[song.title].records.push(song);
  }
  return Object.values(groups);
});

/** 検索文字列で絞り込んだ active 曲。最大 30 件まで（候補が多いときに UI が潰れないように）。 */
const filteredActiveSongs = computed(() => {
  const q = activeSearchQuery.value.trim().toLowerCase();
  if (!q) return [];
  return groupedActiveSongs.value.filter(g => g.title.toLowerCase().includes(q)).slice(0, 30);
});

// ── 楽曲追加フォーム定義 ────────────────────────────
/** 難易度 5 種のメタデータ（コード / ラベル / フォームのキー名 / 色クラス）。 */
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

/** 【関数の役割】 フォームの初期値。追加完了後にこれで ref をリセット。 */
const defaultForm = (): SongForm => ({
  title: '', artist: '', genre: '', bpm: '',
  beginnerNotes: null, beginnerLevel: null,
  normalNotes: null, normalLevel: null,
  hyperNotes: null, hyperLevel: null,
  anotherNotes: null, anotherLevel: null,
  leggendariaNotes: null, leggendariaLevel: null,
  wr: null, avg: null, coef: null, textage: '',
});

/** 楽曲追加フォームの状態。 */
const form = ref<SongForm>(defaultForm());

/** 新曲追加ボタンの二重送信防止フラグ。 */
const isSubmitting = ref(false);
/** 楽曲ドラフトの「適用（公開）」実行中フラグ。 */
const isApplyingSongs = ref(false);
/** 難易度表ドラフトの「適用（公開）」実行中フラグ。 */
const isApplyingDiff = ref(false);
/** 難易度表のドラフト保存中フラグ。 */
const isSavingDiff = ref(false);
/** 「投票から生成」実行中フラグ。 */
const isGeneratingDraft = ref(false);

// コメントツールチップ関連（楽曲ホバー時に出す吹き出し）
/** 現在ホバー中の楽曲キー（"title|difficultyName"）。 */
const tooltipSongKey = ref('');
/** ツールチップに表示するコメント一覧。 */
const tooltipComments = ref<Array<{ totalBeatPt: number; content: string; createdAt: string }>>([]);
/** コメント読み込み中フラグ。 */
const tooltipLoading = ref(false);
/** ツールチップの絶対位置。画面端で見切れないよう動的に調整。 */
const tooltipPosition = ref({ top: 0, left: 0, maxHeight: 240 });
/** コメント取得結果のキャッシュ（楽曲キー -> コメント配列）。モーダル再オープンで消える。 */
const commentCache = new Map<string, Array<any>>();

/** 現在公開中の難易度表（比較用）。 */
const activeDiffTable = ref<{ranks: {rank: string, songs: string[]}[]}>({ranks: []});
/** 現在のドラフト版難易度表。編集はこれをコピーして差分生成。 */
const originalDiffTable = ref<{ranks: {rank: string, songs: string[]}[]}>({ranks: []});
/** 未保存の変更。保存ボタン押下までメモリに留める。 */
const pendingDiffChanges = ref<{title: string, oldRank: string, newRank: string}[]>([]);
/** 移動対象の楽曲タイトル（セレクト）。 */
const diffEditSongTitle = ref('');
/** 移動先ランク（セレクト）。 */
const diffEditNewRank = ref('');

// レベルフィルター（song_data の公式レベルを参照）
/** ☆12 を表示するか。 */
const showLv12 = ref(true);
/** ☆11 を表示するか。 */
const showLv11 = ref(true);

/** 【computed の役割】 song_data から「曲名|難易度名」→ 公式レベル のマップを構築。レベルフィルターで使用。 */
const officialLevelMap = computed(() => {
  const map = new Map<string, number>();
  for (const song of songDataBody.value) {
    if (song.difficulty === '4') map.set(`${song.title}|ANOTHER`, song.level);
    else if (song.difficulty === '10') map.set(`${song.title}|LEGGENDARIA`, song.level);
  }
  return map;
});

/**
 * 【関数の役割】 難易度表のエントリがレベルフィルタ条件を満たすかを判定する。
 * 公式レベルが取得できない曲はどちらかのトグルが ON なら表示する（安全側フォールバック）。
 */
const matchesLevelFilter = (songEntry: string): boolean => {
  const parsed = parseSongTitle(songEntry);
  const level = officialLevelMap.value.get(`${parsed.title}|${parsed.difficultyName}`);
  if (level === 12) return showLv12.value;
  if (level === 11) return showLv11.value;
  return showLv12.value || showLv11.value;
};

/**
 * 【computed の役割】 現在公開中の難易度表 vs ドラフト版の差分を抽出する。
 * 保存済みだが未公開の変更のみが対象。未保存の pendingDiffChanges は含まない。
 */
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

/** 数値化できない（Uncategorized → 数値ティア）移動 = 「配置」に分類。 */
const savedPlacements = computed(() => savedDiffChanges.value.filter(c =>
  (isNaN(parseFloat(c.oldRank)) || isNaN(parseFloat(c.newRank))) && matchesLevelFilter(c.title)));
/** 数値比較で上位へ移動 = 「昇格」。 */
const savedPromotions = computed(() => savedDiffChanges.value.filter(c => {
  const o = parseFloat(c.oldRank), n = parseFloat(c.newRank);
  return !isNaN(o) && !isNaN(n) && n > o && matchesLevelFilter(c.title);
}));
/** 数値比較で下位へ移動 = 「降格」。 */
const savedDemotions = computed(() => savedDiffChanges.value.filter(c => {
  const o = parseFloat(c.oldRank), n = parseFloat(c.newRank);
  return !isNaN(o) && !isNaN(n) && n < o && matchesLevelFilter(c.title);
}));

/** レベルフィルタを無視した「昇格 or 降格」全件（一括取り消しボタンの有効化判定に使う）。 */
const hasSavedPromotionsOrDemotions = computed(() => savedDiffChanges.value.some(c => {
  const o = parseFloat(c.oldRank), n = parseFloat(c.newRank);
  return !isNaN(o) && !isNaN(n);
}));

/**
 * 【computed の役割】 ドラフト + 未保存変更を反映した「実効ランク」で並べた曲一覧。
 * 移動セレクトの選択肢として使う。タイトル昇順。
 */
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

/** 未保存変更のうち、レベルフィルタ条件を満たすもののみに絞った配列。 */
const filteredPendingChanges = computed(() =>
  pendingDiffChanges.value.filter(c => matchesLevelFilter(c.title))
);

/** 移動先ランクのセレクト選択肢（ドラフト版ランクの全名前）。 */
const availableRanks = computed(() => {
  if (!originalDiffTable.value?.ranks) return [];
  return originalDiffTable.value.ranks.map(r => r.rank);
});

/** 【関数の役割】 指定楽曲の、ドラフト上での現在ランクを返す（差分ベースライン）。 */
const originalRankOf = (title: string) => {
    for (const r of originalDiffTable.value.ranks) {
        if (r.songs.includes(title)) return r.rank;
    }
    return '';
};

/**
 * 【関数の役割】 難易度表エントリの文字列を "曲名" と "難易度名" に分解する。
 * 末尾 [L] 付きは LEGGENDARIA、それ以外は ANOTHER 扱い。
 */
const parseSongTitle = (songEntry: string): { title: string; difficultyName: 'ANOTHER' | 'LEGGENDARIA' } => {
  if (songEntry.endsWith('[L]')) {
    return { title: songEntry.slice(0, -3), difficultyName: 'LEGGENDARIA' };
  }
  return { title: songEntry, difficultyName: 'ANOTHER' };
};

/**
 * 【関数の役割】 曲行をホバーした際のツールチップ表示処理。
 * 画面端の見切れ対策で位置を調整し、キャッシュ済みなら即表示、未取得なら API 経由で取得する。
 */
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

/** 【関数の役割】 ホバーから外れた際にツールチップを閉じる。 */
const handleSongLeave = () => {
  tooltipSongKey.value = '';
  tooltipComments.value = [];
};

/**
 * 【関数の役割】 「追加」ボタンで未保存変更 pendingDiffChanges に 1 件エントリする。
 * 元のランクと同じ場所に戻す操作なら、既存エントリを削除して差分 0 にする。
 */
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

/** 【関数の役割】 未保存変更から 1 件を取り消す。 */
const handleRemoveDiffChange = (title: string) => {
    pendingDiffChanges.value = pendingDiffChanges.value.filter(p => p.title !== title);
};

/**
 * 【関数の役割】 保存済み（ドラフトに書き込み済みだが未公開）の変更を取り消し、
 * 指定楽曲を「現在公開中の位置」に戻した新しいドラフトを PUT で保存する。
 */
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

/**
 * 【関数の役割】 ドラフトに保存済みの「昇格」「降格」をまとめて取り消し、それぞれ公開中の位置に戻す。
 * 新規配置（Uncategorized ↔ 数値ランクの移動）は対象外で、そのまま残る。
 * レベルフィルタに関係なく全昇格・降格が対象。
 */
const handleRevertAllPromotionsDemotions = async () => {
    const targets = savedDiffChanges.value.filter(c => {
        const o = parseFloat(c.oldRank), n = parseFloat(c.newRank);
        return !isNaN(o) && !isNaN(n);
    });
    if (targets.length === 0) {
        errorMsg.value = '取り消し対象の昇格・降格はありません';
        return;
    }
    if (!confirm(`${targets.length} 件の昇格・降格をまとめて取り消します（新規配置は残ります）。よろしいですか？`)) return;

    const newTable = JSON.parse(JSON.stringify(originalDiffTable.value));
    for (const c of targets) {
        for (const r of newTable.ranks) {
            r.songs = r.songs.filter((s: string) => s !== c.title);
        }
        const targetRank = newTable.ranks.find((r: any) => r.rank === c.oldRank);
        if (targetRank) targetRank.songs.push(c.title);
    }

    isSavingDiff.value = true;
    errorMsg.value = '';
    successMsg.value = '';
    try {
        const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, {
            method: 'PUT',
            headers: authHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(newTable),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'Error');
        originalDiffTable.value = newTable;
        successMsg.value = `${targets.length} 件の昇格・降格を取り消しました`;
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

// ── データロード ────────────────────────────────────
/**
 * 【関数の役割】 モーダル表示時に初期データを並列取得する。
 * 取得対象: ドラフト状態フラグ / ドラフト楽曲 / ドラフト難易度表 / 公開中の難易度表（差分比較用）。
 */
const loadData = async () => {
  try {
    // ドラフト状態（バッジ表示と「適用」ボタン有効化用）。
    const statusRes = await fetch(`${API_BASE}/api/admin/game-data/status`, { headers: authHeaders() });
    if (statusRes.ok) {
      const status = await statusRes.json();
      hasDraftSongs.value = status.hasDraftSongs;
      hasDraftDiffTable.value = status.hasDraftDifficultyTable;
    }

    // ドラフト楽曲の取得。
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) {
      draftSongs.value = await songsRes.json();
    }

    // active 楽曲の取得（既存曲編集の検索対象）。
    const activeSongsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/active`, { headers: authHeaders() });
    if (activeSongsRes.ok) {
      activeSongs.value = await activeSongsRes.json();
    }

    // 難易度表のドラフト取得。未保存変更はリセット。
    const diffRes = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, { headers: authHeaders() });
    if (diffRes.ok) {
      originalDiffTable.value = await diffRes.json();
      pendingDiffChanges.value = [];
    }
  } catch (e: any) {
    console.error('Failed to load admin game data:', e);
  }

  // 公開中の難易度表（差分比較のベースライン）。認証不要なので失敗しても致命的ではない。
  try {
    const activeRes = await fetch(`${API_BASE}/api/game-data/difficulty-table`);
    if (activeRes.ok) {
      activeDiffTable.value = await activeRes.json();
    }
  } catch (e) {
    console.warn('Failed to fetch active difficulty table for diff:', e);
  }
};

// モーダルが開かれた瞬間にメッセージ類をリセットしてデータを再取得する。
watch(() => props.isOpen, (val) => {
  if (val) {
    errorMsg.value = '';
    successMsg.value = '';
    commentCache.clear();
    cancelEditExistingSong();
    loadData();
  }
});

// ── 楽曲追加 ────────────────────────────────────────
/**
 * 【関数の役割】 フォームの内容をドラフト楽曲として POST し、追加後に
 * ANOTHER / LEGGENDARIA が Lv11 or Lv12 であれば Uncategorized(other) に自動配置する。
 */
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
    
    // Lv11/12 の ANOTHER/LEGGENDARIA は Uncategorized(other) に自動配置（忘れ防止）。
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
    
    // ドラフト楽曲リストを最新化。
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) draftSongs.value = await songsRes.json();
  } catch (e: any) {
    errorMsg.value = '追加エラー: ' + e.message;
  } finally {
    isSubmitting.value = false;
  }
};

// ── 既存曲編集 ────────────────────────────────
/**
 * 【関数の役割】 active 楽曲グループ（曲名単位）の編集を開始する。
 *
 * 手順:
 *  1. 各難易度の active レコードについて from-active を呼び、編集用 draft レコードを用意する
 *  2. 取得した draft の値を共通フォーム（form）に流し込み、編集モードに切り替える
 *  3. 保存時は難易度ごとに PUT を発行する
 */
const handleBeginEditActiveSong = async (group: { title: string; records: any[] }) => {
  isPreparingEdit.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const drafts = await Promise.all(group.records.map(async (rec: any) => {
      const res = await fetch(`${API_BASE}/api/admin/game-data/songs/draft/from-active/${rec.id}`, {
        method: 'POST',
        headers: authHeaders(),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Error');
      return data.draft as any;
    }));

    const newForm = defaultForm();
    const draftIdMap: Record<string, number | null> = {};
    newForm.title = group.title;
    for (const d of drafts) {
      if (d.artist != null) newForm.artist = d.artist;
      if (d.genre != null) newForm.genre = d.genre;
      if (d.bpm != null) newForm.bpm = d.bpm;
      draftIdMap[d.difficulty] = d.id;
      const def = difficultyDefs.find(def => def.code === d.difficulty);
      if (def) {
        newForm[def.notesKey] = d.notes ?? null;
        newForm[def.levelKey] = d.level ?? null;
      }
      // ANOTHER / LEGGENDARIA は共通フィールドを上書き（最後に処理した譜面の値が入る）
      if (d.difficulty === '4' || d.difficulty === '10') {
        newForm.wr = d.wr ?? null;
        newForm.avg = d.avg ?? null;
        newForm.coef = d.coef ?? null;
        newForm.textage = d.textage ?? '';
      }
    }

    form.value = newForm;
    editingDraftIdByDiff.value = draftIdMap;
    editingSongTitle.value = group.title;
    isEditingExistingSong.value = true;
    hasDraftSongs.value = true;
    activeSearchQuery.value = '';

    // ドラフト楽曲一覧を最新化（新しく作成された draft が反映される）。
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) draftSongs.value = await songsRes.json();
  } catch (e: any) {
    errorMsg.value = '編集準備エラー: ' + e.message;
  } finally {
    isPreparingEdit.value = false;
  }
};

/** 【関数の役割】 編集モードを終了してフォームを新規追加状態に戻す。 */
const cancelEditExistingSong = () => {
  form.value = defaultForm();
  editingDraftIdByDiff.value = {};
  editingSongTitle.value = '';
  isEditingExistingSong.value = false;
};

/**
 * 【関数の役割】 編集中の draft レコードを PUT でまとめて更新する。
 * 難易度ごとに notes/level と、ANOTHER/LEGGENDARIA なら wr/avg/coef/textage も送る。
 *
 * 元の曲に無かった難易度 (例: ANO だけの曲に HYP を追加する) はまだ draft ID を持たないので、
 * PUT ではなく POST /songs/draft で新規 SongDefinition を作る。これをしないと「フォームに
 * 値を入れて更新したのに保存されない」というサイレント無視が発生する。
 */
const handleUpdateEditingSong = async () => {
  if (!form.value.title) return;
  isSubmitting.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    // 既存ドラフトの更新 (PUT)
    const entries = Object.entries(editingDraftIdByDiff.value).filter(([, id]) => id != null);
    for (const [code, id] of entries) {
      const def = difficultyDefs.find(d => d.code === code);
      if (!def) continue;
      const body: Record<string, any> = {
        title: form.value.title,
        artist: form.value.artist,
        genre: form.value.genre,
        bpm: form.value.bpm,
        notes: form.value[def.notesKey],
        level: form.value[def.levelKey],
      };
      if (code === '4' || code === '10') {
        body.wr = form.value.wr;
        body.avg = form.value.avg;
        body.coef = form.value.coef;
        body.textage = form.value.textage;
      }
      const res = await fetch(`${API_BASE}/api/admin/game-data/songs/draft/${id}`, {
        method: 'PUT',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Error');
    }

    // 元の曲に無かった難易度 (draft ID 無し かつ notes > 0) を POST で新規追加。
    // addDraftSong は notes が null/0 の難易度をスキップするので、新規追加対象の
    // notes/level だけ詰めて 1 回 POST すれば必要な行だけが作られる。
    const missingDiffs = difficultyDefs.filter(def => {
      if (editingDraftIdByDiff.value[def.code] != null) return false;
      const n = form.value[def.notesKey];
      return typeof n === 'number' && n > 0;
    });
    if (missingDiffs.length > 0) {
      const addBody: Record<string, any> = {
        title: form.value.title,
        artist: form.value.artist,
        genre: form.value.genre,
        bpm: form.value.bpm,
        wr: form.value.wr,
        avg: form.value.avg,
        coef: form.value.coef,
        textage: form.value.textage,
      };
      for (const def of missingDiffs) {
        addBody[def.notesKey] = form.value[def.notesKey];
        addBody[def.levelKey] = form.value[def.levelKey];
      }
      const res = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, {
        method: 'POST',
        headers: authHeaders({ 'Content-Type': 'application/json' }),
        body: JSON.stringify(addBody),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.message || 'Error');
    }

    successMsg.value = `「${form.value.title}」のドラフトを更新しました`;

    // 一覧を最新化。
    const [songsRes, activeSongsRes] = await Promise.all([
      fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() }),
      fetch(`${API_BASE}/api/admin/game-data/songs/active`, { headers: authHeaders() }),
    ]);
    if (songsRes.ok) draftSongs.value = await songsRes.json();
    if (activeSongsRes.ok) activeSongs.value = await activeSongsRes.json();

    cancelEditExistingSong();
  } catch (e: any) {
    errorMsg.value = '更新エラー: ' + e.message;
  } finally {
    isSubmitting.value = false;
  }
};

// ── ドラフト楽曲削除 ─────────────────────────────
/** 【関数の役割】 1 曲（＝関連する難易度行すべて）をドラフトから削除する。 */
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
    
    // 最新化。
    const songsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/draft`, { headers: authHeaders() });
    if (songsRes.ok) draftSongs.value = await songsRes.json();
    hasDraftSongs.value = draftSongs.value.length > 0;
  } catch (e: any) {
    errorMsg.value = '削除エラー: ' + e.message;
  }
};

// ── 投票結果からドラフトを生成 ───────────────
/**
 * 【関数の役割】 ユーザーの投票結果を集計してドラフト難易度表を生成する。
 * 判定ロジック:
 *  - Uncategorized 配下: 数値ティア(11.0〜13.0)で最多得票のティアへ配置
 *  - 既存ランク配下: PROMOTE/STAY/DEMOTE の多数決。STAY が最多 or tie なら現状維持
 *    PROMOTE > DEMOTE なら 1 段上、逆なら 1 段下へ移動。
 * Phase 1 で全 move を記録し、Phase 2 で一括適用（走行中の配列変化で漏れを防ぐ）。
 */
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

    // 投票集計マップ: "title|difficultyName" -> { PROMOTE: n, STAY: n, DEMOTE: n, "12.3": n, ... }
    const voteMap = new Map<string, Record<string, number>>();
    for (const item of votesData) {
      const { title, difficultyName, ...rest } = item;
      const counts: Record<string, number> = {};
      for (const [k, v] of Object.entries(rest)) counts[k] = Number(v) || 0;
      voteMap.set(`${title}|${difficultyName}`, counts);
    }

    const newTable = JSON.parse(JSON.stringify(activeTable));
    const ranks: Array<{ rank: string; songs: string[] }> = newTable.ranks;

    // 数値ランクのインデックスだけを抽出（Uncategorized を除外して昇降格計算に使う）。
    const numericRankIndices: number[] = [];
    for (let i = 0; i < ranks.length; i++) {
      if (!ranks[i].rank.toLowerCase().includes('uncategorized')) numericRankIndices.push(i);
    }

    // Uncategorized 楽曲の配置候補となる数値ティア（11.0 〜 13.0 を 0.1 刻み）。
    const TIER_OPTIONS: string[] = [];
    for (let i = 110; i <= 130; i++) TIER_OPTIONS.push((i / 10).toFixed(1));

    // Phase 1: 全 move を収集（反復中に ranks を書き換えると漏れるため、後でまとめて適用）。
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
          // Uncategorized: 最多得票のティアに配置。
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
          // ランク済み: 多数決（STAY が両者以上なら維持、PROMOTE == DEMOTE の同数も変更なし）。
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

    // Phase 2: 記録した移動を一括適用。
    for (const { song, fromIdx, toIdx } of moves) {
      ranks[fromIdx].songs = ranks[fromIdx].songs.filter((s: string) => s !== song);
      ranks[toIdx].songs.push(song);
    }

    // 新ドラフトを保存。
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

// ── 難易度表ドラフトの保存 ───────────────────
/**
 * 【関数の役割】 pendingDiffChanges を適用した新しい難易度表を PUT で保存する。
 * 同じ曲が元のランクに残らないよう全ランクから除去してから移動先に追加。
 */
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

// ── ドラフト適用（本番公開） ───────────────────
/**
 * 【関数の役割】 楽曲ドラフトのみを公開する。
 * Lv11/12 の ANOTHER/LEGGENDARIA は active 難易度表の Uncategorized に自動追加される。
 * 配置先が Uncategorized のみで PT 算出に影響しないため、BEAT-PT 再計算は走らない。
 */
const handleApplyDraftSongs = async () => {
  if (!confirm('楽曲ドラフトを適用しますか？（Lv11/12 譜面は Uncategorized に自動配置されます。BEAT-PT 再計算は行いません）')) return;

  isApplyingSongs.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/apply/songs`, {
      method: 'POST',
      headers: authHeaders(),
    });
    const data = await res.json();
    if (!res.ok && res.status !== 202) throw new Error(data.message || 'Error');

    successMsg.value = data.message;
    hasDraftSongs.value = false;
    draftSongs.value = [];
  } catch (e: any) {
    errorMsg.value = '適用エラー: ' + e.message;
  } finally {
    isApplyingSongs.value = false;
  }
};

/**
 * 【関数の役割】 難易度表ドラフトのみを公開して全ユーザーの PT 再計算を走らせる。
 * 重い処理なのでバックエンド側は非同期（202 Accepted）で受け付ける。
 */
const handleApplyDraftDiffTable = async () => {
  if (!confirm('難易度表ドラフトを適用しますか？全ユーザーのポイント再計算が実行されます。')) return;

  isApplyingDiff.value = true;
  errorMsg.value = '';
  successMsg.value = '';

  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/apply/difficulty`, {
      method: 'POST',
      headers: authHeaders(),
    });
    const data = await res.json();
    if (!res.ok && res.status !== 202) throw new Error(data.message || 'Error');

    successMsg.value = data.message;
    hasDraftDiffTable.value = false;
    activeDiffTable.value = JSON.parse(JSON.stringify(originalDiffTable.value));
  } catch (e: any) {
    errorMsg.value = '適用エラー: ' + e.message;
  } finally {
    isApplyingDiff.value = false;
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
