<script setup lang="ts">
/**
 * 【View の役割】 週次の練習メニュー（管理者専用・検証中）。
 *
 * 「次のティアに上がるために、今週なにを、どこまで、何回やるか」を 1 画面で出す。
 *  - 計測曲 … 次のティアを分ける登竜門譜面。到達基準は上位ティアの平均レート
 *  - 課題曲 … 弱点軸（皿 / 乱打 / 同時押し …）の譜面で、達成確率 40〜70% の帯
 *  - コスパ埋め … 期待獲得 BEAT-PT の大きい順
 *
 * 週は月曜 0:00 〜 日曜 23:59（JST）。アップロード後にこの画面を開くと、
 * サーバー側が歴代ベストと突き合わせて達成 / 前進を採点し直す。
 *
 * スコアの参照範囲はサーバー側で「歴代（現行 + 過去作、INFINITAS 除く）」に統一されている。
 * 前作で出しているスコアを「未プレイ」と扱わないための措置。
 *
 * 権限:
 *  - useAdmin.isAdmin で表示ガード（実権限チェックはサーバ側 /api/training/**）
 */
import { ref, computed, onMounted } from 'vue';
import { useAdmin } from '../composables/useAdmin';
import { useAuth } from '../composables/useAuth';
import InformalRankBadge from '../components/InformalRankBadge.vue';
import { getRankInfo } from '../utils/beatTier';

const { isAdmin } = useAdmin();
const { authHeaders } = useAuth();
const API_BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

/** メニュー 1 行。サーバーの items 要素と 1:1。 */
interface MenuItem {
  title: string;
  difficultyName: string;
  informalRank: string | null;
  role: 'MEASURE' | 'TASK' | 'FILL';
  axis: string | null;
  targetType: string;
  targetLabel: string | null;
  targetValue: number | null;
  baselineScore: number | null;
  baselineClear: string | null;
  resultScore: number | null;
  resultClear: string | null;
  achieveProbability: number | null;
  expectedGain: number | null;
  plannedPlays: number | null;
  status: 'PENDING' | 'PROGRESSED' | 'ACHIEVED' | 'UNTOUCHED' | 'REPLACED';
  carriedWeeks: number;
  unplayed: boolean;
}

interface MenuResponse {
  weekStart: string;
  weekEnd: string;
  status: string;
  currentTier: string;
  currentTierMinPoints: number;
  totalBeatPt: number;
  weeklyPlays: number;
  regenerateLeft: number;
  nextTier: { name: string; minPoints: number; gap: number } | null;
  weakAxes: string[];
  referenceChartCount: number;
  benchmarkReady: boolean;
  summary: { achieved: number; progressed: number; untouched: number; total: number };
  items: MenuItem[];
}

interface RadarAxis {
  axis: string;
  available: boolean;
  actualRate?: number;
  predictedRate?: number;
  residual?: number;
  chartCount: number;
}

const menu = ref<MenuResponse | null>(null);
const radar = ref<RadarAxis[]>([]);
const review = ref<any>(null);
const isLoading = ref(false);
const isRegenerating = ref(false);
const loadError = ref('');

/** 管理者が他ユーザーを覗くための ID。空なら自分。 */
const viewUserId = ref<string>('');

/** userId クエリを付けた URL を組む。 */
const withUser = (path: string) => {
  const id = viewUserId.value.trim();
  if (!id) return `${API_BASE}${path}`;
  return `${API_BASE}${path}${path.includes('?') ? '&' : '?'}userId=${encodeURIComponent(id)}`;
};

/** メニュー・レーダー・振り返りをまとめて取得する。 */
const loadAll = async () => {
  isLoading.value = true;
  loadError.value = '';
  try {
    const [menuRes, radarRes, reviewRes] = await Promise.all([
      fetch(withUser('/api/training/menu'), { headers: authHeaders() }),
      fetch(withUser('/api/training/radar'), { headers: authHeaders() }),
      fetch(withUser('/api/training/review'), { headers: authHeaders() }),
    ]);
    if (!menuRes.ok) throw new Error(`メニュー取得に失敗しました (HTTP ${menuRes.status})`);
    menu.value = await menuRes.json();
    radar.value = radarRes.ok ? (await radarRes.json()).axes ?? [] : [];
    const reviewData = reviewRes.ok ? await reviewRes.json() : null;
    review.value = reviewData?.weekStart ? reviewData : null;
  } catch (e: any) {
    loadError.value = e?.message ?? '通信エラー';
    menu.value = null;
  } finally {
    isLoading.value = false;
  }
};

/** メニューを組み直す。 */
const regenerate = async () => {
  if (isRegenerating.value) return;
  isRegenerating.value = true;
  loadError.value = '';
  try {
    const res = await fetch(withUser('/api/training/menu/regenerate'), {
      method: 'POST',
      headers: authHeaders(),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data?.error ?? `HTTP ${res.status}`);
    menu.value = data;
  } catch (e: any) {
    loadError.value = e?.message ?? '通信エラー';
  } finally {
    isRegenerating.value = false;
  }
};

onMounted(() => {
  if (isAdmin.value) loadAll();
});

/** 役割ごとに項目を仕分ける。 */
const measureItems = computed(() => (menu.value?.items ?? []).filter(i => i.role === 'MEASURE'));
const taskItems = computed(() => (menu.value?.items ?? []).filter(i => i.role === 'TASK'));
const fillItems = computed(() => (menu.value?.items ?? []).filter(i => i.role === 'FILL'));

/** 現在ティアの色クラス（既存 beatTier のランク定義から引く）。 */
const tierColor = computed(() => {
  if (!menu.value) return 'text-slate-600';
  return getRankInfo(menu.value.totalBeatPt).color;
});

/**
 * 現ティア内の進捗（％）。
 * ティアの幅は 500 / 1000 / 2000 pt とまちまちなので、下限と次ティア下限の
 * 両方をサーバーから受け取って実際の幅で割る。
 */
const tierProgress = computed(() => {
  const m = menu.value;
  if (!m?.nextTier) return 100;
  const span = m.nextTier.minPoints - m.currentTierMinPoints;
  if (span <= 0) return 100;
  const done = m.totalBeatPt - m.currentTierMinPoints;
  return Math.max(0, Math.min(100, (done / span) * 100));
});

/** 達成率の色分け。既存のランクアップ・アドバイスと同じ基準に揃える。 */
const probabilityClass = (p: number | null) => {
  if (p == null) return 'text-slate-400 dark:text-slate-500';
  if (p >= 0.7) return 'text-emerald-600 dark:text-emerald-400';
  if (p >= 0.4) return 'text-slate-500 dark:text-slate-400';
  return 'text-amber-600 dark:text-amber-400';
};

/** 状態ピルの見た目。 */
const statusClass = (status: string) => {
  switch (status) {
    case 'ACHIEVED':
      return 'bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400 border-emerald-200 dark:border-emerald-800';
    case 'PROGRESSED':
      return 'bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800';
    case 'UNTOUCHED':
      return 'bg-transparent text-slate-400 dark:text-slate-500 border-slate-300 dark:border-slate-600';
    default:
      return 'bg-transparent text-slate-400 dark:text-slate-500 border-slate-300 dark:border-slate-600';
  }
};

/** 状態ラベル。前進は伸び幅も出す。 */
const statusLabel = (item: MenuItem) => {
  if (item.status === 'ACHIEVED') return '達成';
  if (item.status === 'PROGRESSED') {
    const gain = (item.resultScore ?? 0) - (item.baselineScore ?? 0);
    return gain > 0 ? `前進 +${gain}` : '前進';
  }
  if (item.status === 'UNTOUCHED') return '未着手';
  return '未着手';
};

/** 現在スコアの表示（採点後の値があればそちら）。 */
const currentScoreOf = (item: MenuItem) => item.resultScore ?? item.baselineScore ?? 0;

/** 目標まであと何点か。到達済みなら null。 */
const remainingOf = (item: MenuItem) => {
  if (item.targetValue == null) return null;
  const diff = item.targetValue - currentScoreOf(item);
  return diff > 0 ? diff : null;
};

const fmt = (n: number | null | undefined) => (n == null ? '-' : n.toLocaleString());

/** レーダーの多角形パスを組む。値はレート % をそのまま半径に写す。 */
const RADAR_CENTER = 110;
const RADAR_MAX_R = 80;
/** レーダーの内外レンジ。この幅で 0〜100% を切り取って差を見やすくする。 */
const RADAR_MIN_RATE = 60;
const RADAR_MAX_RATE = 100;

const radarPoint = (index: number, rate: number) => {
  const total = radar.value.length || 8;
  const angle = (Math.PI * 2 * index) / total - Math.PI / 2;
  const clamped = Math.max(RADAR_MIN_RATE, Math.min(RADAR_MAX_RATE, rate));
  const r = ((clamped - RADAR_MIN_RATE) / (RADAR_MAX_RATE - RADAR_MIN_RATE)) * RADAR_MAX_R;
  return [RADAR_CENTER + r * Math.cos(angle), RADAR_CENTER + r * Math.sin(angle)];
};

const radarLabelPoint = (index: number) => {
  const total = radar.value.length || 8;
  const angle = (Math.PI * 2 * index) / total - Math.PI / 2;
  const r = RADAR_MAX_R + 18;
  return [RADAR_CENTER + r * Math.cos(angle), RADAR_CENTER + r * Math.sin(angle) + 3];
};

/** 実測 / 予測それぞれの多角形。判定不能な軸は中心に落とさず、その軸を飛ばして描く。 */
const polygonOf = (key: 'actualRate' | 'predictedRate') => {
  const pts: string[] = [];
  radar.value.forEach((a, i) => {
    if (!a.available || a[key] == null) return;
    const [x, y] = radarPoint(i, a[key] as number);
    pts.push(`${x.toFixed(1)},${y.toFixed(1)}`);
  });
  return pts.join(' ');
};

/** グリッドの環。60% / 80% / 100% の 3 本。 */
const gridRings = computed(() => {
  const total = radar.value.length || 8;
  return [70, 85, 100].map(rate => {
    const pts: string[] = [];
    for (let i = 0; i < total; i++) {
      const [x, y] = radarPoint(i, rate);
      pts.push(`${x.toFixed(1)},${y.toFixed(1)}`);
    }
    return { rate, points: pts.join(' ') };
  });
});

/** 弱点軸の説明文。残差が負に大きい順に 2 本まで。 */
const weakAxisNote = computed(() => {
  const weak = radar.value
    .filter(a => a.available && a.residual != null)
    .sort((a, b) => (a.residual as number) - (b.residual as number))
    .slice(0, 2);
  if (weak.length === 0) return '';
  return weak.map(a => `${a.axis} ${(a.residual as number).toFixed(1)}%`).join(' / ');
});
</script>

<template>
  <div class="w-full max-w-6xl mx-auto flex flex-col gap-4">
    <!-- 権限ガード -->
    <div v-if="!isAdmin" class="card p-6 text-center text-sm text-slate-500 dark:text-slate-400">
      この機能は現在管理者のみ利用できます。
    </div>

    <template v-else>
      <!-- タイトル行 -->
      <div class="flex items-end justify-between gap-4 flex-wrap">
        <div>
          <h1 class="text-xl font-bold text-slate-900 dark:text-slate-100">練習メニュー</h1>
          <p v-if="menu" class="text-xs text-slate-500 dark:text-slate-400 mt-1">
            {{ menu.weekStart }}（月）0:00 〜 {{ menu.weekEnd }}（日）23:59 · 週 {{ menu.weeklyPlays }} プレイ想定
          </p>
          <p v-else class="text-xs text-slate-500 dark:text-slate-400 mt-1">検証中の管理者向け機能です。</p>
        </div>
        <div class="flex gap-2 items-center">
          <input
            v-model="viewUserId"
            placeholder="userId（空で自分）"
            class="w-40 px-3 py-2 text-xs rounded-md border border-slate-300 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-900 dark:text-slate-100"
          />
          <button class="btn-secondary" :disabled="isLoading" @click="loadAll">
            {{ isLoading ? '読み込み中…' : '読み込む' }}
          </button>
          <button
            class="btn-primary"
            :disabled="isRegenerating || !menu || (menu?.regenerateLeft ?? 0) <= 0"
            @click="regenerate"
          >
            {{ isRegenerating ? '組み直し中…' : `組み直す（残り ${menu?.regenerateLeft ?? 0}）` }}
          </button>
        </div>
      </div>

      <div v-if="loadError" class="card p-4 text-sm text-rose-600 dark:text-rose-400">
        {{ loadError }}
      </div>

      <div v-if="isLoading && !menu" class="card p-10 text-center text-sm text-slate-400 dark:text-slate-500">
        メニューを組み立てています。初回はペア回帰の構築で数秒かかります。
      </div>

      <template v-if="menu">
        <!-- 未集計の注意書き -->
        <div
          v-if="!menu.benchmarkReady"
          class="card p-3 text-xs text-amber-700 dark:text-amber-400 border-amber-300 dark:border-amber-700 bg-amber-50 dark:bg-amber-900/20"
        >
          ティア別ベンチマークが未集計のため、計測曲は空になります。
          <code class="mx-1">POST /api/admin/training/benchmark/refresh</code>
          で即時集計できます（数十秒）。
        </div>

        <!-- サマリー -->
        <div class="card grid grid-cols-1 sm:grid-cols-3 divide-y sm:divide-y-0 sm:divide-x divide-slate-200 dark:divide-slate-700">
          <div class="p-4">
            <p class="section-label">現在</p>
            <p class="text-2xl font-bold tabular-nums leading-tight mt-0.5">
              <span :class="tierColor">{{ menu.currentTier }}</span>
              <span class="text-slate-700 dark:text-slate-200 ml-2">{{ menu.totalBeatPt.toFixed(0) }}</span>
              <span class="text-xs font-semibold text-slate-400 dark:text-slate-500 ml-1">pt</span>
            </p>
            <div class="h-2 rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden my-2">
              <div class="h-full bg-blue-700 dark:bg-blue-500" :style="{ width: tierProgress + '%' }"></div>
            </div>
            <p class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
              <template v-if="menu.nextTier">
                {{ menu.nextTier.name }} まで
                <b class="text-slate-700 dark:text-slate-200">{{ menu.nextTier.gap.toFixed(1) }} pt</b>
              </template>
              <template v-else>最上位ティアに到達しています</template>
            </p>
          </div>
          <div class="p-4">
            <p class="section-label">弱点軸</p>
            <p class="text-2xl font-bold leading-tight mt-0.5">
              {{ menu.weakAxes.length ? menu.weakAxes.join(' · ') : '判定不能' }}
            </p>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-3">
              <template v-if="weakAxisNote">同実力帯との差 {{ weakAxisNote }}</template>
              <template v-else>軸ごとに 8 譜面以上のスコアが必要です</template>
            </p>
          </div>
          <div class="p-4">
            <p class="section-label">今週の進み</p>
            <p class="text-2xl font-bold tabular-nums leading-tight mt-0.5">
              {{ menu.summary.achieved + menu.summary.progressed }}
              <span class="text-sm text-slate-400 dark:text-slate-500 mx-0.5">/</span>
              {{ menu.summary.total }}
              <span class="text-xs font-semibold text-slate-400 dark:text-slate-500 ml-1">曲</span>
            </p>
            <div class="h-2 rounded-full bg-slate-100 dark:bg-slate-700 overflow-hidden my-2 flex">
              <div
                class="h-full bg-emerald-600"
                :style="{ width: (menu.summary.total ? (menu.summary.achieved / menu.summary.total) * 100 : 0) + '%' }"
              ></div>
              <div
                class="h-full bg-blue-700 dark:bg-blue-500"
                :style="{ width: (menu.summary.total ? (menu.summary.progressed / menu.summary.total) * 100 : 0) + '%' }"
              ></div>
            </div>
            <p class="text-xs text-slate-500 dark:text-slate-400 tabular-nums">
              達成 <b class="text-slate-700 dark:text-slate-200">{{ menu.summary.achieved }}</b> ·
              前進 <b class="text-slate-700 dark:text-slate-200">{{ menu.summary.progressed }}</b> ·
              未着手 <b class="text-slate-700 dark:text-slate-200">{{ menu.summary.untouched }}</b>
            </p>
          </div>
        </div>

        <div class="grid grid-cols-1 lg:grid-cols-[minmax(0,1fr)_300px] gap-4 items-start">
          <!-- 左: 3 つの枠 -->
          <div class="flex flex-col gap-4 min-w-0">
            <div
              v-for="group in [
                { key: 'MEASURE', label: '計測曲', items: measureItems, hint: '次のティアを分ける登竜門譜面。目標は上位ティアの平均' },
                { key: 'TASK', label: '課題曲', items: taskItems, hint: '弱点軸の譜面から、達成確率 40〜70% のもの' },
                { key: 'FILL', label: 'コスパ埋め', items: fillItems, hint: '期待獲得 BEAT-PT の大きい順' },
              ]"
              :key="group.key"
              class="card"
            >
              <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 gap-3">
                <h3 class="text-sm font-bold text-slate-800 dark:text-slate-100 flex items-center gap-2">
                  {{ group.label }}
                  <span class="text-[11px] font-medium text-slate-400 dark:text-slate-500">
                    {{ group.items.length }} 曲
                    <template v-if="group.items.length"> · 各 {{ group.items[0].plannedPlays }} 回</template>
                  </span>
                </h3>
                <span class="text-[11px] text-slate-400 dark:text-slate-500 text-right hidden sm:block">{{ group.hint }}</span>
              </div>

              <p v-if="!group.items.length" class="px-4 py-6 text-center text-xs text-slate-400 dark:text-slate-500">
                条件に合う譜面がありませんでした。
              </p>

              <div
                v-for="(item, idx) in group.items"
                :key="`${item.title}|${item.difficultyName}`"
                class="grid grid-cols-[minmax(0,1fr)_auto] sm:grid-cols-[1.5rem_minmax(0,1fr)_7rem_9rem_4rem_4.5rem] gap-x-3 gap-y-1 items-center px-4 py-2.5 border-t border-slate-100 dark:border-slate-700/50 first-of-type:border-t-0"
              >
                <span class="hidden sm:block text-[10px] font-bold text-slate-400 dark:text-slate-500 text-right tabular-nums">
                  {{ idx + 1 }}
                </span>

                <div class="min-w-0">
                  <div class="flex items-center gap-1.5 min-w-0">
                    <p class="font-bold text-slate-800 dark:text-slate-200 text-xs sm:text-sm truncate">{{ item.title }}</p>
                    <InformalRankBadge :rank="item.informalRank" size="xs" class="shrink-0" />
                    <span
                      v-if="item.unplayed"
                      class="shrink-0 text-[9px] font-bold px-1 py-px rounded bg-blue-600 text-white"
                    >埋め</span>
                    <span
                      v-if="item.axis"
                      class="shrink-0 text-[9px] font-bold px-1 py-px rounded bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400"
                    >{{ item.axis }}</span>
                  </div>
                  <p class="text-[10px] text-slate-500 dark:text-slate-400">
                    {{ item.difficultyName }}
                    <template v-if="item.carriedWeeks > 0"> · 持ち越し {{ item.carriedWeeks }} 週目</template>
                    <template v-else-if="item.expectedGain"> · 期待 +{{ item.expectedGain.toFixed(1) }} pt</template>
                  </p>
                </div>

                <div class="hidden sm:block text-[11px] text-slate-500 dark:text-slate-400 tabular-nums">
                  <span class="block text-[10px]">現在</span>
                  <b class="text-xs text-slate-800 dark:text-slate-200">{{ fmt(currentScoreOf(item)) }}</b>
                </div>

                <div class="hidden sm:block tabular-nums">
                  <span class="block text-[10px] font-bold text-blue-700 dark:text-blue-400">
                    目標 {{ item.targetLabel }}
                  </span>
                  <b class="text-xs text-slate-800 dark:text-slate-200">{{ fmt(item.targetValue) }}</b>
                  <span class="block text-[10px] text-slate-400 dark:text-slate-500">
                    <template v-if="remainingOf(item)">あと {{ fmt(remainingOf(item)) }}</template>
                    <template v-else>到達</template>
                  </span>
                </div>

                <div class="hidden sm:block text-right">
                  <p class="text-xs font-bold tabular-nums" :class="probabilityClass(item.achieveProbability)">
                    <template v-if="item.achieveProbability != null">
                      {{ Math.round(item.achieveProbability * 100) }}%
                    </template>
                    <template v-else>-</template>
                  </p>
                  <p class="text-[9px] font-semibold text-slate-400 dark:text-slate-500">達成率</p>
                </div>

                <div class="text-right">
                  <span
                    class="inline-block text-[10px] font-bold rounded-full px-2 py-0.5 border whitespace-nowrap"
                    :class="statusClass(item.status)"
                  >{{ statusLabel(item) }}</span>
                  <!-- スマホでは目標を状態の下にまとめる -->
                  <p class="sm:hidden text-[10px] text-slate-500 dark:text-slate-400 tabular-nums mt-0.5">
                    {{ fmt(currentScoreOf(item)) }} → {{ item.targetLabel }} {{ fmt(item.targetValue) }}
                  </p>
                </div>
              </div>
            </div>
          </div>

          <!-- 右: レーダーと振り返り -->
          <div class="flex flex-col gap-4">
            <div class="card">
              <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
                <h3 class="text-sm font-bold text-slate-800 dark:text-slate-100">弱点レーダー</h3>
                <span class="text-[11px] text-slate-400 dark:text-slate-500">{{ menu.referenceChartCount }} 譜面から</span>
              </div>
              <div class="p-4">
                <svg viewBox="0 0 220 220" class="w-full max-w-[260px] mx-auto block" role="img"
                     aria-label="8 軸の弱点レーダー。実測レートと、同実力帯からの予測レートを重ねている。">
                  <polygon
                    v-for="ring in gridRings"
                    :key="ring.rate"
                    :points="ring.points"
                    fill="none"
                    class="stroke-slate-200 dark:stroke-slate-700"
                    stroke-width="1"
                  />
                  <line
                    v-for="(a, i) in radar"
                    :key="`spoke-${a.axis}`"
                    :x1="RADAR_CENTER" :y1="RADAR_CENTER"
                    :x2="radarPoint(i, RADAR_MAX_RATE)[0]" :y2="radarPoint(i, RADAR_MAX_RATE)[1]"
                    class="stroke-slate-100 dark:stroke-slate-700/60"
                    stroke-width="1"
                  />
                  <polygon
                    :points="polygonOf('predictedRate')"
                    fill="none"
                    class="stroke-slate-400 dark:stroke-slate-500"
                    stroke-width="1.4"
                    stroke-dasharray="3 3"
                  />
                  <polygon
                    :points="polygonOf('actualRate')"
                    class="fill-blue-600/20 stroke-blue-600 dark:fill-blue-500/20 dark:stroke-blue-400"
                    stroke-width="2"
                  />
                  <text
                    v-for="(a, i) in radar"
                    :key="`label-${a.axis}`"
                    :x="radarLabelPoint(i)[0]"
                    :y="radarLabelPoint(i)[1]"
                    text-anchor="middle"
                    class="fill-slate-600 dark:fill-slate-300"
                    font-size="10.5"
                    font-weight="700"
                  >{{ a.axis }}</text>
                </svg>
                <div class="flex gap-3 text-[10px] text-slate-500 dark:text-slate-400 mt-2 flex-wrap justify-center">
                  <span><i class="inline-block w-2.5 h-[3px] rounded-sm bg-blue-600 dark:bg-blue-400 align-middle mr-1"></i>実測</span>
                  <span><i class="inline-block w-2.5 h-[3px] rounded-sm bg-slate-400 dark:bg-slate-500 align-middle mr-1"></i>同実力帯の予測</span>
                </div>
                <p class="text-[11px] text-slate-500 dark:text-slate-400 mt-3 pt-3 border-t border-slate-100 dark:border-slate-700/50">
                  <template v-if="weakAxisNote">
                    予測より沈んでいる軸: <b class="text-slate-700 dark:text-slate-200">{{ weakAxisNote }}</b>。
                    課題曲はこの軸から選んでいます。
                  </template>
                  <template v-else>
                    軸あたり 8 譜面以上のスコアが揃うと判定できます。
                  </template>
                </p>
              </div>
            </div>

            <div v-if="review" class="card">
              <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700">
                <h3 class="text-sm font-bold text-slate-800 dark:text-slate-100">先週の振り返り</h3>
                <span class="text-[11px] text-slate-400 dark:text-slate-500">{{ review.weekStart }} 週</span>
              </div>
              <div class="p-4 text-xs">
                <p class="text-slate-500 dark:text-slate-400 mb-2 tabular-nums">
                  達成率 {{ Math.round((review.completionRate ?? 0) * 100) }}%（{{ review.total }} 曲中）
                </p>
                <div class="flex flex-col gap-1">
                  <div
                    v-for="(bucket, key) in { achieved: '達成', progressed: '前進', untouched: '未着手' }"
                    :key="key"
                    class="flex justify-between gap-2"
                  >
                    <span class="text-slate-500 dark:text-slate-400">{{ bucket }}</span>
                    <span class="font-bold tabular-nums text-slate-700 dark:text-slate-200">
                      {{ (review[key] ?? []).length }}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </template>
    </template>
  </div>
</template>
