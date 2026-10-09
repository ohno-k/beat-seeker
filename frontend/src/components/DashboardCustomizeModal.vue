<script setup lang="ts">
/**
 * 【コンポーネントの役割】 ダッシュボードのカスタマイズモーダル。
 *
 * ScoreDashboard のツールバー（歯車ボタン）から開く。自分のダッシュボードでだけ使う。
 *  - 表示モード（通常／簡易／カスタマイズ）の切替
 *  - カスタマイズ表示で出すウィジェットの表示/非表示と並び順（▲▼）。触ると表示モードもカスタマイズに切り替わる
 *  - 初期設定に戻す
 *
 * 変更は保存ボタン無しで即反映する（RATE-TIER 表示トグルと同じ流儀）。背後のダッシュボードが
 * その場で並び替わるので、見ながら調整できる。状態は useDashboardLayout が持ち、
 * localStorage とサーバー（users.dashboard_layout）へ同期される。
 */
import { useI18n } from '../composables/useI18n';
import { useModalEscape } from '../composables/useModalEscape';
import { useDashboardLayout, PINNED_WIDGET, type DashboardWidgetId } from '../composables/useDashboardLayout';
import DashboardModeSelect from './DashboardModeSelect.vue';

const emit = defineEmits<{ (e: 'close'): void }>();

const { t } = useI18n();
const { layout, mode, isDefault, setWidgetHidden, moveWidget, resetLayout } = useDashboardLayout();

useModalEscape(() => true, () => emit('close'));

const isHidden = (id: DashboardWidgetId) => layout.value.hidden.includes(id);
</script>

<template>
  <Teleport to="body">
    <div class="fixed inset-0 z-[110] flex items-center justify-center p-4 bg-slate-900/50 animate-fade-in" @click.self="emit('close')">
      <div
        class="w-full max-w-md max-h-[90vh] overflow-y-auto bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700"
        role="dialog"
        aria-modal="true"
        :aria-label="t('dashboard.layout.title')"
      >
        <!-- ヘッダー -->
        <div class="px-5 py-4 border-b border-slate-100 dark:border-slate-700 flex items-start justify-between gap-3 sticky top-0 bg-white dark:bg-slate-800 z-10">
          <div class="min-w-0">
            <h3 class="text-lg font-bold text-slate-800 dark:text-slate-100">{{ t('dashboard.layout.title') }}</h3>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('dashboard.layout.subtitle') }}</p>
          </div>
          <button type="button" @click="emit('close')" :aria-label="t('common.close')" class="p-1.5 -mr-1.5 text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-full transition-colors shrink-0">
            <svg class="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
              <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <div class="px-5 py-4 space-y-5">
          <!-- 表示モード（言語設定と同じ 3 択。選んだ瞬間に保存される） -->
          <div>
            <div class="flex flex-wrap items-center justify-between gap-2">
              <p class="text-sm font-semibold text-slate-700 dark:text-slate-300">{{ t('dashboard.layout.modeLabel') }}</p>
              <DashboardModeSelect />
            </div>
            <p class="text-xs text-slate-400 dark:text-slate-500 mt-1">{{ t(`dashboard.layout.mode.${mode}.hint`) }}</p>
          </div>

          <!-- カスタマイズ表示の項目と並び順（カスタマイズ以外のモード中は薄く見せる。触るとカスタマイズに切り替わる） -->
          <div>
            <div class="flex items-center justify-between gap-3 mb-1">
              <h4 class="text-sm font-bold text-slate-400">{{ t('dashboard.layout.widgets') }}</h4>
              <button
                type="button"
                @click="resetLayout"
                :disabled="isDefault"
                class="text-xs font-bold text-blue-700 dark:text-blue-400 hover:underline disabled:text-slate-300 dark:disabled:text-slate-600 disabled:no-underline disabled:cursor-not-allowed"
              >{{ t('dashboard.layout.reset') }}</button>
            </div>
            <p v-if="mode !== 'custom'" class="text-xs text-amber-600 dark:text-amber-400 mb-2">{{ t('dashboard.layout.editNote') }}</p>

            <ul class="divide-y divide-slate-100 dark:divide-slate-700 transition-opacity" :class="{ 'opacity-60': mode !== 'custom' }">
              <li v-for="(id, i) in layout.order" :key="id" class="flex items-center gap-3 py-2.5">
                <label class="flex items-center gap-3 flex-1 min-w-0" :class="id === PINNED_WIDGET ? 'cursor-default' : 'cursor-pointer'">
                  <input
                    type="checkbox"
                    :checked="!isHidden(id)"
                    :disabled="id === PINNED_WIDGET"
                    @change="setWidgetHidden(id, !($event.target as HTMLInputElement).checked)"
                    class="w-4 h-4 shrink-0 rounded border-slate-300 dark:border-slate-600 text-blue-600 focus:ring-blue-500 disabled:opacity-60"
                  >
                  <div class="min-w-0">
                    <p class="text-sm font-semibold text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
                      <span class="truncate">{{ t(`dashboard.widget.${id}`) }}</span>
                      <span v-if="id === PINNED_WIDGET" class="badge shrink-0">{{ t('dashboard.layout.pinned') }}</span>
                    </p>
                    <p class="text-xs text-slate-400 dark:text-slate-500">{{ t(`dashboard.widget.${id}.desc`) }}</p>
                  </div>
                </label>
                <!-- 並び替え。先頭固定の項目は動かせず、2 番目の項目も先頭へは上がれない -->
                <div v-if="id !== PINNED_WIDGET" class="flex items-center gap-1 shrink-0">
                  <button
                    type="button"
                    @click="moveWidget(id, -1)"
                    :disabled="i <= 1"
                    :aria-label="t('dashboard.layout.moveUp')"
                    :title="t('dashboard.layout.moveUp')"
                    class="p-1.5 rounded-md text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-30 disabled:hover:bg-transparent disabled:cursor-not-allowed transition-colors"
                  >
                    <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M5 15l7-7 7 7" /></svg>
                  </button>
                  <button
                    type="button"
                    @click="moveWidget(id, 1)"
                    :disabled="i >= layout.order.length - 1"
                    :aria-label="t('dashboard.layout.moveDown')"
                    :title="t('dashboard.layout.moveDown')"
                    class="p-1.5 rounded-md text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 disabled:opacity-30 disabled:hover:bg-transparent disabled:cursor-not-allowed transition-colors"
                  >
                    <svg class="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5"><path stroke-linecap="round" stroke-linejoin="round" d="M19 9l-7 7-7-7" /></svg>
                  </button>
                </div>
              </li>
            </ul>
          </div>
        </div>

        <div class="px-5 py-4 border-t border-slate-100 dark:border-slate-700">
          <button type="button" @click="emit('close')" class="btn-primary w-full">{{ t('common.close') }}</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
