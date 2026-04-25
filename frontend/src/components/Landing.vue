<script setup lang="ts">
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();

defineEmits<{
  (e: 'navigate', tab: string): void
  (e: 'open-login'): void
}>();
</script>

<template>
  <div class="space-y-16 pb-20 animate-fade-in text-slate-900 dark:text-white">
    <section class="relative overflow-hidden bg-gradient-to-br from-blue-600 via-indigo-600 to-violet-600 rounded-3xl p-8 md:p-16 text-white shadow-xl">
      <div class="relative z-10 max-w-3xl">
        <span class="inline-flex items-center px-4 py-1.5 rounded-full text-sm font-bold bg-white/15 backdrop-blur mb-6 border border-white/20">
          <span class="relative flex h-2 w-2 mr-2">
            <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-white opacity-75"></span>
            <span class="relative inline-flex rounded-full h-2 w-2 bg-white"></span>
          </span>
          {{ t('landing.heroBadge') }}
        </span>
        <h1 class="text-4xl md:text-6xl font-black mb-6 leading-tight">
          {{ t('landing.heroTitle') }}
        </h1>
        <p class="text-lg md:text-xl text-white/90 leading-relaxed mb-8">
          {{ t('landing.heroDesc') }}
        </p>
        <div class="flex flex-wrap gap-3">
          <button @click="$emit('navigate', 'dashboard')" class="px-6 py-3 bg-white text-blue-600 font-bold rounded-xl shadow-lg hover:-translate-y-0.5 transition-all">
            {{ t('landing.ctaTry') }}
          </button>
          <button @click="$emit('open-login')" class="px-6 py-3 bg-white/15 hover:bg-white/25 backdrop-blur text-white font-bold rounded-xl border border-white/20 transition-all">
            {{ t('landing.ctaLogin') }}
          </button>
        </div>
      </div>
      <div class="absolute top-0 right-0 -mr-16 -mt-16 w-96 h-96 bg-white/10 rounded-full blur-3xl"></div>
      <div class="absolute bottom-0 left-0 -ml-16 -mb-16 w-96 h-96 bg-white/5 rounded-full blur-3xl"></div>
    </section>

    <section>
      <h2 class="text-2xl md:text-3xl font-black mb-10 flex items-center gap-3">
        <div class="w-8 h-1.5 bg-blue-600 dark:bg-blue-500 rounded-full"></div>
        {{ t('landing.featuresTitle') }}
      </h2>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl border border-slate-200 dark:border-slate-700">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat1Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat1Desc') }}</p>
        </div>
        <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl border border-slate-200 dark:border-slate-700">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat2Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat2Desc') }}</p>
        </div>
        <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl border border-slate-200 dark:border-slate-700">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat3Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat3Desc') }}</p>
        </div>
        <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl border border-slate-200 dark:border-slate-700">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat4Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat4Desc') }}</p>
        </div>
        <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl border border-slate-200 dark:border-slate-700">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat5Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat5Desc') }}</p>
        </div>
        <div class="bg-white dark:bg-slate-800 p-6 rounded-2xl border border-slate-200 dark:border-slate-700">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat6Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat6Desc') }}</p>
        </div>
      </div>
    </section>

    <section>
      <h2 class="text-2xl md:text-3xl font-black mb-10 flex items-center gap-3">
        <div class="w-8 h-1.5 bg-emerald-600 dark:bg-emerald-500 rounded-full"></div>
        {{ t('landing.guideTitle') }}
      </h2>
      <p class="text-slate-600 dark:text-slate-300 mb-6 leading-relaxed">
        {{ t('landing.guideDesc') }}
      </p>
      <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
        <a href="/guide/csv-import" class="block bg-white dark:bg-slate-800 p-5 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-400 transition-colors">
          <h3 class="font-bold mb-1">{{ t('landing.guide1Title') }}</h3>
          <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('landing.guide1Desc') }}</p>
        </a>
        <a href="/guide/scratch" class="block bg-white dark:bg-slate-800 p-5 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-400 transition-colors">
          <h3 class="font-bold mb-1">{{ t('landing.guide2Title') }}</h3>
          <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('landing.guide2Desc') }}</p>
        </a>
        <a href="/guide/aaa" class="block bg-white dark:bg-slate-800 p-5 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-400 transition-colors">
          <h3 class="font-bold mb-1">{{ t('landing.guide3Title') }}</h3>
          <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('landing.guide3Desc') }}</p>
        </a>
        <a href="/guide/level-up" class="block bg-white dark:bg-slate-800 p-5 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-400 transition-colors">
          <h3 class="font-bold mb-1">{{ t('landing.guide4Title') }}</h3>
          <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('landing.guide4Desc') }}</p>
        </a>
        <a href="/guide/clear-types" class="block bg-white dark:bg-slate-800 p-5 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-400 transition-colors md:col-span-2">
          <h3 class="font-bold mb-1">{{ t('landing.guide5Title') }}</h3>
          <p class="text-sm text-slate-500 dark:text-slate-400">{{ t('landing.guide5Desc') }}</p>
        </a>
      </div>
    </section>

    <section class="bg-slate-50 dark:bg-slate-800/50 rounded-2xl p-8 border border-slate-200 dark:border-slate-700">
      <h2 class="text-xl md:text-2xl font-black mb-4">{{ t('landing.aboutTitle') }}</h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed mb-3">
        {{ t('landing.aboutP1') }}
      </p>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        {{ t('landing.aboutP2') }}
      </p>
      <p class="text-xs text-slate-500 dark:text-slate-400 mt-6">
        {{ t('landing.copyrightDisclaimer') }}
      </p>
    </section>
  </div>
</template>
