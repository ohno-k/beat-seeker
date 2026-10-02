<script setup lang="ts">
/**
 * 【コンポーネントの役割】 CSV の受け取り口（ドラッグ&ドロップ + テキスト貼り付けの 2 タブ）。
 *
 * 機能:
 *  - [text] タブ: e-amusement のスコア CSV テキストを貼り付けて取り込み。空ならクリップボードから自動読み込み
 *  - [file] タブ: ファイルを D&D もしくはクリックで選択。*.csv または text/csv のみ許可
 *  - どちらの経路でも File オブジェクトを作って 'file-dropped' イベントで親に渡す
 *  - テキスト経由の場合は UTF-8 BOM を付与して Excel との互換性を確保
 *
 * emits:
 *  - file-dropped: 検証後の File オブジェクトを親に通知（親側でアップロード/解析処理）
 */
import { ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import { mdiCloudUploadOutline, mdiClipboardCheckOutline } from '@mdi/js';

const { t } = useI18n();

/** ドラッグ中かどうか。UI の枠線色を変えるためのフラグ。 */
const isDragging = ref(false);
/** 非表示の <input type="file"> への参照。プログラム的にクリックして開く。 */
const fileInput = ref<HTMLInputElement | null>(null);
/** 現在アクティブなタブ。初期値は貼り付けタブ。 */
const activeTab = ref<'text' | 'file'>('text');
/** 貼り付け用テキストエリアの内容。 */
const pastedCsvText = ref('');
/** クリップボード読み込み中フラグ（ボタンのスピナー表示）。 */
const isLoadingFromClipboard = ref(false);

const emit = defineEmits<{
  (e: 'file-dropped', file: File): void
}>();

/** 【関数の役割】 D&D 中の視覚フィードバック用。dragover のデフォルト動作抑止必須。 */
const handleDragOver = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = true;
};

/** 【関数の役割】 D&D 領域から離れた際の視覚リセット。 */
const handleDragLeave = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;
};

/**
 * 【関数の役割】 ファイルがドロップされた瞬間のハンドラ。
 * 最初の 1 ファイルのみ採用して validateAndEmit に委譲。
 */
const handleDrop = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;

  if (e.dataTransfer && e.dataTransfer.files.length > 0) {
    const file = e.dataTransfer.files[0];
    validateAndEmit(file);
  }
};

/** 【関数の役割】 <input type="file"> で選ばれた際のハンドラ。 */
const handleFileSelect = (e: Event) => {
  const target = e.target as HTMLInputElement;
  if (target.files && target.files.length > 0) {
    validateAndEmit(target.files[0]);
  }
};

/** 【関数の役割】 非表示の <input type="file"> を動的にクリックしてファイル選択ダイアログを開く。 */
const triggerFileInput = () => {
  fileInput.value?.click();
};

/**
 * 【関数の役割】 受け取ったファイルが CSV かを判定し、正しければ親に渡す。
 * 判定条件: 拡張子 .csv / MIME text/csv / Excel が付ける application/vnd.ms-excel のいずれか。
 * 拒否時はアラートのみ。
 */
const validateAndEmit = (file: File) => {
  if (file.name.toLowerCase().endsWith('.csv') || file.type === 'text/csv' || file.type === 'application/vnd.ms-excel') {
    emit('file-dropped', file);
  } else {
    alert(t('upload.errorCsv'));
  }
};

/**
 * 【関数の役割】 テキストタブの「読み込む」ボタン処理。
 * 1. テキストエリアが空ならクリップボードから自動取得
 * 2. カンマ/タブ/改行の有無で雑に CSV っぽさをチェック
 * 3. BOM 付き UTF-8 の Blob にして File オブジェクトを作り、親に渡す
 * 最後にテキストエリアをクリア。
 */
const handleTextSubmit = async () => {
  let textToProcess = pastedCsvText.value.trim();

  // テキストエリアが空ならクリップボードから読み込む。
  if (!textToProcess) {
    try {
      isLoadingFromClipboard.value = true;
      const clipboardText = await navigator.clipboard.readText();
      if (clipboardText.trim()) {
        textToProcess = clipboardText.trim();
        pastedCsvText.value = textToProcess; // 取得した内容をユーザーに見せるためにテキストエリアにも反映。
      } else {
        alert(t('import.errorEmpty'));
        isLoadingFromClipboard.value = false;
        return;
      }
    } catch (err) {
      console.error('Failed to read clipboard contents: ', err);
      alert(t('import.errorAccess'));
      isLoadingFromClipboard.value = false;
      return;
    }
  }

  // 簡易 CSV チェック: カンマ・タブ・改行がまったく無ければ弾く。
  if (!textToProcess.includes(',') && !textToProcess.includes('\t') && textToProcess.split('\n').length < 2) {
    alert(t('upload.errorInvalid'));
    isLoadingFromClipboard.value = false;
    return;
  }

  // テキストを File オブジェクトに変換。
  const blob = new Blob([textToProcess], { type: 'text/csv;charset=utf-8;' });
  // UTF-8 BOM を先頭に付与。Excel や一部パーサで日本語が文字化けするのを防ぐ保険（PapaParse は通常不要）。
  const bom = new Uint8Array([0xEF, 0xBB, 0xBF]);
  const combinedBlob = new Blob([bom, blob]);

  const file = new File([combinedBlob], 'pasted_scores.csv', { type: 'text/csv' });

  emit('file-dropped', file);
  isLoadingFromClipboard.value = false;
  pastedCsvText.value = ''; // 成功時はテキストエリアをクリア。
};

</script>

<template>
  <div class="w-full max-w-2xl mx-auto flex flex-col items-center">

    <!-- タブ切替（テキスト貼り付け / ファイルドロップ） -->
    <v-tabs v-model="activeTab" grow class="w-full mb-4">
      <v-tab value="text" class="text-sm font-medium">
        {{ t('upload.textTab') }}
      </v-tab>
      <v-tab value="file" class="text-sm font-medium">
        {{ t('upload.fileTab') }}
      </v-tab>
    </v-tabs>

    <!-- タブ内容（2 つを絶対配置して fade でクロスフェード） -->
    <div class="w-full relative min-h-[300px]">
        <!-- ファイルアップロード領域（D&D + クリックで選択） -->
        <transition name="fade">
            <div
            v-if="activeTab === 'file'"
            class="absolute inset-0 w-full h-full p-12 border-2 border-dashed rounded-md transition-all duration-200 flex flex-col items-center justify-center cursor-pointer bg-white dark:bg-slate-800"
            :class="{
                'border-blue-500 dark:border-blue-400 bg-blue-50/50 dark:bg-blue-900/20': isDragging,
                'border-slate-300 dark:border-slate-600 hover:border-slate-400 dark:hover:border-slate-500 hover:bg-slate-50 dark:hover:bg-slate-700/50': !isDragging
            }"
            @dragover="handleDragOver"
            @dragleave="handleDragLeave"
            @drop="handleDrop"
            @click="triggerFileInput"
            >
            <div class="bg-blue-100 dark:bg-blue-900/50 text-blue-600 dark:text-blue-400 p-4 rounded-full mb-4 transition-colors duration-200">
                <v-icon :icon="mdiCloudUploadOutline" size="32" />
            </div>
            <h3 class="text-xl font-bold text-slate-700 dark:text-slate-200 mb-2">
                {{ t('upload.drop') }}
            </h3>
            <p class="text-slate-500 dark:text-slate-400 text-center text-sm mb-6">
                {{ t('upload.dropOrClick') }}<br/>
                {{ t('upload.csvSupport') }}
            </p>

            <v-btn
                color="primary"
                size="large"
                class="px-6 font-medium"
                @click.stop="triggerFileInput"
            >
                {{ t('upload.select') }}
            </v-btn>
            <input
                type="file"
                ref="fileInput"
                accept=".csv,text/csv"
                class="hidden"
                @change="handleFileSelect"
            />
            </div>
        </transition>

        <!-- テキスト貼り付け領域（空ならクリップボード自動読み込み） -->
        <transition name="fade">
            <v-card
            v-if="activeTab === 'text'"
            class="absolute inset-0 w-full h-full p-6 flex flex-col"
            >
            <h3 class="text-lg font-bold text-slate-700 dark:text-slate-200 mb-2">
                {{ t('upload.pasteTitle') }}
            </h3>
            <p class="text-slate-500 dark:text-slate-400 text-sm mb-4">
                <a href="https://p.eagate.573.jp/game/2dx/34/djdata/score_download.html?style=SP" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 hover:underline font-medium">{{ t('upload.officialSiteLinkText') }}</a>{{ t('upload.officialSiteManualHint') }}
            </p>

            <v-textarea
                v-model="pastedCsvText"
                no-resize
                rows="4"
                class="flex-1 w-full font-mono text-sm mb-4"
                :placeholder="t('upload.textareaPlaceholder')"
            />

            <v-btn
                block
                size="large"
                class="font-medium bg-slate-800 dark:bg-slate-700 hover:bg-slate-900 dark:hover:bg-slate-600 text-white"
                :class="{'opacity-75 cursor-wait': isLoadingFromClipboard}"
                @click="handleTextSubmit"
                :disabled="isLoadingFromClipboard"
            >
                <v-icon v-if="!isLoadingFromClipboard" :icon="mdiClipboardCheckOutline" size="20" class="mr-2" />
                <v-progress-circular v-else size="20" width="2" color="white" class="mr-2" />
                {{ pastedCsvText.trim() ? t('upload.load') : t('upload.loadFromClipboard') }}
            </v-btn>
            <p v-if="!pastedCsvText.trim()" class="text-xs text-slate-400 dark:text-slate-500 text-center mt-3">
                {{ t('upload.clipboardAutoLoad') }}
            </p>
            </v-card>
        </transition>
    </div>
  </div>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
  transform: translateY(5px);
}
</style>
