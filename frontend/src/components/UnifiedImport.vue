<script setup lang="ts">
/**
 * 【コンポーネントの役割】 CSV スコア + ARENA バトル履歴をまとめて取り込む統合インポート UI。
 * - タブ切替で「テキスト貼り付け」／「ファイルアップロード」の 2 経路
 * - テキスト貼り付け経路は JSON（ブックマークレット出力）/ CSV 生文字列の両対応
 *     - JSON の場合: scoresCsv → 親へ emit、battles → /api/arena/import に POST
 *     - CSV の場合: そのまま BOM 付きで File 化して親に emit
 * - ファイル経路は .csv のみ受付（D&D + クリックの両対応）
 * - ブックマークレット導入方法のヘルプモーダル（PC/SP タブ）を内包
 *
 * @emits close 処理完了時にモーダルを閉じる。
 * @emits score-file スコア CSV を File として親（メインインポート処理）に引き渡す。
 */
import { ref, computed } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useAuth } from '../composables/useAuth';
import InfinitasMonitor from './InfinitasMonitor.vue';

const { t } = useI18n();
const { user } = useAuth();

const props = defineProps<{ bookmarkletCode: string }>();
const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'score-file', file: File): void;
}>();

/**
 * INFINITAS モードへのアクセス可否。サポーター限定機能。
 * user.isSupporter フラグで判定する。
 */
const canUseInfinitas = computed(() => !!user.value && user.value.isSupporter === true);

// ---- ブックマークレット使い方モーダル関連 ----
/** ヘルプモーダル表示フラグ。 */
const showHelpModal = ref(false);
/** ヘルプ内「PC / SP」タブの選択状態（初期値は SP）。 */
const deviceTab = ref<'pc' | 'sp'>('sp');
/** ブックマークレットコードをコピー済みかの一時フラグ（2 秒で戻る）。 */
const codeCopied = ref(false);

// ---- メインタブ ----
/** インポート方式タブ（テキスト貼り付け / ファイルアップロード / INFINITAS 画面取込）。 */
const importTab = ref<'text' | 'file' | 'infinitas'>('text');

// ---- ファイルアップロード状態 ----
/** D&D 中のハイライト表示フラグ。 */
const isDragging = ref(false);
/** 非表示の <input type=file> への参照。 */
const fileInput = ref<HTMLInputElement | null>(null);
/** 選択／ドロップで確定した CSV ファイル（送信前の一時保持）。 */
const selectedFile = ref<File | null>(null);

/** テキストタブの入力欄バインド。空の場合はクリップボードから読み込む。 */
const pastedText = ref('');

// ---- 処理状態メッセージ ----
/** 送信中フラグ（ボタン無効化・スピナー表示用）。 */
const isImporting = ref(false);
/** 成功メッセージ（緑表示）。 */
const resultMsg = ref('');
/** エラーメッセージ（赤表示）。 */
const resultError = ref('');

const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/**
 * 【関数の役割】 CSV テキストを UTF-8 BOM 付き File に変換する。
 * Excel が BOM なしを Shift_JIS と誤認して文字化けするのを防ぐため先頭に EF BB BF を付与する。
 */
const makeCsvFile = (csvText: string): File => {
  const bom = new Uint8Array([0xEF, 0xBB, 0xBF]);
  const blob = new Blob([bom, csvText], { type: 'text/csv;charset=utf-8;' });
  return new File([blob], 'scores.csv', { type: 'text/csv' });
};

/**
 * 【関数の役割】 テキストタブ／クリップボードから取得した文字列を JSON か CSV に振り分けて処理する。
 * 流れ：
 *   1. JSON.parse を試みる → 失敗なら CSV とみなす
 *   2. CSV ならそのまま親へファイル emit
 *   3. JSON なら
 *       - scoresCsv があれば CSV File に変換して親へ emit
 *       - battles 配列があれば /api/arena/import に POST してバトル履歴投入
 *   4. 処理結果メッセージを合成して 1.5 秒後にモーダルを閉じる
 */
const processText = async (text: string) => {
  isImporting.value = true;
  resultMsg.value = '';
  resultError.value = '';

  try {
    let parsed: any = null;
    let isCsv = false;
    try {
      parsed = JSON.parse(text);
    } catch {
      // JSON ではなかった → CSV として扱う。
      isCsv = true;
    }

    let scoresReady = false;
    let arenaResultMsg = '';

    if (isCsv) {
      emit('score-file', makeCsvFile(text));
      scoresReady = true;
    } else {
      // JSON ルート: scoresCsv と battles を別々に処理する。
      if (parsed.scoresCsv) {
        try {
          emit('score-file', makeCsvFile(parsed.scoresCsv));
          scoresReady = true;
        } catch (e) {
          console.warn('Score file preparation failed:', e);
        }
      }
      if (parsed.battles && Array.isArray(parsed.battles) && parsed.battles.length > 0) {
        // ARENA バトル履歴はサーバに直接 POST（スコアとは経路が異なる）。
        const token = localStorage.getItem('beat-seeker-token');
        const res = await fetch(`${API_BASE}/api/arena/import`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
          body: JSON.stringify(parsed),
        });
        const data = await res.json();
        if (res.ok) {
          arenaResultMsg = data.message;
        } else {
          resultError.value = data.message || t('import.arenaFail');
          return;
        }
      }
    }

    // 結果メッセージを合成（scores / arena の両方走った場合は連結）。
    const parts = [];
    if (scoresReady) parts.push(t('import.scoresProcessing'));
    if (arenaResultMsg) parts.push(arenaResultMsg);
    resultMsg.value = parts.length > 0 ? parts.join('. ') + '.' : t('import.noData');
    setTimeout(() => emit('close'), 1500);
  } catch (e: any) {
    resultError.value = e.message || t('import.fail');
  } finally {
    isImporting.value = false;
  }
};

/**
 * 【関数の役割】 メインの送信ボタン押下ハンドラ。現在のタブに応じて処理を分岐する。
 * - file タブ: 選択済みファイルをそのまま親に emit
 * - text タブ: 入力欄が空なら clipboard.readText() へフォールバック
 */
const handleSubmit = async () => {
  if (importTab.value === 'file') {
    if (!selectedFile.value) return;
    resultMsg.value = '';
    resultError.value = '';
    emit('score-file', selectedFile.value);
    resultMsg.value = t('import.scoresProcessing');
    setTimeout(() => emit('close'), 1500);
    return;
  }
  // テキストタブ: 手動入力があれば優先、なければクリップボード読取。
  const typed = pastedText.value.trim();
  if (typed) {
    await processText(typed);
    pastedText.value = '';
  } else {
    try {
      const text = (await navigator.clipboard.readText()).trim();
      if (!text) {
        resultError.value = t('import.errorEmpty');
        return;
      }
      await processText(text);
    } catch {
      // 権限拒否・非対応ブラウザ等で readText が失敗するケース。
      resultError.value = t('import.errorAccess');
    }
  }
};

/**
 * 【関数の役割】 ドロップまたは選択されたファイルを検証して selectedFile にセットする。
 * 拡張子 .csv もしくは MIME type text/csv / application/vnd.ms-excel を受け付ける。
 */
const stageFile = (file: File) => {
  if (!file.name.toLowerCase().endsWith('.csv') && file.type !== 'text/csv' && file.type !== 'application/vnd.ms-excel') {
    resultError.value = t('import.errorCsv');
    return;
  }
  resultError.value = '';
  selectedFile.value = file;
};

/** 【関数の役割】 ドロップイベントハンドラ。デフォルトの「ブラウザがファイルを開く」動作を止めて stageFile に委譲。 */
const handleDrop = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;
  if (e.dataTransfer?.files.length) stageFile(e.dataTransfer.files[0]);
};

/** 【関数の役割】 ブックマークレットコード（javascript:... 文字列）をクリップボードへコピーし 2 秒だけ成功表示する。 */
const copyBookmarkletCode = async () => {
  try {
    await navigator.clipboard.writeText(props.bookmarkletCode);
    codeCopied.value = true;
    setTimeout(() => { codeCopied.value = false; }, 2000);
  } catch {
    // コピー権限拒否は黙って無視（ユーザーが手動で長押しコピーできる）。
  }
};
</script>

<template>
  <div class="space-y-4">

    <!-- Result messages -->
    <div v-if="resultError" class="p-3 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl text-sm text-red-700 dark:text-red-400">
      {{ resultError }}
    </div>
    <div v-if="resultMsg" class="p-3 bg-green-50 dark:bg-green-900/30 border border-green-200 dark:border-green-800 rounded-xl text-sm text-green-700 dark:text-green-400 font-medium">
      {{ resultMsg }}
    </div>

    <!-- ARENA info banner -->
    <div class="flex items-start gap-2.5 p-3 bg-amber-50 dark:bg-amber-950/40 border border-amber-200 dark:border-amber-800 rounded-xl">
      <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-amber-500 dark:text-amber-400 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 16h-1v-4h-1m1-4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
      <p class="text-xs text-amber-800 dark:text-amber-300 leading-relaxed">
        {{ t('import.arenaHint') }}
        <button @click="showHelpModal = true" class="font-bold underline underline-offset-2 hover:text-amber-600 dark:hover:text-amber-200 transition-colors">
          {{ t('import.bookmarkletHelp') }}
        </button>
      </p>
    </div>

    <!-- Tab switcher -->
    <div class="flex bg-slate-100 dark:bg-slate-800 p-1 rounded-xl border border-slate-200 dark:border-slate-700">
      <button
        class="flex-1 py-2 text-sm font-medium rounded-lg transition-all"
        :class="importTab === 'text' ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        @click="importTab = 'text'"
      >{{ t('import.tabText') }}</button>
      <button
        class="flex-1 py-2 text-sm font-medium rounded-lg transition-all"
        :class="importTab === 'file' ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        @click="importTab = 'file'"
      >{{ t('import.tabFile') }}</button>
      <button
        v-if="canUseInfinitas"
        class="flex-1 py-2 text-sm font-medium rounded-lg transition-all"
        :class="importTab === 'infinitas' ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        @click="importTab = 'infinitas'"
      >{{ t('import.tabInfinitas') }}</button>
    </div>

    <!-- Text paste tab -->
    <div v-if="importTab === 'text'" class="space-y-2">
      <p class="text-xs text-slate-500 dark:text-slate-400" v-html="t('import.textHint', { link: `<a href='https://p.eagate.573.jp/game/2dx/33/djdata/score_download.html?style=SP' target='_blank' rel='noopener noreferrer' class='text-blue-600 dark:text-blue-400 hover:underline font-medium'>${t('import.textHintLinkText')}</a>` })"></p>
      <textarea
        v-model="pastedText"
        class="w-full h-24 p-3 border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 rounded-xl text-xs font-mono text-slate-800 dark:text-slate-100 resize-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500 placeholder-slate-400 dark:placeholder-slate-500"
        :placeholder="t('import.textareaPlaceholder')"
      ></textarea>
    </div>

    <!-- INFINITAS monitor tab (許可ユーザーのみ) -->
    <div v-else-if="importTab === 'infinitas' && canUseInfinitas">
      <InfinitasMonitor />
    </div>

    <!-- File upload tab -->
    <div v-else-if="importTab === 'file'" class="space-y-2">
      <div
        class="border-2 border-dashed rounded-xl p-6 flex flex-col items-center gap-3 cursor-pointer transition-all"
        :class="isDragging
          ? 'border-blue-400 bg-blue-50 dark:bg-blue-900/20'
          : selectedFile
            ? 'border-green-400 bg-green-50 dark:bg-green-900/20'
            : 'border-slate-300 dark:border-slate-600 hover:border-slate-400 dark:hover:border-slate-500 hover:bg-slate-50 dark:hover:bg-slate-800/50'"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop="handleDrop"
        @click="fileInput?.click()"
      >
        <svg v-if="!selectedFile" xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-slate-400 dark:text-slate-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
        </svg>
        <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-green-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
        </svg>
        <div class="text-center">
          <p class="text-sm font-medium text-slate-700 dark:text-slate-300">
            {{ selectedFile ? selectedFile.name : t('import.dropPlaceholder') }}
          </p>
          <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
            {{ selectedFile ? t('import.clickToChange') : t('import.clickToSelect') }}
          </p>
        </div>
        <input type="file" ref="fileInput" accept=".csv,text/csv" class="hidden" @change="e => { const f = (e.target as HTMLInputElement).files?.[0]; if (f) stageFile(f); }" />
      </div>
    </div>

    <!-- Unified submit button（INFINITAS タブでは非表示。InfinitasMonitor が独自のコントロールを持つため） -->
    <button
      v-if="importTab !== 'infinitas'"
      @click="handleSubmit"
      :disabled="isImporting || (importTab === 'file' && !selectedFile)"
      class="w-full py-2.5 bg-slate-800 dark:bg-slate-700 hover:bg-slate-900 dark:hover:bg-slate-600 text-white font-bold rounded-xl transition-all shadow-sm disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2 text-sm"
    >
      <svg v-if="!isImporting && importTab === 'text' && !pastedText.trim()" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
      </svg>
      <svg v-else-if="!isImporting" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
        <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M9 19l3 3m0 0l3-3m-3 3V10" />
      </svg>
      <svg v-else class="animate-spin h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
        <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
        <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"></path>
      </svg>
      <template v-if="isImporting">{{ t('import.importing') }}</template>
      <template v-else-if="importTab === 'text' && !pastedText.trim()">{{ t('import.loadFromClipboard') }}</template>
      <template v-else>{{ t('import.load') }}</template>
    </button>

  </div>

  <!-- Bookmarklet help modal -->
  <Teleport to="body">
    <div v-if="showHelpModal" class="fixed inset-0 z-[60] flex items-center justify-center p-4 bg-black/60 backdrop-blur-sm" @click.self="showHelpModal = false">
      <div class="w-full max-w-lg bg-white dark:bg-slate-800 rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 p-6 animate-fade-in">
        <div class="flex justify-between items-center mb-4">
          <h3 class="text-base font-bold text-slate-800 dark:text-white">{{ t('import.helpTitle') }}</h3>
          <button @click="showHelpModal = false" class="p-1 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 transition-colors rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700">
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">{{ t('import.helpDesc') }}</p>

        <!-- PC / SP tab switcher -->
        <div class="flex bg-slate-100 dark:bg-slate-700 rounded-lg p-0.5 w-fit mb-4">
          <button
            class="px-4 py-1.5 text-xs font-bold rounded-md transition-all"
            :class="deviceTab === 'sp' ? 'bg-white dark:bg-slate-600 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400'"
            @click="deviceTab = 'sp'"
          >{{ t('import.deviceSp') }}</button>
          <button
            class="px-4 py-1.5 text-xs font-bold rounded-md transition-all"
            :class="deviceTab === 'pc' ? 'bg-white dark:bg-slate-600 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400'"
            @click="deviceTab = 'pc'"
          >{{ t('import.devicePc') }}</button>
        </div>

        <!-- Smartphone instructions -->
        <div v-if="deviceTab === 'sp'" class="space-y-3">
          <ol class="text-xs text-slate-700 dark:text-slate-300 space-y-2 list-decimal list-inside leading-relaxed">
            <li>
              <span class="font-bold">{{ t('import.spStep1Title') }}</span>
              <ol class="mt-1.5 ml-4 space-y-1 list-[lower-alpha] list-inside text-slate-600 dark:text-slate-400">
                <li>{{ t('import.spStep1a') }}</li>
                <li>{{ t('import.spStep1b') }}</li>
                <li>{{ t('import.spStep1c') }}</li>
                <li>{{ t('import.spStep1d') }}</li>
                <li>{{ t('import.spStep1e') }}</li>
              </ol>
            </li>
            <li>{{ t('import.spStep2') }}</li>
            <li>{{ t('import.spStep3') }}</li>
            <li>{{ t('import.spStep4') }}</li>
          </ol>
          <button
            @click="copyBookmarkletCode"
            class="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-bold shadow-sm transition-all"
            :class="codeCopied ? 'bg-green-500 text-white' : 'bg-blue-600 hover:bg-blue-700 text-white'"
          >
            <svg v-if="!codeCopied" xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-4 10h6a2 2 0 002-2v-8a2 2 0 00-2-2h-6a2 2 0 00-2 2v8a2 2 0 002 2z" />
            </svg>
            <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 13l4 4L19 7" />
            </svg>
            {{ codeCopied ? t('import.copied') : t('import.copyCode') }}
          </button>
        </div>

        <!-- PC instructions -->
        <div v-else class="space-y-3">
          <ol class="text-xs text-slate-700 dark:text-slate-300 space-y-1.5 list-decimal list-inside leading-relaxed">
            <li>{{ t('import.pcStep1') }}</li>
            <li>{{ t('import.pcStep2') }}</li>
            <li>{{ t('import.pcStep3') }}</li>
          </ol>
          <div class="flex items-center gap-3 flex-wrap">
            <a
              :href="bookmarkletCode"
              class="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded-lg shadow-sm transition-colors text-sm select-none shrink-0"
              @click.prevent
              draggable="true"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M5 5a2 2 0 012-2h10a2 2 0 012 2v16l-7-3.5L5 21V5z" />
              </svg>
              {{ t('import.bookmarkletName') }}
            </a>
            <span class="text-xs text-slate-500 dark:text-slate-400">{{ t('import.dragToRegister') }}</span>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
