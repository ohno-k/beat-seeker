<script setup lang="ts">
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
</script>

<template>
  <div class="bg-white dark:bg-slate-800 rounded-md p-6 sm:p-10 border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <h1 class="text-2xl sm:text-3xl font-bold mb-8 text-slate-900 dark:text-white border-b pb-4 border-slate-200 dark:border-slate-700">
      {{ t('privacyPolicy.title') }}
    </h1>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
        {{ t('terms.privacyTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('terms.privacyIntro') }}</p>
        <ul class="list-disc pl-6 space-y-2">
          <li><strong>{{ t('terms.privacyItem1') }}</strong></li>
          <li><strong>{{ t('terms.privacyItem2') }}</strong></li>
          <li>{{ t('terms.privacyItem3') }}</li>
          <li>{{ t('terms.privacyItem4') }}</li>
        </ul>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
        {{ t('terms.adsTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('terms.adsIntro') }}</p>
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.adsItem1') }}</li>
          <li>{{ t('terms.adsItem2') }}</li>
          <li>
            {{ t('terms.adsItem3') }}
            <a href="https://www.google.com/settings/ads" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80 ml-1">Google</a>
          </li>
          <li>
            {{ t('terms.adsItem4') }}
            <a href="https://policies.google.com/technologies/ads" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80 ml-1">Google</a>
          </li>
        </ul>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
        {{ t('terms.analyticsTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>
          {{ t('terms.analyticsIntro') }}
          <a href="https://policies.google.com/privacy" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80">Google</a>
        </p>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
        {{ t('privacyPolicy.cookieTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('privacyPolicy.cookieIntro') }}</p>
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('privacyPolicy.cookieItem1') }}</li>
          <li>{{ t('privacyPolicy.cookieItem2') }}</li>
          <li>{{ t('privacyPolicy.cookieItem3') }}</li>
        </ul>
      </div>
    </section>

    <div class="mt-12 pt-6 border-t border-slate-200 dark:border-slate-700 text-sm text-slate-500 dark:text-slate-400 text-right">
      <p>{{ t('terms.dateLabel', { date: '2026/03/05', update: '2026/04/26' }) }}</p>
    </div>
  </div>
</template>
