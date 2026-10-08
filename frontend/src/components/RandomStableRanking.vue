<script setup lang="ts">
/**
 * RandomStableRanking.vue
 *
 * 【コンポーネントの役割】 譜面分析ページの「配置の影響が少ない譜面」タブ。
 * 各譜面の 5,040 通りの並びを配置評価（utils/randomEval.ts）の減点で並べ、上位 10%（504 位）と 90%（4,536 位）の
 * 減点の差が小さい譜面から並べる。差が小さい = RANDOM でどの並びを引いても押しやすさがあまり変わらない譜面。
 *
 * 【データ】 当たり配置ランキングと同じ frontend/public/data/random-ranking/summary.json の s10・s90
 * （scripts/build-random-ranking.mts で事前計算）。2P は 1P の左右反転なので減点の分布は 1P と同じで、サイドの切り替えは無い。
 */
import { ref, computed, watch, onMounted } from 'vue';

/** open = 譜面を譜面分析で開く（譜面再生は正規の並びで始める） */
const emit = defineEmits<{ (e: 'open', textage: string, pattern: string): void }>();

interface ChartRow { t: string; d: string; l: number; n: number; x: string; s10?: number; s90?: number; }
interface Summary { generatedAt: string; charts: ChartRow[]; }

const BASE = `${import.meta.env.BASE_URL}data/random-ranking/`;
const summary = ref<Summary | null>(null);
const loadError = ref('');
onMounted(async () => {
  try {
    const res = await fetch(`${BASE}summary.json`);
    if (!res.ok) throw new Error(String(res.status));
    summary.value = await res.json();
  } catch {
    loadError.value = 'ランキングのデータを読み込めませんでした';
  }
});

type LevelFilter = 'all' | 12 | 11 | 'low';
const level = ref<LevelFilter>('all');
const query = ref('');
const PAGE = 100;
const limit = ref(PAGE);
watch([level, query], () => { limit.value = PAGE; });

interface ViewRow { row: ChartRow; s10: number; s90: number; diff: number; }
const rows = computed((): ViewRow[] => {
  const s = summary.value;
  if (!s) return [];
  const q = query.value.trim().toLowerCase();
  const out: ViewRow[] = [];
  for (const r of s.charts) {
    if (r.s10 == null || r.s90 == null) continue;
    if (level.value === 12 && r.l < 12) continue;
    if (level.value === 11 && r.l !== 11) continue;
    if (level.value === 'low' && r.l > 10) continue;
    if (q && !r.t.toLowerCase().includes(q)) continue;
    out.push({ row: r, s10: r.s10, s90: r.s90, diff: Math.round((r.s90 - r.s10) * 10) / 10 });
  }
  out.sort((a, b) => a.diff - b.diff || b.row.l - a.row.l || a.row.t.localeCompare(b.row.t, 'ja'));
  return out;
});
const shown = computed(() => rows.value.slice(0, limit.value));
/** 差のバーの長さの基準（絞り込み後の最大の差） */
const maxDiff = computed(() => Math.max(1, ...rows.value.map(v => v.diff)));
/** データが古く s10・s90 を持たない（作り直し前の summary.json） */
const noData = computed(() => !!summary.value && !summary.value.charts.some(r => r.s10 != null));

const fmt = (v: number) => (Math.round(v * 10) / 10).toFixed(1);
const diffLabel = (d: string) => (d === '10' ? 'L' : 'A');
</script>

<template>
  <section class="ranking text-xs text-slate-600 dark:text-slate-300">
    <p class="text-slate-500 dark:text-slate-400 leading-relaxed">
      RANDOM の 5,040 通りを配置評価の減点で並べたとき、上位 10%（504 位）と 90%（4,536 位）の並びの減点の差が小さい譜面から並べます。
      差が小さいほど、どの並びを引いても押しやすさが変わりにくい譜面です。
    </p>

    <div class="controls mt-3">
      <div class="control">
        <span class="control-label">レベル</span>
        <div class="seg">
          <button type="button" :class="{ on: level === 'all' }" @click="level = 'all'">すべて</button>
          <button type="button" :class="{ on: level === 12 }" @click="level = 12">☆12</button>
          <button type="button" :class="{ on: level === 11 }" @click="level = 11">☆11</button>
          <button type="button" :class="{ on: level === 'low' }" @click="level = 'low'">☆10以下</button>
        </div>
        <input v-model="query" type="search" placeholder="曲名で絞り込み" aria-label="曲名で絞り込み" autocomplete="off"
          class="search rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900 px-2 py-1.5" />
      </div>
    </div>

    <p v-if="loadError" class="mt-3 text-red-600 dark:text-red-400">{{ loadError }}</p>
    <p v-else-if="!summary" class="mt-3 text-slate-400">読み込み中…</p>
    <p v-else-if="noData" class="mt-3 text-slate-400">このランキングのデータはまだありません</p>
    <template v-else>
      <div class="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-slate-500 dark:text-slate-400">
        <span class="tabular-nums">{{ rows.length }} 譜面</span>
        <span v-if="query.trim() && rows.length === 0" class="text-red-500 dark:text-red-400">
          曲名「{{ query.trim() }}」に一致する譜面がありません
          <button type="button" class="ml-1 underline font-semibold" @click="query = ''">絞り込みを解除</button>
        </span>
      </div>

      <div class="head mt-2 text-[10px] font-semibold text-slate-400 dark:text-slate-500" aria-hidden="true">
        <span></span><span></span><span></span>
        <span class="num">10%</span><span class="num">90%</span><span class="num">差</span>
      </div>
      <ol class="rank-list">
        <li v-for="(v, n) in shown" :key="v.row.x">
          <span class="pos tabular-nums">{{ n + 1 }}</span>
          <span class="lv tabular-nums" :class="v.row.d === '10' ? 'leg' : 'ano'">☆{{ v.row.l }} {{ diffLabel(v.row.d) }}</span>
          <span class="title-cell">
            <button type="button" class="title" :title="`${v.row.t} を譜面分析で開く`" @click="emit('open', v.row.x, '1234567')">{{ v.row.t }}</button>
            <span class="bar"><span class="bar-fill" :style="{ width: `${(v.diff / maxDiff) * 100}%` }"></span></span>
          </span>
          <span class="num s tabular-nums" title="上位 10%（504 位）の並びの減点">{{ fmt(v.s10) }}</span>
          <span class="num s tabular-nums" title="上位 90%（4,536 位）の並びの減点">{{ fmt(v.s90) }}</span>
          <span class="num diff tabular-nums">{{ fmt(v.diff) }}</span>
        </li>
      </ol>
      <button v-if="rows.length > limit" type="button" class="more mt-2 text-blue-600 dark:text-blue-400 font-semibold"
        @click="limit += PAGE">さらに表示（残り {{ rows.length - limit }}）</button>

      <p class="mt-4 text-[11px] text-slate-400 dark:text-slate-500 leading-relaxed">
        {{ summary.generatedAt }} 時点の評価基準で計算した {{ rows.length }} 譜面（textage の譜面データが手元にある ANOTHER / LEGGENDARIA）。
        減点は配置評価の総合（減点の形の該当ノーツ数の合計、難所ほど重い）で、1P と 2P で同じ値です。
        曲名を押すと譜面分析タブでその譜面を開きます。
      </p>
    </template>
  </section>
</template>

<style scoped>
.ranking {
  border: 1px solid rgb(226 232 240);
  border-radius: 0.375rem;
  background: white;
  padding: 0.9rem;
}
.dark .ranking { border-color: rgb(51 65 85); background: rgb(30 41 59); }

.controls { display: flex; flex-direction: column; gap: 0.5rem; }
.control { display: flex; flex-wrap: wrap; align-items: center; gap: 0.4rem 0.6rem; }
.control-label { flex: none; width: 3.5rem; font-weight: 600; color: rgb(100 116 139); }
.search { flex: 1 1 10rem; min-width: 0; max-width: 16rem; }

.seg { display: inline-flex; border-radius: 0.375rem; overflow: hidden; border: 1px solid rgb(203 213 225); }
.seg button { padding: 0.35rem 0.6rem; font-weight: 600; white-space: nowrap; color: rgb(71 85 105); background: white; }
.seg button + button { border-left: 1px solid rgb(203 213 225); }
.seg button.on { color: white; background: rgb(37 99 235); }
.dark .seg { border-color: rgb(71 85 105); }
.dark .seg button { color: rgb(203 213 225); background: rgb(51 65 85); }
.dark .seg button + button { border-left-color: rgb(71 85 105); }
.dark .seg button.on { color: white; background: rgb(37 99 235); }

.head,
.rank-list li {
  display: grid;
  grid-template-columns: 2.2rem 3.2rem minmax(0, 1fr) 3.4rem 3.4rem 3.4rem;
  align-items: center;
  column-gap: 0.5rem;
  padding: 0.3rem 0.5rem;
}
.head { padding-top: 0; padding-bottom: 0.15rem; }
.rank-list { display: flex; flex-direction: column; gap: 2px; }
.rank-list li { border-radius: 0.3rem; background: rgb(248 250 252); }
.dark .rank-list li { background: rgb(15 23 42 / 0.5); }
.pos { text-align: right; font-weight: 700; color: rgb(148 163 184); }
.lv { font-weight: 700; white-space: nowrap; }
.lv.ano { color: rgb(220 38 38); }
.lv.leg { color: rgb(147 51 234); }
.dark .lv.ano { color: rgb(248 113 113); }
.dark .lv.leg { color: rgb(192 132 252); }
.title-cell { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  font-weight: 600;
  color: rgb(30 41 59);
}
.title:hover { color: rgb(37 99 235); text-decoration: underline; }
.dark .title { color: rgb(226 232 240); }
.dark .title:hover { color: rgb(96 165 250); }
.bar { display: block; height: 3px; border-radius: 9999px; background: rgb(226 232 240); overflow: hidden; }
.dark .bar { background: rgb(51 65 85); }
.bar-fill { display: block; height: 100%; border-radius: 9999px; background: rgb(16 185 129); }
.num { text-align: right; white-space: nowrap; }
.num.s { color: rgb(100 116 139); }
.dark .num.s { color: rgb(148 163 184); }
.num.diff { font-weight: 700; color: rgb(5 150 105); }
.dark .num.diff { color: rgb(52 211 153); }

/* スマホ幅: 10%・90% の列は畳み、差だけ出す */
@media (max-width: 479px) {
  .head,
  .rank-list li { grid-template-columns: 1.8rem 2.8rem minmax(0, 1fr) 3rem; }
  .num.s,
  .head .num:nth-child(4),
  .head .num:nth-child(5) { display: none; }
  .control-label { width: auto; }
}
</style>
