<script setup lang="ts">
/**
 * 【Viewの役割】 AAA ロードマップ。管理者（ID18）専用の検証ページ。
 *
 * ANOTHER / LEGGENDARIA 全譜面（☆1〜12）を「AAA（または MAX-）を取る難しさ」の 1 本の目盛りに並べ、
 * 難度 0.02 刻みのレベルに分けて、初めての AAA から全 AAA までの道のりとして見せる。
 *
 * レベルの達成とプレイヤーのレベル（2026-09-23 ユーザー指定）:
 *  - そのレベルの譜面の 3 分の 2 以上でライン（AAA / MAX-）を取ったら、そのレベルを達成。
 *  - 達成しているレベルのうち一番高いものが、その人の AAA レベル / MAX- レベル
 *    （下のレベルを飛ばしていても構わない）。
 *  - 分母は対象フィルタ（☆1〜12 / ☆12 のみ 等）に入る、そのレベルの全譜面（未プレーも含む）。
 *
 * データ: API `/api/scores/score-roadmap`（管理者専用）。
 *  - 各譜面の d = そのラインを取るのに必要な実力（難易度表と同じ目盛り）。推定はサーバーのラッシュモデル
 *    （ScoreRoadmapService 参照）。
 *  - lines.*.thetas = 全プレイヤーの推定実力（昇順）。「到達者の割合」に使う。
 *  - user = 表示中ユーザーの推定実力と歴代ベストの桶（理論値の 1/180 単位。160 以上 = AAA、170 以上 = MAX-）。
 *  - 管理者は userId を渡して他ユーザーを表示できる（ユーザー名は `/api/scores/score-roadmap/user-suggest` で部分一致サジェスト）。
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useAuth } from '../composables/useAuth';
import { formatJstDateTime } from '../utils/jstTime';

const { authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

type LineKey = 'aaa' | 'maxMinus';
interface ChartLine { d: number; se: number; n: number; rate: number }
interface Chart { i: number; title: string; difficultyName: string; level: number; playerCount: number; aaa: ChartLine; maxMinus: ChartLine }
interface LineMeta { k: number; thetas: number[] }
interface UserInfo { userId: number; label: string | null; found: boolean; thetaAaa?: number; thetaMaxMinus?: number; plays?: [number, number][] }

const LINES: LineKey[] = ['aaa', 'maxMinus'];
const LINE_BUCKET: Record<LineKey, number> = { aaa: 160, maxMinus: 170 };
const LINE_LABEL: Record<LineKey, string> = { aaa: 'AAA', maxMinus: 'MAX-' };

/**
 * 1 レベルの幅（難度の目盛りで 0.02）。推定の標準誤差（約 0.03）と同程度なので、
 * 隣り合う数レベルの前後は誤差の範囲。0.2 ごと（10 レベルごと）に見出しを入れて大づかみにも読めるようにする。
 */
const LEVEL_W = 0.02;
const GROUP_W = 0.2;
/** レベル達成に必要な割合（3 分の 2）。 */
const CLEAR_NUM = 2, CLEAR_DEN = 3;

const isLoading = ref(true);
const errorMsg = ref('');
const serverRefreshing = ref(false);
const computedAt = ref<string | null>(null);
const charts = ref<Chart[]>([]);
const lines = ref<Record<LineKey, LineMeta> | null>(null);
const user = ref<UserInfo | null>(null);
let pollTimer: ReturnType<typeof setTimeout> | null = null;

/** 表示中のユーザー ID（null = 自分）。 */
const selectedUserId = ref<number | null>(null);

/** 【関数の役割】 API を叩く。計算中は 5 秒おきにポーリングする。 */
async function load(force = false) {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
  const params = new URLSearchParams();
  if (force) params.set('refresh', 'true');
  if (selectedUserId.value != null) params.set('userId', String(selectedUserId.value));
  try {
    const res = await fetch(`${API_BASE}/api/scores/score-roadmap?${params}`, { headers: authHeaders() });
    if (!res.ok) {
      errorMsg.value = res.status === 403 ? '管理者専用のページです' : `APIエラー: ${res.status}`;
      isLoading.value = false;
      return;
    }
    const data = await res.json();
    serverRefreshing.value = !!data.refreshing;
    errorMsg.value = data.error ? `推定エラー: ${data.error}` : '';
    if (data.ready) {
      charts.value = data.charts;
      lines.value = data.lines;
      user.value = data.user ?? null;
      computedAt.value = data.computedAt;
      isLoading.value = false;
    }
    if (data.refreshing) pollTimer = setTimeout(() => load(), 5000);
    else if (!data.ready) isLoading.value = false;
  } catch (e: any) {
    errorMsg.value = `通信エラー: ${e.message}`;
    isLoading.value = false;
  }
}
onMounted(() => load());
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer);
  if (searchTimer) clearTimeout(searchTimer);
});

// ===== ユーザー名検索（サジェスト） =====
interface UserHit { id: number; displayName: string | null; iidxId: string | null }
const userQuery = ref('');
const suggestions = ref<UserHit[]>([]);
const suggestOpen = ref(false);
const suggestIndex = ref(-1);
let searchTimer: ReturnType<typeof setTimeout> | null = null;
let searchSeq = 0;

/** 【関数の役割】 入力のたびに 250ms 待って、管理者用サジェスト API（表示名・IIDX ID の部分一致）で候補を取る。 */
function onUserInput() {
  if (searchTimer) clearTimeout(searchTimer);
  const q = userQuery.value.trim();
  if (!q) { suggestions.value = []; suggestOpen.value = false; return; }
  searchTimer = setTimeout(async () => {
    const seq = ++searchSeq;
    try {
      const res = await fetch(`${API_BASE}/api/scores/score-roadmap/user-suggest?q=${encodeURIComponent(q)}`, { headers: authHeaders() });
      if (!res.ok || seq !== searchSeq) return;
      const hits: UserHit[] = await res.json();
      suggestions.value = hits.slice(0, 10);
      suggestIndex.value = -1;
      suggestOpen.value = true;
    } catch {}
  }, 250);
}
function chooseUser(h: UserHit) {
  selectedUserId.value = h.id;
  userQuery.value = h.displayName ?? h.iidxId ?? String(h.id);
  suggestOpen.value = false;
  load();
}
function resetToSelf() {
  selectedUserId.value = null;
  userQuery.value = '';
  suggestions.value = [];
  suggestOpen.value = false;
  load();
}
function onUserKeydown(e: KeyboardEvent) {
  if (!suggestOpen.value || !suggestions.value.length) return;
  if (e.key === 'ArrowDown') { e.preventDefault(); suggestIndex.value = (suggestIndex.value + 1) % suggestions.value.length; }
  else if (e.key === 'ArrowUp') { e.preventDefault(); suggestIndex.value = (suggestIndex.value - 1 + suggestions.value.length) % suggestions.value.length; }
  else if (e.key === 'Enter') { e.preventDefault(); chooseUser(suggestions.value[Math.max(0, suggestIndex.value)]); }
  else if (e.key === 'Escape') { suggestOpen.value = false; }
}
/** フォーカスが外れたら候補を閉じる（クリック選択が先に走るよう少し待つ）。 */
const closeSuggestSoon = () => setTimeout(() => { suggestOpen.value = false; }, 150);

// ===== 表示設定 =====
const line = ref<LineKey>('aaa');
const TARGETS = [
  { key: '1-12', label: '☆1〜12', min: 1, max: 12 },
  { key: '8-12', label: '☆8〜12', min: 8, max: 12 },
  { key: '10-12', label: '☆10〜12', min: 10, max: 12 },
  { key: '11-12', label: '☆11〜12', min: 11, max: 12 },
  { key: '12', label: '☆12のみ', min: 12, max: 12 },
];
const targetKey = ref('1-12');
const target = computed(() => TARGETS.find((t) => t.key === targetKey.value) ?? TARGETS[0]);
const targetCharts = computed(() => charts.value.filter((c) => c.level >= target.value.min && c.level <= target.value.max));
const onlyOpen = ref(false);

const userBucket = computed(() => new Map<number, number>(user.value?.plays ?? []));
const achievedOn = (c: Chart, l: LineKey) => (userBucket.value.get(c.i) ?? -1) >= LINE_BUCKET[l];
const achieved = (c: Chart) => achievedOn(c, line.value);
const played = (c: Chart) => userBucket.value.has(c.i);
const dOf = (c: Chart) => c[line.value].d;
const userTheta = (l: LineKey) => {
  const u = user.value;
  if (!u?.found) return null;
  return (l === 'aaa' ? u.thetaAaa : u.thetaMaxMinus) ?? null;
};

/** 【関数の役割】 θ が昇順配列 thetas の中で x 以上の人の割合（%）。 */
function shareAtLeast(x: number, l: LineKey = line.value): number {
  const th = lines.value?.[l].thetas ?? [];
  if (!th.length) return 0;
  let lo = 0, hi = th.length;
  while (lo < hi) { const m = (lo + hi) >> 1; if (th[m] < x) lo = m + 1; else hi = m; }
  return ((th.length - lo) * 100) / th.length;
}

/** レベル達成に必要な譜面数。 */
const needFor = (n: number) => Math.ceil((n * CLEAR_NUM) / CLEAR_DEN);

interface Level { no: number; from: number; to: number; charts: Chart[]; done: number; need: number; cleared: boolean; reachShare: number; group: number }

/**
 * 【関数の役割】 指定ラインでのレベル一覧を作る。
 * レベル番号の起点は、対象フィルタに関係なく全譜面の最小難度（フィルタで番号がずれないように）。
 * Lv n = 難度 [base + (n−1)·W, base + n·W)。譜面の無いレベルは作らない。
 */
function buildLevels(l: LineKey): { levels: Level[]; maxNo: number } {
  let minD = Infinity;
  for (const c of charts.value) minD = Math.min(minD, c[l].d);
  const base = Number.isFinite(minD) ? Math.floor(minD / LEVEL_W + 1e-9) * LEVEL_W : 0;
  const noOf = (d: number) => 1 + Math.floor((d - base) / LEVEL_W + 1e-9);
  let maxNo = 1;
  for (const c of charts.value) maxNo = Math.max(maxNo, noOf(c[l].d));
  const byNo = new Map<number, Chart[]>();
  for (const c of targetCharts.value) {
    const no = noOf(c[l].d);
    if (!byNo.has(no)) byNo.set(no, []);
    byNo.get(no)!.push(c);
  }
  const levels = [...byNo.entries()].sort((a, b) => a[0] - b[0]).map(([no, cs]) => {
    cs.sort((a, b) => a[l].d - b[l].d);
    const from = base + (no - 1) * LEVEL_W;
    const to = from + LEVEL_W;
    const done = cs.filter((c) => achievedOn(c, l)).length;
    const need = needFor(cs.length);
    return { no, from, to, charts: cs, done, need, cleared: done >= need, reachShare: shareAtLeast(to, l), group: Math.floor(from / GROUP_W + 1e-9) };
  });
  return { levels, maxNo };
}
const built = computed(() => ({ aaa: buildLevels('aaa'), maxMinus: buildLevels('maxMinus') }));
const levels = computed(() => built.value[line.value].levels);

/** 【関数の役割】 達成しているレベルのうち一番高い番号（= その人の AAA / MAX- レベル）。無ければ 0。 */
function playerLevel(l: LineKey): number {
  let best = 0;
  for (const lv of built.value[l].levels) if (lv.cleared) best = lv.no;
  return best;
}
const myLevel = computed(() => playerLevel(line.value));
const clearedCount = computed(() => levels.value.filter((l) => l.cleared).length);
const totalDone = computed(() => targetCharts.value.filter(achieved).length);

// ===== 展開状態 =====
const expanded = ref(new Set<number>());
watch([myLevel, line, targetKey, () => charts.value.length], () => {
  // 自分のレベルの 1 つ上にある未達成レベル（次の目標）を開いておく
  const next = levels.value.find((l) => l.no > myLevel.value && !l.cleared);
  expanded.value = new Set(next ? [next.no] : []);
}, { immediate: true });
function toggleLevel(no: number) {
  const next = new Set(expanded.value);
  if (next.has(no)) next.delete(no); else next.add(no);
  expanded.value = next;
}
const visibleCharts = (l: Level) => (onlyOpen.value ? l.charts.filter((c) => !achieved(c)) : l.charts);

/** 【関数の役割】 0.2 ごとの見出しを、そのグループの先頭レベルの前にだけ出す。 */
const isGroupHead = (i: number) => i === 0 || levels.value[i - 1].group !== levels.value[i].group;
/** グループ（0.2 幅）ごとの集計。見出しに出す。 */
const groupStats = computed(() => {
  const m = new Map<number, { cleared: number; levels: number; firstNo: number; lastNo: number }>();
  for (const l of levels.value) {
    const g = m.get(l.group) ?? { cleared: 0, levels: 0, firstNo: l.no, lastNo: l.no };
    g.levels++; if (l.cleared) g.cleared++; g.lastNo = l.no;
    m.set(l.group, g);
  }
  return m;
});
const isFirstLevel = (l: Level) => l.no === levels.value[0]?.no;
const isLastLevel = (l: Level) => l.no === levels.value[levels.value.length - 1]?.no;
const pct = (a: number, b: number) => (b ? (a * 100) / b : 0);
const viewingLabel = computed(() => user.value?.label ?? (user.value ? `ID ${user.value.userId}` : ''));
</script>

<template>
  <div class="space-y-6">
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-6">
      <div class="flex flex-wrap items-start justify-between gap-3 mb-2">
        <h2 class="text-xl font-bold text-slate-900 dark:text-white">{{ LINE_LABEL[line] }}ロードマップ</h2>
        <div class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
          <span v-if="computedAt">推定: {{ formatJstDateTime(computedAt) }}</span>
          <span v-if="serverRefreshing" class="text-blue-600 dark:text-blue-400">再計算中…</span>
          <button class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50" :disabled="serverRefreshing" @click="load(true)">再計算</button>
        </div>
      </div>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-5">
        ANOTHER・LEGGENDARIA 全譜面を「{{ LINE_LABEL[line] }} を取るのに必要な実力」の順に並べ、難度 0.02 刻みのレベルに分けています（0.2 ごとに見出し。難度は難易度表と同じ目盛り）。
        各レベルの譜面の 3 分の 2 以上で {{ LINE_LABEL[line] }} を取るとそのレベルを達成し、達成したレベルのうち一番高いものがあなたの {{ LINE_LABEL[line] }} レベルです（自己歴代ベストで判定）。
        推定の誤差は ±0.03 前後あるので、隣り合う数レベルの前後関係は目安です。
      </p>

      <div v-if="isLoading" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-sm text-slate-500">{{ serverRefreshing ? 'サーバーで推定中です（1〜2 分かかります）…' : '読み込み中...' }}</span>
      </div>
      <div v-else-if="errorMsg && !charts.length" class="text-red-500 text-sm py-4">{{ errorMsg }}</div>

      <template v-else>
        <p v-if="errorMsg" class="text-red-500 text-xs mb-2">{{ errorMsg }}（前回の推定を表示中）</p>

        <!-- 設定 -->
        <div class="flex flex-wrap items-center gap-3 mb-5 text-sm">
          <div class="inline-flex rounded border border-slate-300 dark:border-slate-600 overflow-hidden text-xs">
            <button v-for="l in LINES" :key="l" class="px-3 py-1.5"
              :class="line === l ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'text-slate-600 dark:text-slate-300'"
              @click="line = l">{{ LINE_LABEL[l] }}</button>
          </div>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300">
            対象
            <select v-model="targetKey" class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900">
              <option v-for="t in TARGETS" :key="t.key" :value="t.key">{{ t.label }}</option>
            </select>
          </label>
          <!-- ユーザー名検索（管理者が他ユーザーを表示する） -->
          <div class="relative inline-flex items-center gap-1 text-slate-600 dark:text-slate-300">
            <label for="roadmap-user" class="shrink-0">ユーザー</label>
            <input
              id="roadmap-user"
              v-model="userQuery"
              type="text"
              autocomplete="off"
              placeholder="ユーザー名で検索（空欄 = 自分）"
              class="w-60 px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900"
              role="combobox"
              :aria-expanded="suggestOpen"
              aria-controls="roadmap-user-list"
              @input="onUserInput"
              @keydown="onUserKeydown"
              @blur="closeSuggestSoon"
            />
            <button v-if="selectedUserId != null" class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 text-xs" @click="resetToSelf">自分に戻す</button>
            <ul
              v-if="suggestOpen && suggestions.length"
              id="roadmap-user-list"
              role="listbox"
              class="absolute left-14 top-full mt-1 z-20 w-72 max-h-72 overflow-auto rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 shadow-lg text-sm"
            >
              <li
                v-for="(h, hi) in suggestions"
                :key="h.id"
                role="option"
                :aria-selected="hi === suggestIndex"
                class="px-3 py-1.5 cursor-pointer flex items-center gap-2"
                :class="hi === suggestIndex ? 'bg-blue-50 dark:bg-blue-900/40' : 'hover:bg-slate-50 dark:hover:bg-slate-800'"
                @mousedown.prevent="chooseUser(h)"
              >
                <span class="truncate text-slate-800 dark:text-slate-100">{{ h.displayName ?? '(名前なし)' }}</span>
                <span class="ml-auto font-mono text-xs text-slate-400 shrink-0">{{ h.iidxId }}</span>
              </li>
            </ul>
            <span v-if="suggestOpen && !suggestions.length" class="absolute left-14 top-full mt-1 z-20 px-3 py-1.5 rounded-md border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-900 text-xs text-slate-500 shadow">該当するユーザーがいません</span>
          </div>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300 cursor-pointer">
            <input v-model="onlyOpen" type="checkbox" /> 未達成の譜面だけ表示
          </label>
        </div>

        <!-- 現在地 -->
        <div class="text-xs text-slate-500 dark:text-slate-400 mb-1">{{ viewingLabel }}（{{ target.label }}）</div>
        <div v-if="user && !user.found" class="text-sm text-slate-500 mb-6">このユーザーの ANOTHER / LEGGENDARIA のスコアが見つかりません。</div>
        <div v-else class="grid gap-3 mb-6" style="grid-template-columns: repeat(auto-fit, minmax(200px, 1fr))">
          <button
            v-for="l in LINES"
            :key="l"
            class="text-left rounded-md p-4 border"
            :class="line === l ? 'bg-blue-50 dark:bg-blue-900/20 border-blue-300 dark:border-blue-700' : 'bg-slate-50 dark:bg-slate-900/50 border-transparent hover:border-slate-300 dark:hover:border-slate-600'"
            @click="line = l"
          >
            <div class="text-xs text-slate-500 dark:text-slate-400">{{ LINE_LABEL[l] }} レベル</div>
            <div class="text-2xl font-bold font-mono text-slate-900 dark:text-white">
              Lv.{{ playerLevel(l) }}<span class="text-sm text-slate-400"> / {{ built[l].maxNo }}</span>
            </div>
            <div class="text-xs text-slate-500 dark:text-slate-400">
              達成 {{ built[l].levels.filter((x) => x.cleared).length }} / {{ built[l].levels.length }} レベル
              <template v-if="userTheta(l) != null">・推定実力 {{ userTheta(l)!.toFixed(2) }}</template>
            </div>
          </button>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-4">
            <div class="text-xs text-slate-500 dark:text-slate-400">{{ LINE_LABEL[line] }} 達成譜面（歴代ベスト）</div>
            <div class="text-2xl font-bold font-mono text-slate-900 dark:text-white">{{ totalDone }}<span class="text-sm text-slate-400"> / {{ targetCharts.length }}</span></div>
            <div class="h-1.5 rounded bg-slate-200 dark:bg-slate-700 mt-2 overflow-hidden"><div class="h-full bg-blue-600 dark:bg-blue-400" :style="{ width: `${pct(totalDone, targetCharts.length)}%` }"></div></div>
          </div>
        </div>

        <!-- レベル一覧（0.02 刻み。0.2 ごとに見出し） -->
        <div class="flex items-center gap-3 text-xs text-slate-500 dark:text-slate-400 mb-2">
          <span>{{ LINE_LABEL[line] }}：{{ levels.length }} レベル中 {{ clearedCount }} レベル達成</span>
          <span class="inline-flex items-center gap-1"><span class="inline-block w-2.5 h-2.5 rounded-full bg-blue-600 dark:bg-blue-400"></span>達成</span>
          <span class="inline-flex items-center gap-1"><span class="inline-block w-2.5 h-2.5 rounded-full border-2 border-slate-300 dark:border-slate-600"></span>未達成</span>
        </div>
        <div class="rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
          <template v-for="(l, li) in levels" :key="l.no">
            <!-- 0.2 ごとの見出し -->
            <div v-if="isGroupHead(li)" class="flex flex-wrap items-center gap-x-3 px-3 py-1.5 bg-slate-100 dark:bg-slate-900/60 text-xs border-b border-slate-200 dark:border-slate-700">
              <span class="font-bold font-mono text-slate-700 dark:text-slate-200">難度 {{ (l.group * GROUP_W).toFixed(1) }}〜{{ ((l.group + 1) * GROUP_W).toFixed(1) }}</span>
              <span class="text-slate-500">Lv.{{ groupStats.get(l.group)!.firstNo }}〜{{ groupStats.get(l.group)!.lastNo }}</span>
              <span class="ml-auto font-mono text-slate-600 dark:text-slate-300">{{ groupStats.get(l.group)!.cleared }}/{{ groupStats.get(l.group)!.levels }} レベル達成</span>
            </div>
            <div class="border-b border-slate-100 dark:border-slate-700/60" :class="l.no === myLevel ? 'bg-blue-50/70 dark:bg-blue-900/20' : ''">
              <button class="w-full text-left px-3 py-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs hover:bg-slate-50 dark:hover:bg-slate-700/30" @click="toggleLevel(l.no)">
                <span
                  class="inline-block w-2.5 h-2.5 rounded-full border-2 shrink-0"
                  :class="l.cleared ? 'bg-blue-600 border-blue-600 dark:bg-blue-400 dark:border-blue-400' : 'border-slate-300 dark:border-slate-600'"
                ></span>
                <span class="font-bold font-mono text-slate-900 dark:text-white w-14">Lv.{{ l.no }}</span>
                <span class="font-mono text-slate-500 w-24">{{ l.from.toFixed(2) }}〜{{ l.to.toFixed(2) }}</span>
                <span v-if="isFirstLevel(l)" class="font-bold text-blue-700 dark:text-blue-300">初めての {{ LINE_LABEL[line] }}</span>
                <span v-if="isLastLevel(l)" class="font-bold text-blue-700 dark:text-blue-300">全 {{ LINE_LABEL[line] }} の最後</span>
                <span v-if="l.no === myLevel" class="font-bold text-blue-700 dark:text-blue-300">あなたのレベル</span>
                <span class="text-slate-500">{{ l.charts.length }}譜面</span>
                <span class="text-slate-500" :title="`推定実力 ${l.to.toFixed(2)} 以上の人の割合`">到達者 {{ l.reachShare.toFixed(0) }}%</span>
                <span class="ml-auto flex items-center gap-2">
                  <span v-if="l.cleared" class="font-bold text-blue-700 dark:text-blue-300">達成</span>
                  <span v-else class="text-slate-500">あと {{ l.need - l.done }} 譜面</span>
                  <span class="w-20 h-1.5 rounded bg-slate-200 dark:bg-slate-700 overflow-hidden relative">
                    <span class="block h-full bg-blue-600 dark:bg-blue-400" :style="{ width: `${pct(l.done, l.charts.length)}%` }"></span>
                    <!-- 達成ライン（3 分の 2） -->
                    <span class="absolute top-0 bottom-0 w-px bg-slate-500 dark:bg-slate-300" :style="{ left: `${pct(l.need, l.charts.length)}%` }"></span>
                  </span>
                  <span class="font-mono text-slate-700 dark:text-slate-300 w-12 text-right">{{ l.done }}/{{ l.charts.length }}</span>
                  <span class="text-slate-400 w-3">{{ expanded.has(l.no) ? '▾' : '▸' }}</span>
                </span>
              </button>
              <div v-if="expanded.has(l.no)" class="px-3 pb-2 pl-8">
                <div v-if="!visibleCharts(l).length" class="text-xs text-slate-500 py-1">このレベルは全て達成済みです</div>
                <div class="grid gap-x-6" style="grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr))">
                  <div v-for="c in visibleCharts(l)" :key="c.i" class="flex items-center gap-2 text-sm py-1 border-b border-slate-100 dark:border-slate-700/50">
                    <span class="w-4 text-center shrink-0" :title="achieved(c) ? '達成済み' : played(c) ? '未達成' : '未プレー'">
                      <svg v-if="achieved(c)" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4 text-blue-600 dark:text-blue-400"><path fill-rule="evenodd" d="M16.7 5.3a1 1 0 010 1.4l-8 8a1 1 0 01-1.4 0l-4-4a1 1 0 111.4-1.4L8 12.6l7.3-7.3a1 1 0 011.4 0z" clip-rule="evenodd" /></svg>
                      <span v-else-if="played(c)" class="inline-block w-2 h-2 rounded-full border border-slate-400"></span>
                      <span v-else class="inline-block w-2 h-0.5 bg-slate-300 dark:bg-slate-600 align-middle"></span>
                    </span>
                    <span class="font-mono text-xs text-slate-400 w-8 shrink-0">☆{{ c.level }}</span>
                    <span class="truncate" :class="achieved(c) ? 'text-slate-400 dark:text-slate-500' : 'text-slate-800 dark:text-slate-200'">{{ c.title }}</span>
                    <span v-if="c.difficultyName === 'LEGGENDARIA'" class="text-[10px] font-bold text-orange-500 shrink-0">[L]</span>
                    <span class="ml-auto font-mono text-xs text-slate-500 shrink-0" :title="`${LINE_LABEL[line]} 率（${c.playerCount}人中）`">{{ c[line].rate.toFixed(0) }}%</span>
                    <span class="font-mono text-xs text-slate-700 dark:text-slate-300 w-10 text-right shrink-0" title="推定難度">{{ dOf(c).toFixed(2) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>
      </template>
    </div>
  </div>
</template>
