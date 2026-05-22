<script setup lang="ts">
/**
 * 【コンポーネントの役割】 フレンド比較モーダル内の各集計単位（overall/lv11/lv12 サマリーカード、
 * 非公式難易度ランク 1 行）について、自分と相手の勝敗カウント
 * （WIN/LOSS/DRAW/YOU Only/FRIEND Only）の時系列推移を折れ線グラフで表示する。
 *
 * データ源:
 *   - 自分: GET /api/scores/history（ScoreHistoryLog.diffJson を時系列順）
 *   - 相手: GET /api/friends/{friendId}/history（フレンド関係必須。privacyLevel=0/1 で取得可、
 *           privacyLevel=2 や 403/404 は現在値固定にフォールバック）
 *   - バーチャルライバル（friend.id<0）の場合は相手側履歴を取得しない（現在値で固定）
 *
 * 集計ロジック:
 *   - 親から渡された現在の ScoreRecord（Lv.11/12 ANOTHER/LEGGENDARIA）から開始マップを構築
 *   - 履歴中の最初のイベントの oldScore/oldClearType に巻き戻して「履歴開始時点」を再現
 *   - 全イベントを時系列マージし walk-forward。曲ごとの WIN/LOSS/DRAW/YOU Only/FRIEND Only カテゴリ
 *     の前後変化のみ差分更新（O(N) 走査）
 *   - 各イベント時点でスナップショットを記録 → 折れ線データに変換
 *
 * 限界:
 *   - 「現在 Lv.11/12 ANOTHER/LEGGENDARIA としてプレイ済み」の曲のみ追跡対象。過去の難易度改訂は無視。
 *   - 相手側履歴が取れない場合、相手スコアは時系列で動かない（フォールバックで現在値固定）
 */
import { ref, computed, onMounted, watch } from 'vue';
import {
  Chart as ChartJS, LinearScale, CategoryScale, TimeScale,
  PointElement, LineElement, Filler, Tooltip, Legend,
} from 'chart.js';
import { Line } from 'vue-chartjs';
import zoomPlugin from 'chartjs-plugin-zoom';
import { useAuth, API_BASE } from '../composables/useAuth';
import { useDarkMode } from '../composables/useDarkMode';
import { useI18n } from '../composables/useI18n';
import type { Friend } from '../composables/useFriends';
import type { ScoreRecord } from '../utils/scoreData';

ChartJS.register(LinearScale, CategoryScale, TimeScale, PointElement, LineElement, Filler, Tooltip, Legend, zoomPlugin);

/** 集計対象の指定。 'rank:12.0' のような形式で非公式難易度を指定できる。 */
type Scope = 'overall' | 'lv11' | 'lv12' | `rank:${string}`;

const props = defineProps<{
  friend: Friend;
  virtualArea?: { versionNum: number; prefectureFileNum: number } | null;
  isOpen: boolean;
  scope: Scope;
  scopeLabel: string;
  /** 親モーダルが既に取得済の現在スコア（再フェッチを避けるため受け取る）。 */
  myCurrentScores: ScoreRecord[];
  friendCurrentScores: ScoreRecord[];
}>();

const emit = defineEmits<{ (e: 'close'): void }>();

const { authHeaders, isLoggedIn } = useAuth();
const { isDarkMode } = useDarkMode();
const { currentLang } = useI18n();

const isLoading = ref(false);
const errorMsg = ref('');
/** 相手側履歴が取得不能だった（非公開設定 or バーチャル相手）。グラフ上の注意書き表示用。 */
const friendHistoryUnavailable = ref(false);

interface CountSnapshot {
  ts: number;
  win: number;
  loss: number;
  draw: number;
  myOnly: number;
  friendOnly: number;
}

/** 走査結果のスナップショット列（ts 昇順）。Chart.js のデータ源。 */
const snapshots = ref<CountSnapshot[]>([]);

/** 表示モード。'count' は件数の折れ線、'ratio' は勝率(%)の単一折れ線 + 上下背景塗り分け。 */
const displayMode = ref<'count' | 'ratio'>('count');

/** 1 曲 × 1 譜面の追跡用状態。 */
interface SongState {
  score: number;
  clearType: string;
  difficultyLevel: number;
  informalRank?: string;
}

/**
 * 【関数の役割】 ScoreRecord 配列を Lv.11/Lv.12 ANOTHER/LEGGENDARIA のみに絞り込み
 * key (`${title}_${difficultyName}`) → SongState の Map にする。比較対象の母集合になる。
 */
function buildStateMap(records: ScoreRecord[]): Map<string, SongState> {
  const m = new Map<string, SongState>();
  records.forEach(r => {
    if (r.difficultyLevel !== 11 && r.difficultyLevel !== 12) return;
    if (r.difficultyName !== 'ANOTHER' && r.difficultyName !== 'LEGGENDARIA') return;
    const key = `${r.title}_${r.difficultyName}`;
    m.set(key, {
      score: r.score,
      clearType: r.clearType,
      difficultyLevel: r.difficultyLevel,
      informalRank: r.informalRank,
    });
  });
  return m;
}

/** scope に応じて、その key を集計対象に含めるか判定する。 */
function inScope(meta: SongState | undefined): boolean {
  if (!meta) return false;
  if (props.scope === 'overall') return meta.difficultyLevel === 11 || meta.difficultyLevel === 12;
  if (props.scope === 'lv11') return meta.difficultyLevel === 11;
  if (props.scope === 'lv12') return meta.difficultyLevel === 12;
  if (props.scope.startsWith('rank:')) return meta.informalRank === props.scope.slice(5);
  return false;
}

type Side = 'my' | 'friend';
interface HistoryEvent {
  ts: number;
  side: Side;
  key: string;
  oldScore: number;
  newScore: number;
  oldClearType: string;
  newClearType: string;
}

/**
 * 【関数の役割】 履歴 API の戻り値（ScoreHistoryLog の配列）を平坦化し
 * HistoryEvent 配列に変換する。
 *  - keySet に含まれないキーは無視（=現在 Lv.11/12 ANOTHER/LEGGENDARIA でない曲）
 *  - diffJson の difficulty フィールドは 'ANOTHER' / 'LEGGENDARIA' のいずれかのみ採用
 */
function flattenHistoryEvents(rawHistory: any[], side: Side, keySet: Set<string>): HistoryEvent[] {
  const events: HistoryEvent[] = [];
  if (!Array.isArray(rawHistory)) return events;
  for (const entry of rawHistory) {
    if (!entry || !entry.diffJson || entry.diffJson === '[]') continue;
    const dateStr = String(entry.date ?? '');
    if (!dateStr) continue;
    const ts = new Date(dateStr.endsWith('Z') ? dateStr : `${dateStr}Z`).getTime();
    if (!Number.isFinite(ts)) continue;
    let songs: any[];
    try {
      songs = JSON.parse(entry.diffJson);
    } catch {
      continue;
    }
    if (!Array.isArray(songs)) continue;
    for (const s of songs) {
      const diffName = String(s.difficulty ?? s.difficultyName ?? '').toUpperCase();
      if (diffName !== 'ANOTHER' && diffName !== 'LEGGENDARIA') continue;
      const title = String(s.title ?? '');
      if (!title) continue;
      const key = `${title}_${diffName}`;
      if (!keySet.has(key)) continue;
      events.push({
        ts,
        side,
        key,
        oldScore: Number(s.oldScore ?? 0),
        newScore: Number(s.newScore ?? 0),
        oldClearType: String(s.oldClearType ?? 'NO PLAY'),
        newClearType: String(s.newClearType ?? 'NO PLAY'),
      });
    }
  }
  return events;
}

type Category = 'win' | 'loss' | 'draw' | 'myOnly' | 'friendOnly' | null;

/** 自分・相手の SongState から比較カテゴリを返す。score=0 は未プレイ扱い。 */
function categorize(my: SongState | undefined, friend: SongState | undefined): Category {
  const myPlay = !!(my && my.score > 0);
  const friendPlay = !!(friend && friend.score > 0);
  if (!myPlay && !friendPlay) return null;
  if (myPlay && friendPlay) {
    const d = (my!.score) - (friend!.score);
    if (d > 0) return 'win';
    if (d < 0) return 'loss';
    return 'draw';
  }
  return myPlay ? 'myOnly' : 'friendOnly';
}

/**
 * 【関数の役割】 履歴を取得して時系列スナップショット列を構築し snapshots.value に格納する。
 *
 * バーチャル相手（friend.id < 0 もしくは virtualArea 指定）の場合は相手側履歴取得をスキップし、
 * 相手スコアは「現在値で時系列固定」として扱う。
 */
async function loadHistory() {
  isLoading.value = true;
  errorMsg.value = '';
  friendHistoryUnavailable.value = false;
  snapshots.value = [];

  try {
    const currentMy = buildStateMap(props.myCurrentScores);
    const currentFriend = buildStateMap(props.friendCurrentScores);

    // 対象キー集合: 自分か相手のどちらかが「現在 Lv.11/12 でプレイ済み」の曲
    const keySet = new Set<string>();
    currentMy.forEach((_, k) => keySet.add(k));
    currentFriend.forEach((_, k) => keySet.add(k));

    const isVirtual = !!props.virtualArea || props.friend.id < 0;

    const myHistPromise: Promise<any[]> = isLoggedIn.value
      ? fetch(`${API_BASE}/api/scores/history`, { headers: authHeaders() })
          .then(r => (r.ok ? r.json() : []))
          .catch(() => [])
      : Promise.resolve([]);

    let friendHistPromise: Promise<any[]>;
    if (isVirtual) {
      friendHistoryUnavailable.value = true;
      friendHistPromise = Promise.resolve([]);
    } else {
      // /api/friends/{id}/history はフレンド関係必須かつ privacyLevel=0/1 で取得可能。
      // 2(非公開) や非フレンドは 403、未ログインは 401。いずれもフォールバック扱い。
      friendHistPromise = fetch(`${API_BASE}/api/friends/${props.friend.id}/history`, { headers: authHeaders() })
        .then(r => {
          if (r.ok) return r.json();
          friendHistoryUnavailable.value = true;
          return [];
        })
        .catch(() => {
          friendHistoryUnavailable.value = true;
          return [];
        });
    }

    const [myHist, friendHist] = await Promise.all([myHistPromise, friendHistPromise]);

    const myEvents = flattenHistoryEvents(myHist, 'my', keySet);
    const friendEvents = flattenHistoryEvents(friendHist, 'friend', keySet);

    // 履歴開始時点の状態 = 現在値からスタートし、各 side の「最初のイベントの oldScore/oldClearType」で巻き戻し
    const initMy = new Map<string, SongState>();
    currentMy.forEach((v, k) => initMy.set(k, { ...v }));
    const initFriend = new Map<string, SongState>();
    currentFriend.forEach((v, k) => initFriend.set(k, { ...v }));

    const applyFirstEvents = (events: HistoryEvent[], target: Map<string, SongState>) => {
      const ascByKey = new Map<string, HistoryEvent>();
      for (const e of [...events].sort((a, b) => a.ts - b.ts)) {
        if (!ascByKey.has(e.key)) ascByKey.set(e.key, e);
      }
      ascByKey.forEach((e, key) => {
        const cur = target.get(key);
        if (cur) target.set(key, { ...cur, score: e.oldScore, clearType: e.oldClearType });
      });
    };
    applyFirstEvents(myEvents, initMy);
    applyFirstEvents(friendEvents, initFriend);

    // メタ情報（difficultyLevel / informalRank）は現在マップから取り出す（履歴中に難易度変動はないものとして扱う）
    const getMeta = (key: string): SongState | undefined => currentMy.get(key) || currentFriend.get(key);

    // 初期スナップショットの算出
    let win = 0, loss = 0, draw = 0, myOnly = 0, friendOnly = 0;
    const lastCategory = new Map<string, Category>();
    keySet.forEach(key => {
      const meta = getMeta(key);
      if (!inScope(meta)) return;
      const c = categorize(initMy.get(key), initFriend.get(key));
      lastCategory.set(key, c);
      if (c === 'win') win++;
      else if (c === 'loss') loss++;
      else if (c === 'draw') draw++;
      else if (c === 'myOnly') myOnly++;
      else if (c === 'friendOnly') friendOnly++;
    });

    const allEvents = [...myEvents, ...friendEvents].sort((a, b) => a.ts - b.ts);

    // 「両者が CSV を初めて読み込んだ日」= 自分と相手の最初のイベントの遅い方を開始日とする。
    // - 相手側履歴が取れない場合（バーチャル/非公開）は、相手は現在値固定なので自分の最初の日が開始
    // - 自分の履歴が無い場合は開始日が決められないため、結果として何も描画しない
    const minTs = (arr: HistoryEvent[]) => (arr.length === 0 ? null : arr.reduce((m, e) => (e.ts < m ? e.ts : m), arr[0].ts));
    const myFirstTs = minTs(myEvents);
    const friendFirstTs = minTs(friendEvents);
    let startTs: number | null;
    if (myFirstTs === null) {
      startTs = null;
    } else if (friendHistoryUnavailable.value || friendFirstTs === null) {
      startTs = myFirstTs;
    } else {
      startTs = Math.max(myFirstTs, friendFirstTs);
    }

    const out: CountSnapshot[] = [];

    const cursorMy = new Map<string, SongState>();
    initMy.forEach((v, k) => cursorMy.set(k, { ...v }));
    const cursorFriend = new Map<string, SongState>();
    initFriend.forEach((v, k) => cursorFriend.set(k, { ...v }));

    // 同一 ts に属するイベントは全部適用してから 1 スナップショット記録する。
    // 「両者の初 CSV 日」より前は内部状態だけ更新し、スナップショットには記録しない。
    let i = 0;
    while (i < allEvents.length) {
      const ts = allEvents[i].ts;
      let j = i;
      while (j < allEvents.length && allEvents[j].ts === ts) {
        const ev = allEvents[j];
        const meta = getMeta(ev.key);
        const scopeFlag = inScope(meta);
        const oldCat = lastCategory.get(ev.key) ?? null;
        const sideMap = ev.side === 'my' ? cursorMy : cursorFriend;
        const cur = sideMap.get(ev.key);
        if (cur) {
          sideMap.set(ev.key, { ...cur, score: ev.newScore, clearType: ev.newClearType });
        }
        const newCat = categorize(cursorMy.get(ev.key), cursorFriend.get(ev.key));
        if (scopeFlag && oldCat !== newCat) {
          if (oldCat === 'win') win--;
          else if (oldCat === 'loss') loss--;
          else if (oldCat === 'draw') draw--;
          else if (oldCat === 'myOnly') myOnly--;
          else if (oldCat === 'friendOnly') friendOnly--;
          if (newCat === 'win') win++;
          else if (newCat === 'loss') loss++;
          else if (newCat === 'draw') draw++;
          else if (newCat === 'myOnly') myOnly++;
          else if (newCat === 'friendOnly') friendOnly++;
          lastCategory.set(ev.key, newCat);
        }
        j++;
      }
      if (startTs !== null && ts >= startTs) {
        out.push({ ts, win, loss, draw, myOnly, friendOnly });
      }
      i = j;
    }

    snapshots.value = out;
  } catch (err: any) {
    errorMsg.value = err?.message ?? 'failed';
  } finally {
    isLoading.value = false;
  }
}

const formatDateLabel = (ts: number) => {
  const d = new Date(ts);
  const locale = currentLang.value === 'ko' ? 'ko-KR' : (currentLang.value === 'en' ? 'en-US' : 'ja-JP');
  return d.toLocaleDateString(locale, { timeZone: 'Asia/Tokyo', year: '2-digit', month: '2-digit', day: '2-digit' });
};

/**
 * 各スナップショットの「勝率(%)」。両者プレイ済みかつ DRAW でない曲のうち WIN の割合
 * (= win / (win + loss) × 100)。比較対象が 0 件なら null（Chart.js で線が途切れる）。
 */
const ratioSeries = computed(() => snapshots.value.map(s => {
  const denom = s.win + s.loss;
  if (denom <= 0) return null;
  return (s.win / denom) * 100;
}));

/** 勝率の最大値と最小値。プラグインで水平ラインを描画するために使う。null なら描画しない。 */
const ratioMinMax = computed<{ max: number; min: number } | null>(() => {
  const values: number[] = [];
  for (const v of ratioSeries.value) if (v !== null) values.push(v);
  if (values.length === 0) return null;
  let mx = values[0], mn = values[0];
  for (const v of values) {
    if (v > mx) mx = v;
    if (v < mn) mn = v;
  }
  return { max: mx, min: mn };
});

/** ratio モードで、全スナップショットで WIN+LOSS=0 だった場合は折れ線が出ない。案内文表示用フラグ。 */
const hasRatioData = computed(() => ratioSeries.value.some(v => v !== null));

const chartData = computed(() => {
  const labels = snapshots.value.map(s => formatDateLabel(s.ts));
  const blue = isDarkMode.value ? '#60a5fa' : '#2563eb';
  const red = isDarkMode.value ? '#f87171' : '#dc2626';
  const slate = isDarkMode.value ? '#94a3b8' : '#64748b';
  const blueLight = isDarkMode.value ? '#bfdbfe' : '#93c5fd';
  const redLight = isDarkMode.value ? '#fecaca' : '#fca5a5';
  const indigo = isDarkMode.value ? '#a5b4fc' : '#4f46e5';
  const baseDataset = {
    tension: 0.15,
    pointRadius: 2,
    pointHoverRadius: 5,
    borderWidth: 2.5,
    fill: false,
  };
  if (displayMode.value === 'ratio') {
    return {
      labels,
      datasets: [
        {
          ...baseDataset,
          label: '勝率',
          data: ratioSeries.value,
          borderColor: indigo,
          backgroundColor: indigo + '40',
          borderWidth: 3,
          pointRadius: 3,
          spanGaps: false,
        },
      ],
    };
  }
  return {
    labels,
    datasets: [
      { ...baseDataset, label: 'WIN', data: snapshots.value.map(s => s.win), borderColor: blue, backgroundColor: blue + '40' },
      { ...baseDataset, label: 'LOSS', data: snapshots.value.map(s => s.loss), borderColor: red, backgroundColor: red + '40' },
      { ...baseDataset, label: 'DRAW', data: snapshots.value.map(s => s.draw), borderColor: slate, backgroundColor: slate + '40' },
      { ...baseDataset, label: 'YOU Only', data: snapshots.value.map(s => s.myOnly), borderColor: blueLight, backgroundColor: blueLight + '40', borderWidth: 2, borderDash: [4, 4] },
      { ...baseDataset, label: 'FRIEND Only', data: snapshots.value.map(s => s.friendOnly), borderColor: redLight, backgroundColor: redLight + '40', borderWidth: 2, borderDash: [4, 4] },
    ],
  };
});

/**
 * 【プラグイン】 割合モード時のみ作動。
 *  - Y=50% の水平線を引き、その上を青系（自分優勢）、下を赤系（相手優勢）で薄く塗り分ける
 *  - 勝率の最高点と最低点に色付きの水平ラインを引き、右端にラベル（例: "最高 87.5%"）を描画
 * 描画判定は chart.options に乗せたフラグ経由ではなく、外側の displayMode を直接参照。
 */
const winRateBackgroundPlugin = {
  id: 'winRateBackground',
  beforeDatasetsDraw(chart: any) {
    if (displayMode.value !== 'ratio') return;
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.y) return;
    const y50 = scales.y.getPixelForValue(50);
    if (!Number.isFinite(y50)) return;
    const mid = Math.max(chartArea.top, Math.min(chartArea.bottom, y50));
    const isDark = isDarkMode.value;
    const blueFill = isDark ? 'rgba(59,130,246,0.16)' : 'rgba(59,130,246,0.10)';
    const redFill = isDark ? 'rgba(220,38,38,0.16)' : 'rgba(220,38,38,0.10)';
    const lineColor = isDark ? 'rgba(203,213,225,0.7)' : 'rgba(100,116,139,0.7)';

    ctx.save();
    // 上半分（>=50%）: 自分優勢の青背景
    ctx.fillStyle = blueFill;
    ctx.fillRect(chartArea.left, chartArea.top, chartArea.right - chartArea.left, mid - chartArea.top);
    // 下半分（<50%）: 相手優勢の赤背景
    ctx.fillStyle = redFill;
    ctx.fillRect(chartArea.left, mid, chartArea.right - chartArea.left, chartArea.bottom - mid);
    // 50% ライン（破線）
    ctx.strokeStyle = lineColor;
    ctx.lineWidth = 1.5;
    ctx.setLineDash([5, 5]);
    ctx.beginPath();
    ctx.moveTo(chartArea.left, mid);
    ctx.lineTo(chartArea.right, mid);
    ctx.stroke();
    ctx.restore();
  },
  afterDatasetsDraw(chart: any) {
    if (displayMode.value !== 'ratio') return;
    const mm = ratioMinMax.value;
    if (!mm) return;
    const { ctx, chartArea, scales } = chart;
    if (!chartArea || !scales?.y) return;

    const isDark = isDarkMode.value;
    const maxColor = isDark ? 'rgba(96,165,250,0.95)' : 'rgba(37,99,235,0.95)';
    const minColor = isDark ? 'rgba(248,113,113,0.95)' : 'rgba(220,38,38,0.95)';

    const drawLine = (value: number, color: string, label: string, anchor: 'top' | 'bottom') => {
      const y = scales.y.getPixelForValue(value);
      if (!Number.isFinite(y) || y < chartArea.top || y > chartArea.bottom) return;
      ctx.save();
      ctx.strokeStyle = color;
      ctx.lineWidth = 1.5;
      ctx.setLineDash([2, 4]);
      ctx.beginPath();
      ctx.moveTo(chartArea.left, y);
      ctx.lineTo(chartArea.right, y);
      ctx.stroke();
      ctx.setLineDash([]);

      // 右端のラベル: ラインの上 or 下にオフセットして配置
      ctx.font = 'bold 11px sans-serif';
      const text = `${label} ${value.toFixed(1)}%`;
      const padX = 6, padY = 3;
      const tw = ctx.measureText(text).width;
      const th = 14;
      // ライン位置に応じてラベル位置を切替（チャート上端/下端からはみ出さないように）
      let labelY = anchor === 'top' ? y - th - 2 : y + 2;
      if (labelY < chartArea.top) labelY = y + 2;
      if (labelY + th > chartArea.bottom) labelY = y - th - 2;
      const labelX = chartArea.right - tw - padX * 2 - 4;
      ctx.fillStyle = isDark ? 'rgba(15,23,42,0.85)' : 'rgba(255,255,255,0.92)';
      ctx.strokeStyle = color;
      ctx.lineWidth = 1;
      ctx.beginPath();
      const r = 4;
      const x0 = labelX, y0 = labelY, x1 = labelX + tw + padX * 2, y1 = labelY + th + padY * 2 - 6;
      ctx.moveTo(x0 + r, y0);
      ctx.lineTo(x1 - r, y0);
      ctx.quadraticCurveTo(x1, y0, x1, y0 + r);
      ctx.lineTo(x1, y1 - r);
      ctx.quadraticCurveTo(x1, y1, x1 - r, y1);
      ctx.lineTo(x0 + r, y1);
      ctx.quadraticCurveTo(x0, y1, x0, y1 - r);
      ctx.lineTo(x0, y0 + r);
      ctx.quadraticCurveTo(x0, y0, x0 + r, y0);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      ctx.fillStyle = color;
      ctx.textBaseline = 'top';
      ctx.fillText(text, labelX + padX, labelY + 3);
      ctx.restore();
    };

    drawLine(mm.max, maxColor, '最高', 'top');
    // max と min が同じならラインを 1 本だけにする（重ね描き回避）
    if (mm.max !== mm.min) {
      drawLine(mm.min, minColor, '最低', 'bottom');
    }
  },
};

const chartOptions = computed(() => {
  const grid = isDarkMode.value ? 'rgba(148,163,184,0.18)' : 'rgba(148,163,184,0.25)';
  const tickColor = isDarkMode.value ? '#cbd5e1' : '#475569';
  const isRatio = displayMode.value === 'ratio';
  return {
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'nearest' as const, axis: 'x' as const, intersect: false },
    plugins: {
      legend: {
        display: !isRatio,
        position: 'bottom' as const,
        labels: { color: tickColor, font: { size: 12, weight: 'bold' as const }, boxWidth: 18, padding: 12 },
      },
      tooltip: {
        callbacks: {
          label: (ctx: any) => {
            if (isRatio) {
              return ctx.parsed.y === null ? '勝率: --' : `勝率: ${ctx.parsed.y.toFixed(1)}%`;
            }
            return `${ctx.dataset.label}: ${ctx.parsed.y}`;
          },
        },
      },
      zoom: {
        pan: { enabled: true, mode: 'xy' as const, modifierKey: null },
        zoom: {
          wheel: { enabled: true, speed: 0.1 },
          pinch: { enabled: true },
          mode: 'xy' as const,
        },
      },
    },
    scales: {
      x: {
        ticks: { color: tickColor, maxRotation: 0, autoSkip: true, maxTicksLimit: 8 },
        grid: { color: grid, drawTicks: false },
      },
      y: isRatio
        ? {
            min: 0,
            max: 100,
            ticks: {
              color: tickColor,
              stepSize: 25,
              callback: (v: any) => `${v}%`,
            },
            grid: { color: grid, drawTicks: false },
          }
        : {
            beginAtZero: true,
            ticks: { color: tickColor, precision: 0 },
            grid: { color: grid, drawTicks: false },
          },
    },
  } as any;
});

const chartRef = ref<any>(null);
function resetZoom() {
  const c = chartRef.value?.chart;
  if (c && typeof c.resetZoom === 'function') c.resetZoom();
}

/** モード切替時の共通ハンドラ。ズーム状態がモード間で持ち越されると混乱するので毎回リセット。 */
function setDisplayMode(mode: 'count' | 'ratio') {
  if (displayMode.value === mode) return;
  displayMode.value = mode;
  resetZoom();
}

onMounted(() => {
  if (props.isOpen) loadHistory();
});
watch(
  () => [props.isOpen, props.scope, props.friend.id, props.virtualArea?.versionNum, props.virtualArea?.prefectureFileNum],
  ([open]) => {
    if (open) loadHistory();
  },
);
</script>

<template>
  <Teleport to="body">
    <div v-if="isOpen" class="fixed inset-0 z-[10000] flex items-center justify-center p-2 sm:p-4">
      <div class="fixed inset-0 bg-black/50 backdrop-blur-sm" @click="emit('close')"></div>
      <div class="relative z-10 bg-white dark:bg-slate-800 rounded-xl sm:rounded-2xl shadow-2xl border border-slate-200 dark:border-slate-700 w-full max-w-3xl max-h-[95vh] sm:max-h-[90vh] flex flex-col">
        <div class="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <div class="min-w-0">
            <h3 class="font-black text-sm sm:text-base text-slate-800 dark:text-slate-100 flex items-center gap-1.5">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-4 w-4 text-indigo-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 3v18h18M7 14l3-3 4 4 5-7" />
              </svg>
              勝敗の変遷
            </h3>
            <p class="text-[11px] sm:text-xs text-slate-500 dark:text-slate-400 mt-0.5 truncate">
              {{ scopeLabel }} — vs <span class="text-blue-600 dark:text-blue-400 font-bold">{{ friend.displayName }}</span>
            </p>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <button v-if="snapshots.length > 0" type="button" @click="resetZoom"
              class="px-2 h-7 rounded-lg bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-600 dark:text-slate-300 text-xs font-bold flex items-center gap-1 transition-colors">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-3.5 w-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
                <path stroke-linecap="round" stroke-linejoin="round" d="M4 4v6h6M20 20v-6h-6M20 4l-7 7M4 20l7-7" />
              </svg>
              リセット
            </button>
            <button @click="emit('close')" aria-label="閉じる" class="w-7 h-7 rounded-full bg-slate-100 dark:bg-slate-700 hover:bg-slate-200 dark:hover:bg-slate-600 text-slate-500 dark:text-slate-400 font-bold text-sm flex items-center justify-center transition-colors">×</button>
          </div>
        </div>
        <div class="flex-1 overflow-auto p-3 sm:p-4">
          <div v-if="isLoading" class="py-12 flex flex-col items-center justify-center">
            <div class="w-8 h-8 border-4 border-slate-200 dark:border-slate-700 border-t-indigo-600 dark:border-t-indigo-500 rounded-full animate-spin"></div>
            <p class="mt-3 text-xs text-slate-500 dark:text-slate-400 font-bold">履歴を再構築中...</p>
          </div>
          <div v-else-if="errorMsg" class="py-8 text-center text-red-500 dark:text-red-400 font-bold">{{ errorMsg }}</div>
          <div v-else-if="snapshots.length === 0" class="py-12 text-center text-slate-500 dark:text-slate-400">表示できる履歴がありません</div>
          <div v-else>
            <div v-if="friendHistoryUnavailable" class="mb-3 p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-100 dark:border-amber-900/30 rounded-lg text-[11px] sm:text-xs text-amber-700 dark:text-amber-300 font-bold leading-relaxed">
              <p v-if="virtualArea">バーチャルライバルは履歴がないため、相手のスコアは現在値で固定して変遷を再構築しています。</p>
              <p v-else>このフレンドは履歴を非公開にしているため、相手のスコアは現在値で固定して変遷を再構築しています。</p>
            </div>
            <div class="flex items-center justify-between gap-2 mb-3">
              <!-- 件数 / 割合 トグル -->
              <div class="inline-flex rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden text-xs font-bold shrink-0">
                <button
                  type="button"
                  @click="setDisplayMode('count')"
                  :class="displayMode === 'count'
                    ? 'bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300'
                    : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700'"
                  class="px-3 py-1.5 transition-colors"
                >件数</button>
                <button
                  type="button"
                  @click="setDisplayMode('ratio')"
                  :class="displayMode === 'ratio'
                    ? 'bg-indigo-100 dark:bg-indigo-900/40 text-indigo-700 dark:text-indigo-300'
                    : 'bg-white dark:bg-slate-800 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-700'"
                  class="px-3 py-1.5 transition-colors border-l border-slate-200 dark:border-slate-700"
                >割合</button>
              </div>
              <!-- 割合モード時の凡例代わり注釈。狭い画面では非表示にして崩れを防ぐ。 -->
              <div v-if="displayMode === 'ratio'" class="hidden sm:flex items-center gap-3 text-[10px] sm:text-xs text-slate-500 dark:text-slate-400 font-bold flex-wrap justify-end">
                <span class="inline-flex items-center gap-1">
                  <span class="w-2.5 h-2.5 rounded-sm bg-blue-500/30 border border-blue-500/60"></span>
                  自分優勢
                </span>
                <span class="inline-flex items-center gap-1">
                  <span class="w-2.5 h-2.5 rounded-sm bg-red-500/30 border border-red-500/60"></span>
                  相手優勢
                </span>
                <span class="text-slate-400 dark:text-slate-500">勝率 = WIN / (WIN + LOSS)</span>
              </div>
            </div>
            <div class="relative w-full" style="height: clamp(260px, 60vh, 480px);">
              <Line ref="chartRef" :data="chartData" :options="chartOptions" :plugins="[winRateBackgroundPlugin]" />
              <!-- 割合モードで、まだ両者の共通プレイ曲がゼロなら、グラフ枠内に案内を重ねて出す -->
              <div v-if="displayMode === 'ratio' && !hasRatioData" class="absolute inset-0 flex items-center justify-center pointer-events-none">
                <p class="px-3 py-2 rounded-lg bg-white/85 dark:bg-slate-900/80 text-xs text-slate-500 dark:text-slate-400 font-bold border border-slate-200 dark:border-slate-700">
                  両者がプレイ済みの共通曲がまだありません
                </p>
              </div>
            </div>
            <p class="mt-2 text-[10px] sm:text-xs text-slate-400 dark:text-slate-500 text-center">マウスホイールでズーム / ドラッグでパン</p>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>
