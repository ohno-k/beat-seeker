<script setup lang="ts">
/**
 * 【Viewの役割】 スコアロードマップ（AAA / MAX- 統合）。管理者（ID18）専用の検証ページ。
 *
 * ANOTHER / LEGGENDARIA 全譜面（☆1〜12）の「AAA」「MAX-」を 1 つずつの目標とし、全目標を
 * 「達成に必要な実力」の 1 本の目盛り（難易度表と同じ 11.0〜13.1 の目盛り）に並べて、難度 0.02 刻みの
 * レベルに分ける。初めての AAA から全 MAX- までを 1 本の道のりとして見せる。
 *
 * レベルの番号と達成判定（2026-09-23 ユーザー指定・推奨案）:
 *  - 番号: 全目標のうち目標がある 0.02 枠を易しい順に Lv.1 から連番。表示フィルタ（☆・AAA/MAX-）では変わらない。
 *  - 達成: そのレベルの全目標のうち「プレー済み（その譜面を遊んだことがある）」目標の 3 分の 2 以上を達成。
 *    ただしプレー済みが max(2, ⌈n/3⌉) 件（n = そのレベルの目標数、n 以下）に満たないレベルは判定しない
 *    （1 曲だけ遊んで高いレベルを取れないように）。判定は常に全目標で行い、表示フィルタの影響を受けない。
 *  - その人のレベル = 達成しているレベルのうち一番高い番号（下のレベルを飛ばしていても構わない）。
 *  - 完全制覇: そのレベルの全目標（未プレー含む）を達成。「達成」とは別の状態（金の星）で見せる。
 *  - 2026-09-23 の本番データ試算（200 プレイ以上の 1,046 人）: 推定実力とのずれは中央値 0、
 *    ☆11/12 中心の人でも −2（未プレーを分母に入れていた旧ルールでは −35）。
 *
 * データ: API `/api/scores/score-roadmap`（管理者専用）。サーバーは「土台（全目標の難度・全員の実力分布。
 * 3 時間ごとのバッチ、DB 保存）+ 差分（表示ユーザー 1 人分の最新スコアをその場で反映）」で返す。
 *  - charts[].aaa.d / maxMinus.d = 各目標の難度。model.thetas = 全プレイヤーの推定実力（昇順）。
 *  - user.plays = [譜面 i, 桶]（理論値の 1/180 単位。160 以上 = AAA、170 以上 = MAX-）。
 *  - 管理者は userId を渡して他ユーザーを表示できる（`/api/scores/score-roadmap/user-suggest` で部分一致サジェスト）。
 */
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue';
import { useAuth } from '../composables/useAuth';
import { formatJstDateTime } from '../utils/jstTime';
import ScoreRoadmapRankingModal from '../components/ScoreRoadmapRankingModal.vue';

const { authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

type LineKey = 'aaa' | 'maxMinus';
interface ChartLine { d: number; se: number; n: number; rate: number }
interface Chart { i: number; title: string; difficultyName: string; level: number; playerCount: number; aaa: ChartLine; maxMinus: ChartLine }
interface Model { kAaa: number; kMaxMinus: number; thetas: number[] }
interface UserInfo { userId: number; label: string | null; found: boolean; theta?: number; plays?: [number, number][] }
/** 目標 = 譜面 × ライン。 */
interface Target { key: string; c: Chart; line: LineKey; d: number }

const LINES: LineKey[] = ['aaa', 'maxMinus'];
const LINE_BUCKET: Record<LineKey, number> = { aaa: 160, maxMinus: 170 };
const LINE_LABEL: Record<LineKey, string> = { aaa: 'AAA', maxMinus: 'MAX-' };

/**
 * 1 レベルの幅（難度の目盛りで 0.02）。推定の標準誤差（約 0.03）と同程度なので、
 * 隣り合う数レベルの前後は誤差の範囲。0.2 ごと（10 枠ごと）に見出しを入れて大づかみにも読めるようにする。
 */
const LEVEL_W = 0.02;
const GROUP_W = 0.2;

const isLoading = ref(true);
const errorMsg = ref('');
const serverRefreshing = ref(false);
const computedAt = ref<string | null>(null);
const charts = ref<Chart[]>([]);
const model = ref<Model | null>(null);
const user = ref<UserInfo | null>(null);
let pollTimer: ReturnType<typeof setTimeout> | null = null;

/** 表示中のユーザー ID（null = 自分）。 */
const selectedUserId = ref<number | null>(null);

/** 【関数の役割】 API を叩く。土台がまだ無ければ 5 秒おきにポーリングする。 */
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
      model.value = data.model;
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
/** ランキングモーダルの表示フラグ。 */
const showRanking = ref(false);
/** 【関数の役割】 ランキングの行から選んだユーザーのロードマップを表示する。 */
function selectFromRanking(userId: number, displayName: string | null) {
  chooseUser({ id: userId, displayName, iidxId: null });
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

// ===== 表示フィルタ（判定には影響しない） =====
const TARGETS = [
  { key: '1-12', label: '☆1〜12', min: 1, max: 12 },
  { key: '8-12', label: '☆8〜12', min: 8, max: 12 },
  { key: '10-12', label: '☆10〜12', min: 10, max: 12 },
  { key: '11-12', label: '☆11〜12', min: 11, max: 12 },
  { key: '12', label: '☆12のみ', min: 12, max: 12 },
];
const targetKey = ref('1-12');
const range = computed(() => TARGETS.find((t) => t.key === targetKey.value) ?? TARGETS[0]);
const LINE_FILTERS = [
  { key: 'both', label: 'AAA と MAX-' },
  { key: 'aaa', label: 'AAA だけ' },
  { key: 'maxMinus', label: 'MAX- だけ' },
] as const;
const lineFilter = ref<'both' | LineKey>('both');
const onlyOpen = ref(false);
const isShown = (t: Target) =>
  t.c.level >= range.value.min && t.c.level <= range.value.max
  && (lineFilter.value === 'both' || lineFilter.value === t.line)
  && (!onlyOpen.value || !achieved(t));

// ===== 目標と達成状況 =====
const userBucket = computed(() => new Map<number, number>(user.value?.plays ?? []));
const played = (t: Target) => userBucket.value.has(t.c.i);
const achieved = (t: Target) => (userBucket.value.get(t.c.i) ?? -1) >= LINE_BUCKET[t.line];
const targets = computed<Target[]>(() => charts.value.flatMap((c) =>
  LINES.map((line) => ({ key: `${c.i}:${line}`, c, line, d: c[line].d }))));
/** 曲名の表記: ANOTHER は表記なし、LEGGENDARIA は末尾に [L]（難易度表と同じ書き方）。 */
const chartName = (c: Chart) => (c.difficultyName === 'LEGGENDARIA' ? `${c.title}[L]` : c.title);

/** 【関数の役割】 実力 x 以上の人の割合（%）。 */
function shareAtLeast(x: number): number {
  const th = model.value?.thetas ?? [];
  if (!th.length) return 0;
  let lo = 0, hi = th.length;
  while (lo < hi) { const m = (lo + hi) >> 1; if (th[m] < x) lo = m + 1; else hi = m; }
  return ((th.length - lo) * 100) / th.length;
}

/** 判定に必要な最低プレー数。 */
const minPlayedFor = (n: number) => Math.min(n, Math.max(2, Math.ceil(n / 3)));

interface Level {
  no: number; from: number; to: number; items: Target[];
  played: number; done: number; minPlayed: number; cleared: boolean;
  /** 完全制覇: そのレベルの全目標（未プレーも含む）を達成。「達成」とは別の状態として見せる。 */
  complete: boolean;
  remaining: number; reachShare: number; group: number;
}
/**
 * 【computed の役割】 全目標を 0.02 枠に分け、目標のある枠を易しい順に Lv.1 から連番にする。
 * 番号と判定は常に全目標で決める（表示フィルタで変わらない）。
 */
const levels = computed<Level[]>(() => {
  const bySlot = new Map<number, Target[]>();
  for (const t of targets.value) {
    const s = Math.floor(t.d / LEVEL_W + 1e-9);
    if (!bySlot.has(s)) bySlot.set(s, []);
    bySlot.get(s)!.push(t);
  }
  return [...bySlot.entries()].sort((a, b) => a[0] - b[0]).map(([slot, items], idx) => {
    items.sort((a, b) => a.d - b.d);
    const n = items.length;
    const p = items.filter(played).length;
    const x = items.filter(achieved).length;
    const minPlayed = minPlayedFor(n);
    const cleared = p >= minPlayed && x * 3 >= p * 2;
    // あと何件達成すればよいか（未達成のプレー済み or 未プレーの目標を達成した場合）
    const need = Math.ceil((Math.max(p, minPlayed) * 2) / 3);
    const from = slot * LEVEL_W;
    return {
      no: idx + 1, from, to: from + LEVEL_W, items, played: p, done: x, minPlayed, cleared,
      complete: x === n, remaining: Math.max(0, need - x), reachShare: shareAtLeast(from + LEVEL_W), group: Math.floor(from / GROUP_W + 1e-9),
    };
  });
});
const maxLevelNo = computed(() => levels.value.length);
/** その人のレベル = 達成しているレベルのうち一番高い番号。 */
const myLevel = computed(() => {
  let best = 0;
  for (const l of levels.value) if (l.cleared) best = l.no;
  return best;
});
const clearedCount = computed(() => levels.value.filter((l) => l.cleared).length);
const completeCount = computed(() => levels.value.filter((l) => l.complete).length);
/** 表示フィルタの範囲（☆）に入る譜面での、ライン別の達成数。 */
const doneCount = computed(() => {
  const inRange = charts.value.filter((c) => c.level >= range.value.min && c.level <= range.value.max);
  const count = (line: LineKey) => inRange.filter((c) => (userBucket.value.get(c.i) ?? -1) >= LINE_BUCKET[line]).length;
  return { total: inRange.length, aaa: count('aaa'), maxMinus: count('maxMinus') };
});
const theta = computed(() => (user.value?.found ? user.value.theta ?? null : null));

/** 表示するレベル（フィルタで目標が 1 件も残らないレベルは隠す。番号は変えない）。 */
const shownLevels = computed(() => levels.value
  .map((l) => ({ l, shown: l.items.filter(isShown) }))
  .filter((x) => x.shown.length > 0));

// ===== 展開状態 =====
const expanded = ref(new Set<number>());
watch([myLevel, () => charts.value.length], () => {
  // 自分のレベルより上で、まだ達成していない最初のレベル（次の目標）を開いておく
  const next = levels.value.find((l) => l.no > myLevel.value && !l.cleared);
  expanded.value = new Set(next ? [next.no] : []);
}, { immediate: true });
function toggleLevel(no: number) {
  const next = new Set(expanded.value);
  if (next.has(no)) next.delete(no); else next.add(no);
  expanded.value = next;
}

/** 【関数の役割】 0.2 ごとの見出しを、そのグループで最初に表示されるレベルの前にだけ出す。 */
const isGroupHead = (i: number) => i === 0 || shownLevels.value[i - 1].l.group !== shownLevels.value[i].l.group;
const groupStats = computed(() => {
  const m = new Map<number, { cleared: number; complete: number; levels: number; firstNo: number; lastNo: number }>();
  for (const l of levels.value) {
    const g = m.get(l.group) ?? { cleared: 0, complete: 0, levels: 0, firstNo: l.no, lastNo: l.no };
    g.levels++; if (l.cleared) g.cleared++; if (l.complete) g.complete++; g.lastNo = l.no;
    m.set(l.group, g);
  }
  return m;
});
const pct = (a: number, b: number) => (b ? (a * 100) / b : 0);
const viewingLabel = computed(() => user.value?.label ?? (user.value ? `ID ${user.value.userId}` : ''));
</script>

<template>
  <div class="space-y-6">
    <div class="bg-white dark:bg-slate-800 rounded-md border border-slate-200 dark:border-slate-700 p-6">
      <div class="flex flex-wrap items-start justify-between gap-3 mb-2">
        <div class="flex items-center gap-3">
          <h2 class="text-xl font-bold text-slate-900 dark:text-white">スコアロードマップ（AAA・MAX-）</h2>
          <button
            class="px-3 py-1 text-xs font-bold rounded border border-indigo-300 dark:border-indigo-700 text-indigo-700 dark:text-indigo-300 hover:bg-indigo-50 dark:hover:bg-indigo-900/30"
            @click="showRanking = true"
          >ランキング</button>
        </div>
        <div class="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
          <span v-if="computedAt" title="全目標の難度と全プレイヤーの分布は 3 時間ごとに作り直します。表示中のユーザーの達成状況は最新のスコアです">難度の集計: {{ formatJstDateTime(computedAt) }}（3 時間ごと）</span>
          <span v-if="serverRefreshing" class="text-blue-600 dark:text-blue-400">集計し直し中…</span>
          <button class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 disabled:opacity-50" :disabled="serverRefreshing" @click="load(true)">今すぐ集計し直す</button>
        </div>
      </div>
      <p class="text-sm text-slate-500 dark:text-slate-400 mb-5">
        ANOTHER・LEGGENDARIA 全譜面の「AAA」と「MAX-」をそれぞれ 1 つの目標として、達成に必要な実力の順に並べ、難度 0.02 刻みのレベルに分けています（難度は難易度表と同じ目盛り。0.2 ごとに見出し）。
        各レベルで、遊んだことのある譜面の目標の 3 分の 2 以上を達成するとそのレベルを達成（遊んだ目標がレベルの 3 分の 1 かつ 2 件以上必要）。達成したレベルのうち一番高いものがあなたのレベルです（自己歴代ベストで判定）。
        レベルの番号と判定は常に全譜面で決まり、下の絞り込みは表示だけを変えます。
      </p>

      <div v-if="isLoading" class="flex items-center justify-center py-12">
        <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
        <span class="ml-3 text-sm text-slate-500">{{ serverRefreshing ? '初回の集計中です（1〜2 分かかります。次からは待たずに表示されます）…' : '読み込み中...' }}</span>
      </div>
      <div v-else-if="errorMsg && !charts.length" class="text-red-500 text-sm py-4">{{ errorMsg }}</div>

      <template v-else>
        <p v-if="errorMsg" class="text-red-500 text-xs mb-2">{{ errorMsg }}（前回の集計を表示中）</p>

        <!-- 表示フィルタ・ユーザー -->
        <div class="flex flex-wrap items-center gap-3 mb-5 text-sm">
          <div class="inline-flex rounded border border-slate-300 dark:border-slate-600 overflow-hidden text-xs">
            <button v-for="f in LINE_FILTERS" :key="f.key" class="px-3 py-1.5"
              :class="lineFilter === f.key ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'text-slate-600 dark:text-slate-300'"
              @click="lineFilter = f.key">{{ f.label }}</button>
          </div>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300">
            表示
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
            <input v-model="onlyOpen" type="checkbox" /> 未達成の目標だけ表示
          </label>
        </div>

        <!-- 現在地 -->
        <div class="text-xs text-slate-500 dark:text-slate-400 mb-1">{{ viewingLabel }}</div>
        <div v-if="user && !user.found" class="text-sm text-slate-500 mb-6">このユーザーの ANOTHER / LEGGENDARIA のスコアが見つかりません。</div>
        <div v-else class="grid gap-3 mb-6" style="grid-template-columns: repeat(auto-fit, minmax(200px, 1fr))">
          <div class="rounded-md p-4 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800">
            <div class="text-xs text-slate-500 dark:text-slate-400">ロードマップ レベル</div>
            <div class="text-2xl font-bold font-mono text-slate-900 dark:text-white">Lv.{{ myLevel }}<span class="text-sm text-slate-400"> / {{ maxLevelNo }}</span></div>
            <div class="text-xs text-slate-500 dark:text-slate-400">達成 {{ clearedCount }} / {{ maxLevelNo }} レベル（うち完全制覇 {{ completeCount }}）</div>
          </div>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-4">
            <div class="text-xs text-slate-500 dark:text-slate-400">推定実力（参考）</div>
            <div class="text-2xl font-bold font-mono text-slate-900 dark:text-white">{{ theta != null ? theta.toFixed(2) : '—' }}</div>
            <div v-if="theta != null" class="text-xs text-slate-500 dark:text-slate-400">全 {{ model?.thetas.length }} 人中 上位 {{ shareAtLeast(theta).toFixed(1) }}%</div>
          </div>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-4">
            <div class="text-xs text-slate-500 dark:text-slate-400">達成譜面（{{ range.label }}・歴代ベスト）</div>
            <div class="font-mono text-slate-900 dark:text-white">
              <span class="text-xs text-slate-500">AAA</span> <span class="text-xl font-bold">{{ doneCount.aaa }}</span>
              <span class="text-xs text-slate-500 ml-3">MAX-</span> <span class="text-xl font-bold">{{ doneCount.maxMinus }}</span>
              <span class="text-sm text-slate-400"> / {{ doneCount.total }}</span>
            </div>
            <div class="h-1.5 rounded bg-slate-200 dark:bg-slate-700 mt-2 overflow-hidden relative">
              <div class="absolute inset-y-0 left-0 bg-blue-300 dark:bg-blue-700" :style="{ width: `${pct(doneCount.aaa, doneCount.total)}%` }"></div>
              <div class="absolute inset-y-0 left-0 bg-blue-600 dark:bg-blue-400" :style="{ width: `${pct(doneCount.maxMinus, doneCount.total)}%` }"></div>
            </div>
          </div>
        </div>

        <!-- 凡例 -->
        <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500 dark:text-slate-400 mb-2">
          <span>{{ maxLevelNo }} レベル中 {{ clearedCount }} レベル達成・{{ completeCount }} レベル完全制覇</span>
          <span class="inline-flex items-center gap-1">
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-3.5 h-3.5 text-amber-500 dark:text-amber-400"><path d="M9.05 2.93c.3-.92 1.6-.92 1.9 0l1.52 4.67a1 1 0 00.95.69h4.91c.97 0 1.37 1.24.59 1.81l-3.98 2.89a1 1 0 00-.36 1.12l1.52 4.67c.3.92-.76 1.69-1.54 1.12l-3.97-2.89a1 1 0 00-1.18 0l-3.97 2.89c-.78.57-1.84-.2-1.54-1.12l1.52-4.67a1 1 0 00-.36-1.12L1.08 10.1c-.78-.57-.38-1.81.59-1.81h4.91a1 1 0 00.95-.69l1.52-4.67z" /></svg>完全制覇（全目標を達成）
          </span>
          <span class="inline-flex items-center gap-1"><span class="inline-block w-2.5 h-2.5 rounded-full bg-blue-600 dark:bg-blue-400"></span>達成（プレー済みの 3 分の 2）</span>
          <span class="inline-flex items-center gap-1"><span class="inline-block w-2.5 h-2.5 rounded-full border-2 border-slate-300 dark:border-slate-600"></span>未達成</span>
          <span>曲名の表記: 表記なし = ANOTHER、[L] = LEGGENDARIA</span>
        </div>

        <!-- レベル一覧（0.02 刻み。0.2 ごとに見出し） -->
        <div class="rounded-md border border-slate-200 dark:border-slate-700 overflow-hidden">
          <template v-for="({ l, shown }, li) in shownLevels" :key="l.no">
            <div v-if="isGroupHead(li)" class="flex flex-wrap items-center gap-x-3 px-3 py-1.5 bg-slate-100 dark:bg-slate-900/60 text-xs border-b border-slate-200 dark:border-slate-700">
              <span class="font-bold font-mono text-slate-700 dark:text-slate-200">難度 {{ (l.group * GROUP_W).toFixed(1) }}〜{{ ((l.group + 1) * GROUP_W).toFixed(1) }}</span>
              <span class="text-slate-500">Lv.{{ groupStats.get(l.group)!.firstNo }}〜{{ groupStats.get(l.group)!.lastNo }}</span>
              <span class="ml-auto font-mono text-slate-600 dark:text-slate-300">
                {{ groupStats.get(l.group)!.cleared }}/{{ groupStats.get(l.group)!.levels }} レベル達成
                <template v-if="groupStats.get(l.group)!.complete">・制覇 {{ groupStats.get(l.group)!.complete }}</template>
              </span>
            </div>
            <div
              class="border-b border-slate-100 dark:border-slate-700/60"
              :class="l.no === myLevel ? 'bg-blue-50/70 dark:bg-blue-900/20' : l.complete ? 'bg-amber-50/60 dark:bg-amber-900/10' : ''"
            >
              <button class="w-full text-left px-3 py-1.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-xs hover:bg-slate-50 dark:hover:bg-slate-700/30" @click="toggleLevel(l.no)">
                <!-- 状態: 完全制覇 = 金の星 / 達成 = 塗りの丸 / 未達成 = 白抜きの丸 -->
                <svg v-if="l.complete" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-3.5 h-3.5 shrink-0 text-amber-500 dark:text-amber-400" aria-label="完全制覇"><path d="M9.05 2.93c.3-.92 1.6-.92 1.9 0l1.52 4.67a1 1 0 00.95.69h4.91c.97 0 1.37 1.24.59 1.81l-3.98 2.89a1 1 0 00-.36 1.12l1.52 4.67c.3.92-.76 1.69-1.54 1.12l-3.97-2.89a1 1 0 00-1.18 0l-3.97 2.89c-.78.57-1.84-.2-1.54-1.12l1.52-4.67a1 1 0 00-.36-1.12L1.08 10.1c-.78-.57-.38-1.81.59-1.81h4.91a1 1 0 00.95-.69l1.52-4.67z" /></svg>
                <span
                  v-else
                  class="inline-block w-2.5 h-2.5 mx-0.5 rounded-full border-2 shrink-0"
                  :class="l.cleared ? 'bg-blue-600 border-blue-600 dark:bg-blue-400 dark:border-blue-400' : 'border-slate-300 dark:border-slate-600'"
                  :aria-label="l.cleared ? 'レベル達成' : '未達成'"
                ></span>
                <span class="font-bold font-mono text-slate-900 dark:text-white w-14">Lv.{{ l.no }}</span>
                <span class="font-mono text-slate-500 w-24">{{ l.from.toFixed(2) }}〜{{ l.to.toFixed(2) }}</span>
                <span v-if="l.no === 1" class="font-bold text-blue-700 dark:text-blue-300">スタート</span>
                <span v-if="l.no === maxLevelNo" class="font-bold text-blue-700 dark:text-blue-300">最終レベル</span>
                <span v-if="l.no === myLevel" class="font-bold text-blue-700 dark:text-blue-300">あなたのレベル</span>
                <span class="text-slate-500">{{ l.items.length }}目標</span>
                <span class="text-slate-500" :title="`推定実力 ${l.to.toFixed(2)} 以上の人の割合`">到達者 {{ l.reachShare.toFixed(0) }}%</span>
                <span class="ml-auto flex items-center gap-3">
                  <span class="text-slate-500" title="遊んだことのある譜面の目標数 / レベルの目標数">プレー済み {{ l.played }}/{{ l.items.length }}</span>
                  <span class="font-mono text-slate-700 dark:text-slate-300" title="達成した目標数 / プレー済みの目標数">達成 {{ l.done }}/{{ l.played }}</span>
                  <span v-if="l.complete" class="font-bold text-amber-600 dark:text-amber-400 w-20 text-right">完全制覇</span>
                  <span v-else-if="l.cleared" class="font-bold text-blue-700 dark:text-blue-300 w-20 text-right">レベル達成</span>
                  <span v-else class="text-slate-500 w-20 text-right">あと {{ l.remaining }} 件</span>
                  <span class="text-slate-400 w-3">{{ expanded.has(l.no) ? '▾' : '▸' }}</span>
                </span>
              </button>
              <div v-if="expanded.has(l.no)" class="px-3 pb-2 pl-8">
                <div v-if="l.played < l.minPlayed" class="text-xs text-slate-500 py-1">このレベルは、目標を {{ l.minPlayed }} 件以上プレーすると判定されます（現在 {{ l.played }} 件）。</div>
                <div class="grid gap-x-6" style="grid-template-columns: repeat(auto-fit, minmax(min(340px, 100%), 1fr))">
                  <div v-for="t in shown" :key="t.key" class="flex items-center gap-2 text-sm py-1 border-b border-slate-100 dark:border-slate-700/50">
                    <span class="w-4 text-center shrink-0" :title="achieved(t) ? '達成済み' : played(t) ? '未達成' : '未プレー'">
                      <svg v-if="achieved(t)" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20" fill="currentColor" class="w-4 h-4 text-blue-600 dark:text-blue-400"><path fill-rule="evenodd" d="M16.7 5.3a1 1 0 010 1.4l-8 8a1 1 0 01-1.4 0l-4-4a1 1 0 111.4-1.4L8 12.6l7.3-7.3a1 1 0 011.4 0z" clip-rule="evenodd" /></svg>
                      <span v-else-if="played(t)" class="inline-block w-2 h-2 rounded-full border border-slate-400"></span>
                      <span v-else class="inline-block w-2 h-0.5 bg-slate-300 dark:bg-slate-600 align-middle"></span>
                    </span>
                    <span
                      class="text-[10px] font-bold font-mono px-1.5 rounded shrink-0 w-11 text-center"
                      :class="t.line === 'maxMinus' ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-200'"
                    >{{ LINE_LABEL[t.line] }}</span>
                    <span class="font-mono text-xs text-slate-400 w-8 shrink-0">☆{{ t.c.level }}</span>
                    <span class="truncate" :class="achieved(t) ? 'text-slate-400 dark:text-slate-500' : 'text-slate-800 dark:text-slate-200'">{{ chartName(t.c) }}</span>
                    <span class="ml-auto font-mono text-xs text-slate-500 shrink-0" :title="`${LINE_LABEL[t.line]} 率（${t.c.playerCount}人中）`">{{ t.c[t.line].rate.toFixed(0) }}%</span>
                    <span class="font-mono text-xs text-slate-700 dark:text-slate-300 w-10 text-right shrink-0" title="推定難度">{{ t.d.toFixed(2) }}</span>
                  </div>
                </div>
              </div>
            </div>
          </template>
        </div>
      </template>
    </div>

    <ScoreRoadmapRankingModal
      v-if="showRanking"
      :highlight-user-id="user?.userId ?? null"
      @close="showRanking = false"
      @select="selectFromRanking"
    />
  </div>
</template>
