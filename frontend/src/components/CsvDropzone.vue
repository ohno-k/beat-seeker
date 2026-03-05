<script setup lang="ts">
import { ref } from 'vue';

const isDragging = ref(false);
const fileInput = ref<HTMLInputElement | null>(null);
const activeTab = ref<'file' | 'text'>('file');
const pastedCsvText = ref('');
const isLoadingFromClipboard = ref(false);

const emit = defineEmits<{
  (e: 'file-dropped', file: File): void
}>();

const handleDragOver = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = true;
};

const handleDragLeave = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;
};

const handleDrop = (e: DragEvent) => {
  e.preventDefault();
  isDragging.value = false;
  
  if (e.dataTransfer && e.dataTransfer.files.length > 0) {
    const file = e.dataTransfer.files[0];
    validateAndEmit(file);
  }
};

const handleFileSelect = (e: Event) => {
  const target = e.target as HTMLInputElement;
  if (target.files && target.files.length > 0) {
    validateAndEmit(target.files[0]);
  }
};

const triggerFileInput = () => {
  fileInput.value?.click();
};

const validateAndEmit = (file: File) => {
  // Check if it's a CSV based on name or type
  if (file.name.toLowerCase().endsWith('.csv') || file.type === 'text/csv' || file.type === 'application/vnd.ms-excel') {
    emit('file-dropped', file);
  } else {
    alert('CSVファイルをアップロードしてください。'); // Simple alert for now
  }
};

const handleTextSubmit = async () => {
  let textToProcess = pastedCsvText.value.trim();

  // If textarea is empty, try to read from clipboard
  if (!textToProcess) {
    try {
      isLoadingFromClipboard.value = true;
      const clipboardText = await navigator.clipboard.readText();
      if (clipboardText.trim()) {
        textToProcess = clipboardText.trim();
        pastedCsvText.value = textToProcess; // Optional: fill the textarea to show what was read
      } else {
        alert('クリップボードにテキストがありません。');
        isLoadingFromClipboard.value = false;
        return;
      }
    } catch (err) {
      console.error('Failed to read clipboard contents: ', err);
      alert('クリップボードの読み取りに失敗しました。お使いのブラウザか設定で許可されていない可能性があります。手動でペーストしてください。');
      isLoadingFromClipboard.value = false;
      return;
    }
  }

  // Basic validation to see if it looks somewhat like a CSV
  if (!textToProcess.includes(',') && !textToProcess.includes('\t') && textToProcess.split('\n').length < 2) {
    alert('有効なCSVデータではないようです。データを確認してください。');
    isLoadingFromClipboard.value = false;
    return;
  }

  // Create a File object from the text string
  const blob = new Blob([textToProcess], { type: 'text/csv;charset=utf-8;' });
  // Add a BOM so Excel and some parsers handle Japanese characters properly, though PapaParse usually handles it.
  const bom = new Uint8Array([0xEF, 0xBB, 0xBF]);
  const combinedBlob = new Blob([bom, blob]);

  const file = new File([combinedBlob], 'pasted_scores.csv', { type: 'text/csv' });
  
  emit('file-dropped', file);
  isLoadingFromClipboard.value = false;
  pastedCsvText.value = ''; // Clear text after successful load
};

</script>

<template>
  <div class="w-full max-w-2xl mx-auto flex flex-col items-center">
    
    <!-- Tab Navigation -->
    <div class="flex w-full mb-4 bg-slate-100 dark:bg-slate-800 p-1 rounded-xl transition-colors duration-200">
      <button 
        class="flex-1 py-2 text-sm font-medium rounded-lg transition-all"
        :class="activeTab === 'file' ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        @click="activeTab = 'file'"
      >
        ファイルアップロード
      </button>
      <button 
        class="flex-1 py-2 text-sm font-medium rounded-lg transition-all"
        :class="activeTab === 'text' ? 'bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm' : 'text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-200'"
        @click="activeTab = 'text'"
      >
        テキストから読み込む
      </button>
    </div>

    <!-- Active Tab Content -->
    <div class="w-full relative min-h-[300px]">
        <!-- File Upload Area -->
        <transition name="fade">
            <div 
            v-if="activeTab === 'file'"
            class="absolute inset-0 w-full h-full p-12 border-2 border-dashed rounded-2xl transition-all duration-200 flex flex-col items-center justify-center cursor-pointer bg-white dark:bg-slate-800 shadow-sm"
            :class="{
                'border-blue-500 dark:border-blue-400 bg-blue-50/50 dark:bg-blue-900/20 scale-[1.02] shadow-md': isDragging,
                'border-slate-300 dark:border-slate-600 hover:border-slate-400 dark:hover:border-slate-500 hover:bg-slate-50 dark:hover:bg-slate-700/50': !isDragging
            }"
            @dragover="handleDragOver"
            @dragleave="handleDragLeave"
            @drop="handleDrop"
            @click="triggerFileInput"
            >
            <div class="bg-blue-100 dark:bg-blue-900/50 text-blue-600 dark:text-blue-400 p-4 rounded-full mb-4 transition-colors duration-200">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M7 16a4 4 0 01-.88-7.903A5 5 0 1115.9 6L16 6a5 5 0 011 9.9M15 13l-3-3m0 0l-3 3m3-3v12" />
                </svg>
            </div>
            <h3 class="text-xl font-bold text-slate-700 dark:text-slate-200 mb-2">
                CSVファイルをドロップ
            </h3>
            <p class="text-slate-500 dark:text-slate-400 text-center text-sm mb-6">
                またはクリックしてファイルを選択してください。<br/>
                beatmania IIDX公式のスコアデータCSVに対応しています。
            </p>
            
            <button 
                class="px-6 py-2.5 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white font-medium rounded-lg transition-colors shadow-sm"
                @click.stop="triggerFileInput"
            >
                ファイルを選択
            </button>
            <input 
                type="file" 
                ref="fileInput" 
                accept=".csv,text/csv" 
                class="hidden" 
                @change="handleFileSelect"
            />
            </div>
        </transition>

        <!-- Text Paste Area -->
        <transition name="fade">
            <div 
            v-if="activeTab === 'text'"
            class="absolute inset-0 w-full h-full p-6 border border-slate-200 dark:border-slate-700 rounded-2xl bg-white dark:bg-slate-800 shadow-sm flex flex-col transition-colors duration-200"
            >
            <h3 class="text-lg font-bold text-slate-700 dark:text-slate-200 mb-2">
                CSVデータを貼り付け
            </h3>
            <p class="text-slate-500 dark:text-slate-400 text-sm mb-4">
                <a href="https://p.eagate.573.jp/game/2dx/33/djdata/score_download.html?style=SP" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 hover:underline font-medium">公式サイト</a>の「スコアデータCSVダウンロード」画面のテキストをコピーして貼り付けてください。
            </p>
            
            <textarea
                v-model="pastedCsvText"
                class="flex-1 w-full p-3 border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 rounded-lg focus:ring-2 focus:ring-blue-500 dark:focus:ring-blue-400 focus:border-blue-500 dark:focus:border-blue-400 resize-none font-mono text-sm mb-4 text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 transition-colors duration-200"
                placeholder="バージョン,タイトル,ジャンル,アーティスト..."
            ></textarea>
            
            <button 
                class="w-full py-3 bg-slate-800 dark:bg-slate-700 hover:bg-slate-900 dark:hover:bg-slate-600 text-white font-medium rounded-lg transition-all shadow-md flex items-center justify-center gap-2"
                :class="{'opacity-75 cursor-wait': isLoadingFromClipboard}"
                @click="handleTextSubmit"
                :disabled="isLoadingFromClipboard"
            >
                <svg v-if="!isLoadingFromClipboard" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
                </svg>
                <svg v-else class="animate-spin -ml-1 mr-2 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                    <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                    <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                </svg>
                {{ pastedCsvText.trim() ? '読み込む' : 'クリップボードから読み込む' }}
            </button>
            <p v-if="!pastedCsvText.trim()" class="text-xs text-slate-400 dark:text-slate-500 text-center mt-3">
                ボタンを押すと、自動的にクリップボードの内容が読み込まれます。
            </p>
            </div>
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
