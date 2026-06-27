<template>
  <div class="min-h-screen bg-slate-50 dark:bg-slate-900 text-slate-800 dark:text-slate-100 transition-colors">
    <!-- ヘッダ（大会ブランディング） -->
    <header class="bg-gradient-to-br from-indigo-600 via-violet-600 to-fuchsia-600 text-white shadow-lg">
      <div class="max-w-5xl mx-auto px-4 py-8 sm:py-10">
        <p class="text-xs sm:text-sm font-semibold tracking-widest text-white/80 uppercase">beat-seeker 特設ページ</p>
        <h1 class="mt-1 text-3xl sm:text-4xl font-black tracking-tight">きんじょー杯</h1>
        <p class="mt-2 text-sm sm:text-base text-white/90">参加者一覧 ／ ドラフト選考 参考データ</p>
      </div>
    </header>

    <main class="max-w-5xl mx-auto px-4 py-6 sm:py-8">
      <!-- ============ 参加者詳細（ダッシュボード / スコア一覧） ============ -->
      <section v-if="selectedParticipant">
        <button
          @click="closeDetail()"
          class="inline-flex items-center gap-1.5 text-sm font-bold text-indigo-600 dark:text-indigo-400 hover:underline mb-4"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M12.707 5.293a1 1 0 010 1.414L9.414 10l3.293 3.293a1 1 0 01-1.414 1.414l-4-4a1 1 0 010-1.414l4-4a1 1 0 011.414 0z" clip-rule="evenodd" />
          </svg>
          参加者一覧に戻る
        </button>

        <!-- 参加者ヘッダ -->
        <div class="flex flex-wrap items-center gap-x-3 gap-y-1 mb-4">
          <h2 class="text-2xl font-black text-slate-800 dark:text-white break-all">{{ selectedParticipant.displayName || '名無し' }}</h2>
          <span v-if="selectedParticipant.danRank" class="px-2 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-xs font-bold rounded">{{ selectedParticipant.danRank }}</span>
          <span v-if="selectedParticipant.arenaRank" class="px-2 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-xs font-bold rounded">{{ selectedParticipant.arenaRank }}</span>
          <span class="font-mono font-bold text-slate-600 dark:text-slate-300 text-sm">総合力 {{ formatBeatPt(selectedParticipant.totalBeatPt) }}</span>
        </div>

        <!-- メモ（全員が編集可能） -->
        <div class="mb-5">
          <!-- 編集中 -->
          <div v-if="editingNoteId === selectedParticipant.id">
            <label class="block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">メモ</label>
            <textarea
              v-model="noteDraft"
              rows="3"
              maxlength="2000"
              placeholder="ドラフト選考の参考メモ（例: 皿が得意 / 第1巡指名候補 / 縦連が苦手 など）"
              class="w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 resize-y"
            ></textarea>
            <div class="flex items-center justify-end gap-2 mt-2">
              <span v-if="noteError" class="text-xs text-red-600 dark:text-red-400 mr-auto">{{ noteError }}</span>
              <button @click="cancelEditNote" class="px-3 py-1.5 text-sm font-bold text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors">キャンセル</button>
              <button
                @click="saveNote(selectedParticipant)"
                :disabled="savingNote"
                class="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-lg transition-colors disabled:opacity-50"
              >{{ savingNote ? '保存中...' : '保存' }}</button>
            </div>
          </div>
          <!-- 表示（メモあり） -->
          <div v-else-if="selectedParticipant.note" class="group flex items-start gap-2 p-3 rounded-xl bg-amber-50 dark:bg-amber-900/15 border border-amber-200 dark:border-amber-800/50 text-sm text-slate-700 dark:text-slate-200">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 mt-0.5 shrink-0 text-amber-500" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
            <span class="whitespace-pre-wrap break-words flex-1">{{ selectedParticipant.note }}</span>
            <button @click="startEditNote(selectedParticipant)" class="shrink-0 text-xs font-bold text-indigo-600 dark:text-indigo-400 hover:underline">編集</button>
          </div>
          <!-- 表示（メモなし） -->
          <button
            v-else
            @click="startEditNote(selectedParticipant)"
            class="inline-flex items-center gap-1.5 text-sm font-bold text-indigo-600 dark:text-indigo-400 hover:underline"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
            メモを追加
          </button>
        </div>

        <!-- タブ切替: ダッシュボード / スコア一覧 -->
        <div class="flex gap-1 mb-5 border-b border-slate-200 dark:border-slate-700">
          <button @click="detailTab = 'dashboard'" :class="tabClass('dashboard')">ダッシュボード</button>
          <button @click="detailTab = 'scores'" :class="tabClass('scores')">スコア一覧</button>
        </div>

        <!-- 取得状態 -->
        <div v-if="detailLoading" class="flex flex-col items-center justify-center py-16">
          <div class="w-8 h-8 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
          <p class="text-slate-500 font-medium">スコアを取得中...</p>
        </div>
        <div v-else-if="detailError" class="bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300 p-4 rounded-xl border border-red-200 dark:border-red-800">
          {{ detailError }}
        </div>
        <div v-else-if="detailScores.length === 0" class="text-center py-16 text-slate-500 dark:text-slate-400">
          表示できるスコアデータがありません。
        </div>
        <template v-else>
          <ScoreDashboard
            v-if="detailTab === 'dashboard'"
            :scores="detailScores"
            :totalPoints="selectedParticipant.totalBeatPt"
            :viewingMode="'public'"
            :viewingIidxId="selectedParticipant.iidxId"
            :viewingDisplayName="selectedParticipant.displayName"
            class="w-full"
          />
          <ScoreSummary
            v-else
            :scores="detailScores"
            :viewingMode="'public'"
            class="w-full"
          />
        </template>
      </section>

      <!-- ============ 一覧 / マトリクス ============ -->
      <template v-else>
      <!-- トップタブ -->
      <div class="flex gap-1 mb-5 border-b border-slate-200 dark:border-slate-700">
        <button @click="topTab = 'list'" :class="topTabClass('list')">参加者一覧</button>
        <button @click="openMatrixTab" :class="topTabClass('matrix')">マトリクス</button>
      </div>

      <!-- ===== 参加者一覧 ===== -->
      <template v-if="topTab === 'list'">
      <!-- ツールバー: 件数 + (管理者のみ) 追加ボタン -->
      <div class="flex items-center justify-between gap-3 mb-4">
        <p class="text-sm text-slate-500 dark:text-slate-400">
          <span class="font-bold text-slate-700 dark:text-slate-200">{{ participants.length }}</span> 名の参加者
          <span class="hidden sm:inline">（総合力 Beat-Pt 降順）</span>
        </p>
        <button
          v-if="isAdmin"
          @click="openAddModal"
          class="inline-flex items-center gap-1.5 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-lg shadow-sm transition-colors"
        >
          <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
            <path fill-rule="evenodd" d="M10 5a1 1 0 011 1v3h3a1 1 0 110 2h-3v3a1 1 0 11-2 0v-3H6a1 1 0 110-2h3V6a1 1 0 011-1z" clip-rule="evenodd" />
          </svg>
          参加者を追加
        </button>
      </div>

      <!-- ローディング -->
      <div v-if="isLoading && participants.length === 0" class="flex flex-col items-center justify-center py-20">
        <div class="w-8 h-8 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
        <p class="text-slate-500 font-medium">参加者データを取得中...</p>
      </div>

      <!-- エラー -->
      <div v-else-if="loadError" class="bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300 p-4 rounded-xl border border-red-200 dark:border-red-800">
        {{ loadError }}
      </div>

      <!-- 空状態 -->
      <div v-else-if="participants.length === 0" class="text-center py-20">
        <p class="text-slate-500 dark:text-slate-400 font-medium">まだ参加者が登録されていません。</p>
        <p v-if="isAdmin" class="mt-2 text-sm text-slate-400">右上の「参加者を追加」から登録してください。</p>
      </div>

      <!-- 参加者テーブル -->
      <div v-else class="overflow-hidden rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-sm">
        <table class="w-full text-sm">
          <thead>
            <tr class="bg-slate-50 dark:bg-slate-900/50 text-slate-500 dark:text-slate-400 text-xs uppercase tracking-wider">
              <th class="px-3 sm:px-4 py-3 text-center font-semibold w-12">#</th>
              <th class="px-3 sm:px-4 py-3 text-left font-semibold">DJ名</th>
              <th class="px-3 sm:px-4 py-3 text-right font-semibold">総合力</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden md:table-cell" title="LV12 ANOTHER/LEGGENDARIA の AAA 達成数 / 総数">AAA</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden md:table-cell" title="LV12 ANOTHER/LEGGENDARIA の MAX- 達成数 / 総数">MAX-</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden md:table-cell" title="RATE-TIER 下限（上位100曲目）のスコアレート">RATE下限</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden sm:table-cell">段位</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold hidden sm:table-cell">アリーナ</th>
              <th class="px-3 sm:px-4 py-3 text-center font-semibold w-12">メモ</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-slate-100 dark:divide-slate-700">
            <template v-for="(p, idx) in participants" :key="p.id">
            <tr class="hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors">
              <!-- 順位 -->
              <td class="px-3 sm:px-4 py-3 text-center align-top">
                <span :class="rankBadgeClass(idx)">{{ idx + 1 }}</span>
              </td>
              <!-- DJ名（クリックで /kinjocup 内に詳細を表示） + メモ -->
              <td class="px-3 sm:px-4 py-3 align-top">
                <button
                  @click="openDetail(p)"
                  class="font-bold text-indigo-600 dark:text-indigo-400 hover:underline break-all text-left"
                >{{ p.displayName || '名無し' }}</button>
                <div class="flex items-center gap-2 mt-0.5 sm:hidden">
                  <span v-if="p.danRank" class="px-1.5 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold rounded">{{ p.danRank }}</span>
                  <span v-if="p.arenaRank" class="px-1.5 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-[10px] font-bold rounded">{{ p.arenaRank }}</span>
                </div>
                <!-- LV12/RATE 集計（モバイル: 名前下に集約表示） -->
                <div class="flex flex-wrap items-center gap-x-2.5 gap-y-0.5 mt-1 md:hidden text-[11px] font-mono">
                  <span class="text-slate-500 dark:text-slate-400">AAA <span class="font-bold text-emerald-600 dark:text-emerald-400">{{ p.lv12AaaCount }}</span>/{{ p.lv12Total }}</span>
                  <span class="text-slate-500 dark:text-slate-400">MAX- <span class="font-bold text-amber-600 dark:text-amber-400">{{ p.lv12MaxMinusCount }}</span>/{{ p.lv12Total }}</span>
                  <span class="text-slate-500 dark:text-slate-400">下限 <span class="font-bold text-indigo-600 dark:text-indigo-400">{{ formatRateFloor(p) }}</span></span>
                </div>
                <!-- メモ表示（全閲覧者） -->
                <p v-if="p.note" class="text-xs text-slate-600 dark:text-slate-300 mt-1 whitespace-pre-wrap break-words flex items-start gap-1">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5 mt-0.5 shrink-0 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
                  <span>{{ p.note }}</span>
                </p>
                <p v-if="p.lastUploadedAt" class="text-[11px] text-slate-400 mt-0.5">更新: {{ formatDate(p.lastUploadedAt) }}</p>
              </td>
              <!-- 総合力 -->
              <td class="px-3 sm:px-4 py-3 text-right font-mono font-bold text-slate-700 dark:text-slate-200 whitespace-nowrap align-top">
                {{ formatBeatPt(p.totalBeatPt) }}
              </td>
              <!-- LV12 AAA数 / 総数 -->
              <td class="px-3 sm:px-4 py-3 text-center hidden md:table-cell align-top whitespace-nowrap font-mono text-xs text-slate-500 dark:text-slate-400">
                <span class="font-bold text-emerald-600 dark:text-emerald-400">{{ p.lv12AaaCount }}</span> / {{ p.lv12Total }}
              </td>
              <!-- LV12 MAX-数 / 総数 -->
              <td class="px-3 sm:px-4 py-3 text-center hidden md:table-cell align-top whitespace-nowrap font-mono text-xs text-slate-500 dark:text-slate-400">
                <span class="font-bold text-amber-600 dark:text-amber-400">{{ p.lv12MaxMinusCount }}</span> / {{ p.lv12Total }}
              </td>
              <!-- RATE-TIER 下限（100曲目）のスコアレート -->
              <td class="px-3 sm:px-4 py-3 text-center hidden md:table-cell align-top whitespace-nowrap font-mono text-xs">
                <span v-if="p.rateFloorScoreRate != null" class="font-bold text-indigo-600 dark:text-indigo-400">{{ p.rateFloorScoreRate.toFixed(2) }}%</span>
                <span v-else class="text-slate-300 dark:text-slate-600" :title="`RATE-PT対象 ${p.rateEligibleCount} 曲（100曲未満）`">—</span>
              </td>
              <!-- 段位 -->
              <td class="px-3 sm:px-4 py-3 text-center hidden sm:table-cell align-top">
                <span v-if="p.danRank" class="px-2 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-xs font-bold rounded">{{ p.danRank }}</span>
                <span v-else class="text-slate-300 dark:text-slate-600">—</span>
              </td>
              <!-- アリーナ -->
              <td class="px-3 sm:px-4 py-3 text-center hidden sm:table-cell align-top">
                <span v-if="p.arenaRank" class="px-2 py-0.5 bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-400 text-xs font-bold rounded">{{ p.arenaRank }}</span>
                <span v-else class="text-slate-300 dark:text-slate-600">—</span>
              </td>
              <!-- メモ編集（全員）・削除（管理者のみ） -->
              <td class="px-3 sm:px-4 py-3 text-center align-top">
                <div class="flex items-center justify-center gap-1.5">
                  <button
                    @click="startEditNote(p)"
                    class="text-slate-400 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors"
                    :title="p.note ? 'メモを編集' : 'メモを追加'"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
                  </button>
                  <button
                    v-if="isAdmin"
                    @click="handleRemove(p)"
                    :disabled="removingId === p.id"
                    class="text-slate-400 hover:text-red-600 dark:hover:text-red-400 transition-colors disabled:opacity-40"
                    title="名簿から削除"
                  >
                    <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" viewBox="0 0 20 20" fill="currentColor">
                      <path fill-rule="evenodd" d="M9 2a1 1 0 00-.894.553L7.382 4H4a1 1 0 000 2v10a2 2 0 002 2h8a2 2 0 002-2V6a1 1 0 100-2h-3.382l-.724-1.447A1 1 0 0011 2H9zM7 8a1 1 0 012 0v6a1 1 0 11-2 0V8zm5-1a1 1 0 00-1 1v6a1 1 0 102 0V8a1 1 0 00-1-1z" clip-rule="evenodd" />
                    </svg>
                  </button>
                </div>
              </td>
            </tr>
            <!-- メモ インライン編集行（全員） -->
            <tr v-if="editingNoteId === p.id" class="bg-indigo-50/50 dark:bg-indigo-900/10">
              <td :colspan="9" class="px-3 sm:px-4 py-3">
                <label class="block text-xs font-bold text-slate-500 dark:text-slate-400 mb-1">{{ p.displayName || '名無し' }} のメモ</label>
                <textarea
                  v-model="noteDraft"
                  rows="3"
                  maxlength="2000"
                  placeholder="ドラフト選考の参考メモ（例: 皿が得意 / 第1巡指名候補 / 縦連が苦手 など）"
                  class="w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 resize-y"
                ></textarea>
                <div class="flex items-center justify-end gap-2 mt-2">
                  <span v-if="noteError" class="text-xs text-red-600 dark:text-red-400 mr-auto">{{ noteError }}</span>
                  <button @click="cancelEditNote" class="px-3 py-1.5 text-sm font-bold text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors">キャンセル</button>
                  <button
                    @click="saveNote(p)"
                    :disabled="savingNote"
                    class="px-4 py-1.5 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-bold rounded-lg transition-colors disabled:opacity-50"
                  >{{ savingNote ? '保存中...' : '保存' }}</button>
                </div>
              </td>
            </tr>
            </template>
          </tbody>
        </table>
      </div>

      <p class="mt-4 text-[11px] text-slate-400 dark:text-slate-500 leading-relaxed">
        ※ 総合力 (Beat-Pt) は beat-seeker の総合実力指標です。DJ名をタップすると各参加者の詳細データを確認できます。<br />
        ※ AAA数・MAX-数 は LV12（ANOTHER/LEGGENDARIA）の達成数 / 総譜面数。RATE下限 は RATE-TIER 上位100曲目のスコアレート（100曲未満は「—」）です。
      </p>
      </template>

      <!-- ===== マトリクス ===== -->
      <template v-else>
        <!-- レベルサブタブ + 更新 -->
        <div class="flex items-center gap-2 mb-4">
          <div class="flex gap-1.5">
            <button @click="matrixLevel = 'lv10'" :class="levelTabClass('lv10')">LV10以下</button>
            <button @click="matrixLevel = 'lv11'" :class="levelTabClass('lv11')">LV11</button>
            <button @click="matrixLevel = 'lv12'" :class="levelTabClass('lv12')">LV12</button>
          </div>
          <button
            @click="loadMatrix"
            :disabled="matrixLoading"
            class="ml-auto text-xs font-bold text-indigo-600 dark:text-indigo-400 hover:underline disabled:opacity-50"
          >更新</button>
        </div>

        <!-- 状態 -->
        <div v-if="matrixLoading" class="flex flex-col items-center justify-center py-16">
          <div class="w-8 h-8 border-4 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mb-4"></div>
          <p class="text-slate-500 font-medium">勝敗を集計中...</p>
        </div>
        <div v-else-if="matrixError" class="bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300 p-4 rounded-xl border border-red-200 dark:border-red-800">
          {{ matrixError }}
        </div>
        <template v-else-if="currentBracket && currentBracket.players.length > 0">
          <p class="text-[11px] text-slate-400 dark:text-slate-500 mb-2 leading-relaxed">
            総当たりの勝敗表です。各セルは「行プレイヤーの 勝–負」（共通プレイ譜面で EX スコアを比較）。
            列見出しの数字は対戦相手の順位。勝ち数の多い順に上から並べています。
          </p>
          <div class="overflow-x-auto rounded-2xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-sm">
            <table class="text-sm border-collapse">
              <thead>
                <tr class="bg-slate-50 dark:bg-slate-900/50 text-slate-500 dark:text-slate-400 text-xs">
                  <th class="sticky left-0 z-10 bg-slate-50 dark:bg-slate-900/50 px-3 py-2 text-left font-semibold border border-slate-100 dark:border-slate-700">順位 / DJ名</th>
                  <th class="px-2 py-2 text-center font-semibold border border-slate-100 dark:border-slate-700 whitespace-nowrap">成績</th>
                  <th
                    v-for="(col, ci) in currentBracket.players"
                    :key="col.userId"
                    :title="col.displayName"
                    class="px-2 py-2 text-center font-semibold border border-slate-100 dark:border-slate-700"
                  >{{ ci + 1 }}</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="(matRow, ri) in currentBracket.matrix"
                  :key="currentBracket.players[ri].userId"
                  class="hover:bg-slate-50/60 dark:hover:bg-slate-700/20"
                >
                  <td class="sticky left-0 z-10 bg-white dark:bg-slate-800 px-3 py-1.5 font-bold border border-slate-100 dark:border-slate-700 whitespace-nowrap">
                    <span class="text-slate-400 mr-1">{{ ri + 1 }}.</span>
                    <span class="text-indigo-600 dark:text-indigo-400">{{ currentBracket.players[ri].displayName || '名無し' }}</span>
                  </td>
                  <td class="px-2 py-1.5 text-center font-mono text-xs border border-slate-100 dark:border-slate-700 whitespace-nowrap">
                    <span class="font-bold text-slate-700 dark:text-slate-200">{{ currentBracket.players[ri].wins }}-{{ currentBracket.players[ri].losses }}</span>
                    <span class="text-slate-400 ml-1">({{ currentBracket.players[ri].winRate.toFixed(1) }}%)</span>
                  </td>
                  <td
                    v-for="(cell, ci) in matRow"
                    :key="ci"
                    :class="matrixCellClass(cell, ri === ci)"
                  >
                    <template v-if="ri === ci">—</template>
                    <template v-else-if="cell">{{ cell.w }}-{{ cell.l }}</template>
                    <template v-else>-</template>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <div v-else class="text-center py-16 text-slate-500 dark:text-slate-400">
          対象データがありません。
        </div>
      </template>
      </template>
    </main>

    <!-- ============ 参加者追加モーダル（管理者のみ） ============ -->
    <Teleport to="body">
      <div
        v-if="showAddModal"
        class="fixed inset-0 z-[100] bg-slate-900/60 dark:bg-slate-950/80 flex items-center justify-center p-4 backdrop-blur-sm"
        @click.self="showAddModal = false"
      >
        <div class="bg-white dark:bg-slate-900 w-full max-w-lg rounded-2xl shadow-2xl flex flex-col overflow-hidden max-h-[85vh] border border-slate-200 dark:border-slate-800">
          <div class="p-5 border-b border-slate-200 dark:border-slate-800 flex items-center justify-between shrink-0">
            <h2 class="text-lg font-bold text-slate-800 dark:text-white">参加者を追加</h2>
            <button @click="showAddModal = false" class="text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 p-1.5 -mr-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor">
                <path fill-rule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clip-rule="evenodd" />
              </svg>
            </button>
          </div>

          <div class="p-4 border-b border-slate-200 dark:border-slate-800 shrink-0">
            <input
              v-model="userSearch"
              type="text"
              placeholder="DJ名 または IIDX ID で検索"
              class="w-full px-3 py-2 rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
            />
            <p v-if="addError" class="mt-2 text-xs text-red-600 dark:text-red-400">{{ addError }}</p>
          </div>

          <div class="flex-1 overflow-y-auto p-2 bg-slate-50 dark:bg-slate-900/50">
            <div v-if="loadingUsers" class="flex items-center justify-center py-10 text-slate-500 text-sm">
              <div class="w-5 h-5 border-2 border-indigo-200 border-t-indigo-600 rounded-full animate-spin mr-2"></div>
              ユーザー一覧を取得中...
            </div>
            <div v-else-if="usersError" class="p-4 text-sm text-red-600 dark:text-red-400">{{ usersError }}</div>
            <div v-else-if="filteredCandidates.length === 0" class="p-6 text-center text-sm text-slate-400">
              該当するユーザーがいません。
            </div>
            <ul v-else class="space-y-1">
              <li
                v-for="u in filteredCandidates"
                :key="u.id"
                class="flex items-center justify-between gap-3 px-3 py-2.5 rounded-lg bg-white dark:bg-slate-800 border border-slate-100 dark:border-slate-700"
              >
                <div class="flex items-center gap-2 min-w-0">
                  <span class="font-bold text-slate-800 dark:text-white truncate">{{ u.displayName || '名無し' }}</span>
                  <span class="text-[11px] text-slate-400 font-mono shrink-0">{{ u.iidxId }}</span>
                  <span v-if="u.danRank" class="px-1.5 py-0.5 bg-amber-100 text-amber-800 dark:bg-amber-900/30 dark:text-amber-400 text-[10px] font-bold rounded shrink-0">{{ u.danRank }}</span>
                </div>
                <button
                  @click="handleAdd(u)"
                  :disabled="addingId === u.id"
                  class="px-3 py-1 bg-indigo-600 hover:bg-indigo-700 text-white text-xs font-bold rounded-md transition-colors disabled:opacity-50 shrink-0"
                >
                  {{ addingId === u.id ? '追加中...' : '追加' }}
                </button>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
/**
 * 【ビューの役割】「きんじょー杯」特設ページ（/kinjocup）。
 *
 * - 誰でも閲覧できる読み取り専用の参加者一覧（総合力 Beat-Pt 降順）。
 *   DJ名クリックで /kinjocup 内に留まったまま、その参加者のダッシュボード/スコア一覧を表示する
 *   （beat-seeker 本体へは遷移しない。?user=<id> のクエリで戻る/進む・直リンクに対応）。
 * - 各参加者のメモ（ドラフト選考の覚書）は閲覧者全員が編集できる（一覧の鉛筆 / 詳細の編集ボタン）。
 * - 管理者がログイン中のときだけ「参加者を追加 / 削除」の GUI を表示する。
 *   追加は既存ユーザーを検索して名簿に登録する方式（参加者は beat-seeker ユーザーであることが前提）。
 *
 * App.vue が `/kinjocup` パスを検知してこのビューを単独描画する（サイドバー等は描画しない）。
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue';
import { useKinjoCup, type KinjoCupParticipant, type KinjoCupMatrix, type MatrixCell } from '../composables/useKinjoCup';
import { useScores } from '../composables/useScores';
import { useAdmin } from '../composables/useAdmin';
import type { ScoreData } from '../types/ScoreData';
import ScoreDashboard from '../components/ScoreDashboard.vue';
import ScoreSummary from '../components/ScoreSummary.vue';

const { isLoading, fetchParticipants, fetchMatrix, fetchParticipantScores, addParticipant, updateNote, removeParticipant } = useKinjoCup();
const { fetchAllUsers } = useScores();
const { isAdmin } = useAdmin();

// ---------- トップタブ（参加者一覧 / マトリクス） ----------
/** 表示中のトップタブ。 */
const topTab = ref<'list' | 'matrix'>('list');

// ---------- 勝敗マトリクス ----------
/** マトリクスデータ（3区分）。未取得は null。 */
const matrixData = ref<KinjoCupMatrix | null>(null);
/** マトリクス取得中フラグ。 */
const matrixLoading = ref(false);
/** マトリクス取得エラー。 */
const matrixError = ref('');
/** 表示中のレベル区分。 */
const matrixLevel = ref<'lv12' | 'lv11' | 'lv10'>('lv12');

/** 現在表示中の区分のマトリクス。 */
const currentBracket = computed(() => matrixData.value ? matrixData.value[matrixLevel.value] : null);

/** マトリクスを取得する。 */
const loadMatrix = async () => {
  matrixLoading.value = true;
  matrixError.value = '';
  try {
    matrixData.value = await fetchMatrix();
  } catch (e: any) {
    matrixError.value = e?.message || 'マトリクスの取得に失敗しました。';
  } finally {
    matrixLoading.value = false;
  }
};

/** マトリクスタブを開く（初回のみ取得）。 */
const openMatrixTab = () => {
  topTab.value = 'matrix';
  if (!matrixData.value && !matrixLoading.value) loadMatrix();
};

/** トップタブのボタン装飾。 */
const topTabClass = (tab: 'list' | 'matrix'): string => {
  const base = 'px-4 py-2 text-sm font-bold -mb-px border-b-2 transition-colors';
  return topTab.value === tab
    ? `${base} border-indigo-600 text-indigo-600 dark:text-indigo-400 dark:border-indigo-400`
    : `${base} border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200`;
};

/** レベルサブタブのボタン装飾。 */
const levelTabClass = (lv: 'lv12' | 'lv11' | 'lv10'): string => {
  const base = 'px-3 py-1.5 text-xs sm:text-sm font-bold rounded-lg transition-colors';
  return matrixLevel.value === lv
    ? `${base} bg-indigo-600 text-white`
    : `${base} bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700`;
};

/** マトリクスの 1 セルの装飾（勝ち=緑 / 負け=赤 / 引分=中立 / 対角=灰）。 */
const matrixCellClass = (cell: MatrixCell | null, isDiag: boolean): string => {
  const base = 'px-2 py-1.5 text-center font-mono text-xs whitespace-nowrap border border-slate-100 dark:border-slate-700';
  if (isDiag) return `${base} bg-slate-100 dark:bg-slate-700/40 text-slate-300 dark:text-slate-600`;
  if (!cell) return `${base} text-slate-400`;
  if (cell.w > cell.l) return `${base} bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-300 font-bold`;
  if (cell.w < cell.l) return `${base} bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300`;
  return `${base} text-slate-500 dark:text-slate-400`;
};

/** 参加者一覧（サーバから総合力降順で返る）。 */
const participants = ref<KinjoCupParticipant[]>([]);
/** 一覧取得エラー文言。 */
const loadError = ref('');
/** 削除中の参加者エントリ ID（多重押下防止）。 */
const removingId = ref<number | null>(null);

/** 参加者一覧を取得して participants に反映する。 */
const loadParticipants = async () => {
  loadError.value = '';
  try {
    participants.value = await fetchParticipants();
  } catch (e: any) {
    loadError.value = e?.message || '参加者の取得に失敗しました。';
  }
};

// ---------- 参加者詳細（ダッシュボード / スコア一覧） ----------
/** 現在詳細表示中の参加者（null なら一覧表示）。 */
const selectedParticipant = ref<KinjoCupParticipant | null>(null);
/** 詳細のスコア（曲単位グルーピング済み）。 */
const detailScores = ref<ScoreData[]>([]);
/** 詳細スコア取得中フラグ。 */
const detailLoading = ref(false);
/** 詳細スコア取得エラー。 */
const detailError = ref('');
/** 詳細の表示タブ。 */
const detailTab = ref<'dashboard' | 'scores'>('dashboard');

/** 詳細タブのボタン装飾。 */
const tabClass = (tab: 'dashboard' | 'scores'): string => {
  const base = 'px-4 py-2 text-sm font-bold -mb-px border-b-2 transition-colors';
  return detailTab.value === tab
    ? `${base} border-indigo-600 text-indigo-600 dark:text-indigo-400 dark:border-indigo-400`
    : `${base} border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200`;
};

/**
 * 参加者の詳細（ダッシュボード + スコア一覧）を /kinjocup 内で開く。
 * 画面遷移はせず、URL のクエリ (?user=) だけ更新してブラウザの戻る/進むに対応する。
 *
 * @param p     対象参加者
 * @param push  履歴に積むか（popstate からの復元時は false）
 */
const openDetail = async (p: KinjoCupParticipant, push = true) => {
  selectedParticipant.value = p;
  detailTab.value = 'dashboard';
  detailScores.value = [];
  detailError.value = '';
  if (push) {
    history.pushState({ kinjocupUser: p.userId }, '', `/kinjocup?user=${p.userId}`);
  }
  document.title = `${p.displayName || '参加者'} | きんじょー杯`;
  window.scrollTo({ top: 0 });

  detailLoading.value = true;
  try {
    detailScores.value = await fetchParticipantScores(p.userId);
  } catch (e: any) {
    detailError.value = e?.message || 'スコアの取得に失敗しました。';
  } finally {
    detailLoading.value = false;
  }
};

/** 詳細を閉じて参加者一覧に戻る。 */
const closeDetail = (push = true) => {
  selectedParticipant.value = null;
  detailScores.value = [];
  detailError.value = '';
  if (push) {
    history.pushState({}, '', '/kinjocup');
  }
  document.title = 'きんじょー杯 参加者一覧 | beat-seeker';
};

/** URL のクエリ (?user=) を見て詳細/一覧の状態を同期する（初回ロード・popstate 用）。 */
const syncFromUrl = () => {
  const uid = new URLSearchParams(window.location.search).get('user');
  if (uid) {
    const p = participants.value.find(x => String(x.userId) === uid);
    if (p) {
      openDetail(p, false);
      return;
    }
  }
  // クエリが無い / 該当参加者が居ない場合は一覧に戻す（履歴は積まない）
  if (selectedParticipant.value) closeDetail(false);
};

// ---------- 追加モーダル ----------
/** 追加モーダルの開閉。 */
const showAddModal = ref(false);
/** 全ユーザー候補（管理者 API から取得）。 */
const allUsers = ref<any[]>([]);
/** ユーザー一覧取得中フラグ。 */
const loadingUsers = ref(false);
/** ユーザー一覧取得エラー。 */
const usersError = ref('');
/** 検索文字列。 */
const userSearch = ref('');
/** 追加処理中のユーザー ID。 */
const addingId = ref<number | null>(null);
/** 追加処理のエラー文言。 */
const addError = ref('');

/** 既に名簿に登録済みのユーザー ID 集合（候補から除外するため）。 */
const registeredUserIds = computed(() => new Set(participants.value.map(p => p.userId)));

/** 検索 + 登録済み除外を適用した追加候補（DJ名昇順）。 */
const filteredCandidates = computed(() => {
  const q = userSearch.value.trim().toLowerCase();
  return allUsers.value
    .filter(u => !registeredUserIds.value.has(u.id))
    .filter(u => {
      if (!q) return true;
      return (u.displayName || '').toLowerCase().includes(q) || (u.iidxId || '').toLowerCase().includes(q);
    })
    .sort((a, b) => (a.displayName ?? '').localeCompare(b.displayName ?? '', 'ja'))
    .slice(0, 100);
});

/** 追加モーダルを開き、初回のみ全ユーザーを取得する。 */
const openAddModal = async () => {
  showAddModal.value = true;
  addError.value = '';
  if (allUsers.value.length > 0 || loadingUsers.value) return;
  loadingUsers.value = true;
  usersError.value = '';
  try {
    allUsers.value = await fetchAllUsers();
  } catch (e: any) {
    usersError.value = e?.message || 'ユーザー一覧の取得に失敗しました。';
  } finally {
    loadingUsers.value = false;
  }
};

/** 候補ユーザーを名簿に追加する。成功したら一覧を更新（候補からも自動で消える）。 */
const handleAdd = async (u: any) => {
  addingId.value = u.id;
  addError.value = '';
  try {
    await addParticipant(u.id);
    await loadParticipants();
  } catch (e: any) {
    addError.value = `${u.displayName || u.iidxId} の追加に失敗: ${e?.message || ''}`;
  } finally {
    addingId.value = null;
  }
};

// ---------- メモ編集（管理者のみ・一覧でインライン編集） ----------
/** 現在メモ編集中の参加者エントリ ID（null なら非編集）。 */
const editingNoteId = ref<number | null>(null);
/** 編集中メモの下書き。 */
const noteDraft = ref('');
/** メモ保存中フラグ。 */
const savingNote = ref(false);
/** メモ保存エラー文言。 */
const noteError = ref('');

/** メモ編集を開始（その行の下にテキストエリアを展開）。 */
const startEditNote = (p: KinjoCupParticipant) => {
  editingNoteId.value = p.id;
  noteDraft.value = p.note || '';
  noteError.value = '';
};

/** メモ編集をキャンセル。 */
const cancelEditNote = () => {
  editingNoteId.value = null;
  noteDraft.value = '';
  noteError.value = '';
};

/** メモを保存し、ローカルの参加者データへ即時反映する。 */
const saveNote = async (p: KinjoCupParticipant) => {
  savingNote.value = true;
  noteError.value = '';
  try {
    const updated = await updateNote(p.id, noteDraft.value.trim());
    // 一覧側のメモを即時反映（並び順は変わらない）。
    const target = participants.value.find(x => x.id === p.id);
    if (target) target.note = updated.note;
    // 詳細表示中の同一参加者にも反映。
    if (selectedParticipant.value?.id === p.id) selectedParticipant.value.note = updated.note;
    cancelEditNote();
  } catch (e: any) {
    noteError.value = e?.message || 'メモの保存に失敗しました。';
  } finally {
    savingNote.value = false;
  }
};

/** 参加者を名簿から削除する（確認あり）。 */
const handleRemove = async (p: KinjoCupParticipant) => {
  if (!confirm(`${p.displayName || '名無し'} を参加者名簿から削除しますか？`)) return;
  removingId.value = p.id;
  try {
    await removeParticipant(p.id);
    participants.value = participants.value.filter(x => x.id !== p.id);
  } catch (e: any) {
    alert(e?.message || '削除に失敗しました。');
  } finally {
    removingId.value = null;
  }
};

// ---------- 表示ヘルパー ----------
/** 総合力 Beat-Pt を見やすく整形する。 */
const formatBeatPt = (v: number): string =>
  (v ?? 0).toLocaleString(undefined, { maximumFractionDigits: 1 });

/** RATE-TIER 下限（100曲目）のスコアレートを整形する。100曲未満は "—"。 */
const formatRateFloor = (p: KinjoCupParticipant): string =>
  p.rateFloorScoreRate != null ? `${p.rateFloorScoreRate.toFixed(2)}%` : '—';

/** ISO 日時を YYYY/MM/DD 表記にする。 */
const formatDate = (iso: string): string => {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`;
};

/** 上位 3 名に金銀銅のバッジ装飾を付ける。 */
const rankBadgeClass = (idx: number): string => {
  const base = 'inline-flex items-center justify-center w-7 h-7 rounded-full text-xs font-black';
  if (idx === 0) return `${base} bg-amber-400 text-amber-900`;
  if (idx === 1) return `${base} bg-slate-300 text-slate-700`;
  if (idx === 2) return `${base} bg-orange-400 text-orange-900`;
  return `${base} text-slate-500 dark:text-slate-400`;
};

// ---------- ライフサイクル ----------
let robotsMeta: HTMLMetaElement | null = null;
/** ブラウザの戻る/進むで詳細⇔一覧を同期する popstate ハンドラ。 */
const onPopState = () => syncFromUrl();

onMounted(async () => {
  document.title = 'きんじょー杯 参加者一覧 | beat-seeker';
  // 参加者の個人データを含むため検索エンジンには載せない。
  robotsMeta = document.createElement('meta');
  robotsMeta.name = 'robots';
  robotsMeta.content = 'noindex,nofollow';
  document.head.appendChild(robotsMeta);

  await loadParticipants();
  // 直リンク（/kinjocup?user=123）で開かれた場合は該当参加者の詳細を表示。
  syncFromUrl();
  window.addEventListener('popstate', onPopState);
});

onBeforeUnmount(() => {
  window.removeEventListener('popstate', onPopState);
  if (robotsMeta) {
    document.head.removeChild(robotsMeta);
    robotsMeta = null;
  }
});
</script>
