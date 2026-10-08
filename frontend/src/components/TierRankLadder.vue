<template>
  <!--
    ランクの階段表。1 行 = 1 ランク（上から Legend → Novice → Beginner）。
    行の右側にサブティア 1〜5 の閾値を色の濃さで段々に並べ、名前の下の細いバーで
    「全体の目盛りのどこを占めるか」を示す（上位ほど帯が狭い / 等比で等幅、が一目で分かる）。
  -->
  <div class="ladder">
    <!-- Legend（頂点） -->
    <div v-if="legend" class="ladder-row ladder-row--legend">
      <div class="ladder-icon"><RankIcon rank-name="Legend" size="md" lite /></div>
      <div class="ladder-head">
        <p class="text-base font-bold text-amber-500">Legend</p>
        <p class="text-xs font-bold text-slate-500 dark:text-slate-400 tabular-nums">{{ legend.minPoints.toLocaleString() }} pt〜</p>
      </div>
      <div class="ladder-steps">
        <div class="legend-bar">
          <span class="text-xs font-bold text-amber-700 dark:text-amber-300 tabular-nums">{{ legend.minPoints.toLocaleString() }} pt</span>
        </div>
      </div>
    </div>

    <!-- 中間ランク（Mythic → Novice） -->
    <div v-for="name in rankNames" :key="name" class="ladder-row">
      <div class="ladder-icon"><RankIcon :rank-name="name" :tier="3" size="sm" lite /></div>
      <div class="ladder-head">
        <p class="text-sm font-bold" :class="colorOf(name)">{{ name }}</p>
        <p class="text-[11px] font-bold text-slate-400 dark:text-slate-500 tabular-nums">
          {{ fmtFull(bandStart(name)) }} – {{ fmtFull(bandEnd(name)) }}
        </p>
        <!-- 全体目盛り上の位置 -->
        <div class="band-track bg-slate-100 dark:bg-slate-800">
          <div class="band-fill" :class="colorOf(name)" :style="bandStyle(name)"></div>
        </div>
      </div>
      <div class="ladder-steps" :class="colorOf(name)">
        <div v-for="tier in 5" :key="tier" class="step">
          <div class="step-bar" :style="{ opacity: 0.18 + tier * 0.14, height: (8 + tier * 3) + 'px' }"></div>
          <p class="text-[10px] font-bold text-slate-700 dark:text-slate-200 leading-none mt-1">{{ tier }}</p>
          <p class="text-[10px] font-bold text-slate-400 dark:text-slate-500 tabular-nums leading-none mt-0.5">{{ format(getTier(name, tier)?.minPoints ?? 0) }}</p>
        </div>
      </div>
    </div>

    <!-- Beginner（土台） -->
    <div class="ladder-row ladder-row--base">
      <div class="ladder-icon"><RankIcon rank-name="Beginner" size="sm" lite /></div>
      <div class="ladder-head">
        <p class="text-sm font-bold text-slate-400 dark:text-slate-500">Beginner</p>
        <p class="text-[11px] font-bold text-slate-400 dark:text-slate-500 tabular-nums">0 pt –</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 【コンポーネントの役割】 Beat-Tier / Rate-Tier 解説モーダル共通のランク階段表。
 *
 * props:
 *  - groupedRanks: ランク名 → サブティア昇順の RankInfo 配列（getGroupedRanks 系の戻り値）
 *  - rankNames:    中間ランク名（上位から）
 *  - scale:        位置バーの目盛り。'linear'（Beat-Tier）/ 'log'（Rate-Tier、2 倍刻み）
 *  - format:       サブティア閾値の短い表記
 */
import { computed } from 'vue';
import RankIcon from './RankIcon.vue';
import type { RankInfo } from '../utils/beatTier';

const props = withDefaults(defineProps<{
  groupedRanks: Record<string, RankInfo[]>;
  rankNames: string[];
  scale?: 'linear' | 'log';
  format?: (v: number) => string;
}>(), {
  scale: 'linear',
  format: (v: number) => Math.round(v).toLocaleString(),
});

const legend = computed(() => props.groupedRanks['Legend']?.[0]);

const getTier = (name: string, tier: number) => props.groupedRanks[name]?.find(r => r.tier === tier);
const colorOf = (name: string) => props.groupedRanks[name]?.[0]?.color.replace('font-bold', '') ?? '';

/** ランク帯の下限 = サブティア 1 の閾値。 */
const bandStart = (name: string) => getTier(name, 1)?.minPoints ?? 0;
/** ランク帯の上限 = 1 つ上のランクの下限（Mythic は Legend）。 */
const bandEnd = (name: string) => {
  const i = props.rankNames.indexOf(name);
  return i <= 0 ? (legend.value?.minPoints ?? 0) : bandStart(props.rankNames[i - 1]);
};

const fmtFull = (v: number) => Math.round(v).toLocaleString();

/** 目盛りの両端 = 最下位ランクの下限〜Legend。 */
const axisMin = computed(() => bandStart(props.rankNames[props.rankNames.length - 1]));
const axisMax = computed(() => legend.value?.minPoints ?? 1);
const toPos = (v: number) => {
  if (props.scale === 'log') {
    return (Math.log(v) - Math.log(axisMin.value)) / (Math.log(axisMax.value) - Math.log(axisMin.value));
  }
  return (v - axisMin.value) / (axisMax.value - axisMin.value);
};
const bandStyle = (name: string) => {
  const l = toPos(bandStart(name));
  const r = toPos(bandEnd(name));
  return { left: `${l * 100}%`, width: `${Math.max(r - l, 0.01) * 100}%` };
};
</script>

<style scoped>
.ladder {
  container-type: inline-size;
  display: grid;
  gap: 6px;
}
.ladder-row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  grid-template-areas:
    'icon head'
    'steps steps';
  align-items: center;
  column-gap: 12px;
  row-gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
}
.ladder-row:nth-child(even) {
  background: rgb(148 163 184 / 0.08);
}
.ladder-row--base {
  opacity: 0.75;
}
.ladder-icon {
  grid-area: icon;
  display: flex;
  justify-content: center;
}
.ladder-head {
  grid-area: head;
  min-width: 0;
}
.ladder-steps {
  grid-area: steps;
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 4px;
  align-items: end;
}
@container (min-width: 520px) {
  .ladder-row {
    grid-template-columns: 40px 170px minmax(0, 1fr);
    grid-template-areas: 'icon head steps';
  }
}
.step {
  display: flex;
  flex-direction: column;
  align-items: center;
}
.step-bar {
  width: 100%;
  border-radius: 3px;
  background-color: currentColor;
}
.band-track {
  position: relative;
  height: 4px;
  border-radius: 9999px;
  margin-top: 6px;
  overflow: hidden;
}
.band-fill {
  position: absolute;
  top: 0;
  bottom: 0;
  border-radius: 9999px;
  background-color: currentColor;
}
.legend-bar {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 32px;
  border-radius: 6px;
  background: linear-gradient(90deg, rgb(251 191 36 / 0.25), rgb(245 158 11 / 0.45));
}
</style>
