<script setup lang="ts">
/**
 * 【コンポーネントの役割】 CSV スコア + ARENA バトル履歴をまとめて取り込む統合インポート UI。
 * - Android アプリ内で開かれている場合は、最上部に「1タップ取り込み」ボタンを出す
 *   （アプリが非表示 WebView で eagate の公式 CSV を取得 → 同じ処理へ流す。ブックマークレット不要）
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
import { ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import { useNativeBridge } from '../composables/useNativeBridge';
import {
  mdiCheck,
  mdiCheckCircleOutline,
  mdiClipboardOutline,
  mdiClose,
  mdiCloudDownloadOutline,
  mdiCloudUploadOutline,
  mdiContentCopy,
  mdiInformationOutline,
  mdiRefresh,
} from '@mdi/js';

const { t } = useI18n();
const { isNativeApp, message: nativeMessage, startNativeImport } = useNativeBridge();

const props = defineProps<{ bookmarkletCode: string }>();
const emit = defineEmits<{
  (e: 'close'): void;
  /**
   * スコア CSV を親へ引き渡す。
   * `origin` が 'bookmarklet' の場合、その CSV はブックマークレットが生成したもので
   * 「バージョン」列が空欄になる。親は作品バージョンの自動判定をスキップし、現行作として扱う。
   * `pageVersion` はブックマークレットを実行したページの作品番号（読めなければ null）。親はこれを
   * 送信レコードの `sourceVersion` に載せ、前作のページで取った結果をサーバーに弾かせる。
   */
  (e: 'score-file', file: File, origin?: 'bookmarklet', pageVersion?: number | null): void;
}>();

// ---- ブックマークレット使い方モーダル関連 ----
/** ヘルプモーダル表示フラグ。 */
const showHelpModal = ref(false);
/** ヘルプ内「PC / SP」タブの選択状態（初期値は SP）。 */
const deviceTab = ref<'pc' | 'sp'>('sp');
/** ブックマークレットコードをコピー済みかの一時フラグ（2 秒で戻る）。 */
const codeCopied = ref(false);

// ---- メインタブ ----
/** インポート方式タブ（テキスト貼り付け / ファイルアップロード）。 */
const importTab = ref<'text' | 'file'>('text');

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
          // CSV の出どころで作品バージョンの自動判定の可否が変わる。
          //  - 'official'（アプリの1タップ取り込み）… 公式CSVなので「バージョン」列が埋まっており、
          //    通常どおり自動判定させる（origin を渡さない）。
          //  - 'difficulty'（ブックマークレット）… 難易度別ページ由来で「バージョン」列が空欄のため、
          //    origin を伝えて親側の判定をスキップさせる。
          // scoresCsvSource が無い古い出力（更新前のブックマークレットや、以前コピーした
          // クリップボードの内容）は従来どおり 'bookmarklet' 扱いにして挙動を変えない。
          const isOfficialCsv = parsed.scoresCsvSource === 'official';
          const pageVersion = typeof parsed.pageVersion === 'number' ? parsed.pageVersion : null;
          emit('score-file', makeCsvFile(parsed.scoresCsv), isOfficialCsv ? undefined : 'bookmarklet', pageVersion);
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
 * 【関数の役割】 Android アプリの「1タップ取り込み」ボタンのハンドラ。
 *
 * アプリが非表示 WebView で eagate からスコア CSV と ARENA データを収集し、
 * ブックマークレットと同一形式の JSON を返す。あとは貼り付け経路と同じ processText に流すだけで、
 * CSV パース〜サーバ登録までの既存パイプラインがそのまま動く。
 *
 * eagate 未ログインの場合はアプリがログイン画面を出し、ログイン成功後に自動で再実行するため
 * ここでは待たされるだけで何もしない。ログインをキャンセルした場合だけエラーとして案内する。
 */
const handleNativeImport = async () => {
  isImporting.value = true;
  resultMsg.value = '';
  resultError.value = '';
  try {
    const json = await startNativeImport();
    // processText 側でも isImporting を制御するため、ここでは先に降ろしておく。
    isImporting.value = false;
    await processText(json);
  } catch (e: any) {
    isImporting.value = false;
    resultError.value = e?.message === 'login cancelled'
      ? t('import.nativeNeedLogin')
      : t('import.nativeFail');
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
    <v-alert v-if="resultError" type="error" class="text-sm">
      {{ resultError }}
    </v-alert>
    <v-alert v-if="resultMsg" type="success" class="text-sm font-medium">
      {{ resultMsg }}
    </v-alert>

    <!-- Android アプリ内のみ: ブックマークレット不要の 1 タップ取り込み -->
    <v-alert v-if="isNativeApp" type="info" :icon="false">
      <div class="space-y-2">
        <p class="text-xs text-blue-800 dark:text-blue-300 leading-relaxed">{{ t('import.nativeHint') }}</p>
        <v-btn
          color="primary"
          block
          class="text-sm"
          :disabled="isImporting"
          @click="handleNativeImport"
        >
          <v-icon v-if="!isImporting" :icon="mdiRefresh" size="16" class="mr-2" />
          <v-progress-circular v-else size="16" width="2" color="white" class="mr-2" />
          {{ isImporting ? (nativeMessage || t('import.importing')) : t('import.nativeImport') }}
        </v-btn>
      </div>
    </v-alert>

    <!-- ARENA info banner -->
    <v-alert type="warning" :icon="mdiInformationOutline">
      <p class="text-xs text-amber-800 dark:text-amber-300 leading-relaxed">
        {{ t('import.arenaHint') }}
        <button @click="showHelpModal = true" class="font-bold underline underline-offset-2 hover:text-amber-600 dark:hover:text-amber-200 transition-colors">
          {{ t('import.bookmarkletHelp') }}
        </button>
      </p>
    </v-alert>

    <!-- Tab switcher -->
    <v-tabs v-model="importTab" grow>
      <v-tab value="text" class="text-sm font-medium">{{ t('import.tabText') }}</v-tab>
      <v-tab value="file" class="text-sm font-medium">{{ t('import.tabFile') }}</v-tab>
    </v-tabs>

    <!-- Text paste tab -->
    <div v-if="importTab === 'text'" class="space-y-2">
      <p class="text-xs text-slate-500 dark:text-slate-400" v-html="t('import.textHint', { link: `<a href='https://p.eagate.573.jp/game/2dx/34/djdata/score_download.html?style=SP' target='_blank' rel='noopener noreferrer' class='text-blue-600 dark:text-blue-400 hover:underline font-medium'>${t('import.textHintLinkText')}</a>` })"></p>
      <!-- 過去作の CSV も同じ入口で受け付ける（作品は自動判定し、過去作は歴代記録へ保存される） -->
      <p class="text-xs text-slate-500 dark:text-slate-400">{{ t('import.pastCsvHint') }}</p>
      <v-textarea
        v-model="pastedText"
        no-resize
        rows="4"
        class="text-xs font-mono"
        :placeholder="t('import.textareaPlaceholder')"
      />
    </div>

    <!-- File upload tab -->
    <div v-else-if="importTab === 'file'" class="space-y-2">
      <div
        class="border-2 border-dashed rounded-md p-6 flex flex-col items-center gap-3 cursor-pointer transition-all"
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
        <v-icon v-if="!selectedFile" :icon="mdiCloudUploadOutline" size="32" class="text-slate-400 dark:text-slate-500" />
        <v-icon v-else :icon="mdiCheckCircleOutline" size="32" class="text-green-500" />
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
      <!-- 過去作の CSV も同じ入口で受け付ける（作品は自動判定し、過去作は歴代記録へ保存される） -->
      <p class="text-xs text-slate-500 dark:text-slate-400">{{ t('import.pastCsvHint') }}</p>
    </div>

    <!-- Unified submit button -->
    <v-btn
      block
      class="text-sm bg-slate-800 dark:bg-slate-700 hover:bg-slate-900 dark:hover:bg-slate-600 text-white"
      :disabled="isImporting || (importTab === 'file' && !selectedFile)"
      @click="handleSubmit"
    >
      <v-icon v-if="!isImporting && importTab === 'text' && !pastedText.trim()" :icon="mdiClipboardOutline" size="16" class="mr-2" />
      <v-icon v-else-if="!isImporting" :icon="mdiCloudDownloadOutline" size="16" class="mr-2" />
      <v-progress-circular v-else size="16" width="2" color="white" class="mr-2" />
      <template v-if="isImporting">{{ t('import.importing') }}</template>
      <template v-else-if="importTab === 'text' && !pastedText.trim()">{{ t('import.loadFromClipboard') }}</template>
      <template v-else>{{ t('import.load') }}</template>
    </v-btn>

  </div>

  <!-- Bookmarklet help modal -->
  <v-dialog v-model="showHelpModal" max-width="512">
    <v-card class="p-6">
      <div class="flex justify-between items-center mb-4">
        <h3 class="text-base font-bold text-slate-800 dark:text-white">{{ t('import.helpTitle') }}</h3>
        <v-btn icon variant="text" size="small" class="text-slate-400" aria-label="close" @click="showHelpModal = false">
          <v-icon :icon="mdiClose" />
        </v-btn>
      </div>

      <p class="text-sm text-slate-500 dark:text-slate-400 mb-4">{{ t('import.helpDesc') }}</p>

      <!-- PC / SP tab switcher -->
      <v-btn-toggle v-model="deviceTab" mandatory class="mb-4">
        <v-btn value="sp" size="small" class="px-4 text-xs">{{ t('import.deviceSp') }}</v-btn>
        <v-btn value="pc" size="small" class="px-4 text-xs">{{ t('import.devicePc') }}</v-btn>
      </v-btn-toggle>

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
        <v-btn
          :color="codeCopied ? 'success' : 'primary'"
          class="text-sm"
          :prepend-icon="codeCopied ? mdiCheck : mdiContentCopy"
          @click="copyBookmarkletCode"
        >
          {{ codeCopied ? t('import.copied') : t('import.copyCode') }}
        </v-btn>
      </div>

      <!-- PC instructions -->
      <div v-else class="space-y-3">
        <ol class="text-xs text-slate-700 dark:text-slate-300 space-y-1.5 list-decimal list-inside leading-relaxed">
          <li>{{ t('import.pcStep1') }}</li>
          <li>{{ t('import.pcStep2') }}</li>
          <li>{{ t('import.pcStep3') }}</li>
        </ol>
        <div class="flex items-center gap-3 flex-wrap">
          <!-- ブックマークバーへドラッグして登録する本物のリンクが必要なので、ネイティブの <a> のまま残す -->
          <a
            :href="bookmarkletCode"
            class="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 text-white font-bold rounded-lg transition-colors text-sm select-none shrink-0"
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
    </v-card>
  </v-dialog>
</template>
