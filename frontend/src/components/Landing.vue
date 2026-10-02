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
    <v-card tag="section" class="p-8 md:p-14">
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
          <v-btn color="primary" size="large" class="px-6" @click="$emit('navigate', 'dashboard')">
            {{ t('landing.ctaTry') }}
          </v-btn>
          <v-btn variant="text" color="primary" class="text-sm font-semibold" @click="$emit('open-login')">
            {{ t('landing.ctaLogin') }}
          </v-btn>
        </div>
      </div>
    </v-card>

    <section>
      <h2 class="text-xl md:text-2xl font-bold mb-8 pb-3 border-b border-slate-200 dark:border-slate-700">
        {{ t('landing.featuresTitle') }}
      </h2>
      <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
        <v-card class="p-6">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat1Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat1Desc') }}</p>
        </v-card>
        <v-card class="p-6">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat2Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat2Desc') }}</p>
        </v-card>
        <v-card class="p-6">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat3Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat3Desc') }}</p>
        </v-card>
        <v-card class="p-6">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat4Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat4Desc') }}</p>
        </v-card>
        <v-card class="p-6">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat5Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat5Desc') }}</p>
        </v-card>
        <v-card class="p-6">
          <h3 class="text-lg font-bold mb-2">{{ t('landing.feat6Title') }}</h3>
          <p class="text-slate-600 dark:text-slate-300 leading-relaxed">{{ t('landing.feat6Desc') }}</p>
        </v-card>
      </div>
    </section>

    <v-card tag="section" class="bg-slate-50 dark:bg-slate-800/50 p-8">
      <h2 class="text-lg md:text-xl font-bold mb-4">{{ t('landing.aboutTitle') }}</h2>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed mb-3">
        {{ t('landing.aboutP1') }}
      </p>
      <p class="text-slate-600 dark:text-slate-300 leading-relaxed">
        {{ t('landing.aboutP2') }}
      </p>
      <p class="text-xs text-slate-500 dark:text-slate-400 mt-6">
        {{ t('landing.copyrightDisclaimer') }}
      </p>
    </v-card>
  </div>
</template>
