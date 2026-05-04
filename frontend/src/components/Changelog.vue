<script setup lang="ts">
/**
 * 【コンポーネントの役割】 アプリ更新履歴と「難易度表改訂履歴」の 2 タブを表示するページ。
 *
 * 機能:
 *  - `activeTab` でタブ切替（アプリ更新 / 難易度改訂）
 *  - 難易度改訂は `data/difficulty_revisions.json` を読み込み、追加曲・変更曲を一覧化
 *  - 日付表記と「第N版」のラベルを言語ごとに整形
 *
 * props/emits: なし。
 */
import { ref } from 'vue';
import { useI18n } from '../composables/useI18n';
import difficultyRevisions from '../data/difficulty_revisions.json';

// 翻訳関数と現在言語。`currentLang` は日付の整形ロジックに使用。
const { t, currentLang } = useI18n();
/** 現在表示中のタブ。'changelog'（更新履歴）か 'difficulty'（難易度改訂）。 */
const activeTab = ref<'changelog' | 'difficulty'>('changelog');

/** 難易度改訂 1 回分を表す型（JSON のスキーマ）。 */
interface RevisionEntry {
  version: number;
  label: string;
  appVersion: string;
  date: string;
  added: { title: string; rank: string }[];
  changed: { title: string; from: string; to: string }[];
}

// JSON を型付きで扱う。`as` キャストは静的データの明示的な型付け用途。
const revisions = difficultyRevisions as RevisionEntry[];

/**
 * 【関数の役割】 "YYYY-MM" 形式の日付を言語に応じた表現へ整形する。
 * @param dateStr 例: "2026-03"
 * @returns 日本語: "2026年3月" / 韓国語: "2026년 3월" / 英語: "March 2026"
 */
function formatDate(dateStr: string): string {
  const [year, month] = dateStr.split('-');
  const m = parseInt(month, 10);
  if (currentLang.value === 'ja') return `${year}年${m}月`;
  if (currentLang.value === 'ko') return `${year}년 ${m}월`;
  const months = ['January','February','March','April','May','June','July','August','September','October','November','December'];
  return `${months[m - 1]} ${year}`;
}

/**
 * 【関数の役割】 難易度改訂の版数をラベル化する。
 * @param version 版数（1, 2, 3 ...）
 * @returns 日本語: "第N版" / 韓国語: "제N판" / 英語: "Nst/nd/rd/th Edition"
 */
function editionLabel(version: number): string {
  if (currentLang.value === 'ja') return `第${version}版`;
  if (currentLang.value === 'ko') return `제${version}판`;
  const suffixes: Record<number, string> = { 1: 'st', 2: 'nd', 3: 'rd' };
  return `${version}${suffixes[version] || 'th'} Edition`;
}
</script>

<template>
  <div class="space-y-8 animate-fade-in pb-16">
    <!-- ヘッダー部（タイトル + タブ切替ボタン） -->
    <div class="bg-white dark:bg-slate-800 p-8 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <h2 class="text-3xl font-black text-slate-800 dark:text-slate-100 flex items-center gap-3">
        <svg xmlns="http://www.w3.org/2000/svg" class="h-8 w-8 text-blue-600 dark:text-blue-400" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm1-12a1 1 0 10-2 0v4a1 1 0 00.293.707l2.828 2.829a1 1 0 101.415-1.415L11 9.586V6z" clip-rule="evenodd" />
        </svg>
        {{ t('changelog.title') }}
      </h2>
      <p class="text-slate-500 dark:text-slate-400 mt-2 font-medium tracking-wide">
        {{ t('changelog.desc') }}
      </p>

      <!-- タブ切替ボタン（システム更新 / 難易度改訂） -->
      <div class="flex flex-wrap mt-6 gap-2 border-t border-slate-100 dark:border-slate-700 pt-6">
        <button
          @click="activeTab = 'changelog'"
          class="px-5 py-2.5 text-sm font-bold rounded-lg transition-all"
          :class="activeTab === 'changelog' ? 'bg-blue-600 text-white shadow-md' : 'bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-600'"
        >
          {{ t('changelog.tabSystem') }}
        </button>
        <button
          @click="activeTab = 'difficulty'"
          class="px-5 py-2.5 text-sm font-bold rounded-lg transition-all"
          :class="activeTab === 'difficulty' ? 'bg-indigo-600 text-white shadow-md' : 'bg-slate-100 dark:bg-slate-700/50 text-slate-600 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-600'"
        >
          {{ t('changelog.tabDifficulty') }}
        </button>
      </div>
    </div>

    <div v-if="activeTab === 'changelog'" class="space-y-8 animate-in slide-in-from-bottom-4 duration-300">
      <!-- Update Entry: v1.7.0 -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.7.0</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v170Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.may2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v170f1') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.6.0 -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.6.0</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v160Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.april2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v160f1') }}</li>
              <li>{{ t('changelog.v160f2') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.5.0 -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.5.0</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v150Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.april2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v150f1') }}</li>
              <li>{{ t('changelog.v150f2') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.4.0 -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.4.0</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v140Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.april2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v140f1') }}</li>
              <li>{{ t('changelog.v140f2') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.3.1 (Localization Support) -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.3.1</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v131Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.march2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v131f1') }}</li>
              <li>{{ t('changelog.v131f2') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.3.0 (Difficulty Revision) -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.3.0</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v130Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.march2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v130f1') }}</li>
              <li>{{ t('changelog.v130f2') }}</li>
              <li>{{ t('changelog.v130f3') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.2.0 (Notification Tabs & Vote UI) -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.2.0</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v120Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.march2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.newFeatures') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li><span class="font-bold text-slate-900 dark:text-slate-100">{{ t('changelog.v120f1_title') }}:</span> {{ t('changelog.v120f1_desc') }}</li>
              <li><span class="font-bold text-slate-900 dark:text-slate-100">{{ t('changelog.v120f2_title') }}:</span> {{ t('changelog.v120f2_desc') }}</li>
            </ul>
          </div>
          <div>
            <h4 class="text-sm font-black text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1 1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" /></svg>
              {{ t('changelog.bugFixes') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v120b1') }}</li>
              <li>{{ t('changelog.v120b2') }}</li>
            </ul>
          </div>
        </div>
      </div>

      <!-- Update Entry: v1.1.1 (Folder Rank System Redesign) -->
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">Ver 1.1.1</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.v111Title') }}</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ t('changelog.march2026') }}</span>
        </div>

        <div class="p-8 space-y-6">
          <div>
            <h4 class="text-sm font-black text-blue-600 dark:text-blue-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.improvements') }}
            </h4>
            <ul class="list-disc list-inside text-slate-700 dark:text-slate-300 space-y-2 ml-2 leading-relaxed font-medium">
              <li>{{ t('changelog.v111i1') }}</li>
              <li>{{ t('changelog.v111i2') }}</li>
              <li>{{ t('changelog.v111i3') }}</li>
            </ul>
          </div>
        </div>
      </div>
      
      <!-- Older entries can be added here with similar structure -->
    </div>

    <!-- 難易度改訂履歴タブ（JSON からループ描画） -->
    <div v-else-if="activeTab === 'difficulty'" class="space-y-8 animate-in slide-in-from-bottom-4 duration-300">

      <div v-for="rev in revisions" :key="rev.version" class="bg-white dark:bg-slate-800 rounded-2xl shadow-sm border border-slate-200 dark:border-slate-700 overflow-hidden transition-colors duration-200">
        <div class="px-8 py-5 border-b border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/80 flex items-center justify-between">
          <div class="flex items-center gap-3">
            <span class="bg-indigo-600 text-white text-xs font-bold px-3 py-1 rounded-full uppercase tracking-wider">{{ editionLabel(rev.version) }}</span>
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-200">{{ t('changelog.difficultyRevision') }} ({{ rev.appVersion }})</h3>
          </div>
          <span class="text-sm font-bold text-slate-500 dark:text-slate-400">{{ formatDate(rev.date) }}</span>
        </div>

        <div class="p-8 space-y-6">
          <!-- 追加曲ブロック（その版で新規に難易度が付いた曲） -->
          <div v-if="rev.added.length > 0">
            <h4 class="text-sm font-black text-indigo-600 dark:text-indigo-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M10 18a8 8 0 100-16 8 8 0 000 16zm3.707-9.293a1 1 0 00-1.414-1.414L9 10.586 7.707 9.293a1 1 0 00-1.414 1.414l2 2a1 1 0 001.414 0l4-4z" clip-rule="evenodd" /></svg>
              {{ t('changelog.addedSongs') }}
            </h4>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-slate-700 dark:text-slate-300 ml-2 font-medium">
              <div v-for="(song, i) in rev.added" :key="i" class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1">
                <span>{{ song.title }}</span>
                <span class="font-bold text-blue-600 dark:text-blue-400">{{ song.rank }}</span>
              </div>
            </div>
          </div>

          <!-- 変更曲ブロック（from → to で難易度が変動した曲） -->
          <div v-if="rev.changed.length > 0">
            <h4 class="text-sm font-black text-amber-500 dark:text-amber-400 uppercase tracking-widest mb-3 flex items-center gap-2">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" viewBox="0 0 20 20" fill="currentColor"><path fill-rule="evenodd" d="M8.257 3.099c.765-1.36 2.722-1.36 3.486 0l5.58 9.92c.75 1.334-.213 2.98-1.742 2.98H4.42c-1.53 0-2.493-1.646-1.743-2.98l5.58-9.92zM11 13a1 1 0 11-2 0 1 1 0 012 0zm-1-8a1 1 0 00-1-1v3a1 1 0 002 0V6a1 1 0 00-1-1z" clip-rule="evenodd" /></svg>
              {{ t('changelog.changedSongs') }}
            </h4>
            <div class="grid grid-cols-1 md:grid-cols-2 gap-x-6 gap-y-2 text-slate-700 dark:text-slate-300 ml-2 font-medium">
              <div v-for="(song, i) in rev.changed" :key="i" class="flex justify-between items-center border-b border-slate-100 dark:border-slate-700/50 pb-1">
                <span>{{ song.title }}</span>
                <div class="flex items-center gap-2">
                  <span class="text-slate-400 line-through">{{ song.from }}</span>
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-3 w-3 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M14 5l7 7m0 0l-7 7m7-7H3" /></svg>
                  <span class="font-bold text-amber-600 dark:text-amber-500">{{ song.to }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

    </div>
  </div>
</template>

<style scoped>
.animate-fade-in {
  animation: fadeIn 0.4s ease-out forwards;
}

@keyframes fadeIn {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}
</style>
