<script setup lang="ts">
/**
 * 【Viewの役割】 AAA ロードマップ。管理者（ID18）専用の検証ページ。
 *
 * ANOTHER / LEGGENDARIA 全譜面（☆1〜12）を「AAA（または MAX-）を取る難しさ」の 1 本の目盛りに並べ、
 * 0.2 刻みのステージに分けて、初めての AAA から全 AAA までの道のりとして見せる。
 *
 * データ: API `/api/scores/score-roadmap`（管理者専用）。
 *  - 各譜面の d = そのラインを取るのに必要な実力（難易度表と同じ目盛り）。推定はサーバーのラッシュモデル
 *    （ScoreRoadmapService 参照）。実力 θ の人がその譜面でラインに届く確率は σ(k・(θ − d))。
 *  - lines.*.thetas = 全プレイヤーの θ（昇順）。「そのステージに届いている人の割合」に使う。
 *  - user = 表示中ユーザーの θ と歴代ベストの桶（理論値の 1/180 単位。160 以上 = AAA、170 以上 = MAX-）。
 *
 * ステージの意味: ステージ上端の実力 U があれば、そのステージまでの全譜面で 50% 以上の確率でラインに届く。
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

const LINE_BUCKET: Record<LineKey, number> = { aaa: 160, maxMinus: 170 };
const LINE_LABEL: Record<LineKey, string> = { aaa: 'AAA', maxMinus: 'MAX-' };

const isLoading = ref(true);
const errorMsg = ref('');
const serverRefreshing = ref(false);
const computedAt = ref<string | null>(null);
const charts = ref<Chart[]>([]);
const lines = ref<Record<LineKey, LineMeta> | null>(null);
const user = ref<UserInfo | null>(null);
let pollTimer: ReturnType<typeof setTimeout> | null = null;

/** 表示中のユーザー ID（空 = 自分）。 */
const userIdInput = ref<string>('');

/** 【関数の役割】 API を叩く。計算中は 5 秒おきにポーリングする。 */
async function load(force = false) {
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null; }
  const params = new URLSearchParams();
  if (force) params.set('refresh', 'true');
  if (userIdInput.value.trim()) params.set('userId', userIdInput.value.trim());
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
onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer); });

// ===== 表示設定 =====
const line = ref<LineKey>('aaa');
/** 対象にする最低レベル（低レベルを外したいとき用）。 */
const minLevel = ref(1);
const STAGE_LO = 10.4, STAGE_HI = 13.2, STAGE_W = 0.2;

const sig = (x: number) => 1 / (1 + Math.exp(-x));
const k = computed(() => lines.value?.[line.value].k ?? 6);
const userTheta = computed(() => {
  const u = user.value;
  if (!u?.found) return null;
  return line.value === 'aaa' ? u.thetaAaa ?? null : u.thetaMaxMinus ?? null;
});
/** 譜面 i → 表示ユーザーの桶。 */
const userBucket = computed(() => new Map<number, number>(user.value?.plays ?? []));
const achieved = (c: Chart) => (userBucket.value.get(c.i) ?? -1) >= LINE_BUCKET[line.value];
const played = (c: Chart) => userBucket.value.has(c.i);
const dOf = (c: Chart) => c[line.value].d;
const pOf = (c: Chart) => (userTheta.value == null ? null : sig(k.value * (userTheta.value - dOf(c))));

/** 【関数の役割】 θ が昇順配列 thetas の中で x 以上の人の割合（%）。 */
function shareAtLeast(x: number): number {
  const th = lines.value?.[line.value].thetas ?? [];
  if (!th.length) return 0;
  let lo = 0, hi = th.length;
  while (lo < hi) { const m = (lo + hi) >> 1; if (th[m] < x) lo = m + 1; else hi = m; }
  return ((th.length - lo) * 100) / th.length;
}

const targetCharts = computed(() => charts.value.filter((c) => c.level >= minLevel.value));

interface Stage { idx: number; from: number | null; to: number | null; charts: Chart[]; done: number; upper: number; reachShare: number }
const stages = computed<Stage[]>(() => {
  const n = Math.round((STAGE_HI - STAGE_LO) / STAGE_W) + 2;
  const buckets: Chart[][] = Array.from({ length: n }, () => []);
  for (const c of targetCharts.value) {
    const d = dOf(c);
    const s = d < STAGE_LO ? 0 : d >= STAGE_HI ? n - 1 : 1 + Math.floor((d - STAGE_LO) / STAGE_W + 1e-9);
    buckets[s].push(c);
  }
  const out: Stage[] = [];
  buckets.forEach((cs, s) => {
    if (!cs.length) return;
    cs.sort((a, b) => dOf(a) - dOf(b));
    const from = s === 0 ? null : STAGE_LO + (s - 1) * STAGE_W;
    const to = s === n - 1 ? null : STAGE_LO + s * STAGE_W;
    const upper = dOf(cs[cs.length - 1]);
    out.push({ idx: out.length + 1, from, to, charts: cs, done: cs.filter(achieved).length, upper, reachShare: shareAtLeast(upper) });
  });
  return out;
});

/** 表示ユーザーの現在のステージ（θ を含むステージ。θ が無ければ最初の未完了ステージ）。 */
const currentStageIdx = computed(() => {
  const th = userTheta.value;
  const list = stages.value;
  if (!list.length) return 1;
  if (th != null) {
    const hit = list.find((s) => th < s.upper);
    return hit ? hit.idx : list[list.length - 1].idx;
  }
  const firstOpen = list.find((s) => s.done < s.charts.length);
  return firstOpen ? firstOpen.idx : list[list.length - 1].idx;
});

const totalDone = computed(() => targetCharts.value.filter(achieved).length);

/** 次に狙う譜面: 未達成のうち、届く確率が高い順。 */
const nextTargets = computed(() => {
  if (userTheta.value == null) return [];
  return targetCharts.value
    .filter((c) => !achieved(c))
    .map((c) => ({ c, p: pOf(c)! }))
    .sort((a, b) => b.p - a.p)
    .slice(0, 12);
});

// ===== 展開状態 =====
const expanded = ref(new Set<number>());
const onlyOpen = ref(false);
watch([currentStageIdx, line, () => charts.value.length], () => {
  expanded.value = new Set([currentStageIdx.value, currentStageIdx.value + 1]);
}, { immediate: true });
function toggleStage(idx: number) {
  const next = new Set(expanded.value);
  if (next.has(idx)) next.delete(idx); else next.add(idx);
  expanded.value = next;
}
const visibleCharts = (s: Stage) => (onlyOpen.value ? s.charts.filter((c) => !achieved(c)) : s.charts);

function stageTitle(s: Stage, total: number): string {
  if (s.idx === 1) return `初めての ${LINE_LABEL[line.value]}`;
  if (s.idx === total) return `全 ${LINE_LABEL[line.value]} への最終ステージ`;
  return '';
}
function stageRange(s: Stage): string {
  if (s.from == null) return `〜${s.to!.toFixed(1)}`;
  if (s.to == null) return `${s.from.toFixed(1)}〜`;
  return `${s.from.toFixed(1)}〜${s.to.toFixed(1)}`;
}
const pct = (a: number, b: number) => (b ? (a * 100) / b : 0);
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
        ANOTHER・LEGGENDARIA 全譜面を「{{ LINE_LABEL[line] }} を取るのに必要な実力」の順に並べ、0.2 刻みのステージに分けています。
        実力と難しさは難易度表と同じ目盛りで、ステージの右端の実力があれば、そこまでの譜面はどれも五分以上の確率で {{ LINE_LABEL[line] }} に届く目安です（各プレイヤーの自己歴代ベストから推定）。
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
            <button v-for="l in (['aaa', 'maxMinus'] as LineKey[])" :key="l" class="px-3 py-1.5"
              :class="line === l ? 'bg-slate-800 text-white dark:bg-slate-200 dark:text-slate-900' : 'text-slate-600 dark:text-slate-300'"
              @click="line = l">{{ LINE_LABEL[l] }}</button>
          </div>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300">
            対象
            <select v-model.number="minLevel" class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900">
              <option :value="1">☆1〜12</option>
              <option :value="8">☆8〜12</option>
              <option :value="10">☆10〜12</option>
              <option :value="11">☆11〜12</option>
            </select>
          </label>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300">
            ユーザーID
            <input v-model="userIdInput" type="text" inputmode="numeric" placeholder="自分" class="w-20 px-2 py-1 rounded border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-900" @keydown.enter="load()" />
            <button class="px-2 py-1 rounded border border-slate-300 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700" @click="load()">表示</button>
          </label>
          <label class="inline-flex items-center gap-1 text-slate-600 dark:text-slate-300 cursor-pointer">
            <input v-model="onlyOpen" type="checkbox" /> 未達成だけ表示
          </label>
        </div>

        <!-- 現在地 -->
        <div class="grid gap-3 mb-6" style="grid-template-columns: repeat(auto-fit, minmax(220px, 1fr))">
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-4">
            <div class="text-xs text-slate-500 dark:text-slate-400">{{ user?.label ?? (user ? `ID ${user.userId}` : '') }} の推定実力（{{ LINE_LABEL[line] }}）</div>
            <div v-if="userTheta != null" class="text-2xl font-bold font-mono text-slate-900 dark:text-white">{{ userTheta.toFixed(2) }}</div>
            <div v-else class="text-sm text-slate-500 mt-1">スコアが見つかりません</div>
            <div v-if="userTheta != null" class="text-xs text-slate-500 dark:text-slate-400">全 {{ lines?.[line].thetas.length }} 人中 上位 {{ shareAtLeast(userTheta).toFixed(1) }}%</div>
          </div>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-4">
            <div class="text-xs text-slate-500 dark:text-slate-400">{{ LINE_LABEL[line] }} 達成（歴代ベスト）</div>
            <div class="text-2xl font-bold font-mono text-slate-900 dark:text-white">{{ totalDone }}<span class="text-sm text-slate-400"> / {{ targetCharts.length }}</span></div>
            <div class="h-1.5 rounded bg-slate-200 dark:bg-slate-700 mt-2 overflow-hidden"><div class="h-full bg-blue-600 dark:bg-blue-400" :style="{ width: `${pct(totalDone, targetCharts.length)}%` }"></div></div>
          </div>
          <div class="rounded-md bg-slate-50 dark:bg-slate-900/50 p-4">
            <div class="text-xs text-slate-500 dark:text-slate-400">現在のステージ</div>
            <div class="text-2xl font-bold font-mono text-slate-900 dark:text-white">STAGE {{ currentStageIdx }}<span class="text-sm text-slate-400"> / {{ stages.length }}</span></div>
            <div class="text-xs text-slate-500 dark:text-slate-400">推定実力を含むステージ</div>
          </div>
        </div>

        <!-- 次に狙う譜面 -->
        <div v-if="nextTargets.length" class="mb-6">
          <h3 class="text-sm font-bold text-slate-700 dark:text-slate-200 mb-2">次に狙う譜面（未達成のうち届く確率が高い順）</h3>
          <div class="grid gap-x-6 gap-y-1" style="grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr))">
            <div v-for="t in nextTargets" :key="t.c.i" class="flex items-center gap-2 text-sm py-1 border-b border-slate-100 dark:border-slate-700/50">
              <span class="font-mono text-xs text-slate-400 w-8">☆{{ t.c.level }}</span>
              <span class="truncate text-slate-800 dark:text-slate-200">{{ t.c.title }}</span>
              <span v-if="t.c.difficultyName === 'LEGGENDARIA'" class="text-[10px] font-bold text-orange-500">[L]</span>
              <span v-if="!played(t.c)" class="text-[10px] px-1.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-300 shrink-0">未プレー</span>
              <span class="ml-auto font-mono text-xs text-slate-500 shrink-0">{{ dOf(t.c).toFixed(2) }}</span>
              <span class="font-mono font-bold text-slate-800 dark:text-slate-100 w-12 text-right shrink-0">{{ (t.p * 100).toFixed(0) }}%</span>
            </div>
          </div>
        </div>

        <!-- ステージ一覧 -->
        <ol class="relative border-l-2 border-slate-200 dark:border-slate-700 ml-3 space-y-3">
          <li v-for="s in stages" :key="s.idx" class="ml-5">
            <span
              class="absolute -left-[9px] mt-3 w-4 h-4 rounded-full border-2"
              :class="s.done === s.charts.length
                ? 'bg-blue-600 border-blue-600 dark:bg-blue-400 dark:border-blue-400'
                : s.idx === currentStageIdx
                  ? 'bg-white dark:bg-slate-800 border-blue-600 dark:border-blue-400 ring-4 ring-blue-100 dark:ring-blue-900/50'
                  : 'bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-600'"
            ></span>
            <div class="rounded-md border" :class="s.idx === currentStageIdx ? 'border-blue-300 dark:border-blue-700' : 'border-slate-200 dark:border-slate-700'">
              <button class="w-full text-left px-4 py-3 flex flex-wrap items-center gap-x-4 gap-y-1" @click="toggleStage(s.idx)">
                <span class="font-bold text-slate-900 dark:text-white">STAGE {{ s.idx }}</span>
                <span v-if="stageTitle(s, stages.length)" class="text-xs font-bold text-blue-700 dark:text-blue-300">{{ stageTitle(s, stages.length) }}</span>
                <span class="font-mono text-xs text-slate-500">難度 {{ stageRange(s) }}</span>
                <span class="text-xs text-slate-500">{{ s.charts.length }}譜面</span>
                <span class="text-xs text-slate-500" :title="`実力 ${s.upper.toFixed(2)} 以上の人の割合`">到達者 {{ s.reachShare.toFixed(0) }}%</span>
                <span class="ml-auto flex items-center gap-2">
                  <span class="w-28 h-1.5 rounded bg-slate-200 dark:bg-slate-700 overflow-hidden"><span class="block h-full bg-blue-600 dark:bg-blue-400" :style="{ width: `${pct(s.done, s.charts.length)}%` }"></span></span>
                  <span class="font-mono text-xs text-slate-700 dark:text-slate-300 w-20 text-right">{{ s.done }}/{{ s.charts.length }}</span>
                  <span class="text-slate-400 text-xs w-3">{{ expanded.has(s.idx) ? '▾' : '▸' }}</span>
                </span>
              </button>
              <div v-if="expanded.has(s.idx)" class="border-t border-slate-100 dark:border-slate-700 px-4 py-3">
                <div v-if="!visibleCharts(s).length" class="text-xs text-slate-500">このステージは全て達成済みです</div>
                <div class="grid gap-x-6" style="grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr))">
                  <div v-for="c in visibleCharts(s)" :key="c.i" class="flex items-center gap-2 text-sm py-1 border-b border-slate-100 dark:border-slate-700/50">
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
          </li>
        </ol>
      </template>
    </div>
  </div>
</template>
