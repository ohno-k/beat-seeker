<script setup lang="ts">
/**
 * RandomPanel.vue
 *
 * 【コンポーネントの役割】 譜面再生の下に出す RANDOM の補助。
 * - 判別: 曲頭の何打鍵目で元の白鍵（1・3・5・7）のレーンが分かるか、並び全体が分かるか（utils/randomEval.ts の identifyRandom）。
 *   曲頭の打鍵を「正規」と「今の並び」で並べて、ゲームで光ったレーンからどう読むかを見せる
 * - 配置評価: 5,040 通りの並びを手の負荷で順位付けする（evaluateRandom）。押すと計算し、並びを選ぶとその RANDOM で再生できる
 */
import { ref, computed, watch } from 'vue';
import type { ChartTimeline } from '../utils/chartPlayback';
import { evaluateRandom, identifyRandom, WHITE_KEYS, type RandomEvaluation, type RandomCandidate, type RandomMetrics } from '../utils/randomEval';
import PatternChips from './PatternChips.vue';

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
const SCRATCH_KEY_OPTIONS = [2, 3, 4];
const scratchKeys = ref(3);
const evaluation = ref<RandomEvaluation | null>(null);
const computing = ref(false);
const showWorst = ref(false);

function evaluate() {
  computing.value = true;
  // 計算（0.1〜0.3 秒）の前に「計算中」を描かせる
  setTimeout(() => {
    evaluation.value = evaluateRandom(props.timeline, props.side, scratchKeys.value);
    computing.value = false;
  }, 30);
}
// 評価済みなら、手の分け方やサイドを変えたときに計算し直す
watch([scratchKeys, () => props.side], () => { if (evaluation.value) evaluate(); });

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
const percent = (rank: number) => Math.max(1, Math.round((rank / 5040) * 100));

/** 割合の表示（分母 0 は「—」） */
function rate(ok: number, total: number): string {
  return total > 0 ? `${Math.round((ok / total) * 100)}%` : '—';
}
/** 重く見る 3 つ（皿同時・連皿中は逆の手・16 分交互）。連皿の無い譜面では連皿を出さない */
function keyLine(m: RandomMetrics) {
  const parts = [`皿と同時に取れる ${rate(m.scratchSimulOk, m.scratchSimulTotal)}`];
  if (m.streamTotal > 0) parts.push(`連皿中は逆の手 ${rate(m.streamOk, m.streamTotal)}`);
  parts.push(`16分の左右交互 ${rate(m.alt16, m.sixteenthPairs)}`);
  return parts.join(' ／ ');
}
/** そのほかの負荷 */
function metricLine(m: RandomMetrics) {
  return `皿の前後 ${m.scratchNear} ／ 片手連打 ${Math.round(m.fastSameHand)} ／ 片手3鍵以上 ${m.bigChords} ／ 片手最大 ${m.peakHandDensity}/秒`;
}
</script>

<template>
  <div class="random-panel mt-4 flex flex-col gap-3 text-xs text-slate-600 dark:text-slate-300">
    <!-- ── 判別 ── -->
    <details class="panel" open>
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
          <span class="font-semibold text-slate-500 dark:text-slate-400">皿側の手が持つ鍵盤</span>
          <div class="seg">
            <button v-for="n in SCRATCH_KEY_OPTIONS" :key="n" type="button" :class="{ on: scratchKeys === n }" @click="scratchKeys = n">
              {{ side === 1 ? `1〜${n}` : `${8 - n}〜7` }}
            </button>
          </div>
          <button v-if="!evaluation" type="button" class="eval-btn" :disabled="computing" @click="evaluate">
            {{ computing ? '計算中…' : '5,040 通りを評価する' }}
          </button>
          <span v-else-if="computing" class="text-slate-400">計算中…</span>
        </div>

        <template v-if="evaluation">
          <div class="mt-3 flex flex-col gap-1">
            <div v-if="current" class="flex flex-wrap items-center gap-1.5">
              今の並び <PatternChips :pattern="current.pattern" small />
              <b class="text-slate-800 dark:text-white tabular-nums">{{ current.rank }} 位</b>
              <span class="text-slate-400 dark:text-slate-500">（上位 {{ percent(current.rank) }}%）</span>
            </div>
            <div v-if="current" class="key-line">{{ keyLine(current.metrics) }}</div>
          </div>

          <!-- 正規・MIRROR・R-RANDOM の最良がどこに来るか（RANDOM を使うか決める目安） -->
          <div class="mt-3 font-semibold text-slate-500 dark:text-slate-400">正規・MIRROR の順位</div>
          <ol class="cand-list">
            <li v-for="b in baseRows" :key="b.label" :class="{ current: b.cand.pattern === currentPattern }">
              <span class="rank tabular-nums">{{ b.cand.rank }}</span>
              <span class="cand-pattern">
                <span class="base-label">{{ b.label }}</span>
                <PatternChips :pattern="b.cand.pattern" />
                <span class="text-[10px] text-slate-400 dark:text-slate-500 whitespace-nowrap">上位 {{ percent(b.cand.rank) }}%</span>
              </span>
              <button type="button" class="apply-btn" @click="emit('apply', b.cand.pattern)">この並びで再生</button>
              <span class="cand-metrics">
                <span class="key-line">{{ keyLine(b.cand.metrics) }}</span>
                <span class="block text-[10px] text-slate-400 dark:text-slate-500">{{ metricLine(b.cand.metrics) }}</span>
              </span>
            </li>
          </ol>

          <div class="mt-3 font-semibold text-slate-500 dark:text-slate-400">押しやすい並び</div>
          <ol class="cand-list">
            <li v-for="g in top" :key="g.head.pattern" :class="{ current: g.head.pattern === currentPattern }">
              <span class="rank tabular-nums">{{ g.head.rank }}</span>
              <span class="cand-pattern">
                <PatternChips :pattern="g.head.pattern" />
                <span v-if="g.others" class="text-[10px] text-slate-400 dark:text-slate-500 whitespace-nowrap">ほか同点 {{ g.others }} 通り</span>
              </span>
              <button type="button" class="apply-btn" @click="emit('apply', g.head.pattern)">この並びで再生</button>
              <span class="cand-metrics">
                <span class="key-line">{{ keyLine(g.head.metrics) }}</span>
                <span class="block text-[10px] text-slate-400 dark:text-slate-500">{{ metricLine(g.head.metrics) }}</span>
              </span>
            </li>
          </ol>

          <button type="button" class="mt-2 text-blue-600 dark:text-blue-400 font-semibold" @click="showWorst = !showWorst">
            {{ showWorst ? '避けたい並びを隠す' : '避けたい並びを見る' }}
          </button>
          <ol v-if="showWorst" class="cand-list">
            <li v-for="g in worst" :key="g.head.pattern" :class="{ current: g.head.pattern === currentPattern }">
              <span class="rank tabular-nums">{{ g.head.rank }}</span>
              <span class="cand-pattern">
                <PatternChips :pattern="g.head.pattern" />
                <span v-if="g.others" class="text-[10px] text-slate-400 dark:text-slate-500 whitespace-nowrap">ほか同点 {{ g.others }} 通り</span>
              </span>
              <button type="button" class="apply-btn" @click="emit('apply', g.head.pattern)">この並びで再生</button>
              <span class="cand-metrics">
                <span class="key-line">{{ keyLine(g.head.metrics) }}</span>
                <span class="block text-[10px] text-slate-400 dark:text-slate-500">{{ metricLine(g.head.metrics) }}</span>
              </span>
            </li>
          </ol>

          <p class="mt-3 text-[11px] text-slate-400 dark:text-slate-500 leading-relaxed">
            RANDOM は鍵盤をレーンごと入れ替えるので、縦連打はどの並びでも同じです。当たり配置の決め手として次の 3 つを特に重く見ています:
            皿と同時に取れる＝単発の皿と同じタイミングの鍵盤が、皿側の手に来る割合（皿と一緒に同じ手で取れる）。
            連皿中は逆の手＝連続スクラッチ（BPM 140 の 16 分以上の間隔で 3 回以上続く皿）の最中の鍵盤が、皿を回さない方の手に来る割合（連皿の無い譜面では出しません）。
            16分の左右交互＝16 分で続く打鍵が左右の手で交互になる割合（両手の和音をはさむ組はどの並びでも交互にならないので、100% にはなりません）。
            この 3 つはこの譜面で一番良い並びとの差を重みづけして足し、さらに次の負荷で差をつけています:
            皿の前後＝皿と同時ではないが前後 0.1 秒に皿側の手へ来るノーツ（連皿の最中は除く）、片手連打＝同じ手で 0.105 秒未満に続く別レーンへの打鍵（離れたレーンほど重い）、
            片手3鍵以上＝1 つの手で 3 鍵以上の同時押し、片手最大＝片手の 1 秒あたりの最大ノーツ数。
            運指や CN の押しっぱなしは考えていない目安です。
          </p>
        </template>
      </div>
    </details>
  </div>
</template>

<style scoped>
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
/* スマホ幅では並び（7 マス）とボタンが横に並ばないので、ボタンを指標の下の行へ */
@media (max-width: 479px) {
  .cand-list li { grid-template-columns: 2rem minmax(0, 1fr); }
  .cand-list .apply-btn { grid-column: 2; grid-row: 3; justify-self: start; }
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

.eval-btn { padding: 0.4rem 0.8rem; border-radius: 0.375rem; font-weight: 700; color: white; background: rgb(37 99 235); white-space: nowrap; }
.eval-btn:disabled { opacity: 0.6; }

.cand-list { margin-top: 0.4rem; display: flex; flex-direction: column; gap: 0.35rem; }
.cand-list li {
  display: grid;
  grid-template-columns: 2rem minmax(0, 1fr) auto;
  align-items: center;
  column-gap: 0.6rem;
  row-gap: 0.2rem;
  padding: 0.4rem 0.5rem;
  border-radius: 0.375rem;
  background: rgb(248 250 252);
}
.cand-list li.current { box-shadow: inset 0 0 0 2px rgb(37 99 235); }
.dark .cand-list li { background: rgb(30 41 59); }
.rank { font-weight: 700; text-align: right; color: rgb(100 116 139); }
.cand-pattern { display: flex; flex-wrap: wrap; align-items: center; gap: 0.2rem 0.5rem; min-width: 0; }
.cand-metrics { grid-column: 2 / -1; }
.base-label { font-weight: 700; color: rgb(51 65 85); white-space: nowrap; }
.dark .base-label { color: rgb(226 232 240); }
.key-line { display: block; font-size: 11px; font-weight: 600; color: rgb(4 120 87); }
.dark .key-line { color: rgb(110 231 183); }
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
