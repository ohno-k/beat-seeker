<script setup lang="ts">
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
</script>

<template>
  <div class="bg-white dark:bg-slate-800 rounded-md p-6 sm:p-10 border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <h1 class="text-2xl sm:text-3xl font-bold mb-8 text-slate-900 dark:text-white border-b pb-4 border-slate-200 dark:border-slate-700">
      {{ t('contactPage.title') }}
    </h1>

    <section class="mb-10">
      <p class="text-base leading-relaxed text-slate-700 dark:text-slate-300 mb-6">
        {{ t('contactPage.intro') }}
      </p>

      <h2 class="text-lg font-bold mb-3 text-blue-600 dark:text-blue-400">
        {{ t('contactPage.channelsTitle') }}
      </h2>
      <ul class="space-y-3 text-sm sm:text-base text-slate-700 dark:text-slate-300">
        <li class="flex items-start gap-3 bg-slate-50 dark:bg-slate-900/40 rounded-lg p-4 border border-slate-200 dark:border-slate-700">
          <span class="font-bold text-slate-900 dark:text-white shrink-0">{{ t('contactPage.emailLabel') }}</span>
          <a href="mailto:beat.seeker.iidx@gmail.com" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80 break-all">beat.seeker.iidx@gmail.com</a>
        </li>
        <li class="flex items-start gap-3 bg-slate-50 dark:bg-slate-900/40 rounded-lg p-4 border border-slate-200 dark:border-slate-700">
          <span class="font-bold text-slate-900 dark:text-white shrink-0">{{ t('contactPage.xLabel') }}</span>
          <a href="https://x.com/beat_seeker_" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80">@beat_seeker_</a>
        </li>
      </ul>

      <h2 class="text-lg font-bold mt-10 mb-3 text-blue-600 dark:text-blue-400">
        {{ t('contactPage.aboutTitle') }}
      </h2>
      <ul class="list-disc pl-6 space-y-2 text-sm sm:text-base text-slate-700 dark:text-slate-300 leading-relaxed">
        <li>{{ t('contactPage.about1') }}</li>
        <li>{{ t('contactPage.about2') }}</li>
        <li>{{ t('contactPage.about3') }}</li>
      </ul>

      <h2 class="text-lg font-bold mt-10 mb-3 text-blue-600 dark:text-blue-400">
        {{ t('contactPage.operatorTitle') }}
      </h2>
      <ul class="list-disc pl-6 space-y-2 text-sm sm:text-base text-slate-700 dark:text-slate-300 leading-relaxed">
        <li>{{ t('contactPage.operatorName') }}</li>
        <li>{{ t('contactPage.operatorRegion') }}</li>
        <li>{{ t('contactPage.operatorSince') }}</li>
        <li>{{ t('contactPage.operatorPurpose') }}</li>
      </ul>

      <p class="text-xs text-slate-500 dark:text-slate-400 mt-10">
        {{ t('contactPage.copyrightDisclaimer') }}
      </p>
    </section>
  </div>
</template>
