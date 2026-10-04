<script setup lang="ts">
/**
 * RandomPanel.vue
 *
 * 【コンポーネントの役割】 譜面再生の下に出す RANDOM の補助。
 * - 判別: 曲頭の何打鍵目で元の白鍵（1・3・5・7）のレーンが分かるか、並び全体が分かるか（utils/randomEval.ts の identifyRandom）。
 *   曲頭の打鍵を「正規」と「今の並び」で並べて、ゲームで光ったレーンからどう読むかを見せる
 * - 配置評価: 5,040 通りの並びを、押しにくい形に当たるノーツの数（難所ほど重い）で順位付けする（evaluateRandom）。並びを選ぶとその RANDOM で再生できる。
 *   「評価表を見る」で、1 位・今の並び・正規・MIRROR・R-RANDOM 最良の形ごとの数と評価基準をモーダル（RandomEvalModal）で出す
 */
import { ref, computed, watch, onMounted } from 'vue';
import type { ChartTimeline } from '../utils/chartPlayback';
import { evaluateRandom, identifyRandom, WHITE_KEYS, type RandomEvaluation, type RandomCandidate } from '../utils/randomEval';
import PatternChips from './PatternChips.vue';
import RandomEvalModal from './RandomEvalModal.vue';

const props = defineProps<{
  timeline: ChartTimeline;
  side: 1 | 2;
  /** 今の並び（S-RANDOM のときは空） */
  currentPattern: string;
}>();
const emit = defineEmits<{ (e: 'apply', pattern: string): void }>();

// ── 判別 ─────────────────────────────────────────────────
const ident = computed(() => identifyRandom(props.timeline));

/** 1 打鍵分の 7 マス（左のレーンから）。filled = その打鍵で光る、white = そこに来ている元の鍵盤が白鍵 */
function cells(keys: number[], pattern: string) {
  const out: { filled: boolean; white: boolean }[] = [];
  for (let lane = 1; lane <= 7; lane++) {
    const orig = Number(pattern[lane - 1]);
    out.push({ filled: keys.includes(orig), white: WHITE_KEYS.has(orig) });
  }
  // 2P は皿が右なので、表示もレーン順のまま（鍵盤の並びは左から 1〜7 レーン）
  return out;
}

// ── 配置評価 ─────────────────────────────────────────────
const evaluation = ref<RandomEvaluation | null>(null);
const computing = ref(false);
const showWorst = ref(false);

function evaluate() {
  computing.value = true;
  // 計算（0.1〜0.3 秒）の前に「計算中」を描かせる
  setTimeout(() => {
    evaluation.value = evaluateRandom(props.timeline, props.side);
    computing.value = false;
  }, 30);
}
// 表示したらすぐ計算する。サイドを変えたら計算し直す
onMounted(evaluate);
watch(() => props.side, evaluate);

const current = computed(() => (props.currentPattern ? evaluation.value?.byPattern.get(props.currentPattern) ?? null : null));
/** 同じ順位の並びを 1 組にまとめる（代表 = 辞書順で最初の並び、others = 同点のほかの並びの数） */
function groupByRank(list: RandomCandidate[]) {
  const groups: { head: RandomCandidate; others: number }[] = [];
  for (const c of list) {
    const last = groups[groups.length - 1];
    if (last && last.head.rank === c.rank) last.others++;
    else groups.push({ head: c, others: 0 });
  }
  return groups;
}
const top = computed(() => groupByRank(evaluation.value?.candidates ?? []).slice(0, 10));
const worst = computed(() => groupByRank(evaluation.value?.candidates ?? []).slice(-5).reverse());
const offRank = computed(() => evaluation.value?.byPattern.get('1234567') ?? null);
const mirRank = computed(() => evaluation.value?.byPattern.get('7654321') ?? null);
/** R-RANDOM で出る 12 通りのうち一番良いもの */
const bestRRandom = computed(() => {
  const ev = evaluation.value;
  if (!ev) return null;
  const rots: string[] = [];
  for (const base of ['1234567', '7654321']) for (let s = 1; s <= 6; s++) rots.push(base.slice(s) + base.slice(0, s));
  return rots.map(p => ev.byPattern.get(p)!).sort((a, b) => a.rank - b.rank)[0];
});

/** 正規・MIRROR・R-RANDOM の最良（評価の前は空） */
const baseRows = computed(() => {
  const rows: { label: string; cand: RandomCandidate }[] = [];
  if (offRank.value) rows.push({ label: '正規', cand: offRank.value });
  if (mirRank.value) rows.push({ label: 'MIRROR', cand: mirRank.value });
  if (bestRRandom.value) rows.push({ label: 'R-RAN 最良', cand: bestRRandom.value });
  return rows;
});
/** 上位 10%（5,040 通り中 504 位以内）。正規・MIRROR・R-RANDOM がここに入るなら、RANDOM を使わなくても当たりに近い */
const TOP_RANK = 504;
const isTop = (rank: number) => rank <= TOP_RANK;
const topLabels = computed(() => baseRows.value.filter(b => isTop(b.cand.rank)).map(b => b.label));
const percent = (rank: number) => Math.max(1, Math.round((rank / 5040) * 100));

// ── 評価表（モーダル） ──
const showEvalTable = ref(false);
/** 評価表に並べる並び（1 位・今の並び・正規・MIRROR・R-RANDOM 最良） */
const evalColumns = computed(() => {
  const ev = evaluation.value;
  if (!ev) return [];
  const cols: { label: string; cand: RandomCandidate }[] = [];
  // 同じ並びが重なったら 1 列にまとめて見出しを並べる（今の並びが正規なら「今の並び・正規」）
  const add = (label: string, c: RandomCandidate | null | undefined) => {
    if (!c) return;
    const same = cols.find(x => x.cand.pattern === c.pattern);
    if (same) same.label += `・${label}`;
    else cols.push({ label, cand: c });
  };
  add('1 位', ev.candidates[0]);
  add('今の並び', current.value);
  add('正規', offRank.value);
  add('MIRROR', mirRank.value);
  add('R-RAN 最良', bestRRandom.value);
  return cols;
});

</script>

<template>
  <div class="random-panel mt-4 flex flex-col gap-3 text-xs text-slate-600 dark:text-slate-300">
    <!-- ── 判別 ── -->
    <details class="panel">
      <summary class="panel-title">RANDOM の判別（白鍵がどこに来たか）</summary>
      <div class="panel-body">
        <p v-if="ident.whiteAt !== null" class="leading-relaxed">
          元の白鍵（1・3・5・7）のレーンは、曲頭から
          <b class="text-slate-800 dark:text-white">{{ ident.whiteAt + 1 }} 打鍵目</b>（小節 {{ ident.opening[ident.whiteAt].measure }}）で分かります。
          <template v-if="ident.fullAt !== null">
            並び全体は <b class="text-slate-800 dark:text-white">{{ ident.fullAt + 1 }} 打鍵目</b>（小節 {{ ident.opening[ident.fullAt].measure }}）で決まります。
          </template>
          <template v-else>並び全体は曲頭の打鍵だけでは決まりません（いつも一緒に出る鍵盤があります）。</template>
        </p>
        <p v-else class="leading-relaxed">この譜面は曲頭の打鍵だけでは白鍵のレーンを見分けられません（白鍵と黒鍵がいつも一緒に出ます）。</p>
        <p class="mt-1 text-[11px] text-slate-400 dark:text-slate-500 leading-relaxed">
          ゲームで最初の数打鍵が光ったレーンを「正規」の形と見比べると、元の白鍵がどのレーンに来たか分かります。
          白いマスが元の白鍵、青いマスが元の黒鍵です。<span v-if="!currentPattern">S-RANDOM ではノーツごとに変わるので判別できません。</span>
        </p>

        <div class="open-scroll mt-2">
        <table class="open-table">
          <thead>
            <tr>
              <th class="text-left">打鍵</th>
              <th>正規</th>
              <th v-if="currentPattern">今の並び<br><PatternChips :pattern="currentPattern" small /></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(ev, i) in ident.opening" :key="i"
              :class="{ mark: i === ident.whiteAt || i === ident.fullAt, after: ident.whiteAt !== null && i > Math.max(ident.whiteAt, ident.fullAt ?? -1) }">
              <td class="whitespace-nowrap tabular-nums">
                #{{ i + 1 }}<span class="text-slate-400 dark:text-slate-500"> 小節{{ ev.measure }}</span>
                <span v-if="i === ident.whiteAt || i === ident.fullAt" class="tags">
                  <span v-if="i === ident.whiteAt" class="tag">白鍵が分かる</span>
                  <span v-if="i === ident.fullAt" class="tag tag-full">並びが決まる</span>
                </span>
              </td>
              <td><span class="lanes"><span v-for="(c, j) in cells(ev.keys, '1234567')" :key="j" class="cell"
                :class="[c.white ? 'w' : 'b', { on: c.filled }]"></span></span></td>
              <td v-if="currentPattern"><span class="lanes"><span v-for="(c, j) in cells(ev.keys, currentPattern)" :key="j" class="cell"
                :class="[c.white ? 'w' : 'b', { on: c.filled }]"></span></span></td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>
    </details>

    <!-- ── 配置評価 ── -->
    <details class="panel" open>
      <summary class="panel-title">RANDOM の配置評価（当たり乱探し）</summary>
      <div class="panel-body">
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-slate-500 dark:text-slate-400">
            皿側の手が {{ side === 1 ? '1〜3' : '5〜7' }}、もう一方の手が {{ side === 1 ? '4〜7' : '1〜4' }} レーンを押す前提で評価します。
          </span>
          <span v-if="computing" class="text-slate-400">5,040 通りを計算中…</span>
        </div>

        <template v-if="evaluation">
          <div class="mt-3 flex flex-col gap-1">
            <p v-if="topLabels.length" class="top-notice">
              ★ {{ topLabels.join('・') }} が 5,040 通りの上位10%に入っています（RANDOM を使わなくても当たりに近い配置です）
            </p>
            <div v-if="current" class="flex flex-wrap items-center gap-1.5">
              今の並び <PatternChips :pattern="current.pattern" small />
              <b class="text-slate-800 dark:text-white tabular-nums">{{ current.rank }} 位</b>
              <span v-if="isTop(current.rank)" class="top-badge">上位10%</span>
              <span v-else class="text-slate-400 dark:text-slate-500">（上位 {{ percent(current.rank) }}%）</span>
            </div>
          </div>

          <!-- 正規・MIRROR・R-RANDOM の最良がどこに来るか（RANDOM を使うか決める目安） -->
          <div class="mt-3 font-semibold text-slate-500 dark:text-slate-400">正規・MIRROR の順位</div>
          <ol class="cand-list">
            <li v-for="b in baseRows" :key="b.label"
              :class="{ current: b.cand.pattern === currentPattern, 'top-rank': isTop(b.cand.rank) }">
              <span class="rank tabular-nums">{{ b.cand.rank }}</span>
              <span class="cand-pattern">
                <span class="base-label">{{ b.label }}</span>
                <PatternChips :pattern="b.cand.pattern" small />
                <span v-if="isTop(b.cand.rank)" class="top-badge">上位10%</span>
                <span v-else class="text-[10px] text-slate-400 dark:text-slate-500 whitespace-nowrap">上位 {{ percent(b.cand.rank) }}%</span>
              </span>
              <button type="button" class="apply-btn" @click="emit('apply', b.cand.pattern)"><span class="btn-long">この並びで再生</span><span class="btn-short">再生</span></button>
            </li>
          </ol>

          <div class="mt-3 font-semibold text-slate-500 dark:text-slate-400">押しやすい並び</div>
          <ol class="cand-list">
            <li v-for="g in top" :key="g.head.pattern" :class="{ current: g.head.pattern === currentPattern }">
              <span class="rank tabular-nums">{{ g.head.rank }}</span>
              <span class="cand-pattern">
                <PatternChips :pattern="g.head.pattern" small />
                <span v-if="g.others" class="text-[10px] text-slate-400 dark:text-slate-500 whitespace-nowrap">ほか同点 {{ g.others }} 通り</span>
              </span>
              <button type="button" class="apply-btn" @click="emit('apply', g.head.pattern)"><span class="btn-long">この並びで再生</span><span class="btn-short">再生</span></button>
            </li>
          </ol>

          <button type="button" class="mt-2 text-blue-600 dark:text-blue-400 font-semibold" @click="showWorst = !showWorst">
            {{ showWorst ? '避けたい並びを隠す' : '避けたい並びを見る' }}
          </button>
          <ol v-if="showWorst" class="cand-list">
            <li v-for="g in worst" :key="g.head.pattern" :class="{ current: g.head.pattern === currentPattern }">
              <span class="rank tabular-nums">{{ g.head.rank }}</span>
              <span class="cand-pattern">
                <PatternChips :pattern="g.head.pattern" small />
                <span v-if="g.others" class="text-[10px] text-slate-400 dark:text-slate-500 whitespace-nowrap">ほか同点 {{ g.others }} 通り</span>
              </span>
              <button type="button" class="apply-btn" @click="emit('apply', g.head.pattern)"><span class="btn-long">この並びで再生</span><span class="btn-short">再生</span></button>
            </li>
          </ol>

          <button type="button" class="eval-open mt-3" @click="showEvalTable = true">
            <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path stroke-linecap="round" stroke-linejoin="round" d="M3 10h18M3 14h18M9 4v16M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" /></svg>
            評価表を見る（形ごとの減点と評価基準）
          </button>
          <RandomEvalModal v-if="showEvalTable" :columns="evalColumns" :side="side" @close="showEvalTable = false" />
        </template>
      </div>
    </details>
  </div>
</template>

<style scoped>
.eval-open {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.4rem 0.75rem;
  border-radius: 0.375rem;
  font-weight: 600;
  color: rgb(37 99 235);
  border: 1px solid rgb(147 197 253);
}
.eval-open:hover { background: rgb(239 246 255); }
.dark .eval-open { color: rgb(147 197 253); border-color: rgb(30 64 175); }
.dark .eval-open:hover { background: rgb(30 41 59); }
.panel { border: 1px solid rgb(226 232 240); border-radius: 0.375rem; }
.dark .panel { border-color: rgb(51 65 85); }
.panel-title {
  cursor: pointer;
  padding: 0.6rem 0.75rem;
  font-weight: 700;
  color: rgb(51 65 85);
  list-style-position: inside;
}
.dark .panel-title { color: rgb(226 232 240); }
.panel-body { padding: 0 0.75rem 0.75rem; }

.open-scroll { overflow-x: auto; }
.open-table { border-collapse: collapse; }
.tags { display: flex; gap: 0.25rem; margin-top: 0.15rem; }
.open-table th { padding: 0.25rem 0.5rem; font-weight: 600; color: rgb(100 116 139); text-align: left; white-space: nowrap; }
.open-table td { padding: 0.2rem 0.5rem; vertical-align: middle; }
.open-table tr.mark td { background: rgb(254 249 195 / 0.6); }
.dark .open-table tr.mark td { background: rgb(113 63 18 / 0.35); }
.open-table tr.after { opacity: 0.55; }
.tag { padding: 0 0.3rem; border-radius: 0.2rem; font-size: 10px; font-weight: 700; background: rgb(250 204 21); color: rgb(66 32 6); }
.tag-full { background: rgb(34 197 94); color: white; }

.lanes { display: inline-flex; gap: 2px; }
.cell { width: 0.8rem; height: 1rem; border-radius: 2px; }
/* 光っていないマスは淡く、光ったマスは元の鍵盤の色（白鍵＝白、黒鍵＝青）で塗る */
.cell.w { background: rgb(241 245 249); box-shadow: inset 0 0 0 1px rgb(203 213 225); }
.cell.b { background: rgb(219 234 254); }
.cell.w.on { background: white; box-shadow: inset 0 0 0 2px rgb(15 23 42); }
.cell.b.on { background: rgb(37 99 235); }
.dark .cell.w { background: rgb(30 41 59); box-shadow: inset 0 0 0 1px rgb(71 85 105); }
.dark .cell.b { background: rgb(30 58 138 / 0.5); }
.dark .cell.w.on { background: rgb(241 245 249); box-shadow: none; }
.dark .cell.b.on { background: rgb(59 130 246); }
/* スマホ幅では 1 行に収めるため、ボタンを「再生」に縮め、配置の「最良」の文字を省く */
.btn-short { display: none; }
@media (max-width: 479px) {
  .cand-list li { grid-template-columns: 1.6rem minmax(0, 1fr) auto; column-gap: 0.4rem; }
  .btn-long { display: none; }
  .btn-short { display: inline; }
}
@media (max-width: 419px) {
  .cell { width: 0.62rem; }
  .open-table th, .open-table td { padding-left: 0.3rem; padding-right: 0.3rem; }
}

.seg { display: inline-flex; border-radius: 0.375rem; overflow: hidden; border: 1px solid rgb(203 213 225); }
.seg button { padding: 0.35rem 0.6rem; font-weight: 600; white-space: nowrap; color: rgb(71 85 105); background: white; }
.seg button + button { border-left: 1px solid rgb(203 213 225); }
.seg button.on { color: white; background: rgb(37 99 235); }
.dark .seg { border-color: rgb(71 85 105); }
.dark .seg button { color: rgb(203 213 225); background: rgb(51 65 85); }
.dark .seg button + button { border-left-color: rgb(71 85 105); }
.dark .seg button.on { color: white; background: rgb(37 99 235); }


.cand-list { margin-top: 0.3rem; display: flex; flex-direction: column; gap: 0.2rem; }
.cand-list li {
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 0.6rem;
  row-gap: 0.2rem;
  padding: 0.25rem 0.5rem;
  border-radius: 0.375rem;
  background: rgb(248 250 252);
}
.cand-list li.current { box-shadow: inset 0 0 0 2px rgb(37 99 235); }
.dark .cand-list li { background: rgb(30 41 59); }
.rank { font-weight: 700; text-align: right; color: rgb(100 116 139); }
.cand-pattern { display: flex; flex-wrap: wrap; align-items: center; gap: 0.2rem 0.5rem; min-width: 0; }
/* 正規・MIRROR・R-RANDOM が上位 10% に入ったとき */
.cand-list li.top-rank { background: rgb(254 243 199); box-shadow: inset 0 0 0 2px rgb(245 158 11); }
.dark .cand-list li.top-rank { background: rgb(120 53 15 / 0.35); box-shadow: inset 0 0 0 2px rgb(217 119 6); }
.cand-list li.top-rank.current { box-shadow: inset 0 0 0 2px rgb(245 158 11), inset 0 0 0 4px rgb(37 99 235); }
.top-badge {
  padding: 0.05rem 0.4rem;
  border-radius: 9999px;
  font-size: 10px;
  font-weight: 700;
  white-space: nowrap;
  color: rgb(69 26 3);
  background: rgb(251 191 36);
}
.top-notice {
  padding: 0.4rem 0.6rem;
  border-radius: 0.375rem;
  font-weight: 600;
  color: rgb(146 64 14);
  background: rgb(254 243 199);
}
.dark .top-notice { color: rgb(253 230 138); background: rgb(120 53 15 / 0.35); }
.base-label { font-weight: 700; color: rgb(51 65 85); white-space: nowrap; }
.dark .base-label { color: rgb(226 232 240); }
.apply-btn {
  padding: 0.35rem 0.6rem;
  border-radius: 0.375rem;
  font-weight: 600;
  white-space: nowrap;
  color: rgb(37 99 235);
  border: 1px solid rgb(147 197 253);
}
.apply-btn:hover { background: rgb(239 246 255); }
.dark .apply-btn { color: rgb(147 197 253); border-color: rgb(30 64 175); }
.dark .apply-btn:hover { background: rgb(15 23 42); }
</style>
