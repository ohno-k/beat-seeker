<template>
  <!-- 「対象曲 → 1曲ごとにポイント化 → 上位100曲を合算」の 3 ステップ図。狭い幅では縦に積む。 -->
  <div class="flow-wrap">
  <div class="flow">
    <template v-for="(step, i) in steps" :key="i">
      <div class="flow-step bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700">
        <span class="flow-num text-white" :class="accent === 'blue' ? 'bg-blue-600 dark:bg-blue-500' : 'bg-emerald-600 dark:bg-emerald-500'">{{ i + 1 }}</span>
        <div class="min-w-0">
          <p class="text-sm font-bold text-slate-800 dark:text-slate-100">{{ step.title }}</p>
          <p class="text-xs font-bold text-slate-500 dark:text-slate-400 mt-0.5">{{ step.desc }}</p>
        </div>
      </div>
      <div v-if="i < steps.length - 1" class="flow-arrow text-slate-300 dark:text-slate-600" aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
          <path d="M12 5v14M6 13l6 6 6-6" />
        </svg>
      </div>
    </template>
  </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 Tier 解説モーダルの「合計ポイントの決まり方」3 ステップ図。
 *
 * props:
 *  - steps:  各ステップの見出しと補足
 *  - accent: 番号バッジの色（Beat-Tier=blue / Rate-Tier=emerald）
 */
defineProps<{
  steps: { title: string; desc: string }[];
  accent: 'blue' | 'emerald';
}>();
</script>

<style scoped>
/* @container は自分自身を判定できないので、幅の基準は外側のラッパーに置く */
.flow-wrap {
  container-type: inline-size;
}
.flow {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
}
.flow-step {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 8px;
  flex: 1 1 0;
  min-width: 0;
}
.flow-num {
  flex-shrink: 0;
  width: 26px;
  height: 26px;
  border-radius: 9999px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 700;
}
.flow-arrow {
  display: flex;
  justify-content: center;
}
.flow-arrow svg {
  width: 18px;
  height: 18px;
}
@container (min-width: 640px) {
  .flow {
    flex-direction: row;
    align-items: center;
  }
  .flow-arrow svg {
    transform: rotate(-90deg);
  }
}
</style>
