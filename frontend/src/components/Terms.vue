<script setup lang="ts">
/**
 * 【コンポーネントの役割】 利用規約・データ取得・サポーター・知的財産・免責・プライバシー・
 * 広告・解析・規約変更・お問い合わせをまとめて表示する静的ページ。
 *
 * 翻訳キーは `terms.*` に集約され、日付やメール/X リンクはテンプレートに直書き。
 * 同じ文言をクローラー向けに静的出力する `scripts/prerender.mjs` と内容を揃えること。
 * props/emits: なし。
 */
import { useI18n } from '../composables/useI18n';

// 翻訳関数だけ使う。
const { t } = useI18n();
</script>

<template>
  <div class="bg-white dark:bg-slate-800 rounded-md p-6 sm:p-10 border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <h1 class="text-2xl sm:text-3xl font-bold mb-8 text-slate-900 dark:text-white border-b pb-4 border-slate-200 dark:border-slate-700">
      {{ t('terms.title') }}
    </h1>

    <!-- 利用規約 -->
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
          <li>{{ t('terms.tosItemAccount') }}</li>
          <li>{{ t('terms.tosItem3') }}</li>
          <li>{{ t('terms.tosItem4') }}</li>
          <li>{{ t('terms.tosItemFakeScores') }}</li>
          <li>{{ t('terms.tosItemPosts') }}</li>
          <li>{{ t('terms.tosItem5') }}</li>
        </ul>
      </div>
    </section>

    <!-- 公式サイトからのデータ取得 -->
    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M12 2.25a.75.75 0 01.75.75v11.69l3.22-3.22a.75.75 0 111.06 1.06l-4.5 4.5a.75.75 0 01-1.06 0l-4.5-4.5a.75.75 0 111.06-1.06l3.22 3.22V3a.75.75 0 01.75-.75zm-9 13.5a.75.75 0 01.75.75v2.25a1.5 1.5 0 001.5 1.5h13.5a1.5 1.5 0 001.5-1.5V16.5a.75.75 0 011.5 0v2.25a3 3 0 01-3 3H5.25a3 3 0 01-3-3V16.5a.75.75 0 01.75-.75z" clip-rule="evenodd" />
        </svg>
        {{ t('terms.dataImportTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.dataImportItem1') }}</li>
          <li>{{ t('terms.dataImportItem2') }}</li>
        </ul>
      </div>
    </section>

    <!-- サポーター機能 -->
    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path d="M11.645 20.91l-.007-.003-.022-.012a15.247 15.247 0 01-.383-.218 25.18 25.18 0 01-4.244-3.17C4.688 15.36 2.25 12.174 2.25 8.25 2.25 5.322 4.714 3 7.688 3A5.5 5.5 0 0112 5.052 5.5 5.5 0 0116.313 3c2.973 0 5.437 2.322 5.437 5.25 0 3.925-2.438 7.111-4.739 9.256a25.175 25.175 0 01-4.244 3.17 15.247 15.247 0 01-.383.219l-.022.012-.007.004-.003.001a.752.752 0 01-.704 0l-.003-.001z" />
        </svg>
        {{ t('terms.supporterTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.supporterItem1') }}</li>
          <li>{{ t('terms.supporterItem2') }}</li>
          <li>{{ t('terms.supporterItem3') }}</li>
        </ul>
      </div>
    </section>

    <!-- 知的財産権 -->
    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M12.516 2.17a.75.75 0 00-1.032 0 11.209 11.209 0 01-7.877 3.08.75.75 0 00-.722.515A12.74 12.74 0 002.25 9.75c0 5.942 4.064 10.933 9.563 12.348a.749.749 0 00.374 0c5.499-1.415 9.563-6.406 9.563-12.348 0-1.39-.223-2.73-.635-3.985a.75.75 0 00-.722-.516l-.143.001c-2.996 0-5.717-1.17-7.734-3.08zm3.094 8.016a.75.75 0 10-1.22-.872l-3.236 4.53L9.53 12.22a.75.75 0 00-1.06 1.06l2.25 2.25a.75.75 0 001.14-.094l3.75-5.25z" clip-rule="evenodd" />
        </svg>
        {{ t('terms.ipTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.ipItem1') }}</li>
          <li>{{ t('terms.ipItem2') }}</li>
          <li>{{ t('terms.ipItem3') }}</li>
        </ul>
      </div>
    </section>

    <!-- 免責事項 -->
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
          <li>{{ t('terms.disclaimerItem5') }}</li>
          <li>{{ t('terms.disclaimerItem6') }}</li>
        </ul>
      </div>
    </section>

    <!-- プライバシーポリシー -->
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
          <li>{{ t('terms.privacyItemPublic') }}</li>
          <li>{{ t('terms.privacyItemShare') }}</li>
          <li>
            {{ t('terms.privacyItem3') }}
            <ul class="list-[circle] pl-6 mt-2 space-y-1">
              <li>{{ t('terms.privacyExt1') }}</li>
              <li>{{ t('terms.privacyExt2') }}</li>
              <li>{{ t('terms.privacyExt3') }}</li>
              <li>{{ t('terms.privacyExt4') }}</li>
              <li>{{ t('terms.privacyExt5') }}</li>
            </ul>
          </li>
          <li>{{ t('terms.privacyItem4') }}</li>
          <li>{{ t('terms.privacyItemRetention') }}</li>
          <li>{{ t('terms.privacyItemRequest') }}</li>
        </ul>
      </div>
    </section>

    <!-- 広告の配信 -->
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
        </ul>
      </div>
    </section>

    <!-- アクセス解析 -->
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

    <!-- 規約の変更・準拠法 -->
    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400 flex items-center gap-2">
        <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="currentColor" class="size-6">
          <path fill-rule="evenodd" d="M12 2.25a.75.75 0 01.75.75v.756a49.106 49.106 0 019.152 1 .75.75 0 01-.152 1.485h-1.918l2.474 10.124a.75.75 0 01-.375.84A6.723 6.723 0 0118.75 18a6.723 6.723 0 01-3.181-.795.75.75 0 01-.375-.84l2.474-10.124H12.75v13.28c1.293.076 2.534.343 3.697.776a.75.75 0 01-.262 1.453h-8.37a.75.75 0 01-.262-1.453c1.162-.433 2.404-.7 3.697-.775V6.24H6.332l2.474 10.124a.75.75 0 01-.375.84A6.723 6.723 0 015.25 18a6.723 6.723 0 01-3.181-.795.75.75 0 01-.375-.84L4.168 6.241H2.25a.75.75 0 01-.152-1.485 49.105 49.105 0 019.152-1V3a.75.75 0 01.75-.75zm4.878 13.543l1.872-7.662 1.872 7.662h-3.744zm-9.756 0L5.25 8.131l-1.872 7.662h3.744z" clip-rule="evenodd" />
        </svg>
        {{ t('terms.changesTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.changesItem1') }}</li>
          <li>{{ t('terms.changesItem2') }}</li>
          <li>{{ t('terms.changesItem3') }}</li>
        </ul>
      </div>
    </section>

    <!-- お問い合わせ -->
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
      <p>{{ t('terms.dateLabel', { date: '2026/03/05', update: '2026/09/12' }) }}</p>
    </div>
  </div>
</template>
