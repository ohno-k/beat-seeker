<script setup lang="ts">
/**
 * 【コンポーネントの役割】 プライバシーポリシー単独ページ。
 *
 * 本文の翻訳キーは `Terms.vue` と共有 (`terms.privacy*` / `terms.ads*` / `terms.analytics*`)。
 * Cookie・ローカルストレージの節だけ本ページ固有 (`privacyPolicy.*`)。
 * クローラー向けの静的 HTML は `scripts/prerender.mjs` にあるので、文言を変えたら揃えること。
 */
import { useI18n } from '../composables/useI18n';

const { t } = useI18n();
</script>

<template>
  <div class="bg-white dark:bg-slate-800 rounded-md p-6 sm:p-10 border border-slate-200 dark:border-slate-700 animate-fade-in text-slate-800 dark:text-slate-200">
    <h1 class="text-2xl sm:text-3xl font-bold mb-8 text-slate-900 dark:text-white border-b pb-4 border-slate-200 dark:border-slate-700">
      {{ t('privacyPolicy.title') }}
    </h1>

    <!-- 取得情報・利用目的・公開範囲・第三者提供・削除 -->
    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
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
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
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

    <!-- Cookie・ローカルストレージ -->
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
        <p class="text-slate-500 dark:text-slate-400 text-xs">{{ t('privacyPolicy.cookieNote') }}</p>
      </div>
    </section>

    <!-- 規約の変更・準拠法 (利用規約と共通) -->
    <section class="mb-10">
      <h2 class="text-xl font-bold mb-4 text-blue-600 dark:text-blue-400">
        {{ t('terms.changesTitle') }}
      </h2>
      <div class="space-y-4 text-sm sm:text-base leading-relaxed text-slate-700 dark:text-slate-300">
        <ul class="list-disc pl-6 space-y-2">
          <li>{{ t('terms.changesItem1') }}</li>
          <li>{{ t('terms.changesItem3') }}</li>
        </ul>
      </div>
    </section>

    <div class="mt-12 pt-6 border-t border-slate-200 dark:border-slate-700 text-sm text-slate-500 dark:text-slate-400 text-right">
      <p>{{ t('terms.dateLabel', { date: '2026/03/05', update: '2026/09/12' }) }}</p>
    </div>
  </div>
</template>
