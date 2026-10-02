<template>
    <v-dialog
      :model-value="isOpen"
      max-width="768"
      @update:model-value="(v: boolean) => { if (!v) $emit('close') }"
    >
      <v-card class="w-full flex flex-col overflow-hidden max-h-[90vh]">

        <!-- Header -->
        <v-card-title class="flex flex-wrap items-center justify-between gap-2 shrink-0">
          <span class="flex items-center gap-2">
            <v-icon :icon="mdiMusic" color="indigo" />
            ゲームデータ管理
          </span>
          <div class="flex items-center gap-2">
            <!-- Apply buttons (楽曲と難易度表で独立に適用) -->
            <v-btn
              color="success"
              size="small"
              :disabled="isApplyingSongs || isApplyingDiff || draftSongs.length === 0"
              @click="handleApplyDraftSongs"
            >
              <v-progress-circular indeterminate v-if="isApplyingSongs" size="16" width="2" class="mr-1" />
              {{ isApplyingSongs ? '適用中...' : '楽曲を適用' }}
            </v-btn>
            <v-btn
              color="success"
              size="small"
              :disabled="isApplyingSongs || isApplyingDiff || savedDiffChanges.length === 0"
              @click="handleApplyDraftDiffTable"
            >
              <v-progress-circular indeterminate v-if="isApplyingDiff" size="16" width="2" class="mr-1" />
              {{ isApplyingDiff ? '適用中...' : '難易度表を適用' }}
            </v-btn>
            <v-btn icon variant="text" size="small" aria-label="close" @click="$emit('close')">
              <v-icon :icon="mdiClose" />
            </v-btn>
          </div>
        </v-card-title>

        <!-- Draft status -->
        <v-alert v-if="hasDraftSongs || hasDraftDiffTable" type="warning" variant="tonal" density="compact" rounded="0" class="shrink-0">
          <div class="flex items-center gap-2 flex-wrap">
            <span>未適用のドラフトがあります</span>
            <v-chip v-if="hasDraftSongs" label size="small" color="warning">楽曲 {{ draftSongs.length }}件</v-chip>
            <v-chip v-if="hasDraftDiffTable" label size="small" color="warning">難易度表</v-chip>
          </div>
        </v-alert>

        <!-- Tab bar -->
        <v-tabs v-model="activeTab" grow color="indigo" class="shrink-0">
          <v-tab value="songs">楽曲追加</v-tab>
          <v-tab value="difficulty">難易度表</v-tab>
        </v-tabs>
        <v-divider />

        <!-- Status messages -->
        <v-alert v-if="errorMsg" type="error" density="compact" class="mx-5 mt-4 shrink-0">{{ errorMsg }}</v-alert>
        <v-alert v-if="successMsg" type="success" density="compact" class="mx-5 mt-4 shrink-0">{{ successMsg }}</v-alert>

        <!-- Tab content -->
        <div class="flex-1 overflow-y-auto p-5">

          <!-- Songs Tab -->
          <div v-if="activeTab === 'songs'">
            <!-- 楽曲・譜面の自動同期（bemaniwiki 新曲リスト / 旧曲リスト、textage 譜面） -->
            <v-card variant="outlined" class="mb-4">
              <v-card-text>
              <div class="flex flex-wrap items-center justify-between gap-2 mb-2">
                <div class="flex items-center gap-2">
                  <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300">楽曲・譜面の自動同期</h3>
                  <v-btn-toggle v-model="wikiSyncSource" mandatory color="indigo" variant="outlined" divided density="compact" :disabled="isWikiSyncing">
                    <v-btn
                      v-for="opt in wikiSyncSourceOptions"
                      :key="opt.value"
                      :value="opt.value"
                      size="small"
                    >{{ opt.label }}</v-btn>
                  </v-btn-toggle>
                </div>
                <div class="flex items-center gap-2">
                  <v-btn
                    variant="outlined"
                    size="small"
                    class="whitespace-nowrap"
                    :disabled="isWikiSyncing"
                    @click="handleWikiSync(true)"
                  >差分を確認</v-btn>
                  <v-btn
                    color="indigo"
                    size="small"
                    class="whitespace-nowrap"
                    :disabled="isWikiSyncing"
                    @click="handleWikiSync(false)"
                  >
                    <v-progress-circular indeterminate v-if="isWikiSyncing" size="14" width="2" class="mr-1" />
                    {{ isWikiSyncing ? '同期中...' : '今すぐ同期' }}
                  </v-btn>
                </div>
              </div>
              <p v-if="wikiSyncSource === 'new'" class="text-xs text-slate-500 dark:text-slate-400 mb-2">
                ZINRAI 新曲リストの SP レベル・ノーツ数・GENRE/ARTIST/BPM を公開中の楽曲へ直接反映します（ドラフトは経由しません）。
                Lv11/12 の ANOTHER/LEGGENDARIA は難易度表の Uncategorized に入ります。自動実行は毎日 0:20 / 6:20 / 12:20 / 18:20。
                未解禁（灰色表記）・未記載の譜面は wiki が埋まり次第、次回以降に取り込みます。
              </p>
              <p v-else-if="wikiSyncSource === 'textage'" class="text-xs text-slate-500 dark:text-slate-400 mb-2">
                textage.cc から、譜面傾向プロファイル（スコア予測・スキルツリー・曲詳細の譜面傾向に使う）が無い SP 譜面を取得・解析して保存し、楽曲の textage リンクも記録します。
                ページのノーツ数が登録値と一致した譜面だけ採用し、一致しない譜面（textage に未掲載・別譜面）は保留します。
                旧方式（2026-04 の一括投入）のプロファイルも順次解析し直し、誤ったリンクは訂正します。
                自動実行は毎日 4:50 / 16:50（1 回最大 120 ページ）。ここからの実行は 20 ページまで（30 秒ほどかかります）。
              </p>
              <p v-else class="text-xs text-slate-500 dark:text-slate-400 mb-2">
                ZINRAI 旧曲リスト + 旧曲総ノーツ数リストから、レベル変更・ノーツ数の訂正・旧曲への譜面追加・復活曲を公開中の楽曲へ直接反映します。
                公式 CSV と表記が違う曲名（ÆTHER → ATHER など）は ARTIST・GENRE・ノーツ数で既存曲に読み替え、二重登録しません。
                自動実行は毎日 5:40。自動実行で変更が 30 曲を超えたときは反映を見送るので、ここで差分を確認して手動で同期してください（取得に 10 秒ほどかかります）。
              </p>
              <div v-if="wikiSyncResultShown" class="text-xs space-y-1 mb-2">
                <div class="font-bold" :class="wikiSyncResultShown.status === 'SUCCESS' ? 'text-emerald-700 dark:text-emerald-400' : wikiSyncResultShown.status === 'NEEDS_REVIEW' ? 'text-amber-700 dark:text-amber-400' : 'text-slate-700 dark:text-slate-300'">
                  {{ wikiSyncResultShown.message }}
                </div>
                <v-expansion-panels v-if="wikiSyncSections.length > 0" multiple variant="accordion">
                  <v-expansion-panel v-for="sec in wikiSyncSections" :key="sec.key">
                    <v-expansion-panel-title>{{ sec.label }} {{ sec.items.length }}件</v-expansion-panel-title>
                    <v-expansion-panel-text>
                      <ul class="ml-4 list-disc space-y-0.5 max-h-40 overflow-y-auto text-xs">
                        <li v-for="(item, i) in sec.items" :key="i" class="break-all">{{ item }}</li>
                      </ul>
                    </v-expansion-panel-text>
                  </v-expansion-panel>
                </v-expansion-panels>
              </div>
              <div v-if="wikiSyncRunsShown.length > 0" class="text-[11px] text-slate-500 dark:text-slate-400 space-y-0.5">
                <div class="font-bold">最近の実行（{{ wikiSyncSourceLabel }}）</div>
                <div v-for="r in wikiSyncRunsShown" :key="r.id" class="flex flex-wrap gap-x-2">
                  <span>{{ formatWikiRunTime(r.startedAt) }}</span>
                  <span>{{ r.trigger === 'scheduled' ? '定期' : '手動' }}{{ r.dryRun ? '(確認のみ)' : '' }}</span>
                  <span :class="r.status === 'FAILED' ? 'text-red-500' : r.status === 'NEEDS_REVIEW' ? 'text-amber-600 dark:text-amber-400' : r.status === 'SUCCESS' ? 'text-emerald-600 dark:text-emerald-400' : ''">{{ wikiRunStatusLabel(r) }}</span>
                  <span v-if="r.status !== 'FAILED'">追加 {{ r.addedCount }} / 更新 {{ r.updatedCount }} / 保留 {{ r.heldCount }}{{ r.pageChanged === false ? ' / ページ変更なし' : '' }}</span>
                  <span v-else class="break-all">{{ r.errorMessage }}</span>
                </div>
              </div>
              </v-card-text>
            </v-card>

            <!-- Edit existing active song -->
            <v-card variant="outlined" class="mb-4">
              <v-card-title class="text-subtitle-1">既存曲を編集</v-card-title>
              <v-card-text>
              <v-text-field
                v-model="activeSearchQuery"
                :disabled="isEditingExistingSong"
                type="text"
                placeholder="曲名で検索..."
                color="indigo"
                :prepend-inner-icon="mdiMagnify"
                hide-details
                class="w-full"
              />
              <div v-if="filteredActiveSongs.length > 0 && !isEditingExistingSong" class="mt-2 max-h-48 overflow-y-auto space-y-1">
                <v-card
                  v-for="g in filteredActiveSongs"
                  :key="g.title"
                  :disabled="isPreparingEdit"
                  variant="tonal"
                  density="compact"
                  class="w-full text-left"
                  :title="g.title"
                  :subtitle="`${g.artist} / ${g.genre} / ${g.difficulties.join(', ')}`"
                  @click="handleBeginEditActiveSong(g)"
                />
              </div>
              <div v-else-if="activeSearchQuery && !isEditingExistingSong" class="mt-2 text-xs text-slate-400 dark:text-slate-500">
                該当する楽曲が見つかりません
              </div>
              <div v-if="isPreparingEdit" class="mt-2 text-xs text-slate-500 dark:text-slate-400 flex items-center gap-2">
                <v-progress-circular indeterminate size="14" width="2" />
                編集用ドラフトを準備中...
              </div>
              </v-card-text>
            </v-card>

            <!-- Add Song Form -->
            <v-card variant="outlined" class="mb-4">
              <v-card-text>
              <v-alert v-if="isEditingExistingSong" type="info" :icon="false" color="indigo" density="compact" class="mb-3">
                <div class="flex items-center justify-between">
                  <div class="text-sm">
                    <span class="font-bold">編集中:</span> {{ editingSongTitle }}
                  </div>
                  <v-btn variant="outlined" size="small" @click="cancelEditExistingSong">
                    キャンセル
                  </v-btn>
                </div>
              </v-alert>
              <h3 class="text-subtitle-1 mb-3">{{ isEditingExistingSong ? '楽曲情報の編集' : '新曲追加' }}</h3>

              <!-- Basic info -->
              <div class="grid grid-cols-2 gap-3 mb-3">
                <v-text-field v-model="form.title" type="text" color="indigo" label="曲名 *" hide-details class="w-full" placeholder="曲名" />
                <v-text-field v-model="form.artist" type="text" color="indigo" label="アーティスト" hide-details class="w-full" placeholder="アーティスト名" />
                <v-text-field v-model="form.genre" type="text" color="indigo" label="ジャンル" hide-details class="w-full" placeholder="ジャンル" />
                <v-text-field v-model="form.bpm" type="text" color="indigo" label="BPM" hide-details class="w-full" placeholder="150 / 130-180" />
              </div>

              <!-- Per-difficulty fields -->
              <div class="space-y-2 mb-3">
                <div v-for="diff in difficultyDefs" :key="diff.code"
                  class="flex items-center gap-2 p-2 rounded-lg border border-slate-200 dark:border-slate-700"
                  :class="diff.bgClass"
                >
                  <v-chip label variant="flat" class="w-20 justify-center shrink-0" :color="({ '1': 'green', '2': 'blue', '3': 'amber-darken-2', '4': 'red', '10': 'purple' } as Record<string, string>)[diff.code]">{{ diff.label }}</v-chip>
                  <div class="flex items-center gap-1.5 flex-1">
                    <v-text-field v-model.number="form[diff.notesKey]" type="number" min="0" density="compact" label="ノーツ" hide-details class="w-28 flex-none" placeholder="0" />
                    <v-text-field v-model.number="form[diff.levelKey]" type="number" min="1" max="12" density="compact" label="☆" hide-details class="w-24 flex-none" placeholder="0" />
                  </div>
                </div>
              </div>

              <!-- Extra fields for ANOTHER/LEGGENDARIA -->
              <v-expansion-panels variant="accordion" class="mb-3">
                <v-expansion-panel>
                  <v-expansion-panel-title>詳細フィールド (ANOTHER/LEGG用)</v-expansion-panel-title>
                  <v-expansion-panel-text>
                    <div class="grid grid-cols-4 gap-2">
                      <v-text-field v-model.number="form.wr" type="number" label="WR" density="compact" hide-details class="w-full" />
                      <v-text-field v-model.number="form.avg" type="number" label="AVG" density="compact" hide-details class="w-full" />
                      <v-text-field v-model.number="form.coef" type="number" step="0.01" label="coef" density="compact" hide-details class="w-full" />
                      <v-text-field v-model="form.textage" type="text" label="textage" density="compact" hide-details class="w-full" />
                    </div>
                  </v-expansion-panel-text>
                </v-expansion-panel>
              </v-expansion-panels>

              <v-btn
                color="indigo"
                block
                :disabled="isSubmitting || !form.title"
                @click="isEditingExistingSong ? handleUpdateEditingSong() : handleAddSong()"
              >
                <v-progress-circular indeterminate v-if="isSubmitting" size="16" width="2" class="mr-2" />
                {{ isEditingExistingSong ? 'ドラフトを更新' : 'ドラフトに追加' }}
              </v-btn>
              </v-card-text>
            </v-card>

            <!-- Draft songs list -->
            <div v-if="draftSongs.length > 0">
              <h3 class="font-bold text-sm text-slate-700 dark:text-slate-300 mb-2 flex items-center gap-2">
                ドラフト楽曲一覧
                <span class="text-xs font-normal text-slate-500 dark:text-slate-400">({{ draftSongs.length }}件)</span>
              </h3>
              <div class="space-y-1">
                <v-card v-for="song in groupedDraftSongs" :key="song.title"
                  variant="outlined"
                  density="compact"
                  :title="song.title"
                  :subtitle="`${song.artist} / ${song.genre} / ${song.difficulties.join(', ')}`"
                >
                  <template #append>
                  <v-btn
                    icon
                    variant="text"
                    size="small"
                    color="error"
                    class="shrink-0"
                    title="削除"
                    aria-label="削除"
                    @click="handleDeleteDraftSong(song.ids)"
                  >
                    <v-icon :icon="mdiDelete" size="18" />
                  </v-btn>
                  </template>
                </v-card>
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
                <v-btn
                  color="warning"
                  size="small"
                  class="whitespace-nowrap"
                  :disabled="isGeneratingDraft"
                  @click="generateDraftFromVotes"
                >
                  <v-progress-circular indeterminate v-if="isGeneratingDraft" size="14" width="2" class="mr-1" />
                  投票から生成
                </v-btn>
                <v-btn
                  color="indigo"
                  size="small"
                  class="whitespace-nowrap"
                  :disabled="isSavingDiff || pendingDiffChanges.length === 0"
                  @click="handleSaveDiffTable"
                >
                  <v-progress-circular indeterminate v-if="isSavingDiff" size="14" width="2" class="mr-1" />
                  下書き保存 ({{ pendingDiffChanges.length }}件)
                </v-btn>
              </div>
            </div>

            <!-- Difficulty table profiles (named draft snapshots) -->
            <v-card variant="outlined" class="mb-4">
              <v-card-title class="text-subtitle-1 flex items-center gap-1.5">
                <v-icon :icon="mdiArchiveOutline" size="small" />
                プロファイル（下書きを名前付きで保存）
              </v-card-title>
              <v-card-text>
              <p class="text-[11px] text-slate-400 dark:text-slate-500 mb-2 leading-snug">
                現在の下書き（保存前の変更も含む）を名前を付けて保存します。読み込むと下書きが置き換わります。適用は従来どおり「難易度表を適用」ボタンから。
              </p>
              <!-- Save-as form -->
              <div class="flex items-center gap-2 mb-3">
                <v-text-field
                  v-model="newProfileName"
                  type="text"
                  maxlength="60"
                  placeholder="プロファイル名（例: 強G案）"
                  color="indigo"
                  density="compact"
                  hide-details
                  class="flex-1 min-w-0"
                  @keyup.enter="handleSaveProfile()"
                />
                <v-btn
                  color="indigo"
                  class="whitespace-nowrap"
                  :disabled="isProfileBusy || !newProfileName.trim()"
                  @click="handleSaveProfile()"
                >
                  現在の内容を保存
                </v-btn>
              </div>
              <!-- Profile list -->
              <div v-if="diffProfiles.length > 0" class="space-y-1">
                <v-card
                  v-for="p in diffProfiles"
                  :key="p.name"
                  variant="tonal"
                  density="compact"
                  class="min-w-0"
                  :title="p.name"
                  :subtitle="`${p.songCount} 曲`"
                >
                  <template #append>
                  <div class="flex items-center gap-1.5 shrink-0">
                    <v-btn
                      variant="tonal"
                      color="indigo"
                      size="small"
                      :disabled="isProfileBusy"
                      title="このプロファイルを下書きに読み込む"
                      @click="handleLoadProfile(p.name)"
                    >
                      読み込み
                    </v-btn>
                    <v-btn
                      variant="tonal"
                      size="small"
                      :disabled="isProfileBusy"
                      title="現在の内容でこのプロファイルを上書き"
                      @click="handleSaveProfile(p.name)"
                    >
                      上書き
                    </v-btn>
                    <v-btn
                      icon
                      variant="text"
                      size="x-small"
                      color="error"
                      :disabled="isProfileBusy"
                      title="削除"
                      aria-label="削除"
                      @click="handleDeleteProfile(p.name)"
                    >
                      <v-icon :icon="mdiDelete" size="16" />
                    </v-btn>
                  </div>
                  </template>
                </v-card>
              </div>
              <div v-else class="text-xs text-slate-400 dark:text-slate-500 py-2 text-center">
                保存されたプロファイルはありません
              </div>
              </v-card-text>
            </v-card>

            <!-- Level filter checkboxes -->
            <div class="mb-3 flex items-center gap-4">
              <v-checkbox v-model="showLv12" color="indigo" label="☆12" density="compact" hide-details class="flex-none" />
              <v-checkbox v-model="showLv11" color="indigo" label="☆11" density="compact" hide-details class="flex-none" />
            </div>

            <v-card variant="outlined" class="mb-4">
              <v-card-title class="text-subtitle-1">楽曲のランク移動</v-card-title>
              <v-card-text>
              <div class="flex flex-col sm:flex-row items-stretch sm:items-center gap-2">
                <v-select
                  v-model="diffEditSongTitle"
                  :items="[
                    { title: '曲名を選択...', value: '' },
                    ...effectiveSongsList.map(song => ({
                      title: `${song.title.length > 40 ? song.title.substring(0, 37) + '...' : song.title} (現在: ${song.rank})`,
                      value: song.title,
                    })),
                  ]"
                  item-title="title"
                  item-value="value"
                  color="indigo"
                  hide-details
                  class="flex-1 min-w-0"
                />
                <v-select
                  v-model="diffEditNewRank"
                  :items="[{ title: '移動先...', value: '' }, ...availableRanks.map(r => ({ title: r, value: r }))]"
                  item-title="title"
                  item-value="value"
                  color="indigo"
                  hide-details
                  class="sm:w-40 sm:flex-none"
                />
                <v-btn variant="tonal" color="indigo" class="whitespace-nowrap" :disabled="!diffEditSongTitle || !diffEditNewRank" @click="handleAddDiffChange">
                  追加
                </v-btn>
              </div>
              </v-card-text>
            </v-card>

            <!-- Saved draft changes (applied to draft, not yet published) -->
            <div v-if="savedDiffChanges.length > 0" class="mb-3 space-y-2">
              <div v-if="hasSavedPromotionsOrDemotions" class="flex justify-end">
                <v-btn
                  variant="outlined"
                  color="warning"
                  size="small"
                  :disabled="isSavingDiff"
                  title="新規配置（Uncategorized との相互移動）はそのまま残し、昇格・降格のみを取り消します"
                  @click="handleRevertAllPromotionsDemotions"
                >
                  昇格・降格を一括取り消し
                </v-btn>
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
                    <v-btn icon variant="text" size="x-small" color="error" class="ml-2 shrink-0" :disabled="isSavingDiff" title="取り消す" aria-label="取り消す" @click="handleRevertSavedChange(change)">
                      <v-icon :icon="mdiDelete" size="16" />
                    </v-btn>
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
                    <v-btn icon variant="text" size="x-small" color="error" class="ml-2 shrink-0" :disabled="isSavingDiff" title="取り消す" aria-label="取り消す" @click="handleRevertSavedChange(change)">
                      <v-icon :icon="mdiDelete" size="16" />
                    </v-btn>
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
                    <v-btn icon variant="text" size="x-small" color="error" class="ml-2 shrink-0" :disabled="isSavingDiff" title="取り消す" aria-label="取り消す" @click="handleRevertSavedChange(change)">
                      <v-icon :icon="mdiDelete" size="16" />
                    </v-btn>
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
                     <v-btn icon variant="text" size="x-small" color="error" title="元に戻す" aria-label="元に戻す" @click="handleRemoveDiffChange(change.title)">
                        <v-icon :icon="mdiDelete" size="16" />
                     </v-btn>
                  </div>
               </div>
            </div>
            <div v-if="savedDiffChanges.length === 0 && pendingDiffChanges.length === 0" class="text-center text-sm py-8 text-slate-400 dark:text-slate-500 border border-dashed border-slate-300 dark:border-slate-700 rounded-md">
              変更はありません。<br>上のフォームから楽曲を選んでランクを移動してください。
            </div>
          </div>
        </div>
      </v-card>
    </v-dialog>

  <Teleport to="body">
    <!-- Comment tooltip（v-dialog より前面に出すため z-index を v-dialog（2400 台）より上にしている） -->
    <div
      v-if="isOpen && tooltipSongKey && (tooltipComments.length > 0 || tooltipLoading)"
      class="fixed z-[3000] bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-700 rounded-md shadow-xl p-3 w-80 overflow-y-auto pointer-events-none"
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
import { jstParts } from '../utils/jstTime';
import { mdiArchiveOutline, mdiClose, mdiDelete, mdiMagnify, mdiMusic } from '@mdi/js';

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

// ── bemaniwiki 楽曲同期（新曲リスト / 旧曲リスト）──────────
/**
 * 取得元。new = 新曲リスト、old = 旧曲リスト + 旧曲総ノーツ数リスト（バックエンドの WikiSongSyncService.SOURCE_*）、
 * textage = textage.cc からの譜面傾向プロファイルの拡充（TextageChartSyncService.SOURCE）。
 */
type WikiSyncSource = 'new' | 'old' | 'textage';
const wikiSyncSourceOptions: { value: WikiSyncSource; label: string }[] = [
  { value: 'new', label: '新曲リスト' },
  { value: 'old', label: '旧曲リスト' },
  { value: 'textage', label: 'textage 譜面' },
];
/** 【computed の役割】 選択中の取得元の表示名。 */
const wikiSyncSourceLabel = computed(() =>
  wikiSyncSourceOptions.find(o => o.value === wikiSyncSource.value)?.label ?? '');
/** パネルで操作・表示する取得元。 */
const wikiSyncSource = ref<WikiSyncSource>('new');
/** 同期の実行中フラグ（差分確認・本実行の両方）。 */
const isWikiSyncing = ref(false);
/** 直近の同期結果（POST /wiki-sync のレスポンスそのまま）。モーダル再オープンで消える。 */
const wikiSyncResult = ref<any | null>(null);
/** 同期の実行履歴（新しい順。新曲・旧曲の両方を含む）。 */
const wikiSyncRuns = ref<any[]>([]);

/** 【computed の役割】 選択中の取得元の同期結果だけを表示する（取得元を切り替えたら別リストの結果は隠す）。 */
const wikiSyncResultShown = computed(() => {
  const r = wikiSyncResult.value;
  return r && (r.source ?? 'new') === wikiSyncSource.value ? r : null;
});

/** 【computed の役割】 選択中の取得元の実行履歴（直近 5 件）。source 列が無い頃の記録は新曲リスト。 */
const wikiSyncRunsShown = computed(() =>
  wikiSyncRuns.value.filter(r => (r.source ?? 'new') === wikiSyncSource.value).slice(0, 5));

/** 【computed の役割】 同期結果の内訳を折りたたみ表示用に並べる（空の区分は出さない）。 */
const wikiSyncSections = computed(() => {
  const r = wikiSyncResultShown.value;
  if (!r) return [];
  // textage 譜面は同じ形のレスポンスで欄の意味が違う（TextageChartSyncService.sync の説明どおり）
  if (r.source === 'textage') {
    return [
      { key: 'added', label: '新規に解析', items: (r.added ?? []) as string[] },
      { key: 'updated', label: '再解析（旧方式から）', items: (r.updated ?? []) as string[] },
      { key: 'titleMatches', label: 'textage のページを特定・訂正', items: (r.titleMatches ?? []) as string[] },
      { key: 'held', label: '保留', items: (r.held ?? []) as string[] },
      { key: 'skippedSongs', label: '取得上限で次回に回した曲', items: (r.skippedSongs ?? []) as string[] },
      { key: 'warnings', label: '警告', items: (r.warnings ?? []) as string[] },
    ].filter(sec => sec.items.length > 0);
  }
  return [
    { key: 'added', label: '追加', items: (r.added ?? []) as string[] },
    { key: 'updated', label: '更新', items: (r.updated ?? []) as string[] },
    { key: 'uncategorizedAdded', label: 'Uncategorized に追加', items: (r.uncategorizedAdded ?? []) as string[] },
    { key: 'held', label: '保留', items: (r.held ?? []) as string[] },
    { key: 'skippedSongs', label: '配信前', items: (r.skippedSongs ?? []) as string[] },
    { key: 'warnings', label: '警告', items: (r.warnings ?? []) as string[] },
    { key: 'titleMatches', label: '曲名の読み替え（wiki → 登録済み）', items: (r.titleMatches ?? []) as string[] },
  ].filter(sec => sec.items.length > 0);
});

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

// ── 難易度表プロファイル（名前付きドラフトスナップショット）──
/** 保存済みプロファイル一覧（名前と曲数）。 */
const diffProfiles = ref<{ name: string; songCount: number }[]>([]);
/** 新規保存フォームのプロファイル名。 */
const newProfileName = ref('');
/** プロファイル操作（保存/読み込み/削除）実行中フラグ。ボタンの多重押下防止。 */
const isProfileBusy = ref(false);

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

    // bemaniwiki 新曲同期の実行履歴。
    await loadWikiSyncRuns();

    // 難易度表のドラフト取得。未保存変更はリセット。
    const diffRes = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/draft`, { headers: authHeaders() });
    if (diffRes.ok) {
      originalDiffTable.value = await diffRes.json();
      pendingDiffChanges.value = [];
    }

    // 難易度表プロファイル一覧。
    await loadDiffProfiles();
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
 * ANOTHER / LEGGENDARIA が Lv11 or Lv12 であれば Uncategorized に自動配置する。
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
    
    // Lv11/12 の ANOTHER/LEGGENDARIA は Uncategorized に自動配置（忘れ防止）。
    const addedSongsToDiff: string[] = [];
    if (form.value.anotherLevel === 11 || form.value.anotherLevel === 12) {
        addedSongsToDiff.push(form.value.title);
    }
    if (form.value.leggendariaLevel === 11 || form.value.leggendariaLevel === 12) {
        addedSongsToDiff.push(form.value.title + '[L]');
    }
    
    if (addedSongsToDiff.length > 0 && originalDiffTable.value.ranks) {
        const newTable = JSON.parse(JSON.stringify(originalDiffTable.value));
        // 旧名 'Uncategorized(other)' はリネーム前の profile 復元時のフォールバック。
        const uncatOther = newTable.ranks.find((r: any) => r.rank === 'Uncategorized')
            || newTable.ranks.find((r: any) => r.rank === 'Uncategorized(other)');
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

// ── bemaniwiki 新曲同期 ───────────────────────────────
/** 【関数の役割】 同期の実行履歴（新しい順・最大 20 件）を取得する。失敗しても致命的ではないので警告のみ。 */
const loadWikiSyncRuns = async () => {
  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/songs/wiki-sync/runs`, { headers: authHeaders() });
    if (res.ok) wikiSyncRuns.value = await res.json();
  } catch (e) {
    console.warn('Failed to load wiki sync runs:', e);
  }
};

/**
 * 【関数の役割】 bemaniwiki の新曲リスト / 旧曲リスト（選択中の取得元）の同期をその場で実行する（定期実行と同じ処理）。
 *
 * dryRun=true なら差分の確認だけで DB は変わらない。本実行のあとは active 楽曲一覧と履歴を再取得する。
 * 反映先は draft ではなく公開中の楽曲なので、「楽曲を適用」を押す必要は無い。
 * 手動実行には旧曲リストの自動反映の上限（30 曲）はかからない。
 */
const handleWikiSync = async (dryRun: boolean) => {
  isWikiSyncing.value = true;
  errorMsg.value = '';
  successMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/songs/wiki-sync`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ dryRun, source: wikiSyncSource.value }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Error');
    wikiSyncResult.value = data;
    if (!dryRun) {
      successMsg.value = data.message;
      const activeSongsRes = await fetch(`${API_BASE}/api/admin/game-data/songs/active`, { headers: authHeaders() });
      if (activeSongsRes.ok) activeSongs.value = await activeSongsRes.json();
    }
  } catch (e: any) {
    errorMsg.value = (wikiSyncSource.value === 'textage' ? 'textage 譜面同期エラー: ' : 'bemaniwiki 同期エラー: ') + e.message;
  } finally {
    isWikiSyncing.value = false;
    await loadWikiSyncRuns();
  }
};

/** 【関数の役割】 実行履歴の日時（JST オフセット付き ISO）を JST の "9/16 06:20" 形式にする。 */
const formatWikiRunTime = (iso: string | null) => {
  if (!iso) return '';
  const p = jstParts(iso);
  if (!p) return iso;
  return `${p.month}/${p.day} ${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
};

/** 【関数の役割】 実行結果ステータスの表示ラベル。 */
const wikiRunStatusLabel = (r: any) => {
  if (r.status === 'FAILED') return '失敗';
  if (r.status === 'NEEDS_REVIEW') return '要確認（未反映）';
  if (r.status === 'SUCCESS') return r.dryRun ? '差分あり' : '反映あり';
  return '変更なし';
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

// ── 難易度表プロファイル ───────────────────────
/**
 * 【関数の役割】 現在の下書き（originalDiffTable）に未保存変更（pendingDiffChanges）を
 * 適用した「実効テーブル」を生成する。プロファイル保存時のスナップショット対象。
 */
const buildEffectiveDiffTable = () => {
  const t = JSON.parse(JSON.stringify(originalDiffTable.value));
  for (const change of pendingDiffChanges.value) {
    for (const r of t.ranks) {
      r.songs = r.songs.filter((s: string) => s !== change.title);
    }
    const target = t.ranks.find((r: any) => r.rank === change.newRank);
    if (target) target.songs.push(change.title);
  }
  return t;
};

/** 【関数の役割】 保存済みプロファイル一覧を取得する。 */
const loadDiffProfiles = async () => {
  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/profiles`, { headers: authHeaders() });
    if (res.ok) diffProfiles.value = await res.json();
  } catch (e) {
    console.warn('Failed to load difficulty table profiles:', e);
  }
};

/**
 * 【関数の役割】 現在の実効テーブルを名前付きプロファイルとして保存する。
 * 引数 overwriteName を渡すと既存プロファイルを上書きモードで保存する（一覧の「上書き」ボタン用）。
 * 新規保存時は入力欄 newProfileName を使い、同名が存在すれば確認を挟む。
 */
const handleSaveProfile = async (overwriteName?: string) => {
  const name = (overwriteName ?? newProfileName.value).trim();
  if (!name) return;

  const exists = diffProfiles.value.some(p => p.name === name);
  if (overwriteName) {
    if (!confirm(`プロファイル「${name}」を現在の内容で上書きしますか？`)) return;
  } else if (exists) {
    if (!confirm(`プロファイル「${name}」は既に存在します。上書きしますか？`)) return;
  }

  isProfileBusy.value = true;
  errorMsg.value = '';
  successMsg.value = '';
  try {
    const table = buildEffectiveDiffTable();
    const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/profiles?name=${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify(table),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Error');
    successMsg.value = data.message;
    if (!overwriteName) newProfileName.value = '';
    await loadDiffProfiles();
  } catch (e: any) {
    errorMsg.value = 'プロファイル保存エラー: ' + e.message;
  } finally {
    isProfileBusy.value = false;
  }
};

/**
 * 【関数の役割】 プロファイルを下書きに読み込む（下書きを丸ごと置換）。
 * 未保存の変更や現在の下書きは失われるため確認する。読み込み後は active との差分を再計算するため
 * 「昇格/降格/配置」一覧がこのプロファイル基準で表示される。
 */
const handleLoadProfile = async (name: string) => {
  if (!confirm(`プロファイル「${name}」を下書きに読み込みますか？現在の下書き（未保存の変更を含む）は置き換えられます。`)) return;

  isProfileBusy.value = true;
  errorMsg.value = '';
  successMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/profiles/load?name=${encodeURIComponent(name)}`, {
      method: 'POST',
      headers: authHeaders(),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Error');
    originalDiffTable.value = data;
    pendingDiffChanges.value = [];
    hasDraftDiffTable.value = true;
    successMsg.value = `プロファイル「${name}」を下書きに読み込みました`;
  } catch (e: any) {
    errorMsg.value = 'プロファイル読み込みエラー: ' + e.message;
  } finally {
    isProfileBusy.value = false;
  }
};

/** 【関数の役割】 プロファイルを削除する。 */
const handleDeleteProfile = async (name: string) => {
  if (!confirm(`プロファイル「${name}」を削除しますか？`)) return;

  isProfileBusy.value = true;
  errorMsg.value = '';
  successMsg.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/admin/game-data/difficulty-table/profiles?name=${encodeURIComponent(name)}`, {
      method: 'DELETE',
      headers: authHeaders(),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.message || 'Error');
    successMsg.value = data.message;
    await loadDiffProfiles();
  } catch (e: any) {
    errorMsg.value = 'プロファイル削除エラー: ' + e.message;
  } finally {
    isProfileBusy.value = false;
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
 * 適用と同時に、公開中の表との差分がサーバ側で更新履歴ページの「第N版」として自動記録される
 * （記録した版と内訳はレスポンスの message に含まれる）。
 */
const handleApplyDraftDiffTable = async () => {
  if (!confirm('難易度表ドラフトを適用しますか？全ユーザーのポイント再計算が実行されます。\n公開中の表との差分（新規追加・既存変更・表から除外）は、更新履歴ページの難易度改訂タブに「第N版」として自動記録されます。')) return;

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
