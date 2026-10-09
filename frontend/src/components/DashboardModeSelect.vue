<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ダッシュボードの表示モード（通常／簡易／カスタマイズ）の切替。
 *
 * サイドバーの言語切替と同じセグメント型。選んだ瞬間に useDashboardLayout が localStorage と
 * サーバー（users.dashboard_layout）へ保存し、別端末でも同じ表示になる。
 * ダッシュボードのツールバー・カスタマイズモーダル・プロフィール編集の表示設定で共用する。
 */
import { useI18n } from '../composables/useI18n';
import { useDashboardLayout, DASHBOARD_MODES } from '../composables/useDashboardLayout';

const { t } = useI18n();
const { mode, setMode } = useDashboardLayout();
</script>

<template>
  <div
    role="radiogroup"
    :aria-label="t('dashboard.layout.modeLabel')"
    class="inline-flex items-center gap-1 p-1 bg-slate-50 dark:bg-slate-900/50 rounded-md border border-slate-200 dark:border-slate-700"
  >
    <button
      v-for="m in DASHBOARD_MODES"
      :key="m"
      type="button"
      role="radio"
      :aria-checked="mode === m"
      :title="t(`dashboard.layout.mode.${m}.hint`)"
      @click="setMode(m)"
      class="px-3 py-1 text-xs font-semibold rounded transition-colors whitespace-nowrap"
      :class="mode === m
        ? 'bg-blue-700 dark:bg-blue-600 text-white'
        : 'text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 hover:bg-white dark:hover:bg-slate-700'"
    >{{ t(`dashboard.layout.mode.${m}`) }}</button>
  </div>
</template>
