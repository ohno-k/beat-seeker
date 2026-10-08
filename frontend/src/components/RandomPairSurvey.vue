<script setup lang="ts">
/**
 * 【コンポーネントの役割】 RANDOM の 2 つの並びを流れる譜面で見比べて「どっちが押しやすいか」を答えるアンケート
 * （サイドバーの「配置アンケート」から開くモーダル RandomSurveyModal の中身。ログイン中のユーザーなら誰でも答えられる）。
 *
 * 答えは当たり配置ランキングの減点の形ごとの係数を学習する正解データになる（scripts/fit-random-weights.mts）。
 * - 譜面は当たり配置ランキングの summary.json から、選んだレベル帯の中で無作為に選ぶ
 * - 2 つの並びと見せる区間は utils/randomPairQuestion.ts（Web Worker で計算）。次の問題を裏で先に作っておく
 * - 区間を utils/chartSnapshot.ts で緑数字 {@link GREEN} 相当（一定速度）で降らせ、左右同時に繰り返し再生する
 * - 答えは 1 問ごとに POST /api/random-pairs。直前の 1 問は取り消せる
 * - 管理者だけ: 並びの番号と今の評価の表示、今の評価との一致、学習用データの書き出し
 *   （一般のユーザーには答えに影響しないよう出さない）
 *
 * 操作: 譜面をクリック（または ← / →）で押しやすい方、↓ で同じくらい、S で判断できない（スキップ）、Z で直前の取り消し。
 * キー操作はこのコンポーネントが表示されている間だけ効く（親はモーダルを閉じたら v-if で外す）。
 */
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import { API_BASE, useAuth } from '../composables/useAuth';
import { useAdmin } from '../composables/useAdmin';
import { usePlaySide } from '../composables/usePlaySide';
import { buildChartTimeline, type ChartPlaybackData, type ChartTimeline } from '../utils/chartPlayback';
import { makeFallingChart, greenToVisibleSec, type FallingChart } from '../utils/chartSnapshot';
import type { PairQuestion } from '../utils/randomPairQuestion';

type Choice = 'LEFT' | 'RIGHT' | 'SAME' | 'SKIP';
type LevelGroup = '12' | '11' | 'low';
interface SummaryRow { t: string; d: string; l: number; x: string; g: LevelGroup }
interface Prepared { row: SummaryRow; tl: ChartTimeline; q: PairQuestion }

/** 先に作っておく問題の数 */
const PREFETCH = 2;

const { isAdmin } = useAdmin();
const { authHeaders, user } = useAuth();
const isLoggedIn = computed(() => !!user.value);
const { profileSide } = usePlaySide();
const side = computed<1 | 2>(() => profileSide.value ?? 1);

const levels = ref<Record<LevelGroup, boolean>>({ '12': true, '11': true, low: false });
const measures = ref(4);
const showPatterns = ref(false);

const charts = ref<SummaryRow[]>([]);
const loadError = ref('');
const current = ref<Prepared | null>(null);
const queue: Prepared[] = [];
const preparing = ref(0);
const saving = ref(false);
const message = ref('');
const count = ref<number | null>(null);
const history: { id: number; prepared: Prepared; agreed: boolean | null }[] = [];
const canUndo = ref(false);
/** この画面を開いてからの、今の評価との一致（同じくらい・スキップと、今の評価が同点の問題は除く） */
const agree = ref({ n: 0, hit: 0 });
let shownAt = 0;
/** 条件を変えたら増やし、古い条件で作った問題を捨てる */
let generation = 0;
/** 譜面の一覧を読み込んで出題を始めたか（{@link start}） */
let started = false;

// ── Web Worker ──────────────────────────────────────────
let worker: Worker | null = null;
let nextId = 0;
const pending = new Map<number, (r: { question?: PairQuestion | null; error?: string }) => void>();
function getWorker(): Worker {
  if (!worker) {
    worker = new Worker(new URL('../workers/randomPairWorker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent<{ id: number; question?: PairQuestion | null; error?: string }>) => {
      pending.get(e.data.id)?.(e.data);
      pending.delete(e.data.id);
    };
  }
  return worker;
}
function askWorker(data: ChartPlaybackData): Promise<PairQuestion | null> {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, r => (r.error ? reject(new Error(r.error)) : resolve(r.question ?? null)));
    getWorker().postMessage({ id, data, side: side.value, measures: measures.value });
  });
}

// ── 問題を作る ──────────────────────────────────────────
const pool = computed(() => charts.value.filter(c => levels.value[c.g]));

async function prepareOne(gen: number): Promise<Prepared | null> {
  for (let attempt = 0; attempt < 5; attempt++) {
    const list = pool.value;
    if (list.length === 0) return null;
    const row = list[Math.floor(Math.random() * list.length)];
    try {
      const res = await fetch(`${API_BASE}/api/analysis/chart-playback?textage=${encodeURIComponent(row.x)}`);
      if (!res.ok) continue;
      const data: ChartPlaybackData = await res.json();
      const q = await askWorker(data);
      if (gen !== generation) return null;
      if (q) return { row, tl: buildChartTimeline(data), q };
    } catch {
      // 取れない譜面は飛ばして別の譜面を選ぶ
    }
  }
  return null;
}

/** 先読みを PREFETCH 個まで満たし、表示中が無ければ出す */
async function fill() {
  const gen = generation;
  while (gen === generation && queue.length + preparing.value < PREFETCH + (current.value ? 0 : 1)) {
    preparing.value++;
    const p = await prepareOne(gen);
    preparing.value--;
    if (gen !== generation) return;
    if (!p) { message.value = '問題を作れませんでした（譜面を取れない・条件に合う譜面が無い）'; return; }
    queue.push(p);
    if (!current.value) show();
  }
}

function show() {
  current.value = queue.shift() ?? null;
  shownAt = performance.now();
  void fill();
}

function restart() {
  if (!started || charts.value.length === 0) return;
  generation++;
  queue.length = 0;
  current.value = null;
  message.value = '';
  void fill();
}
watch([levels, measures, side], restart, { deep: true });

// ── 描画（区間を緑数字 GREEN で降らせ、左右同時に繰り返す） ──
/** 緑数字（表示時間 = 緑数字 / 600 秒） */
const GREEN = 270;
const VISIBLE_SEC = greenToVisibleSec(GREEN);
/** 1 周の終わりに空ける秒数（次の周の頭と見分けやすく） */
const LOOP_GAP = 0.6;
const leftCanvas = ref<HTMLCanvasElement | null>(null);
const rightCanvas = ref<HTMLCanvasElement | null>(null);
const boardRef = ref<HTMLElement | null>(null);
let charts2: [FallingChart, FallingChart] | null = null;
let raf = 0;
let loopStart = 0;
function layout() {
  const p = current.value;
  if (!p || !charts2 || !leftCanvas.value || !rightCanvas.value || !boardRef.value) return;
  const w = Math.min(230, Math.floor((boardRef.value.clientWidth - 24) / 2) - 8);
  const h = Math.max(280, Math.min(480, Math.round(window.innerHeight * 0.5)));
  charts2[0].resize(leftCanvas.value, w, h);
  charts2[1].resize(rightCanvas.value, w, h);
}
function frame(ts: number) {
  const p = current.value;
  if (!p || !charts2 || !leftCanvas.value || !rightCanvas.value) { raf = 0; return; }
  // 区間の最初のノーツが上端から入ってくるところから、最後のノーツが判定ラインを過ぎて LOOP_GAP 秒まで
  const from = p.q.startTime - VISIBLE_SEC;
  const length = p.q.endTime - from + LOOP_GAP;
  const now = from + (((ts - loopStart) / 1000) % length);
  charts2[0].draw(leftCanvas.value, now, VISIBLE_SEC);
  charts2[1].draw(rightCanvas.value, now, VISIBLE_SEC);
  raf = requestAnimationFrame(frame);
}
function stopLoop() {
  if (raf) cancelAnimationFrame(raf);
  raf = 0;
}
watch(current, async p => {
  stopLoop();
  charts2 = p ? [
    makeFallingChart(p.tl, p.q.patternLeft, side.value, p.q.startTime, p.q.endTime),
    makeFallingChart(p.tl, p.q.patternRight, side.value, p.q.startTime, p.q.endTime),
  ] : null;
  if (!p) return;
  await nextTick();
  layout();
  loopStart = performance.now();
  raf = requestAnimationFrame(frame);
});

// ── 答える ──────────────────────────────────────────────
async function answer(choice: Choice) {
  const p = current.value;
  if (!p || saving.value) return;
  saving.value = true;
  message.value = '';
  try {
    const res = await fetch(`${API_BASE}/api/random-pairs`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({
        textage: p.row.x, title: p.row.t, difficulty: p.row.d, level: p.row.l, side: side.value,
        ...p.q, choice, responseMs: Math.round(performance.now() - shownAt),
      }),
    });
    if (!res.ok) throw new Error(String(res.status));
    const data = await res.json();
    count.value = data.count;
    let agreed: boolean | null = null;
    if ((choice === 'LEFT' || choice === 'RIGHT') && p.q.modelLeft !== p.q.modelRight) {
      agreed = (choice === 'LEFT') === (p.q.modelLeft < p.q.modelRight);
      agree.value = { n: agree.value.n + 1, hit: agree.value.hit + (agreed ? 1 : 0) };
    }
    history.push({ id: data.id, prepared: p, agreed });
    if (history.length > 20) history.shift();
    canUndo.value = true;
    show();
  } catch {
    message.value = '保存できませんでした。もう一度押してください';
  } finally {
    saving.value = false;
  }
}

/** 直前の回答を取り消して、その問題をもう一度出す */
async function undo() {
  if (saving.value || history.length === 0) return;
  const last = history[history.length - 1];
  saving.value = true;
  try {
    const res = await fetch(`${API_BASE}/api/random-pairs/${last.id}`, { method: 'DELETE', headers: authHeaders() });
    if (!res.ok) throw new Error(String(res.status));
    count.value = (await res.json()).count;
    history.pop();
    canUndo.value = history.length > 0;
    if (last.agreed !== null) agree.value = { n: agree.value.n - 1, hit: agree.value.hit - (last.agreed ? 1 : 0) };
    if (current.value) queue.unshift(current.value);
    current.value = last.prepared;
    shownAt = performance.now();
  } catch {
    message.value = '取り消せませんでした';
  } finally {
    saving.value = false;
  }
}

/** 学習用データ（全回答 + オプション投票の集計）を JSON で保存する */
async function exportData() {
  const res = await fetch(`${API_BASE}/api/admin/random-pairs/export`, { headers: authHeaders() });
  if (!res.ok) { message.value = '書き出せませんでした'; return; }
  const blob = new Blob([await res.text()], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `random-pairs-${new Date().toISOString().slice(0, 10)}.json`;
  a.click();
  URL.revokeObjectURL(a.href);
}

function onKey(e: KeyboardEvent) {
  if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement) return;
  const k = e.key.toLowerCase();
  const map: Record<string, () => void> = {
    arrowleft: () => answer('LEFT'), arrowright: () => answer('RIGHT'), arrowdown: () => answer('SAME'),
    s: () => answer('SKIP'), z: () => undo(),
  };
  if (map[k]) { e.preventDefault(); map[k](); }
}

/** 管理者と分かったら（ログイン情報の読み込みを待って）1 回だけ始める */
async function start() {
  if (started || !isLoggedIn.value) return;
  started = true;
  try {
    const res = await fetch(`${import.meta.env.BASE_URL}data/random-ranking/summary.json`);
    if (!res.ok) throw new Error(String(res.status));
    charts.value = (await res.json()).charts;
  } catch {
    loadError.value = '譜面の一覧（当たり配置ランキングのデータ）を読み込めませんでした';
    return;
  }
  fetch(`${API_BASE}/api/random-pairs/stats`, { headers: authHeaders() })
    .then(r => (r.ok ? r.json() : null)).then(d => { if (d) count.value = d.count; }).catch(() => {});
  void fill();
}
watch(isLoggedIn, start);
onMounted(() => {
  window.addEventListener('keydown', onKey);
  window.addEventListener('resize', layout);
  void start();
});
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey);
  window.removeEventListener('resize', layout);
  stopLoop();
  generation++;
  worker?.terminate();
});
</script>

<template>
  <div class="space-y-3">
    <p class="text-xs text-slate-500 dark:text-slate-400">
      同じ区間を 2 通りの RANDOM の並びで流しています（緑数字 {{ GREEN }} 相当で繰り返し再生）。押しやすい方を選んでください。
    </p>

    <div v-if="!isLoggedIn" class="text-center py-10 text-sm text-slate-500 dark:text-slate-400">
      ログインすると答えられます。
    </div>

    <template v-else>
      <!-- 進み具合と条件 -->
      <div class="space-y-2">
        <div class="flex items-center justify-between text-xs font-semibold text-slate-600 dark:text-slate-300">
          <span>あなたの回答 {{ count ?? '…' }} 件</span>
          <span v-if="isAdmin && agree.n > 0" class="text-slate-400">今の評価との一致 {{ agree.hit }} / {{ agree.n }}</span>
        </div>
        <div class="flex flex-wrap items-center gap-x-3 gap-y-2 text-xs text-slate-600 dark:text-slate-300">
          <label v-for="g in (['12', '11', 'low'] as const)" :key="g" class="flex items-center gap-1">
            <input v-model="levels[g]" type="checkbox" />{{ g === 'low' ? '☆10 以下' : `☆${g}` }}
          </label>
          <label class="flex items-center gap-1">
            区間
            <select v-model.number="measures" class="bg-transparent border border-slate-300 dark:border-slate-600 rounded px-1">
              <option :value="2">2 小節</option>
              <option :value="4">4 小節</option>
              <option :value="8">8 小節</option>
            </select>
          </label>
          <template v-if="isAdmin">
            <label class="flex items-center gap-1"><input v-model="showPatterns" type="checkbox" />並びと今の評価</label>
            <button class="ml-auto underline" @click="exportData">回答データを書き出す</button>
          </template>
        </div>
      </div>

      <p v-if="loadError" class="text-sm text-rose-600">{{ loadError }}</p>
      <p v-if="message" class="text-sm text-rose-600">{{ message }}</p>

      <!-- 問題 -->
      <div ref="boardRef" class="space-y-3">
        <div v-if="!current" class="py-24 text-center text-sm text-slate-400">問題を準備しています…</div>
        <template v-else>
          <div class="text-center text-sm font-semibold text-slate-700 dark:text-slate-200">
            {{ current.row.t }}
            <span class="block text-xs text-slate-400">☆{{ current.row.l }} {{ current.row.d === '10' ? 'LEGGENDARIA' : 'ANOTHER' }}
              ・ {{ current.q.startMeasure }}〜{{ current.q.endMeasure }} 小節</span>
          </div>
          <div class="flex justify-center gap-3">
            <button
              v-for="pos in (['LEFT', 'RIGHT'] as const)" :key="pos"
              class="flex flex-col items-center gap-1 rounded-lg p-1 border-2 border-transparent hover:border-indigo-400 focus:outline-none disabled:opacity-60"
              :disabled="saving"
              @click="answer(pos)"
            >
              <canvas :ref="el => { if (pos === 'LEFT') leftCanvas = el as HTMLCanvasElement; else rightCanvas = el as HTMLCanvasElement; }" class="rounded" />
              <span class="text-xs font-semibold text-slate-500">{{ pos === 'LEFT' ? '← 左' : '右 →' }}</span>
              <span v-if="isAdmin && showPatterns" class="text-[11px] font-mono text-slate-400">
                {{ pos === 'LEFT' ? current.q.patternLeft : current.q.patternRight }}
                ・ {{ pos === 'LEFT' ? current.q.modelLeft : current.q.modelRight }}
              </span>
            </button>
          </div>
          <div class="flex flex-wrap justify-center gap-2 text-xs">
            <button class="px-3 py-1.5 rounded border border-slate-300 dark:border-slate-600" :disabled="saving" @click="answer('SAME')">同じくらい（↓）</button>
            <button class="px-3 py-1.5 rounded border border-slate-300 dark:border-slate-600" :disabled="saving" @click="answer('SKIP')">判断できない（S）</button>
            <button class="px-3 py-1.5 rounded border border-slate-300 dark:border-slate-600 disabled:opacity-40" :disabled="saving || !canUndo" @click="undo">直前を取り消す（Z）</button>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
