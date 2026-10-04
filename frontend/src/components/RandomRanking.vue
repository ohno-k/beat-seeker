<script setup lang="ts">
/**
 * RandomRanking.vue
 *
 * 【コンポーネントの役割】 譜面分析ページの「当たり配置ランキング」タブ。
 * 正規・MIRROR・R-RANDOM（12 通りの最良）・自由入力の並びが、各譜面の 5,040 通り中で何位かを一覧にし、
 * （自由入力は「.」を 2 つ以上入れるとワイルドカード。残りの鍵盤の入れ方のうち譜面ごとに一番良い並びを R-RANDOM と同じく出す）
 * 当たり（順位の小さい）譜面から並べる。サイドはプロフィールのプレイサイド（未ログインは画面で切り替え）。
 *
 * 【データ】 scripts/build-random-ranking.mts で事前に計算した frontend/public/data/random-ranking/ のファイル（1P）。
 * 2P は 1P の左右反転なので、2P で並び p の順位は 1P で p を逆順にした並びの順位として読み替える（2P のファイルは無い）。
 * - summary.json: 譜面ごとの正規・MIRROR・R-RANDOM 最良の順位
 * - ranks-12.bin / ranks-11.bin / ranks-low.bin: 自由入力用。譜面ごとに 5,040 バイト（並びの辞書順）、
 *   1 バイト = floor((順位 - 1) × 256 / 5040)。自由入力を使ったときだけ、表示に必要なレベルのファイルを読む
 * 評価ロジック（utils/randomEval.ts）を変えたら作り直す必要がある（画面に計算日を出す）。
 */
import { ref, computed, watch, onMounted } from 'vue';
import PatternChips from './PatternChips.vue';
import { allPatterns } from '../utils/randomEval';
import { isValidPattern } from '../utils/chartPlayback';
import { usePlaySide } from '../composables/usePlaySide';

/** open = 譜面を譜面分析で開く。pattern = その行の並び（譜面再生をこの並びで始める） */
const emit = defineEmits<{ (e: 'open', textage: string, pattern: string): void }>();

interface ChartRow {
  t: string; d: string; l: number; n: number; x: string;
  off: number; mir: number; rr: number; rrp: string; best: string;
  g: '12' | '11' | 'low'; i: number;
}
interface Summary { generatedAt: string; side: number; total: number; bucketsPerRank: number; charts: ChartRow[]; }

// プレイサイド: ログイン中はプロフィールの設定、未ログインは画面の切り替え。
// データは 1P のものだけ。2P の評価は 1P の左右反転なので、2P で並び p の順位 = 1P で p を逆順にした並びの順位として読み替える
const { profileSide } = usePlaySide();
const guestSide = ref<1 | 2>(1);
const side = computed<1 | 2>(() => profileSide.value ?? guestSide.value);
/** 今のサイドの並び → 1P のデータを引く並び */
const to1P = (p: string) => (side.value === 2 ? [...p].reverse().join('') : p);
const BASE = `${import.meta.env.BASE_URL}data/random-ranking/`;
const TOP10 = 504; // 5,040 通りの上位 10%

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

// ── 並べ方 ───────────────────────────────────────────────
type Mode = 'off' | 'mirror' | 'rran' | 'free';
const MODES: { value: Mode; label: string }[] = [
  { value: 'off', label: '正規' },
  { value: 'mirror', label: 'MIRROR' },
  { value: 'rran', label: 'R乱' },
  { value: 'free', label: '自由入力' },
];
const mode = ref<Mode>('off');
const freeInput = ref('');
/**
 * 自由入力の解釈。1〜7 を 1 回ずつの 7 桁なら 1 つの並び。「.」をワイルドカード（どの鍵盤でもよいレーン）として 2 つ以上入れると、
 * 残りの鍵盤を「.」の位置に入れた全部の並びが候補になり、譜面ごとに一番良い並びを出す（R乱と同じ見せ方）。
 * candidates は今のサイドの並びと、1P のデータを引く添字
 */
const freeQuery = computed((): { candidates: { pattern: string; idx: number }[]; wild: boolean; error: string } | null => {
  const v = freeInput.value;
  if (!v) return null;
  if (v.length < 7) return null; // 入力途中
  const digits = v.replace(/\./g, '');
  if (new Set(digits).size !== digits.length) return { candidates: [], wild: false, error: '同じ鍵盤は 1 回まで' };
  const dots = 7 - digits.length;
  if (dots === 1) return { candidates: [], wild: false, error: 'ワイルド（.）は 2 つ以上' };
  if (dots === 0) {
    if (!isValidPattern(v)) return { candidates: [], wild: false, error: '1〜7 を 1 回ずつ' };
    return { candidates: [{ pattern: v, idx: PATTERN_INDEX.get(to1P(v))! }], wild: false, error: '' };
  }
  const rest = [1, 2, 3, 4, 5, 6, 7].map(String).filter(k => !digits.includes(k));
  const out: { pattern: string; idx: number }[] = [];
  const fill = (chars: string[], pool: string[]) => {
    const i = chars.indexOf('.');
    if (i < 0) { const p = chars.join(''); out.push({ pattern: p, idx: PATTERN_INDEX.get(to1P(p))! }); return; }
    for (let n = 0; n < pool.length; n++) {
      chars[i] = pool[n];
      fill(chars, pool.filter((_, m) => m !== n));
      chars[i] = '.';
    }
  };
  fill([...v], rest);
  return { candidates: out, wild: true, error: '' };
});
/** 自由入力が使える状態か（並びかワイルドカードとして正しい） */
const freeReady = computed(() => !!freeQuery.value && !freeQuery.value.error);
function onFreeInput(e: Event) {
  freeInput.value = (e.target as HTMLInputElement).value.replace(/[^1-7.]/g, '').slice(0, 7);
}

type LevelFilter = 'all' | 12 | 11 | 'low';
const level = ref<LevelFilter>('all');
const query = ref('');
const PAGE = 100;
const limit = ref(PAGE);
watch([mode, level, query, freeInput], () => { limit.value = PAGE; });

// ── 自由入力: レベルごとの順位ファイル ─────────────────────────
const PATTERN_INDEX = new Map(allPatterns().map((p, i) => [p, i]));
const bins = ref<Partial<Record<ChartRow['g'], Uint8Array>>>({});
const binLoading = ref(false);
const neededGroups = computed((): ChartRow['g'][] =>
  level.value === 'all' ? ['12', '11', 'low'] : level.value === 12 ? ['12'] : level.value === 11 ? ['11'] : ['low']);
watch([mode, freeReady, neededGroups], async () => {
  if (mode.value !== 'free' || !freeReady.value) return;
  const missing = neededGroups.value.filter(g => !bins.value[g]);
  if (!missing.length) return;
  binLoading.value = true;
  try {
    for (const g of missing) {
      const res = await fetch(`${BASE}ranks-${g}.bin`);
      if (!res.ok) throw new Error(String(res.status));
      bins.value = { ...bins.value, [g]: new Uint8Array(await res.arrayBuffer()) };
    }
  } catch {
    loadError.value = '自由入力の順位データを読み込めませんでした';
  } finally {
    binLoading.value = false;
  }
}, { immediate: true });

/**
 * 自由入力の、その譜面での順位の幅（約 20 位刻み）と並び。ワイルドカードなら候補のうち一番良い並び
 * （同じ幅に複数あれば先に見つかったもの）。データ未読込なら null
 */
function freeRank(r: ChartRow): { from: number; to: number; pattern: string } | null {
  const bin = bins.value[r.g];
  const q = freeQuery.value;
  if (!bin || !q || q.error || !summary.value) return null;
  const base = r.i * summary.value.total;
  let bucket = 256;
  let pattern = '';
  for (const c of q.candidates) {
    const b = bin[base + c.idx];
    if (b < bucket) { bucket = b; pattern = c.pattern; if (b === 0) break; }
  }
  if (!pattern) return null;
  const { total, bucketsPerRank } = summary.value;
  // 保存側は bucket = floor((順位 - 1) × bucketsPerRank / total)。この bucket になる順位は
  // ceil(bucket × total / bucketsPerRank) + 1 〜 ceil((bucket + 1) × total / bucketsPerRank)（切り捨てで戻すと端が 1 位ずれる）
  return { from: Math.ceil((bucket * total) / bucketsPerRank) + 1, to: Math.ceil(((bucket + 1) * total) / bucketsPerRank), pattern };
}

// ── 一覧 ─────────────────────────────────────────────────
interface ViewRow { row: ChartRow; rank: number; rankTo: number | null; pattern: string; }
const rows = computed((): ViewRow[] => {
  const s = summary.value;
  if (!s) return [];
  const q = query.value.trim().toLowerCase();
  const out: ViewRow[] = [];
  for (const r of s.charts) {
    if (level.value === 12 && r.l < 12) continue;
    if (level.value === 11 && r.l !== 11) continue;
    if (level.value === 'low' && r.l > 10) continue;
    if (q && !r.t.toLowerCase().includes(q)) continue;
    let rank: number | null;
    let rankTo: number | null = null;
    let pattern: string;
    // 2P は 1P の左右反転: 2P の正規 = 1P の MIRROR、2P の MIRROR = 1P の正規、R乱の最良は 1P の最良の並びを逆順にしたもの
    const two = side.value === 2;
    if (mode.value === 'off') { rank = two ? r.mir : r.off; pattern = '1234567'; }
    else if (mode.value === 'mirror') { rank = two ? r.off : r.mir; pattern = '7654321'; }
    else if (mode.value === 'rran') { rank = r.rr; pattern = to1P(r.rrp); }
    else {
      if (!freeReady.value) continue;
      const fr = freeRank(r);
      if (!fr) continue;
      rank = fr.from; rankTo = fr.to; pattern = fr.pattern;
    }
    if (rank === null) continue;
    out.push({ row: r, rank, rankTo, pattern });
  }
  out.sort((a, b) => a.rank - b.rank || b.row.l - a.row.l || a.row.t.localeCompare(b.row.t, 'ja'));
  return out;
});
const shown = computed(() => rows.value.slice(0, limit.value));
const topCount = computed(() => rows.value.filter(v => v.rank <= TOP10).length);

const percent = (rank: number) => Math.max(1, Math.round((rank / 5040) * 100));
const diffLabel = (d: string) => (d === '10' ? 'L' : 'A');
const levelLabel = (l: number) => `☆${l}`;
</script>

<template>
  <section class="ranking text-xs text-slate-600 dark:text-slate-300">
    <p class="text-slate-500 dark:text-slate-400 leading-relaxed">
      各譜面で、選んだ並びが RANDOM の 5,040 通り中の何位か（{{ side }}P・配置評価と同じ基準）を一覧にします。順位が小さいほど当たりです。
    </p>

    <!-- 並び・絞り込み -->
    <div class="controls mt-3">
      <div class="control">
        <span class="control-label">並び</span>
        <div class="seg">
          <button v-for="m in MODES" :key="m.value" type="button" :class="{ on: mode === m.value }" @click="mode = m.value">{{ m.label }}</button>
        </div>
        <template v-if="mode === 'free'">
          <input :value="freeInput" type="text" maxlength="7" placeholder="例: 2461357 / 246...." aria-label="並び（. はワイルドカード）"
            class="free-input tabular-nums rounded border bg-white dark:bg-slate-700 px-2 py-1.5"
            :class="freeQuery?.error ? 'border-red-400 dark:border-red-500' : 'border-slate-300 dark:border-slate-600'"
            @input="onFreeInput" />
          <PatternChips v-if="freeReady" :pattern="freeInput" small />
          <span v-if="freeQuery?.wild && freeReady" class="text-slate-400 dark:text-slate-500 tabular-nums">{{ freeQuery.candidates.length }} 通りから譜面ごとに最良</span>
          <span v-if="freeQuery?.error" class="text-red-500 dark:text-red-400">{{ freeQuery.error }}</span>
        </template>
      </div>
      <div class="control">
        <span class="control-label">サイド</span>
        <span v-if="profileSide" class="font-semibold">{{ profileSide }}P<span class="ml-1 font-normal text-slate-400 dark:text-slate-500">（プロフィールの設定）</span></span>
        <div v-else class="seg">
          <button type="button" :class="{ on: guestSide === 1 }" @click="guestSide = 1">1P</button>
          <button type="button" :class="{ on: guestSide === 2 }" @click="guestSide = 2">2P</button>
        </div>
      </div>
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
    <template v-else>
      <div class="mt-3 flex flex-wrap items-baseline gap-x-3 gap-y-1 text-slate-500 dark:text-slate-400">
        <span v-if="mode === 'free' && !freeReady">並びを 7 桁で入力してください（1〜7 を 1 回ずつ。決めないレーンは . にすると、残りの鍵盤の入れ方のうち譜面ごとに一番良い並びを出します。. は 2 つ以上）</span>
        <span v-else-if="binLoading">自由入力の順位データを読み込み中…</span>
        <template v-else>
          <span class="tabular-nums">{{ rows.length }} 譜面</span>
          <span class="tabular-nums">上位 10% に入る譜面 <b class="text-amber-600 dark:text-amber-400">{{ topCount }}</b></span>
          <span v-if="query.trim() && rows.length === 0" class="text-red-500 dark:text-red-400">
            曲名「{{ query.trim() }}」に一致する譜面がありません
            <button type="button" class="ml-1 underline font-semibold" @click="query = ''">絞り込みを解除</button>
          </span>
        </template>
      </div>

      <ol class="rank-list mt-2">
        <li v-for="(v, n) in shown" :key="v.row.x" :class="{ top: v.rank <= TOP10 }">
          <span class="pos tabular-nums">{{ n + 1 }}</span>
          <span class="lv tabular-nums" :class="v.row.d === '10' ? 'leg' : 'ano'">{{ levelLabel(v.row.l) }} {{ diffLabel(v.row.d) }}</span>
          <button type="button" class="title" :title="`${v.row.t} を譜面分析で開く（${v.pattern} で譜面再生）`" @click="emit('open', v.row.x, v.pattern)">{{ v.row.t }}</button>
          <PatternChips v-if="mode === 'rran' || (mode === 'free' && freeQuery?.wild)" :pattern="v.pattern" small class="chips" />
          <span class="rank tabular-nums">
            <span v-if="v.rank <= TOP10" class="top-badge">上位10%</span>
            {{ v.rankTo ? `${v.rank}〜${v.rankTo}` : v.rank }}位
            <span class="pct">（{{ percent(v.rank) }}%）</span>
          </span>
        </li>
      </ol>
      <button v-if="rows.length > limit" type="button" class="more mt-2 text-blue-600 dark:text-blue-400 font-semibold"
        @click="limit += PAGE">さらに表示（残り {{ rows.length - limit }}）</button>

      <p class="mt-4 text-[11px] text-slate-400 dark:text-slate-500 leading-relaxed">
        {{ summary.generatedAt }} 時点の評価基準で計算した {{ summary.charts.length }} 譜面（textage の譜面データが手元にある ANOTHER / LEGGENDARIA）。
        R乱は 12 通り（正規・MIRROR をずらしたもの）のうち一番良い並びです。自由入力の順位は約 20 位刻みの幅で表示します。自由入力で . を入れたレーン（2 つ以上）はワイルドカードになり、残りの鍵盤の入れ方のうち譜面ごとに一番良い並びと順位を出します。
        曲名を押すと譜面分析タブでその譜面を開きます（配置評価で細かい内訳を確認できます）。
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
.free-input { width: 6rem; letter-spacing: 0.1em; font-weight: 700; }
.search { flex: 1 1 10rem; min-width: 0; max-width: 16rem; }

.seg { display: inline-flex; border-radius: 0.375rem; overflow: hidden; border: 1px solid rgb(203 213 225); }
.seg button { padding: 0.35rem 0.6rem; font-weight: 600; white-space: nowrap; color: rgb(71 85 105); background: white; }
.seg button + button { border-left: 1px solid rgb(203 213 225); }
.seg button.on { color: white; background: rgb(37 99 235); }
.dark .seg { border-color: rgb(71 85 105); }
.dark .seg button { color: rgb(203 213 225); background: rgb(51 65 85); }
.dark .seg button + button { border-left-color: rgb(71 85 105); }
.dark .seg button.on { color: white; background: rgb(37 99 235); }

.rank-list { display: flex; flex-direction: column; gap: 2px; }
.rank-list li {
  display: grid;
  grid-template-columns: 2.2rem 3.2rem minmax(0, 1fr) auto auto;
  align-items: center;
  column-gap: 0.5rem;
  padding: 0.3rem 0.5rem;
  border-radius: 0.3rem;
  background: rgb(248 250 252);
}
.dark .rank-list li { background: rgb(15 23 42 / 0.5); }
.rank-list li.top { background: rgb(254 243 199); }
.dark .rank-list li.top { background: rgb(120 53 15 / 0.35); }
.pos { text-align: right; font-weight: 700; color: rgb(148 163 184); }
.lv { font-weight: 700; white-space: nowrap; }
.lv.ano { color: rgb(220 38 38); }
.lv.leg { color: rgb(147 51 234); }
.dark .lv.ano { color: rgb(248 113 113); }
.dark .lv.leg { color: rgb(192 132 252); }
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
.rank { white-space: nowrap; text-align: right; font-weight: 700; color: rgb(51 65 85); }
.dark .rank { color: rgb(226 232 240); }
.pct { font-weight: 400; color: rgb(148 163 184); }
.top-badge {
  margin-right: 0.3rem;
  padding: 0.05rem 0.35rem;
  border-radius: 9999px;
  font-size: 10px;
  font-weight: 700;
  color: rgb(69 26 3);
  background: rgb(251 191 36);
}

/* スマホ幅: 順位は 2 行目に回し、R乱の並びも 2 行目へ */
@media (max-width: 479px) {
  .rank-list li { grid-template-columns: 1.8rem 2.8rem minmax(0, 1fr); row-gap: 0.15rem; }
  .rank-list .chips { grid-column: 3; grid-row: 2; }
  .rank-list .rank { grid-column: 3; grid-row: 2; justify-self: end; }
  .control-label { width: auto; }
}
</style>
