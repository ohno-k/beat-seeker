<script setup lang="ts">
/**
 * 【コンポーネントの役割】 利用規約・免責・プライバシー・広告・解析・お問い合わせを
 * まとめて表示する静的ページ。
 *
 * 翻訳キーは `terms.*` に集約され、日付やメール/X リンクはテンプレートに直書き。
 * props/emits: なし。
 */
import { useI18n } from '../composables/useI18n';

// 翻訳関数だけ使う。
const { t } = useI18n();
</script>

<template>
  <div class="bg-white dark:bg-slate-800 rounded-2xl p-6 sm:p-10 shadow-sm border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <h1 class="text-2xl sm:text-3xl font-bold mb-8 text-slate-900 dark:text-white border-b pb-4 border-slate-200 dark:border-slate-700">
      {{ t('terms.title') }}
    </h1>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12zm11.378-3.917c-.89-.777-2.366-.777-3.255 0a.75.75 0 01-.988-1.129c1.454-1.272 3.776-1.272 5.23 0 1.513 1.324 1.513 3.518 0 4.842a3.75 3.75 0 01-.837.552c-.676.328-1.028.774-1.028 1.152v.75a.75.75 0 01-1.5 0v-.75c0-1.279 1.06-2.107 1.875-2.502.182-.088.351-.199.503-.331.89-.777.89-2.038 0-2.815zM12 18a.75.75 0 100-1.5.75.75 0 000 1.5z" clip-rule="evenodd" />
        </svg>
        {{ t('terms.tosTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('terms.tosIntro') }}</p>
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.tosItem1') }}</li>
          <li><strong>{{ t('terms.tosItem2') }}</strong></li>
          <li>{{ t('terms.tosItem3') }}</li>
          <li>{{ t('terms.tosItem4') }}</li>
          <li>{{ t('terms.tosItem5') }}</li>
        </ul>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M9.401 3.003c1.155-2 4.043-2 5.197 0l7.355 12.748c1.154 2-.29 4.5-2.599 4.5H4.645c-2.309 0-3.752-2.5-2.598-4.5L9.4 3.003zM12 8.25a.75.75 0 01.75.75v3.75a.75.75 0 01-1.5 0V9a.75.75 0 01.75-.75zm0 8.25a.75.75 0 100-1.5.75.75 0 000 1.5z" clip-rule="evenodd" />
        </svg>
        {{ t('terms.disclaimerTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.disclaimerItem1') }}</li>
          <li>{{ t('terms.disclaimerItem2') }}</li>
          <li>{{ t('terms.disclaimerItem3') }}</li>
          <li>{{ t('terms.disclaimerItem4') }}</li>
        </ul>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M12 1.5a5.25 5.25 0 00-5.25 5.25v3a3 3 0 00-3 3v6.75a3 3 0 003 3h10.5a3 3 0 003-3v-6.75a3 3 0 00-3-3v-3c0-2.9-2.35-5.25-5.25-5.25zm3.75 8.25v-3a3.75 3.75 0 10-7.5 0v3h7.5z" clip-rule="evenodd" />
        </svg>
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
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path d="M15.75 10.5V6a3.75 3.75 0 10-7.5 0v4.5m11.356-1.993l1.263 12c.07.665-.45 1.243-1.119 1.243H4.25a1.125 1.125 0 01-1.12-1.243l1.264-12A1.125 1.125 0 015.513 7.5h12.974c.576 0 1.059.435 1.119 1.007zM8.625 10.5a.375.375 0 11-.75 0 .375.375 0 01.75 0zm7.5 0a.375.375 0 11-.75 0 .375.375 0 01.75 0z" />
        </svg>
        {{ t('terms.adsTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('terms.adsIntro') }}</p>
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.adsItem1') }}</li>
          <li>{{ t('terms.adsItem2') }}</li>
          <li>{{ t('terms.adsItem3') }}<a href="https://www.google.com/settings/ads" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80 ml-1">Google</a></li>
          <li>{{ t('terms.adsItem4') }}<a href="https://policies.google.com/technologies/ads" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80 ml-1">Google</a></li>
        </ul>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M2.25 12c0-5.385 4.365-9.75 9.75-9.75s9.75 4.365 9.75 9.75-4.365 9.75-9.75 9.75S2.25 17.385 2.25 12zm8.706-1.442c1.146-.573 2.437.463 2.126 1.706l-.709 2.836.042-.02a.75.75 0 01.67 1.34l-.04.022c-1.147.573-2.438-.463-2.127-1.706l.71-2.836-.042.02a.75.75 0 11-.671-1.34l.041-.022zM12 9a.75.75 0 100-1.5.75.75 0 000 1.5z" clip-rule="evenodd" />
        </svg>
        {{ t('terms.analyticsTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('terms.analyticsIntro') }} <a href="https://policies.google.com/privacy" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80">Google</a></p>
      </div>
    </section>

    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path d="M1.5 8.67v8.58a3 3 0 003 3h15a3 3 0 003-3V8.67l-8.928 5.493a3 3 0 01-3.144 0L1.5 8.67z" />
          <path d="M22.5 6.908V6.75a3 3 0 00-3-3h-15a3 3 0 00-3 3v.158l9.714 5.978a1.5 1.5 0 001.572 0L22.5 6.908z" />
        </svg>
        {{ t('terms.contactTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <p>{{ t('terms.contactIntro') }}</p>
        <ul class="list-disc pl-6 space-y-2">
          <li><strong>{{ t('terms.contactEmail') }}</strong> <a href="mailto:beat.seeker.iidx@gmail.com" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80">beat.seeker.iidx@gmail.com</a></li>
          <li><strong>{{ t('terms.contactX') }}</strong> <a href="https://x.com/beat_seeker_" target="_blank" rel="noopener noreferrer" class="text-blue-600 dark:text-blue-400 underline hover:opacity-80">@beat_seeker_</a> {{ t('terms.contactXNote') }}</li>
        </ul>
        <p class="text-slate-500 dark:text-slate-400 text-xs">{{ t('terms.contactFooter') }}</p>
      </div>
    </section>

    <div class="mt-12 pt-6 border-t border-slate-200 dark:border-slate-700 text-sm text-slate-500 dark:text-slate-400 text-right">
      <p>{{ t('terms.dateLabel', { date: '2026/03/05', update: '2026/03/15' }) }}</p>
    </div>
  </div>
</template>
