<script setup lang="ts">
/**
 * 【View の役割】 OBS ブラウザソース用の配信オーバーレイ（`/obs/score-overlay`）。
 *
 * キャプチャボードのソースの静止画を obs-websocket で定期的に取り、プレー画面を読み取って、
 * スコアグラフの横に「単曲ティアの拡大目盛り」を出す。YOU の EX スコアを毎回読み、
 * 届いたティアの線をその場で黄色にする。
 *
 * 座標系: このページは 1920×1080 の箱をウィンドウ幅に合わせて拡大縮小して描く。
 * OBS では「ブラウザソースの幅 1920・高さ 1080」にし、キャプチャのソースの「変換」をコピーして
 * そのまま貼り付ければ、ゲーム画面とぴったり重なる。
 *
 * クエリパラメータ:
 *  - source   … キャプチャボードのソース名（OBS のソース一覧の名前そのまま）。必須
 *  - password … obs-websocket のパスワード（設定している場合）
 *  - host / port … 既定 127.0.0.1 / 4455
 *  - interval … 読み取り間隔ミリ秒。既定 250
 *  - debug=1  … 読み取り状況を左上に出す
 *  - test=1   … OBS を使わず、録画や画像ファイルを読み込んで試すモード
 */
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue';
import { createWorker, PSM, type Worker as TesseractWorker } from 'tesseract.js';
import { gameDataReady, songData, diffTable, useGameData, type SongDataEntry } from '../composables/useGameData';
import {
  FOLDER_RANK_DEFS,
  SCORE_RATE_THRESHOLDS,
  calculatePoints,
  calculateScoreRateTierPoints,
  getFolderRankThresholdRateAt,
  TOP_CHART_LIMIT,
} from '../utils/beatTier';
import { getNumericRank, getScoreGradeInfo, getSongTierInfoByRate, tierLabel } from '../utils/uploadReport';
import { API_BASE, TOKEN_KEY } from '../composables/constants';
import RankIcon from '../components/RankIcon.vue';
import LoginModal from '../components/LoginModal.vue';
import { useAuth } from '../composables/useAuth';
import {
  binarizeForOcr,
  detectPlayLayout,
  fingerprintDistance,
  frameMeanLuma,
  isPlayScoreAreaVisible,
  readChartHeader,
  readCurrentExScore,
  titleFingerprint,
  type DifficultyName,
  type PlayLayout,
  type RgbaFrame,
} from '../utils/iidxPlayScreen';
import { matchChartByOcr } from '../utils/overlaySongMatch';
import { startResultTimer, stepResultTimer, type ResultTimerState } from '../utils/overlayResultTimer';
import { ObsWebSocketClient } from '../utils/obsWebSocket';

const params = new URLSearchParams(window.location.search);
const testMode = params.get('test') === '1';
// 読み取り状況（配置・曲・EX・OCR）の左上表示は debug=1 のときだけ。テストモードでも既定では出さない。
const debug = params.get('debug') === '1';
const sourceName = params.get('source') ?? '';
const password = params.get('password') ?? '';
const obsUrl = `ws://${params.get('host') || '127.0.0.1'}:${params.get('port') || '4455'}`;
const intervalMs = Math.max(100, Number(params.get('interval')) || 250);
/** テストモード専用: 難易度表に無い譜面でも目盛りを確認できるよう、☆を上書きする（例 rank=12.0）。 */
const testRankOverride = testMode ? params.get('rank') || null : null;
/** テストモード専用: 読み取った EX の代わりにこの値を使う（達成時の表示確認用、例 ex=3070）。 */
const testExOverride = testMode && params.get('ex') ? Number(params.get('ex')) : null;
/** テストモード専用: リザルトの「前回ベスト」をこの値にする（表示確認用、例 best=2455）。 */
const testBestOverride = testMode && params.get('best') ? Number(params.get('best')) : null;
/** テストモード専用: 録画の指定区間を真っ黒にしてプレー中の暗転を再現する（例 blackout=60:2）。 */
const testBlackout = (() => {
  const m = testMode ? (params.get('blackout') ?? '').match(/^(\d+(?:\.\d+)?):(\d+(?:\.\d+)?)$/) : null;
  return m ? { from: Number(m[1]), sec: Number(m[2]) } : null;
})();
/** テストモード専用: 歴代ベストの過去作記録を上書きする（例 alltime=2480:33）。 */
const testAllTimeOverride = (() => {
  const m = testMode ? (params.get('alltime') ?? '').match(/^(\d+):(\d+)$/) : null;
  return m ? { score: Number(m[1]), version: Number(m[2]) } : null;
})();

const BASE_W = 1920;
const BASE_H = 1080;
/** プレー画面が見えなくなってから「曲が終わった」とみなすまでの時間。 */
const LOST_PLAY_MS = 1500;
/** 曲名の指紋がこれ以上変わったら別の曲とみなして読み直す。 */
const TITLE_CHANGE_THRESHOLD = 12;
/** 1 回の読み取り間で自然に増えうる EX の上限。これを超える跳びは 2 回続けて読めたときだけ採る。 */
const MAX_EX_STEP = 80;
/** 画面全体の平均輝度がこれ未満なら暗転とみなす。 */
const DARK_LUMA = 22;
const DIFF_CODE: Record<DifficultyName, string> = {
  BEGINNER: '1', NORMAL: '2', HYPER: '3', ANOTHER: '4', LEGGENDARIA: '10',
};

// ---------------------------------------------------------------------------
// 状態
// ---------------------------------------------------------------------------

interface IdentifiedChart {
  title: string;
  artist: string;
  difficulty: DifficultyName;
  maxEx: number;
  /** 非公式難易度（☆11.0〜13.1）。範囲外や未掲載は null（ティアの目盛りは出さない）。 */
  informalRank: string | null;
}

/**
 * ログイン必須。OBS のブラウザソースは普段のブラウザとログイン状態を共有しないので、
 * 未ログインならこのページの中でログイン画面を出す（OBS ではソースを右クリック →「対話」で入力する）。
 * ログイン情報は localStorage に残るので、次回からは入力不要。
 */
const { isLoggedIn, isLoading: authLoading, authHeaders, fetchCurrentUser } = useAuth();
/** 未ログインでログイン画面を出している間 true。 */
const needLogin = ref(false);

const status = ref('起動中…');
const layout = shallowRef<PlayLayout | null>(null);
const chart = ref<IdentifiedChart | null>(null);
const identifying = ref(false);
const ocrDebug = ref('');
const exScore = ref<number | null>(null);
let pendingEx: number | null = null;
let lastPlaySeenAt = 0;
let currentFingerprint: number[] | null = null;
let nextIdentifyAt = 0;

const viewportScale = ref(1);
const updateScale = () => {
  viewportScale.value = Math.min(window.innerWidth / BASE_W, window.innerHeight / BASE_H) || 1;
};

// ---------------------------------------------------------------------------
// 非公式難易度の索引（ANOTHER = 曲名、LEGGENDARIA = 曲名[L]）
// ---------------------------------------------------------------------------

const informalIndex = computed(() => {
  const m = new Map<string, string>();
  for (const r of diffTable.value) {
    for (const s of r.songs) {
      if (s.endsWith('[L]')) m.set(`${s.slice(0, -3)}_LEGGENDARIA`, r.rank);
      else m.set(`${s}_ANOTHER`, r.rank);
    }
  }
  return m;
});

// ---------------------------------------------------------------------------
// 曲の特定（OCR）
// ---------------------------------------------------------------------------

let ocrEng: TesseractWorker | null = null;
let ocrJpn: TesseractWorker | null = null;
let ocrInit: Promise<void> | null = null;

/** tesseract のワーカーを 2 つ用意する。英語だけの方が英字の曲名を正確に読み、日本語込みは仮名・漢字を読める。 */
function ensureOcr(): Promise<void> {
  if (!ocrInit) {
    ocrInit = (async () => {
      const [eng, jpn] = await Promise.all([createWorker('eng'), createWorker(['jpn', 'eng'])]);
      for (const w of [eng, jpn]) await w.setParameters({ tessedit_pageseg_mode: PSM.SINGLE_LINE });
      ocrEng = eng;
      ocrJpn = jpn;
    })();
  }
  return ocrInit;
}

function frameToCanvas(f: RgbaFrame): HTMLCanvasElement {
  const c = document.createElement('canvas');
  c.width = f.width;
  c.height = f.height;
  c.getContext('2d')!.putImageData(new ImageData(new Uint8ClampedArray(f.data), f.width, f.height), 0, 0);
  return c;
}

async function identifyChart(frame: RgbaFrame, l: PlayLayout): Promise<void> {
  const header = readChartHeader(frame, l);
  if (!header.difficulty) {
    ocrDebug.value = '難易度の札が読めません';
    return;
  }
  await ensureOcr();
  const titleCanvas = frameToCanvas(binarizeForOcr(frame, header.titleRect));
  const artistCanvas = frameToCanvas(binarizeForOcr(frame, header.artistRect));
  const read = async (w: TesseractWorker, c: HTMLCanvasElement) => (await w.recognize(c)).data.text.trim();
  const titles = [await read(ocrEng!, titleCanvas), await read(ocrJpn!, titleCanvas)];
  const artists = [await read(ocrEng!, artistCanvas), await read(ocrJpn!, artistCanvas)];

  const code = DIFF_CODE[header.difficulty];
  const pool = (songData.value as SongDataEntry[]).filter(s => s.difficulty === code && s.notes > 0);
  // 英語・日本語の読みの組合せのうち、照合スコアが最も高いものを採る
  let result = matchChartByOcr(pool, titles[0], artists[0]);
  for (const t of titles) {
    for (const a of artists) {
      const r = matchChartByOcr(pool, t, a);
      if ((r.ranked[0]?.score ?? 0) > (result.ranked[0]?.score ?? 0)) result = r;
    }
  }
  ocrDebug.value = `OCR: ${titles.join(' / ')} | ${artists.join(' / ')} → ${result.ranked
    .slice(0, 3)
    .map(r => `${r.chart.title}(${r.score.toFixed(2)})`)
    .join(', ')}`;
  if (!result.best) return;

  const s = result.best.chart;
  chart.value = {
    title: s.title,
    artist: s.artist,
    difficulty: header.difficulty,
    maxEx: s.notes * 2,
    informalRank: testRankOverride ?? informalIndex.value.get(`${s.title}_${header.difficulty}`) ?? null,
  };
}

// ---------------------------------------------------------------------------
// 1 フレームの処理
// ---------------------------------------------------------------------------

function acceptEx(v: number | null): void {
  if (v === null) return;
  const max = chart.value?.maxEx;
  if (max !== undefined && v > max) return;
  const cur = exScore.value;
  if (cur === null || (v >= cur && v - cur <= MAX_EX_STEP)) {
    exScore.value = v;
    pendingEx = null;
    return;
  }
  // 大きな跳びや減少は誤読の可能性があるので、同じ値が 2 回続いたときだけ採る
  if (pendingEx === v) {
    exScore.value = v;
    pendingEx = null;
  } else {
    pendingEx = v;
  }
}

function resetPlay(): void {
  chart.value = null;
  exScore.value = null;
  pendingEx = null;
  currentFingerprint = null;
  nextIdentifyAt = 0;
  ocrDebug.value = '';
}

// ---------------------------------------------------------------------------
// リザルト表示
// ---------------------------------------------------------------------------

interface ResultCard {
  chart: IdentifiedChart;
  ex: number;
  /** 今回より前の自己ベスト（最後に取り込んだ CSV の記録と、この配信中のプレーの高い方）。未プレーは 0。 */
  best: number | null;
  /**
   * 今回より前の歴代自己ベスト（今作の記録と前作以前の記録の高い方）と、その作品の表示名。
   * どちらの記録も無ければ null。
   */
  allTime: { score: number; label: string } | null;
  shownAt: number;
}

const resultCard = ref<ResultCard | null>(null);
/** 表示中のリザルトを消すタイミングの判定状態。 */
let resultTimer: ResultTimerState | null = null;
/** リザルト表示中に、プレー画面と判定されたフレームが続いた数。 */
let playStreak = 0;
/** リザルト表示中、プレー画面がこのフレーム数続いたら表示を消して次の曲に移る。 */
const PLAY_STREAK_TO_HIDE_RESULT = 3;
/** 自己ベストの読み込みエラー（status と違い、読み取りのたびに消さない）。 */
const bestError = ref('');
/** ログインユーザーの自己ベスト（曲名|難易度 → EX）。この配信中に更新したらここも上げる。 */
const bestScores = new Map<string, number>();
const bestKey = (c: IdentifiedChart) => `${c.title}|${c.difficulty}`;
/** プレー画面が消えた直後の「確定前のリザルト」。暗転を見たら表示に移す。 */
let pendingResult: { chart: IdentifiedChart; ex: number } | null = null;

/** 前作以前の自己ベスト（曲名|難易度 → 最高 EX とその作品）。今作の記録は含めない。 */
const pastBestScores = new Map<string, { score: number; version: number }>();
/** 作品番号 → 作品名（例 33 → "Sparkle Shower"）。前作スコアの一覧 API から取る。 */
const versionNames = new Map<number, string>();

/** 【関数の役割】 前作以前のスコアを読み、譜面ごとの過去作ベストを作る。失敗しても今作の表示は続ける。 */
async function loadPastBestScores(): Promise<void> {
  try {
    const [bestRes, sumRes] = await Promise.all([
      fetch(`${API_BASE}/api/scores/past/best`, { headers: authHeaders(), cache: 'no-store' }),
      fetch(`${API_BASE}/api/scores/past/summary`, { headers: authHeaders(), cache: 'no-store' }),
    ]);
    if (sumRes.ok) {
      for (const s of (await sumRes.json()) as { version: number; versionName: string }[]) {
        versionNames.set(s.version, s.versionName);
      }
    }
    if (!bestRes.ok) return;
    const rows = (await bestRes.json()) as { v: number; t: string; d: string; s: number | null }[];
    pastBestScores.clear();
    for (const r of rows) {
      const key = `${r.t}|${r.d}`;
      const score = r.s ?? 0;
      const cur = pastBestScores.get(key);
      // 同点なら新しい作品の記録を採る
      if (score > 0 && (!cur || score > cur.score || (score === cur.score && r.v > cur.version))) {
        pastBestScores.set(key, { score, version: r.v });
      }
    }
  } catch {
    // 過去作スコアが読めなくても、今作との比較は出す
  }
}

/** 【関数の役割】 ログインユーザーの全スコアを読み、譜面ごとの自己ベストを作る。401 なら false（ログインし直し）。 */
async function loadBestScores(): Promise<boolean> {
  try {
    const res = await fetch(`${API_BASE}/api/scores/me`, { headers: authHeaders(), cache: 'no-store' });
    if (res.status === 401 || res.status === 403) return false;
    if (!res.ok) {
      bestError.value = `自己ベストを読めませんでした (HTTP ${res.status})`;
      return true;
    }
    const rows = (await res.json()) as { title: string; difficultyName: string; score: number }[];
    bestScores.clear();
    for (const r of rows) {
      const key = `${r.title}|${r.difficultyName}`;
      if ((r.score ?? 0) > (bestScores.get(key) ?? 0)) bestScores.set(key, r.score);
    }
    bestError.value = '';
    bestVersion.value++;
  } catch (e) {
    bestError.value = `自己ベストの読み込みに失敗: ${(e as Error).message}`;
  }
  await loadPastBestScores();
  return true;
}

function showResult(p: { chart: IdentifiedChart; ex: number }): void {
  const key = bestKey(p.chart);
  const best = testBestOverride ?? bestScores.get(key) ?? 0;
  // 歴代: 今作のベストと過去作のベストの高い方。同点なら今作を採る
  const past = testAllTimeOverride
    ? { score: testAllTimeOverride.score, version: testAllTimeOverride.version }
    : pastBestScores.get(key);
  let allTime: ResultCard['allTime'] = best > 0 ? { score: best, label: '今作' } : null;
  if (past && past.score > (allTime?.score ?? 0)) {
    const name = versionNames.get(past.version);
    allTime = { score: past.score, label: name ? `${past.version} ${name}` : `IIDX ${past.version}` };
  }
  const now = performance.now();
  resultCard.value = { chart: p.chart, ex: p.ex, best, allTime, shownAt: now };
  resultTimer = startResultTimer(now);
  // 同じ配信の中で同じ曲をもう一度やったとき、今回のスコアを「前回ベスト」として比べる
  if (p.ex > (bestScores.get(key) ?? 0)) {
    bestScores.set(key, p.ex);
    bestVersion.value++;
  }
}

/** 表示中のリザルトの中身（取り込み時のレポートと同じ計算）。 */
const resultView = computed(() => {
  const r = resultCard.value;
  if (!r) return null;
  const { chart: c, ex, best, allTime } = r;
  const rate = c.maxEx > 0 ? (ex / c.maxEx) * 100 : 0;
  const rank = c.informalRank ?? undefined;
  const grade = getScoreGradeInfo(ex, c.maxEx);
  const newTier = getSongTierInfoByRate(rate, rank);
  const oldRate = best && c.maxEx > 0 ? (best / c.maxEx) * 100 : 0;
  const oldTier = best ? getSongTierInfoByRate(oldRate, rank) : null;
  const tierChanged = !!(oldTier && newTier && tierLabel(oldTier) !== tierLabel(newTier));
  const pt = rank ? calculatePoints(rate, rank) : 0;
  const oldPt = rank && best ? calculatePoints(oldRate, rank) : 0;
  // 次のサブティアまでの残り
  let next: { label: string; gap: number } | null = null;
  if (newTier && rank) {
    const idx = FOLDER_RANK_DEFS.findIndex(d => d.name === newTier.name && (d.tier ?? null) === (newTier.tier ?? null));
    if (idx > 0) {
      const need = Math.ceil((c.maxEx * getFolderRankThresholdRateAt(idx - 1, rank)) / 100 - 1e-9);
      const d = FOLDER_RANK_DEFS[idx - 1];
      next = { label: d.tier ? `${d.name} ${d.tier}` : d.name, gap: need - ex };
    }
  }
  const isNewRecord = best !== null && ex > best;
  // RATE-PT（全譜面共通の score rate → 点数。サイトの RATE-PT と同じ計算）
  // RATE-PT はサイトと同じく ANOTHER / LEGGENDARIA だけ
  const rateTarget = isRatePtTarget(c);
  const ratePt = rateTarget ? calculateScoreRateTierPoints(rate) : 0;
  const oldRatePt = rateTarget && best ? calculateScoreRateTierPoints(oldRate) : 0;
  // 次の Rate-PT の区切り（まだ届いていない一番下の区切り）
  let rateNext: { label: string; right: string; gap: number } | null = null;
  for (const t of rateTarget ? SCORE_RATE_THRESHOLDS : []) {
    const need = Math.ceil((c.maxEx * t.rate) / 100 - 1e-9);
    if (ex < need) {
      const loss = c.maxEx - need;
      rateNext = { label: `${t.points}PT`, right: loss === 0 ? 'MAX' : `MAX-${loss}`, gap: need - ex };
      break;
    }
  }
  return {
    ratePt,
    ratePtDiff: best !== null && isNewRecord ? ratePt - oldRatePt : null,
    rateNext,
    title: c.title,
    difficulty: c.difficulty,
    // ☆は数値のときだけ出す（未分類の新曲は "Uncategorized" なので出さない）
    rank: getNumericRank(c.informalRank ?? undefined),
    ex,
    maxEx: c.maxEx,
    rate,
    grade,
    best,
    diff: best !== null ? ex - best : null,
    isNewRecord,
    newTier,
    oldTier: tierChanged && isNewRecord ? oldTier : null,
    pt,
    ptDiff: best !== null && isNewRecord ? pt - oldPt : null,
    next,
    allTime,
    allTimeDiff: allTime ? ex - allTime.score : null,
    // 歴代ベストの更新（過去作の記録を初めて超えたときも含む）。今作ベストの更新と同時なら両方出す
    isNewAllTime: !!allTime && ex > allTime.score,
  };
});

/**
 * 【関数の役割】 プレー画面が見えないフレームでの、リザルトの出し入れ。
 *
 *  - プレー直後: 暗転（平均輝度が {@link DARK_LUMA} 未満）を見たら表示する。暗転が映らない環境もあるので、
 *    プレー画面が {@link LOST_PLAY_MS} 消えたままでも表示する（プレーのあとは必ずリザルトが来る）。
 *  - 表示中: リザルト画面（明るい画面）が十分映ったあとの次の暗転（選曲画面へ戻る切り替わり）で消す。
 *    判定は utils/overlayResultTimer.ts。次のプレー画面が出たら即座に消す（processFrame 側）。
 */
function handleNonPlayFrame(frame: RgbaFrame, now: number): void {
  playStreak = 0;
  const dark = frameMeanLuma(frame) < DARK_LUMA;
  if (pendingResult && (dark || now - lastPlaySeenAt > LOST_PLAY_MS)) {
    // 誤判定（グラフ欄の背景の暗転など）でプレー中にリザルトを出してしまった場合に、
    // プレー画面へ戻ったら元に戻せるよう、プレーの状態と自己ベストを控えておく
    const key = bestKey(pendingResult.chart);
    suspendedPlay = {
      chart: pendingResult.chart,
      ex: pendingResult.ex,
      layoutId: layout.value?.id ?? '',
      fingerprint: currentFingerprint,
      key,
      prevBest: bestScores.get(key),
      shownAt: now,
    };
    showResult(pendingResult);
    pendingResult = null;
    // プレーは終わったので、ここでプレーの状態を片付ける。残しておくと次のフレームで
    // 同じプレーがもう一度リザルト候補になり、更新後の自己ベストと比べて「差 0」になる。
    layout.value = null;
    resetPlay();
    return;
  }
  if (resultCard.value && resultTimer && stepResultTimer(resultTimer, now, dark)) {
    resultCard.value = null;
    resultTimer = null;
  }
}

/** リザルトを出す直前のプレーの状態（誤ってリザルトを出したとき、プレーへ戻すため）。 */
let suspendedPlay: {
  chart: IdentifiedChart;
  ex: number;
  layoutId: string;
  fingerprint: number[] | null;
  key: string;
  /** リザルトで上書きする前の自己ベスト（無ければ undefined）。 */
  prevBest: number | undefined;
  shownAt: number;
} | null = null;
/** リザルトを出してからこの時間内にプレー画面へ戻ったら、誤表示とみなしてプレーを続ける。 */
const RESUME_WITHIN_MS = 8000;
/** 曲名の見た目がこのフレーム数続けて変わったら別の曲とみなす（250ms 間隔で約 1 秒）。 */
const TITLE_CHANGE_FRAMES = 4;
let titleChangeStreak = 0;

/**
 * 【関数の役割】 リザルト表示中にプレー画面が続いていると分かったとき、同じプレーなら状態を元に戻す。
 *
 * 同じプレーとみなす条件: 表示から {@link RESUME_WITHIN_MS} 以内・グラフの配置が同じ・曲名の指紋が同じ・
 * 今読める EX が控えた EX 以上（同じ曲をやり直した場合は EX が 0 から始まるので、ここで別のプレーと分かる）。
 * 戻すもの: 曲・EX・曲名の指紋・自己ベスト（リザルトで上書きした分を取り消す）。
 */
function resumeSuspendedPlay(frame: RgbaFrame, l: PlayLayout, now: number): void {
  const s = suspendedPlay;
  suspendedPlay = null;
  if (!s || now - s.shownAt > RESUME_WITHIN_MS || s.layoutId !== l.id || !s.fingerprint) return;
  const fp = titleFingerprint(frame, readChartHeader(frame, l).titleRect);
  if (fingerprintDistance(fp, s.fingerprint) > TITLE_CHANGE_THRESHOLD) return;
  const exNow = readCurrentExScore(frame, l);
  if (exNow === null || exNow < s.ex) return;
  layout.value = l;
  chart.value = s.chart;
  exScore.value = exNow;
  currentFingerprint = fp;
  if (s.prevBest === undefined) bestScores.delete(s.key);
  else bestScores.set(s.key, s.prevBest);
  bestVersion.value++;
}

function processFrame(frame: RgbaFrame): void {
  const now = performance.now();
  let det = detectPlayLayout(frame);
  // 目盛り線が一時的に見えなくても（棒グラフや背景の絵で判定が外れる）、同じ配置で
  // YOU とペースメーカーの数字が読めている間はプレーが続いているとみなす。
  // これが無いと、プレー中に線の判定が 1.5 秒途切れただけでリザルトが出てしまう。
  if (!det && layout.value && isPlayScoreAreaVisible(frame, layout.value)) {
    det = { layout: layout.value, confidence: 0 };
  }
  if (!det) {
    // プレー画面が消えた最初のフレームで、最終スコアを確定前のリザルトとして控える
    if (layout.value && !pendingResult && chart.value && exScore.value !== null) {
      pendingResult = { chart: { ...chart.value }, ex: exScore.value };
    }
    if (layout.value && now - lastPlaySeenAt > LOST_PLAY_MS) {
      layout.value = null;
      resetPlay();
    }
    handleNonPlayFrame(frame, now);
    return;
  }
  // リザルト表示中は、プレー画面が数フレーム続いたときだけ「次の曲が始まった」とみなす。
  // リザルト画面の 1 フレームの誤判定で表示が消えないようにするため。
  if (resultCard.value) {
    playStreak++;
    if (playStreak < PLAY_STREAK_TO_HIDE_RESULT) return;
    resultCard.value = null;
    resultTimer = null;
    resumeSuspendedPlay(frame, det.layout, now);
  }
  suspendedPlay = null;
  playStreak = 0;
  // プレー画面に戻った（誤検知の一瞬の途切れ、または次の曲）。リザルトの候補は捨てる
  pendingResult = null;
  lastPlaySeenAt = now;
  if (layout.value?.id !== det.layout.id) {
    layout.value = det.layout;
  }

  const header = readChartHeader(frame, det.layout);
  const fp = titleFingerprint(frame, header.titleRect);
  // 曲名の見た目が数フレーム続けて変わったときだけ別の曲とみなす。リザルトへ切り替わる瞬間などに
  // 1 フレームだけ曲名の部分が変わると、そこでプレーの記録を消してしまい、リザルトが出なくなっていた。
  if (currentFingerprint && fingerprintDistance(fp, currentFingerprint) > TITLE_CHANGE_THRESHOLD) {
    titleChangeStreak++;
    if (titleChangeStreak >= TITLE_CHANGE_FRAMES) {
      titleChangeStreak = 0;
      resetPlay();
      currentFingerprint = fp;
    }
  } else {
    titleChangeStreak = 0;
    currentFingerprint = fp;
  }

  if (!chart.value && !identifying.value && now >= nextIdentifyAt) {
    identifying.value = true;
    identifyChart(frame, det.layout)
      .catch(e => { ocrDebug.value = `OCR エラー: ${(e as Error).message}`; })
      .finally(() => {
        identifying.value = false;
        // 特定できなかったら 2 秒おいて読み直す
        if (!chart.value) nextIdentifyAt = performance.now() + 2000;
      });
  }

  acceptEx(testExOverride ?? readCurrentExScore(frame, det.layout));
}

// ---------------------------------------------------------------------------
// フレームの取得（OBS / テスト）
// ---------------------------------------------------------------------------

const workCanvas = document.createElement('canvas');
const workCtx = workCanvas.getContext('2d', { willReadFrequently: true })!;

function grab(src: CanvasImageSource, w: number, h: number): RgbaFrame {
  if (workCanvas.width !== w || workCanvas.height !== h) {
    workCanvas.width = w;
    workCanvas.height = h;
  }
  workCtx.drawImage(src, 0, 0, w, h);
  return workCtx.getImageData(0, 0, w, h);
}

let stopped = false;
let loopTimer: ReturnType<typeof setTimeout> | null = null;
const obs = new ObsWebSocketClient();

async function obsTick(): Promise<void> {
  if (!obs.connected) {
    status.value = `OBS に接続中… (${obsUrl})`;
    try {
      await obs.connect(obsUrl, password);
      status.value = '';
    } catch (e) {
      status.value = (e as Error).message;
      return;
    }
  }
  const dataUrl = await obs.getSourceScreenshot(sourceName);
  const blob = await (await fetch(dataUrl)).blob();
  const bmp = await createImageBitmap(blob);
  try {
    processFrame(grab(bmp, bmp.width, bmp.height));
  } finally {
    bmp.close();
  }
  status.value = '';
}

const testVideo = ref<HTMLVideoElement | null>(null);
const testImage = ref<HTMLImageElement | null>(null);
const testMediaUrl = ref('');
const testMediaKind = ref<'video' | 'image' | null>(null);

function testTick(): void {
  if (testMediaKind.value === 'video' && testVideo.value && testVideo.value.videoWidth) {
    const v = testVideo.value;
    const f = grab(v, v.videoWidth, v.videoHeight);
    // テスト専用: blackout=開始秒:秒数 の区間は真っ黒なフレームにする（プレー中の暗転の再現）
    if (testBlackout && v.currentTime >= testBlackout.from && v.currentTime < testBlackout.from + testBlackout.sec) {
      f.data.fill(0);
      for (let i = 3; i < f.data.length; i += 4) f.data[i] = 255;
    }
    processFrame(f);
  } else if (testMediaKind.value === 'image' && testImage.value?.naturalWidth) {
    const i = testImage.value;
    processFrame(grab(i, i.naturalWidth, i.naturalHeight));
  }
}

function onTestFile(ev: Event): void {
  const file = (ev.target as HTMLInputElement).files?.[0];
  if (!file) return;
  if (testMediaUrl.value) URL.revokeObjectURL(testMediaUrl.value);
  layout.value = null;
  resetPlay();
  testMediaUrl.value = URL.createObjectURL(file);
  testMediaKind.value = file.type.startsWith('video/') ? 'video' : 'image';
}

async function loop(): Promise<void> {
  if (stopped) return;
  const started = performance.now();
  try {
    if (testMode) testTick();
    else await obsTick();
  } catch (e) {
    status.value = (e as Error).message;
  }
  const wait = Math.max(intervalMs - (performance.now() - started), testMode ? 50 : 30);
  // 接続に失敗しているときは 3 秒おきに再試行する
  loopTimer = setTimeout(loop, !testMode && !obs.connected ? 3000 : wait);
}

// ---------------------------------------------------------------------------
// 目盛りの計算
// ---------------------------------------------------------------------------

interface LadderLine {
  key: string;
  /** 線の左のラベル（ティア名、または Rate-PT の点数）。 */
  label: string;
  requiredEx: number;
  /** 線の右の数字（ティアは必要 EX、Rate-PT は MAX からの減点 "MAX-146"）。 */
  right: string;
  y: number;
  achieved: boolean;
  isNext: boolean;
}

type LadderDef = Omit<LadderLine, 'y' | 'achieved' | 'isNext'>;

/** 【関数の役割】 単曲ティアの目盛り（MAX と 11 本のブロック境界）。☆11.0〜13.1 以外は null。 */
function buildTierDefs(c: IdentifiedChart): LadderDef[] | null {
  if (!c.informalRank) return null;
  const defs: LadderDef[] = [{ key: 'MAX', label: 'MAX', requiredEx: c.maxEx, right: String(c.maxEx) }];
  for (let b = 0; b <= 10; b++) {
    const idx = b * 5;
    const rate = getFolderRankThresholdRateAt(idx, c.informalRank);
    if (!rate) return null;
    const requiredEx = Math.ceil((c.maxEx * rate) / 100 - 1e-9);
    defs.push({ key: FOLDER_RANK_DEFS[idx].name, label: FOLDER_RANK_DEFS[idx].name, requiredEx, right: String(requiredEx) });
  }
  return defs;
}

/**
 * 【関数の役割】 Rate-PT の目盛り（Lv10 以下など、単曲ティアの無い譜面用）。
 *
 * Rate-PT の区切り（AA=1PT, AAA=2PT, 以降 MAX までの残りが半分になるごとに倍）を上から並べ、
 * 右の数字は「MAX からの減点」で出す（例: 4PT = MAX-146）。
 */
function buildRateDefs(c: IdentifiedChart): LadderDef[] {
  return [...SCORE_RATE_THRESHOLDS].reverse().map(t => {
    const requiredEx = Math.ceil((c.maxEx * t.rate) / 100 - 1e-9);
    const loss = c.maxEx - requiredEx;
    return { key: `${t.points}PT`, label: `${t.points}PT`, requiredEx, right: loss === 0 ? 'MAX' : `MAX-${loss}` };
  });
}

/** RATE-PT の対象難易度（サイトの RATE-PT と同じく ANOTHER / LEGGENDARIA のみ）。 */
const isRatePtTarget = (c: IdentifiedChart) => c.difficulty === 'ANOTHER' || c.difficulty === 'LEGGENDARIA';

/**
 * 目盛りの種類。☆11.0〜13.1 の譜面は単曲ティア、それ以外の ANOTHER / LEGGENDARIA（Lv10 以下・未分類の新曲）は
 * Rate-PT。HYPER などはどちらの対象でもないので目盛りを出さない。
 */
const ladderKind = computed<'tier' | 'rate' | null>(() => {
  const c = chart.value;
  if (!c) return null;
  if (buildTierDefs(c)) return 'tier';
  return isRatePtTarget(c) ? 'rate' : null;
});

/** 目盛りの帯の幅と、グラフとの隙間（1920×1080 基準）。 */
const STRIP_W = 230;
const STRIP_GAP = 8;
/**
 * 帯の上端からグラフの理論値の高さまでの余白。
 * 実機の GRAPH INFORMATION 欄に合わせて、オレンジの見出し・YOU 行・NEXT 行・☆の札をここに置く。
 */
const STRIP_PAD_TOP = 122;
/** グラフ部分の上端から MAX の線までの高さ。☆の札（上）と MAX のラベル（線の上）が重ならない高さ。 */
const GRAPH_HEAD = 38;
/** 帯の下端の余白（0 点の位置より下）。 */
const STRIP_PAD_BOTTOM = 6;

const strip = computed(() => {
  const l = layout.value;
  if (!l) return null;
  // グラフの、BGA 側（レーンの反対側）に置く
  const faceLeft = (l.side === '1P') === (l.placement === 'far');
  return {
    x: faceLeft ? l.graphX0 - STRIP_GAP - STRIP_W : l.graphX1 + STRIP_GAP,
    top: l.fullY,
    bottom: l.zeroY,
    faceLeft,
  };
});

/**
 * MAX と 11 本のブロック境界（Legend 〜 Novice）。上から下へ等間隔に並べ、
 * Novice の下にもう 1 区間あけて 0 点（グラフの下端）にする。棒はそこから伸びる。
 */
const ladder = computed<LadderLine[] | null>(() => {
  const c = chart.value;
  const s = strip.value;
  if (!c || !s || c.maxEx <= 0 || !ladderKind.value) return null;
  const defs = ladderKind.value === 'tier' ? buildTierDefs(c)! : buildRateDefs(c);
  const ex = exScore.value ?? 0;
  // defs.length 本の線 + 0 点 = defs.length 区間
  const step = (s.bottom - s.top) / defs.length;
  // 達成済みは下側に連なるので、未達成のうち一番下が次の目標
  let nextIdx = -1;
  for (let i = defs.length - 1; i >= 0; i--) {
    if (ex < defs[i].requiredEx) { nextIdx = i; break; }
  }
  return defs.map((d, i) => ({
    ...d,
    y: s.top + step * i,
    achieved: ex >= d.requiredEx,
    isNext: i === nextIdx,
  }));
});

/**
 * YOU の棒の上端の y。隣り合う目盛りの間は EX で線形補間する。
 * Novice 未満は「0 点 = 帯の下端」から Novice までを線形に伸ばす。
 */
function exToY(ex: number): number | null {
  const lines = ladder.value;
  const s = strip.value;
  if (!lines || !s) return null;
  const points = [...lines, { requiredEx: 0, y: s.bottom }];
  if (ex >= points[0].requiredEx) return points[0].y;
  for (let i = 0; i < points.length - 1; i++) {
    const hi = points[i];
    const lo = points[i + 1];
    if (ex >= lo.requiredEx) {
      const t = (ex - lo.requiredEx) / Math.max(1, hi.requiredEx - lo.requiredEx);
      return lo.y - t * (lo.y - hi.y);
    }
  }
  return s.bottom;
}

const barTopY = computed(() => exToY(exScore.value ?? 0));

// ---------------------------------------------------------------------------
// 自己ベスト・上位 100 譜面入りの線
// ---------------------------------------------------------------------------

/** 自己ベストが変わったら（読み込み直後・リザルトで更新したとき）上位 100 の計算をやり直すための版数。 */
const bestVersion = ref(0);

const DIFF_NAME_BY_CODE: Record<string, DifficultyName> = {
  '1': 'BEGINNER', '2': 'NORMAL', '3': 'HYPER', '4': 'ANOTHER', '10': 'LEGGENDARIA',
};

/**
 * 【関数の役割】 今作の自己ベストから、譜面ごとの BEAT-PT と RATE-PT を出す（サイトと同じ計算）。
 *  - BEAT-PT: 非公式難易度（☆）のある譜面だけ。calculatePoints(score rate, ☆)
 *  - RATE-PT: ANOTHER / LEGGENDARIA だけ。calculateScoreRateTierPoints(score rate)
 */
const myChartPoints = computed(() => {
  void bestVersion.value;
  const notesByKey = new Map<string, number>();
  for (const s of songData.value as SongDataEntry[]) {
    const name = DIFF_NAME_BY_CODE[s.difficulty];
    if (name && s.notes > 0) notesByKey.set(`${s.title}|${name}`, s.notes);
  }
  const beat: { key: string; pt: number }[] = [];
  const rate: { key: string; pt: number }[] = [];
  for (const [key, score] of bestScores) {
    const notes = notesByKey.get(key);
    if (!notes || score <= 0) continue;
    const r = (score / (notes * 2)) * 100;
    const [title, diff] = key.split('|');
    const rank = informalIndex.value.get(`${title}_${diff}`);
    if (rank) {
      const pt = calculatePoints(r, rank);
      if (pt > 0) beat.push({ key, pt });
    }
    if (diff === 'ANOTHER' || diff === 'LEGGENDARIA') {
      const pt = calculateScoreRateTierPoints(r);
      if (pt > 0) rate.push({ key, pt });
    }
  }
  beat.sort((a, b) => b.pt - a.pt);
  rate.sort((a, b) => b.pt - a.pt);
  return { beat, rate };
});

/**
 * 【関数の役割】 この譜面を除いた他の譜面の中で 100 番目の点数（上位 100 に入るにはこれを上回る必要がある）。
 * 他の譜面が 100 譜面に満たなければ、どんな点数でも入れるので null（線を出さない）。
 */
function top100Threshold(list: { key: string; pt: number }[], selfKey: string): number | null {
  let count = 0;
  for (const e of list) {
    if (e.key === selfKey) continue;
    count++;
    if (count === TOP_CHART_LIMIT) return e.pt;
  }
  return null;
}

/** 【関数の役割】 点数がしきい値を上回る最小の EX（理論値でも届かなければ null）。点数は EX に対して単調。 */
function minExAbove(maxEx: number, threshold: number, ptOf: (ratePct: number) => number): number | null {
  if (ptOf(100) <= threshold) return null;
  let lo = 0;
  let hi = maxEx;
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2);
    if (ptOf((mid / maxEx) * 100) > threshold) hi = mid;
    else lo = mid + 1;
  }
  return lo;
}

interface ExtraMarker {
  key: 'best' | 'beat' | 'rate';
  label: string;
  ex: number;
  y: number;
  /** 札の y（近い札どうしが重ならないようにずらした位置）。 */
  tagY: number;
  achieved: boolean;
}

/**
 * 目盛りの上に重ねる 3 本の線。
 *  - 自己ベスト（今作。プレー前の記録）
 *  - BEAT-PT の上位 100 入り（☆のある譜面で、理論値なら入れるときだけ）
 *  - RATE-PT の上位 100 入り（ANOTHER / LEGGENDARIA）
 */
const extraMarkers = computed<ExtraMarker[]>(() => {
  void bestVersion.value;
  const c = chart.value;
  if (!c || !ladder.value || c.maxEx <= 0) return [];
  const key = bestKey(c);
  const ex = exScore.value ?? 0;
  const list: Omit<ExtraMarker, 'y' | 'tagY' | 'achieved'>[] = [];
  const best = bestScores.get(key) ?? 0;
  if (best > 0) list.push({ key: 'best', label: '自己ベスト', ex: best });
  const { beat, rate } = myChartPoints.value;
  if (c.informalRank && getNumericRank(c.informalRank)) {
    const th = top100Threshold(beat, key);
    const rank = c.informalRank;
    const need = th === null ? null : minExAbove(c.maxEx, th, r => calculatePoints(r, rank));
    if (need !== null) list.push({ key: 'beat', label: 'BEAT TOP100', ex: need });
  }
  if (isRatePtTarget(c)) {
    const th = top100Threshold(rate, key);
    const need = th === null ? null : minExAbove(c.maxEx, th, r => calculateScoreRateTierPoints(r));
    if (need !== null) list.push({ key: 'rate', label: 'RATE TOP100', ex: need });
  }
  const placed = list
    .map(m => ({ ...m, y: exToY(m.ex) ?? 0, tagY: 0, achieved: ex >= m.ex }))
    .sort((a, b) => a.y - b.y);
  // 札は 18px 以上離す
  let lastTag = -Infinity;
  for (const m of placed) {
    m.tagY = Math.max(m.y, lastTag + 18);
    lastTag = m.tagY;
  }
  return placed;
});

const nextLine = computed(() => ladder.value?.find(l => l.isNext) ?? null);
const nextRemaining = computed(() =>
  nextLine.value && exScore.value !== null ? nextLine.value.requiredEx - exScore.value : null,
);

/** 実機の 4 桁表示に合わせ、先頭のゼロを薄くした EX。 */
const exDigits = computed(() => {
  const s = String(Math.max(0, exScore.value ?? 0)).padStart(4, '0');
  const lead = s.match(/^0*(?=\d)/)?.[0] ?? '';
  return { lead, rest: s.slice(lead.length) };
});

/** 届いた瞬間に一度だけ光らせる線。 */
const flashKeys = ref(new Set<string>());
const flashTimers: ReturnType<typeof setTimeout>[] = [];
watch(
  () => ({ chartKey: chart.value ? `${chart.value.title}_${chart.value.difficulty}` : '', achieved: (ladder.value ?? []).filter(l => l.achieved).map(l => l.key) }),
  (now, prev) => {
    // 曲が替わったとき（同じ譜面の続きでないとき）は光らせない
    if (!prev || now.chartKey !== prev.chartKey) return;
    for (const k of now.achieved) {
      if (prev.achieved.includes(k)) continue;
      flashKeys.value = new Set([...flashKeys.value, k]);
      flashTimers.push(setTimeout(() => {
        const n = new Set(flashKeys.value);
        n.delete(k);
        flashKeys.value = n;
      }, 1200));
    }
  },
);

// ---------------------------------------------------------------------------

/** リアクティブな条件が真になるまで待つ。 */
function waitFor(cond: () => boolean): Promise<void> {
  if (cond()) return Promise.resolve();
  return new Promise(resolve => {
    const stop = watch(cond, v => {
      if (v) {
        stop();
        resolve();
      }
    });
  });
}

onMounted(async () => {
  updateScale();
  window.addEventListener('resize', updateScale);
  if (!testMode) {
    document.documentElement.style.background = 'transparent';
    document.body.style.background = 'transparent';
  }
  document.body.style.margin = '0';
  document.body.style.overflow = 'hidden';

  if (!testMode && !sourceName) {
    status.value = 'URL に source（キャプチャボードのソース名）を指定してください';
    return;
  }
  // 曲データと難易度表
  useGameData().fetchGameData();

  // ログインを確認する。未ログイン（またはトークン切れ）ならログイン画面を出し、ログインされるまで待つ
  await waitFor(() => !authLoading.value);
  for (;;) {
    if (isLoggedIn.value && (await loadBestScores())) break;
    if (isLoggedIn.value) {
      // スコア取得が 401 = トークン切れ。ログイン状態を取り直してから入力を待つ
      localStorage.removeItem(TOKEN_KEY);
      await fetchCurrentUser();
    }
    needLogin.value = true;
    status.value = '';
    await waitFor(() => isLoggedIn.value);
    needLogin.value = false;
  }
  await gameDataReady;
  // OCR のワーカーは最初の曲が来る前に温めておく
  ensureOcr().catch(e => { ocrDebug.value = `OCR の準備に失敗: ${(e as Error).message}`; });
  status.value = '';
  loop();
});

onBeforeUnmount(() => {
  stopped = true;
  if (loopTimer) clearTimeout(loopTimer);
  flashTimers.forEach(clearTimeout);
  window.removeEventListener('resize', updateScale);
  obs.close();
  ocrEng?.terminate();
  ocrJpn?.terminate();
  if (testMediaUrl.value) URL.revokeObjectURL(testMediaUrl.value);
});
</script>

<template>
  <div class="ov-page" :class="{ 'ov-test': testMode }">
    <div v-if="testMode" class="ov-toolbar">
      <label>
        録画・画像を開く
        <input type="file" accept="video/*,image/*" @change="onTestFile" />
      </label>
      <span class="ov-hint">録画はここで再生してください。プレー画面の間、目盛りが出ます。</span>
    </div>

    <div
      class="ov-stage"
      :style="{ width: `${BASE_W}px`, height: `${BASE_H}px`, transform: `scale(${viewportScale})` }"
    >
      <!-- テスト用の元映像（OBS では描かない） -->
      <video
        v-if="testMode && testMediaKind === 'video'"
        ref="testVideo"
        class="ov-media"
        :src="testMediaUrl"
        controls
        muted
        playsinline
      />
      <img v-if="testMode && testMediaKind === 'image'" ref="testImage" class="ov-media" :src="testMediaUrl" alt="" />

      <!-- 単曲ティアの拡大目盛り -->
      <transition name="ov-fade">
        <!-- 実機の GRAPH INFORMATION 欄に似せた構成: 見出し → YOU 行 → NEXT 行 → グラフ -->
        <div
          v-if="strip && ladder && !resultCard"
          class="ov-strip"
          :style="{
            left: `${strip.x}px`,
            top: `${strip.top - STRIP_PAD_TOP}px`,
            width: `${STRIP_W}px`,
            height: `${strip.bottom - strip.top + STRIP_PAD_TOP + STRIP_PAD_BOTTOM}px`,
          }"
        >
          <div class="ov-head">{{ ladderKind === 'rate' ? 'RATE-PT INFORMATION' : 'BEAT TIER INFORMATION' }}</div>
          <div class="ov-row ov-row-you">
            <span class="ov-row-label">YOU</span>
            <span class="ov-digits"><span class="ov-digits-lead">{{ exDigits.lead }}</span>{{ exDigits.rest }}</span>
          </div>
          <div class="ov-row ov-row-next">
            <span class="ov-row-label">{{
              !nextLine ? 'ALL CLEAR' : ladderKind === 'rate' ? `NEXT ${nextLine.right}` : `NEXT ${nextLine.label}`
            }}</span>
            <span v-if="nextRemaining !== null" class="ov-digits ov-digits-next">-{{ nextRemaining }}</span>
          </div>

          <div class="ov-graph" :style="{ top: `${STRIP_PAD_TOP - GRAPH_HEAD}px` }">
            <div class="ov-tag">{{ ladderKind === 'rate' ? 'RATE-PT' : `☆${chart?.informalRank}` }}</div>
          </div>
          <div
            v-for="line in ladder"
            :key="line.key"
            class="ov-line"
            :class="{
              'is-achieved': line.achieved,
              'is-next': line.isNext,
              'is-flash': flashKeys.has(line.key),
            }"
            :style="{ top: `${line.y - strip.top + STRIP_PAD_TOP}px` }"
          >
            <span class="ov-label">{{ line.label }}</span>
            <span class="ov-ex">{{ line.right }}</span>
          </div>
          <!-- YOU の棒（実機と同じく下から伸び、目盛りの線を隠す） -->
          <div
            v-if="barTopY !== null"
            class="ov-bar"
            :class="{ 'ov-bar-rate': ladderKind === 'rate' }"
            :style="{
              top: `${barTopY - strip.top + STRIP_PAD_TOP}px`,
              bottom: `${STRIP_PAD_BOTTOM}px`,
            }"
          />
          <!-- 自己ベスト・上位 100 入りの線（棒の上に重ねる）。名前の札は帯の外側（グラフと反対側）に出す -->
          <div
            v-for="m in extraMarkers"
            :key="m.key"
            class="ov-mark"
            :class="[`ov-mark-${m.key}`, { 'is-achieved': m.achieved }]"
            :style="{ top: `${m.y - strip.top + STRIP_PAD_TOP}px` }"
          />
          <div
            v-for="m in extraMarkers"
            :key="`${m.key}-tag`"
            class="ov-mark-tag"
            :class="[`ov-mark-${m.key}`, strip.faceLeft ? 'tag-left' : 'tag-right', { 'is-achieved': m.achieved }]"
            :style="{ top: `${m.tagY - strip.top + STRIP_PAD_TOP}px` }"
          >
            <span class="ov-mark-name">{{ m.label }}</span>
            <span class="ov-mark-ex">{{ m.ex }}</span>
          </div>
        </div>
      </transition>

      <!-- リザルト画面の中央（イラストの位置）に出す成果。CSV 取り込み時のレポートと同じ項目 -->
      <transition name="ov-pop">
        <div v-if="resultView" class="ov-result">
          <div class="ov-head">BEAT-SEEKER RESULT</div>
          <div class="ov-res-song">
            <span class="ov-res-diff" :class="`diff-${resultView.difficulty.toLowerCase()}`">{{ resultView.difficulty }}</span>
            <span v-if="resultView.rank" class="ov-res-rank">☆{{ resultView.rank }}</span>
            <span class="ov-res-title">{{ resultView.title }}</span>
          </div>

          <div class="ov-res-score">
            <div class="ov-res-score-label">EX SCORE</div>
            <div class="ov-res-score-main">
              <span class="ov-res-ex">{{ resultView.ex }}</span>
              <span v-if="resultView.diff !== null" class="ov-res-diff-num" :class="resultView.diff > 0 ? 'up' : resultView.diff < 0 ? 'down' : ''">
                {{ resultView.diff > 0 ? `+${resultView.diff}` : resultView.diff }}
              </span>
            </div>
            <div v-if="resultView.isNewRecord || resultView.isNewAllTime" class="ov-res-badges">
              <span v-if="resultView.isNewRecord" class="ov-res-new">NEW RECORD</span>
              <span v-if="resultView.isNewAllTime" class="ov-res-new ov-res-new-alltime">歴代ベスト更新</span>
            </div>
          </div>

          <div v-if="resultView.allTime" class="ov-res-alltime">
            <span class="ov-res-label">歴代自己ベスト</span>
            <span class="ov-res-alltime-score">{{ resultView.allTime.score }}</span>
            <span class="ov-res-alltime-ver">{{ resultView.allTime.label }}</span>
            <span
              v-if="resultView.allTimeDiff !== null"
              class="ov-res-alltime-diff"
              :class="resultView.allTimeDiff > 0 ? 'up' : resultView.allTimeDiff < 0 ? 'down' : ''"
            >{{ resultView.allTimeDiff > 0 ? `+${resultView.allTimeDiff}` : resultView.allTimeDiff }}</span>
          </div>

          <div class="ov-res-grid">
            <div class="ov-res-cell">
              <div class="ov-res-label">SCORE RATE</div>
              <div class="ov-res-value">{{ resultView.rate.toFixed(2) }}<small>%</small></div>
            </div>
            <div class="ov-res-cell">
              <div class="ov-res-label">DJ LEVEL</div>
              <div class="ov-res-value">{{ resultView.grade.main }}</div>
              <div class="ov-res-sub">{{ resultView.grade.sub }}</div>
            </div>
            <div class="ov-res-cell">
              <div class="ov-res-label">BEAT-PT</div>
              <div class="ov-res-value">{{ resultView.pt ? resultView.pt.toFixed(2) : '---' }}</div>
              <div v-if="resultView.ptDiff" class="ov-res-sub up">+{{ resultView.ptDiff.toFixed(2) }}</div>
            </div>
            <div class="ov-res-cell">
              <div class="ov-res-label">RATE-PT</div>
              <div class="ov-res-value">{{ resultView.ratePt ? resultView.ratePt.toFixed(2) : '---' }}</div>
              <div v-if="resultView.ratePtDiff" class="ov-res-sub up">+{{ resultView.ratePtDiff.toFixed(2) }}</div>
            </div>
          </div>

          <!-- 単曲ティアの無い譜面（Lv10 以下など）は、次の Rate-PT の区切りまでを出す -->
          <div v-if="!resultView.newTier && resultView.rateNext" class="ov-res-tier">
            <div class="ov-res-label">RATE-PT</div>
            <div class="ov-res-next">
              次の {{ resultView.rateNext.label }}（{{ resultView.rateNext.right }}）まで あと <b>{{ resultView.rateNext.gap }}</b>
            </div>
          </div>

          <div v-if="resultView.newTier" class="ov-res-tier">
            <div class="ov-res-label">単曲ティア</div>
            <div class="ov-res-tier-row">
              <template v-if="resultView.oldTier">
                <RankIcon :rank-name="resultView.oldTier.name" :tier="resultView.oldTier.tier" size="sm" disable-party lite class="opacity-60" />
                <span class="ov-res-tier-name old">{{ tierLabel(resultView.oldTier) }}</span>
                <span class="ov-res-arrow">▶</span>
              </template>
              <RankIcon :rank-name="resultView.newTier.name" :tier="resultView.newTier.tier" size="md" disable-party />
              <span class="ov-res-tier-name">{{ tierLabel(resultView.newTier) }}</span>
            </div>
            <div v-if="resultView.next" class="ov-res-next">
              次の {{ resultView.next.label }} まで あと <b>{{ resultView.next.gap }}</b>
            </div>
          </div>
        </div>
      </transition>
    </div>

    <!-- 未ログイン時はログイン画面だけを出す（OBS ではソースを右クリック →「対話」で入力） -->
    <template v-if="needLogin">
      <div class="ov-login-note">
        配信オーバーレイを使うには beat-seeker へのログインが必要です。<br />
        OBS ではこのソースを右クリックして「対話」を開き、ログインしてください。
      </div>
      <LoginModal :is-open="true" @close="() => {}" />
    </template>

    <div v-if="debug || status || bestError" class="ov-debug">
      <div v-if="status">{{ status }}</div>
      <div v-if="bestError">{{ bestError }}</div>
      <template v-if="debug">
        <div>配置: {{ layout ? layout.id : 'プレー画面ではない' }}</div>
        <div>
          曲: {{ chart ? `${chart.title} [${chart.difficulty}] ☆${chart.informalRank ?? '対象外'} MAX ${chart.maxEx}` : identifying ? '特定中…' : '未特定' }}
        </div>
        <div>EX: {{ exScore ?? '-' }}</div>
        <div v-if="ocrDebug">{{ ocrDebug }}</div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.ov-page {
  position: fixed;
  inset: 0;
  overflow: hidden;
  background: transparent;
  font-family: 'Helvetica Neue', Arial, 'Hiragino Sans', 'Yu Gothic', Meiryo, sans-serif;
  font-feature-settings: 'tnum' on, 'lnum' on;
  color: #fff;
}
.ov-test {
  background: #111;
}
.ov-toolbar {
  position: absolute;
  right: 8px;
  bottom: 8px;
  z-index: 10;
  display: flex;
  gap: 12px;
  align-items: center;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.7);
  font-size: 13px;
}
.ov-hint {
  opacity: 0.7;
}
.ov-stage {
  position: absolute;
  left: 0;
  top: 0;
  transform-origin: 0 0;
}
.ov-media {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: fill;
}

/*
 * 目盛りの帯。色はすべて実機（ZINRAI）のスコアグラフから測った値に合わせている。
 *  - 見出しのオレンジ = (241,129,0)
 *  - グラフの地 = 上 (83,83,83) → 下 (35,35,35) のグラデーション + 2px 間隔の横縞
 *  - 目盛り線 = (137,130,212)、直下に紺の影 (0,0,25)。届いたら黄色 (226,234,61)
 *  - YOU の棒 = 上 (81,86,210) → 中 (62,137,210) → 下 (41,190,207)
 *  - MAX SCORE の札 = 金 (158,134,64) 系
 */
.ov-strip {
  position: absolute;
  /* 自己ベスト等の名前の札を帯の外側に出すので、はみ出しは隠さない */
  overflow: visible;
  background: #000;
  box-shadow: 0 0 0 2px #111, 0 4px 14px rgba(0, 0, 0, 0.6);
  pointer-events: none;
  --ov-line: rgb(137, 130, 212);
  --ov-line-shadow: rgb(0, 0, 25);
  --ov-done: rgb(226, 234, 61);
  --ov-done-shadow: rgb(40, 40, 0);
}

.ov-head {
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 20px;
  background: linear-gradient(#ff9a1a, rgb(241, 129, 0));
  color: #3a1d00;
  font-size: 12px;
  font-weight: 800;
  line-height: 20px;
  letter-spacing: 0.08em;
  text-align: center;
}

.ov-row {
  position: absolute;
  left: 0;
  right: 0;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding: 0 10px 2px;
  border-bottom: 1px solid rgb(181, 101, 29);
  background: linear-gradient(#0b0b0b, #1c1c1c);
}
.ov-row-you {
  top: 20px;
  height: 34px;
}
.ov-row-next {
  top: 55px;
  height: 28px;
}
.ov-row {
  gap: 8px;
}
.ov-row-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  color: #f1f1f1;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  white-space: nowrap;
}
/* NEXT 行は「NEXT INTERMEDIATE」のように長くなるので小さめにし、数字の方を必ず残す（2026-10-11 「-140」が「-14」に切れていた） */
.ov-row-next .ov-row-label {
  font-size: 12px;
  letter-spacing: 0.02em;
}
.ov-digits {
  flex: none;
}
.ov-digits {
  color: #f4f4f4;
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  font-size: 27px;
  font-weight: 900;
  line-height: 1;
  letter-spacing: 0.06em;
}
.ov-digits-lead {
  color: #5b5b5b;
}
.ov-digits-next {
  font-size: 20px;
  color: var(--ov-done);
}

.ov-graph {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  background:
    repeating-linear-gradient(to bottom, rgba(0, 0, 0, 0.18) 0 1px, transparent 1px 2px),
    linear-gradient(rgb(83, 83, 83), rgb(54, 54, 54) 55%, rgb(35, 35, 35));
}
/* 実機の「MAX SCORE」札の形（右端を斜めに切った金色の札） */
.ov-tag {
  position: absolute;
  left: 0;
  top: 2px;
  padding: 0 18px 0 8px;
  height: 15px;
  background: linear-gradient(#f3d44a, rgb(196, 160, 40) 60%, rgb(158, 134, 64));
  clip-path: polygon(0 0, 100% 0, calc(100% - 9px) 100%, 0 100%);
  color: #fff;
  font-size: 12px;
  font-weight: 900;
  line-height: 15px;
  letter-spacing: 0.06em;
  text-shadow:
    1px 0 0 #3b2a00, -1px 0 0 #3b2a00, 0 1px 0 #3b2a00, 0 -1px 0 #3b2a00;
}

.ov-line {
  position: absolute;
  left: 4px;
  right: 0;
  height: 2px;
  margin-top: -1px;
  background: var(--ov-line);
  box-shadow: 0 1px 0 var(--ov-line-shadow);
  color: var(--ov-line);
  transition: background-color 0.15s, color 0.15s;
}
/* ラベルは実機の「AAA」と同じく、線の左端の上に乗る縁取り文字 */
.ov-line > span {
  position: absolute;
  bottom: 2px;
  font-size: 14px;
  font-weight: 900;
  line-height: 1;
  letter-spacing: 0.03em;
  text-transform: uppercase;
  text-shadow:
    1px 0 0 var(--ov-line-shadow), -1px 0 0 var(--ov-line-shadow),
    0 1px 0 var(--ov-line-shadow), 0 -1px 0 var(--ov-line-shadow),
    1px 1px 0 var(--ov-line-shadow), -1px -1px 0 var(--ov-line-shadow);
}
.ov-label {
  left: 2px;
}
.ov-ex {
  right: 6px;
  font-size: 13px !important;
}
.ov-line.is-next > span {
  color: #fff;
}
.ov-line.is-achieved {
  background: var(--ov-done);
  box-shadow: 0 1px 0 var(--ov-done-shadow);
  color: var(--ov-done);
}
.ov-line.is-achieved > span {
  text-shadow:
    1px 0 0 var(--ov-done-shadow), -1px 0 0 var(--ov-done-shadow),
    0 1px 0 var(--ov-done-shadow), 0 -1px 0 var(--ov-done-shadow),
    1px 1px 0 var(--ov-done-shadow), -1px -1px 0 var(--ov-done-shadow);
}
.ov-line.is-flash {
  animation: ov-flash 1.2s ease-out;
}
@keyframes ov-flash {
  0% { box-shadow: 0 0 18px 6px rgba(226, 234, 61, 1); }
  100% { box-shadow: 0 1px 0 var(--ov-done-shadow); }
}

/* YOU の棒。ラベル（左）と必要 EX（右）の間に置き、実機と同じく線を隠す */
.ov-bar {
  position: absolute;
  /* 一番長いラベル（INTERMEDIATE）の右端と、右の必要 EX の左端の間 */
  left: 136px;
  width: 46px;
  background:
    linear-gradient(to right, rgba(255, 255, 255, 0.18), transparent 25%, transparent 75%, rgba(0, 0, 0, 0.15)),
    linear-gradient(rgb(81, 86, 210), rgb(62, 137, 210) 50%, rgb(41, 190, 207));
  box-shadow: inset 0 0 0 1px rgba(20, 40, 120, 0.6);
  transition: top 0.2s linear;
  z-index: 2;
}
/*
 * 自己ベスト・上位 100 入りの線。棒の上に重ねる破線。色は実機の「自己ベスト」に合わせた緑、
 * BEAT-PT はオレンジ、RATE-PT は水色。届いたら実線になる。
 */
.ov-mark {
  position: absolute;
  left: 0;
  right: 0;
  height: 0;
  margin-top: -1px;
  border-top: 3px dashed var(--mark-color);
  filter: drop-shadow(0 0 2px rgba(0, 0, 0, 0.9));
  z-index: 3;
}
.ov-mark.is-achieved {
  border-top-style: solid;
}
.ov-mark-best { --mark-color: #8be04e; }
.ov-mark-beat { --mark-color: #ff9a1a; }
.ov-mark-rate { --mark-color: #22d3ee; }
/* 名前の札。目盛りの文字と重ならないよう帯の外側（グラフと反対側）に出す */
.ov-mark-tag {
  position: absolute;
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: -10px;
  padding: 1px 6px;
  border-left: 4px solid var(--mark-color);
  background: rgba(0, 0, 0, 0.78);
  white-space: nowrap;
  z-index: 3;
}
.ov-mark-tag.tag-left {
  right: calc(100% + 4px);
}
.ov-mark-tag.tag-right {
  left: calc(100% + 4px);
}
.ov-mark-name {
  color: var(--mark-color);
  font-size: 12px;
  font-weight: 900;
  letter-spacing: 0.04em;
}
.ov-mark-ex {
  color: #fff;
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  font-size: 14px;
}
.ov-mark-tag.is-achieved .ov-mark-ex::after {
  content: ' ✓';
  color: var(--mark-color);
}

/* Rate-PT の目盛りは左のラベル（512PT）が短く右の数字（MAX-115）が長いので、棒を左に寄せる */
.ov-bar.ov-bar-rate {
  left: 72px;
}
/*
 * リザルト画面の中央（イラストの位置、1920×1080 基準で x=960 を中心）に出す成果パネル。
 * 配色は目盛りの帯と同じく実機の GRAPH INFORMATION 欄に合わせる。
 */
.ov-result {
  position: absolute;
  left: 650px;
  top: 250px;
  width: 620px;
  padding: 26px 0 18px;
  background:
    repeating-linear-gradient(to bottom, rgba(0, 0, 0, 0.18) 0 1px, transparent 1px 2px),
    linear-gradient(rgba(70, 70, 70, 0.94), rgba(30, 30, 30, 0.94));
  box-shadow: 0 0 0 2px #111, 0 0 0 4px rgba(241, 129, 0, 0.8), 0 12px 40px rgba(0, 0, 0, 0.7);
  pointer-events: none;
}
.ov-result .ov-head {
  height: 26px;
  line-height: 26px;
  font-size: 14px;
}
.ov-res-song {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 22px 0;
  min-width: 0;
}
.ov-res-diff {
  flex: none;
  padding: 2px 10px;
  border-radius: 3px;
  font-size: 14px;
  font-weight: 900;
  letter-spacing: 0.06em;
  background: #555;
}
.ov-res-diff.diff-another { background: linear-gradient(#d33, #8b1515); }
.ov-res-diff.diff-leggendaria { background: linear-gradient(#a855f7, #6b21a8); }
.ov-res-diff.diff-hyper { background: linear-gradient(#e0a020, #8a5a08); }
.ov-res-diff.diff-normal { background: linear-gradient(#3b82f6, #1e3a8a); }
.ov-res-diff.diff-beginner { background: linear-gradient(#22c55e, #166534); }
.ov-res-rank {
  flex: none;
  color: #f3d44a;
  font-size: 18px;
  font-weight: 900;
}
.ov-res-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 24px;
  font-weight: 800;
}

.ov-res-score {
  margin: 14px 22px 0;
  padding: 10px 16px 12px;
  background: linear-gradient(#0b0b0b, #1c1c1c);
  border-bottom: 1px solid rgb(181, 101, 29);
}
.ov-res-score-label,
.ov-res-label {
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 800;
  letter-spacing: 0.08em;
}
.ov-res-score-main {
  display: flex;
  align-items: baseline;
  gap: 16px;
}
.ov-res-ex {
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  font-size: 64px;
  font-weight: 900;
  line-height: 1;
  letter-spacing: 0.04em;
}
.ov-res-diff-num {
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  font-size: 30px;
  font-weight: 900;
  color: #94a3b8;
}
.ov-res-diff-num.up,
.ov-res-sub.up {
  color: rgb(226, 234, 61);
}
.ov-res-diff-num.down {
  color: #f87171;
}
.ov-res-badges {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.ov-res-new-alltime {
  background: linear-gradient(#fff36b, rgb(226, 234, 61)) !important;
}
/* 歴代自己ベストの行（EX SCORE の直下） */
.ov-res-alltime {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin: 8px 22px 0;
  padding: 7px 16px;
  background: rgba(0, 0, 0, 0.45);
}
.ov-res-alltime-score {
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  font-size: 24px;
  font-weight: 900;
}
.ov-res-alltime-ver {
  color: #c4b5fd;
  font-size: 14px;
  font-weight: 800;
}
.ov-res-alltime-diff {
  margin-left: auto;
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  font-size: 20px;
  font-weight: 900;
  color: #94a3b8;
}
.ov-res-alltime-diff.up {
  color: rgb(226, 234, 61);
}
.ov-res-alltime-diff.down {
  color: #f87171;
}
.ov-res-new {
  display: inline-block;
  padding: 1px 10px;
  background: linear-gradient(#ffb02e, rgb(241, 129, 0));
  color: #2a1300;
  font-size: 14px;
  font-weight: 900;
  letter-spacing: 0.1em;
  animation: ov-blink 1.2s ease-in-out infinite;
}
@keyframes ov-blink {
  50% { opacity: 0.55; }
}

.ov-res-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin: 12px 22px 0;
}
.ov-res-cell {
  padding: 8px 12px;
  background: rgba(0, 0, 0, 0.45);
}
.ov-res-value {
  font-family: 'Arial Black', 'Helvetica Neue', Arial, sans-serif;
  /* 4 欄並び（SCORE RATE / DJ LEVEL / BEAT-PT / RATE-PT）で「AAA+136」が収まる大きさ */
  font-size: 21px;
  white-space: nowrap;
  font-weight: 900;
  line-height: 1.2;
}
.ov-res-value small {
  font-size: 16px;
}
.ov-res-sub {
  color: #94a3b8;
  font-size: 14px;
  font-weight: 800;
}

.ov-res-tier {
  margin: 12px 22px 0;
  padding: 10px 14px;
  background: rgba(0, 0, 0, 0.45);
}
.ov-res-tier-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 4px;
}
.ov-res-tier-name {
  font-size: 26px;
  font-weight: 900;
}
.ov-res-tier-name.old {
  font-size: 18px;
  color: #94a3b8;
}
.ov-res-arrow {
  color: rgb(226, 234, 61);
  font-size: 18px;
}
.ov-res-next {
  margin-top: 6px;
  color: #e2e8f0;
  font-size: 15px;
  font-weight: 700;
}
.ov-res-next b {
  color: rgb(226, 234, 61);
  font-size: 18px;
}

.ov-pop-enter-active {
  transition: opacity 0.35s, transform 0.35s cubic-bezier(0.2, 1.4, 0.4, 1);
}
.ov-pop-leave-active {
  transition: opacity 0.3s;
}
.ov-pop-enter-from {
  opacity: 0;
  transform: scale(0.92);
}
.ov-pop-leave-to {
  opacity: 0;
}

.ov-login-note {
  position: absolute;
  left: 50%;
  top: 24px;
  z-index: 60;
  transform: translateX(-50%);
  padding: 10px 18px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.8);
  font-size: 15px;
  line-height: 1.6;
  text-align: center;
}

.ov-fade-enter-active,
.ov-fade-leave-active {
  transition: opacity 0.3s;
}
.ov-fade-enter-from,
.ov-fade-leave-to {
  opacity: 0;
}

.ov-debug {
  position: absolute;
  left: 8px;
  top: 8px;
  z-index: 10;
  max-width: 60vw;
  padding: 6px 10px;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.7);
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
}
</style>
