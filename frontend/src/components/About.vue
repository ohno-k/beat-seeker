<script setup lang="ts">
/**
 * 【コンポーネントの役割】 「このサイトについて」ページ。
 *
 * 機能:
 *  - Hero/3 ステップ/機能一覧/FAQ を i18n で翻訳しながら表示する静的ページ
 *  - 翻訳キーは `about.*` に集約されている
 *
 * props/emits: なし（純粋な表示コンポーネント）。
 */
import { useI18n } from '../composables/useI18n';

// 翻訳関数を取り出す。現在の言語は i18n 側でリアクティブに切り替わる。
const { t } = useI18n();
</script>

<template>
  <div class="space-y-16 pb-20 animate-fade-in text-slate-900 dark:text-white">
    <!-- ヒーロー（キャッチコピー）セクション -->
    <section class="relative overflow-hidden bg-white dark:bg-slate-800 rounded-md p-8 md:p-16 border border-slate-200 dark:border-slate-700 transition-colors duration-200">
      <div class="relative z-10 max-w-3xl">
        <span class="inline-flex items-center px-4 py-1.5 rounded text-sm font-bold bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400 mb-6 border border-blue-100 dark:border-blue-800/50">
          <span class="relative flex h-2 w-2 mr-2">
            <span class="relative inline-flex rounded-full h-2 w-2 bg-blue-500"></span>
          </span>
          {{ t('about.heroBadge') }}
        </span>
        <h2 class="text-4xl md:text-5xl font-bold mb-6 leading-tight">
          {{ t('about.heroTitle') }}
        </h2>
        <p class="text-lg md:text-xl text-slate-600 dark:text-slate-300 leading-relaxed font-medium mb-4">
          {{ t('about.heroP1') }}
        </p>
        <p class="text-lg text-slate-500 dark:text-slate-400 leading-relaxed">
          {{ t('about.heroP2') }}
        </p>
      </div>
    </section>

    <!-- 使い方を 3 ステップで示すセクション -->
    <section>
      <h3 class="text-2xl font-bold mb-10 flex items-center gap-3">
        <div class="w-8 h-1.5 bg-blue-600 dark:bg-blue-500 rounded-full"></div>
        {{ t('about.stepsTitle') }}
      </h3>
      
      <div class="grid grid-cols-1 md:grid-cols-3 gap-8">
        <div class="group bg-white dark:bg-slate-800 p-8 rounded-md border border-slate-200 dark:border-slate-700 hover:border-blue-400 dark:hover:border-blue-500 transition-all duration-300">
          <div class="w-14 h-14 bg-slate-100 dark:bg-slate-700/50 rounded-md flex items-center justify-center text-2xl font-bold text-slate-400 dark:text-slate-500 mb-6 group-hover:bg-blue-600 group-hover:text-white transition-colors">1</div>
          <h4 class="text-xl font-bold mb-3">{{ t('about.step1Title') }}</h4>
          <p class="text-slate-500 dark:text-slate-400 font-medium leading-relaxed">
            {{ t('about.step1Desc') }}
          </p>
        </div>

        <div class="group bg-white dark:bg-slate-800 p-8 rounded-md border border-slate-200 dark:border-slate-700 hover:border-blue-400 dark:hover:border-blue-500 transition-all duration-300">
          <div class="w-14 h-14 bg-slate-100 dark:bg-slate-700/50 rounded-md flex items-center justify-center text-2xl font-bold text-slate-400 dark:text-slate-500 mb-6 group-hover:bg-blue-600 group-hover:text-white transition-colors">2</div>
          <h4 class="text-xl font-bold mb-3">{{ t('about.step2Title') }}</h4>
          <p class="text-slate-500 dark:text-slate-400 font-medium leading-relaxed">
            {{ t('about.step2Desc') }}
          </p>
        </div>

        <div class="group bg-white dark:bg-slate-800 p-8 rounded-md border border-slate-200 dark:border-slate-700 hover:border-blue-400 dark:hover:border-blue-500 transition-all duration-300">
          <div class="w-14 h-14 bg-slate-100 dark:bg-slate-700/50 rounded-md flex items-center justify-center text-2xl font-bold text-slate-400 dark:text-slate-500 mb-6 group-hover:bg-blue-600 group-hover:text-white transition-colors">3</div>
          <h4 class="text-xl font-bold mb-3">{{ t('about.step3Title') }}</h4>
          <p class="text-slate-500 dark:text-slate-400 font-medium leading-relaxed">
            {{ t('about.step3Desc') }}
          </p>
        </div>
      </div>
    </section>

    <!-- 機能一覧（ダッシュボード／BeatTier／難易度表 等） -->
    <section>
      <h3 class="text-2xl font-bold mb-10 flex items-center gap-3">
        <div class="w-8 h-1.5 bg-indigo-600 dark:bg-indigo-500 rounded-full"></div>
        {{ t('about.featuresTitle') }}
      </h3>
      
      <div class="grid grid-cols-1 md:grid-cols-2 gap-8">
        <!-- Dashboard -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-blue-100 dark:bg-blue-900/30 rounded-lg text-blue-600 dark:text-blue-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z" /></svg>
            </span>
            {{ t('about.featDashboardTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.featDashboardDesc') }}
          </p>
        </div>

        <!-- Beat-Tier -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-indigo-100 dark:bg-indigo-900/30 rounded-lg text-indigo-600 dark:text-indigo-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>
            </span>
            {{ t('about.featBeatTierTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.featBeatTierDesc') }}
          </p>
        </div>

        <!-- Table -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-emerald-100 dark:bg-emerald-900/30 rounded-lg text-emerald-600 dark:text-emerald-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 10h18M3 14h18m-9-4v8m-7 0h14a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" /></svg>
            </span>
            {{ t('about.featTableTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.featTableDesc') }}
          </p>
        </div>

        <!-- Report -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-amber-100 dark:bg-amber-900/30 rounded-lg text-amber-600 dark:text-amber-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" /></svg>
            </span>
            {{ t('about.featReportTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.featReportDesc') }}
          </p>
        </div>

        <!-- History -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-purple-100 dark:bg-purple-900/30 rounded-lg text-purple-600 dark:text-purple-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" /></svg>
            </span>
            {{ t('about.featHistoryTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.featHistoryDesc') }}
          </p>
        </div>

        <!-- Cloud -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-rose-100 dark:bg-rose-900/30 rounded-lg text-rose-600 dark:text-rose-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 15a4 4 0 004 4h9a5 5 0 10-.1-9.999 5.002 5.002 0 10-9.78 2.096A4.001 4.001 0 003 15z" /></svg>
            </span>
            {{ t('about.featCloudTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.featCloudDesc') }}
          </p>
        </div>

        <!-- Dark Mode -->
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="flex items-center gap-3 text-lg font-bold mb-4">
            <span class="p-2 bg-slate-100 dark:bg-slate-700/50 rounded-lg text-slate-600 dark:text-slate-400">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z" /></svg>
            </span>
            {{ t('about.darkModeTitle') }}
          </h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.darkModeDesc') }}
          </p>
        </div>
      </div>
    </section>

    <!-- FAQ（よくある質問） -->
    <section>
      <h3 class="text-2xl font-bold mb-10 flex items-center gap-3">
        <div class="w-8 h-1.5 bg-emerald-600 dark:bg-emerald-500 rounded-full"></div>
        {{ t('about.faqTitle') }}
      </h3>
      
      <div class="space-y-4">
        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="text-lg font-bold mb-3">{{ t('about.faqQ1') }}</h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.faqA1') }}
          </p>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="text-lg font-bold mb-3">{{ t('about.faqQ2') }}</h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.faqA2') }}
          </p>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="text-lg font-bold mb-3">{{ t('about.faqQ3') }}</h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.faqA3') }}
          </p>
        </div>

        <div class="bg-white dark:bg-slate-800 rounded-md p-8 border border-slate-200 dark:border-slate-700">
          <h4 class="text-lg font-bold mb-3">{{ t('about.faqQ4') }}</h4>
          <p class="text-slate-600 dark:text-slate-300 font-medium leading-relaxed">
            {{ t('about.faqA4') }}
          </p>
        </div>
      </div>
    </section>
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
