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
    <v-card tag="section">
      <v-card-text class="pa-6 pa-md-12">
      <div class="max-w-3xl">
        <div class="flex items-center gap-3 mb-5">
          <!-- 鍵盤7つ+スクラッチのモチーフ -->
          <span class="inline-flex items-center gap-[3px]" aria-hidden="true">
            <span class="w-2.5 h-2.5 rounded-full bg-red-500 mr-1"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-white border border-slate-300 dark:border-slate-500"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-blue-600"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-white border border-slate-300 dark:border-slate-500"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-blue-600"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-white border border-slate-300 dark:border-slate-500"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-blue-600"></span>
            <span class="w-1.5 h-3 rounded-[1px] bg-white border border-slate-300 dark:border-slate-500"></span>
          </span>
          <span class="text-xs font-semibold text-slate-500 dark:text-slate-400">{{ t('landing.heroBadge') }}</span>
        </div>
        <h1 class="text-3xl md:text-5xl font-bold mb-5 leading-snug tracking-tight">
          {{ t('landing.heroTitle') }}
        </h1>
        <p class="text-base md:text-lg text-slate-600 dark:text-slate-300 leading-relaxed mb-8">
          {{ t('landing.heroDesc') }}
        </p>
        <div class="flex flex-wrap items-center gap-4">
          <v-btn color="primary" size="large" @click="$emit('navigate', 'dashboard')">
            {{ t('landing.ctaTry') }}
          </v-btn>
          <v-btn variant="text" color="primary" size="large" @click="$emit('open-login')">
            {{ t('landing.ctaLogin') }}
          </v-btn>
        </div>
      </div>
      </v-card-text>
    </v-card>

    <section>
      <h2 class="text-xl md:text-2xl font-bold mb-8 pb-3 border-b border-slate-200 dark:border-slate-700">
        {{ t('landing.featuresTitle') }}
      </h2>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <v-card>
          <v-card-title class="whitespace-normal">{{ t('landing.feat1Title') }}</v-card-title>
          <v-card-text>{{ t('landing.feat1Desc') }}</v-card-text>
        </v-card>
        <v-card>
          <v-card-title class="whitespace-normal">{{ t('landing.feat2Title') }}</v-card-title>
          <v-card-text>{{ t('landing.feat2Desc') }}</v-card-text>
        </v-card>
        <v-card>
          <v-card-title class="whitespace-normal">{{ t('landing.feat3Title') }}</v-card-title>
          <v-card-text>{{ t('landing.feat3Desc') }}</v-card-text>
        </v-card>
        <v-card>
          <v-card-title class="whitespace-normal">{{ t('landing.feat4Title') }}</v-card-title>
          <v-card-text>{{ t('landing.feat4Desc') }}</v-card-text>
        </v-card>
        <v-card>
          <v-card-title class="whitespace-normal">{{ t('landing.feat5Title') }}</v-card-title>
          <v-card-text>{{ t('landing.feat5Desc') }}</v-card-text>
        </v-card>
        <v-card>
          <v-card-title class="whitespace-normal">{{ t('landing.feat6Title') }}</v-card-title>
          <v-card-text>{{ t('landing.feat6Desc') }}</v-card-text>
        </v-card>
      </div>
    </section>

    <v-card tag="section" variant="tonal">
      <v-card-title class="whitespace-normal">{{ t('landing.aboutTitle') }}</v-card-title>
      <v-card-text>
      <p class="leading-relaxed mb-3">
        {{ t('landing.aboutP1') }}
      </p>
      <p class="leading-relaxed">
        {{ t('landing.aboutP2') }}
      </p>
      <p class="text-xs opacity-70 mt-6">
        {{ t('landing.copyrightDisclaimer') }}
      </p>
      </v-card-text>
    </v-card>
  </div>
</template>
