<script setup lang="ts">
/**
 * 【View の役割】 OBS ブラウザソース用の配信オーバーレイ（`/obs/score-overlay`）。
 *
 * キャプチャボードのソースの静止画を obs-websocket で定期的に取り、プレー画面を読み取って、
 * スコアグラフの横に「単曲ティアの拡大目盛り」を出す。YOU の EX スコアを毎回読み、
 * 届いたティアの線をその場で黄色にする。
 *
 * 座標系: このページは 1920×1080 の箱をウィンドウ幅に合わせて拡大縮小して描く。
 * OBS では「ブラウザソースの幅 1920・高さ 1080」にし、キャプチャのソースの変形をコピーして
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
import { FOLDER_RANK_DEFS, getFolderRankThresholdRateAt } from '../utils/beatTier';
import {
  binarizeForOcr,
  detectPlayLayout,
  fingerprintDistance,
  readChartHeader,
  readCurrentExScore,
  titleFingerprint,
  type DifficultyName,
  type PlayLayout,
  type RgbaFrame,
} from '../utils/iidxPlayScreen';
import { matchChartByOcr } from '../utils/overlaySongMatch';
import { ObsWebSocketClient } from '../utils/obsWebSocket';

const params = new URLSearchParams(window.location.search);
const testMode = params.get('test') === '1';
const debug = testMode || params.get('debug') === '1';
const sourceName = params.get('source') ?? '';
const password = params.get('password') ?? '';
const obsUrl = `ws://${params.get('host') || '127.0.0.1'}:${params.get('port') || '4455'}`;
const intervalMs = Math.max(100, Number(params.get('interval')) || 250);
/** テストモード専用: 難易度表に無い譜面でも目盛りを確認できるよう、☆を上書きする（例 rank=12.0）。 */
const testRankOverride = testMode ? params.get('rank') : null;
/** テストモード専用: 読み取った EX の代わりにこの値を使う（達成時の表示確認用、例 ex=3070）。 */
const testExOverride = testMode && params.get('ex') ? Number(params.get('ex')) : null;

const BASE_W = 1920;
const BASE_H = 1080;
/** プレー画面が見えなくなってから「曲が終わった」とみなすまでの時間。 */
const LOST_PLAY_MS = 1500;
/** 曲名の指紋がこれ以上変わったら別の曲とみなして読み直す。 */
const TITLE_CHANGE_THRESHOLD = 12;
/** 1 回の読み取り間で自然に増えうる EX の上限。これを超える跳びは 2 回続けて読めたときだけ採る。 */
const MAX_EX_STEP = 80;
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

function processFrame(frame: RgbaFrame): void {
  const now = performance.now();
  const det = detectPlayLayout(frame);
  if (!det) {
    if (layout.value && now - lastPlaySeenAt > LOST_PLAY_MS) {
      layout.value = null;
      resetPlay();
    }
    return;
  }
  lastPlaySeenAt = now;
  if (layout.value?.id !== det.layout.id) {
    layout.value = det.layout;
  }

  const header = readChartHeader(frame, det.layout);
  const fp = titleFingerprint(frame, header.titleRect);
  if (currentFingerprint && fingerprintDistance(fp, currentFingerprint) > TITLE_CHANGE_THRESHOLD) {
    resetPlay();
  }
  currentFingerprint = fp;

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
    processFrame(grab(v, v.videoWidth, v.videoHeight));
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
  label: string;
  requiredEx: number;
  y: number;
  achieved: boolean;
  isNext: boolean;
}

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
  if (!c || !c.informalRank || !s) return null;
  const defs: { key: string; label: string; requiredEx: number }[] = [
    { key: 'MAX', label: 'MAX', requiredEx: c.maxEx },
  ];
  for (let b = 0; b <= 10; b++) {
    const idx = b * 5;
    const rate = getFolderRankThresholdRateAt(idx, c.informalRank);
    if (!rate) return null;
    defs.push({
      key: FOLDER_RANK_DEFS[idx].name,
      label: FOLDER_RANK_DEFS[idx].name,
      requiredEx: Math.ceil((c.maxEx * rate) / 100 - 1e-9),
    });
  }
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
const barTopY = computed(() => {
  const lines = ladder.value;
  const s = strip.value;
  if (!lines || !s) return null;
  const ex = exScore.value ?? 0;
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
  // 曲データと難易度表（ログイン不要）
  useGameData().fetchGameData();
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
          v-if="strip && ladder"
          class="ov-strip"
          :style="{
            left: `${strip.x}px`,
            top: `${strip.top - STRIP_PAD_TOP}px`,
            width: `${STRIP_W}px`,
            height: `${strip.bottom - strip.top + STRIP_PAD_TOP + STRIP_PAD_BOTTOM}px`,
          }"
        >
          <div class="ov-head">BEAT TIER INFORMATION</div>
          <div class="ov-row ov-row-you">
            <span class="ov-row-label">YOU</span>
            <span class="ov-digits"><span class="ov-digits-lead">{{ exDigits.lead }}</span>{{ exDigits.rest }}</span>
          </div>
          <div class="ov-row ov-row-next">
            <span class="ov-row-label">{{ nextLine ? `NEXT ${nextLine.label}` : 'ALL CLEAR' }}</span>
            <span v-if="nextRemaining !== null" class="ov-digits ov-digits-next">-{{ nextRemaining }}</span>
          </div>

          <div class="ov-graph" :style="{ top: `${STRIP_PAD_TOP - GRAPH_HEAD}px` }">
            <div class="ov-tag">☆{{ chart?.informalRank }}</div>
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
            <span class="ov-ex">{{ line.requiredEx }}</span>
          </div>
          <!-- YOU の棒（実機と同じく下から伸び、目盛りの線を隠す） -->
          <div
            v-if="barTopY !== null"
            class="ov-bar"
            :style="{
              top: `${barTopY - strip.top + STRIP_PAD_TOP}px`,
              bottom: `${STRIP_PAD_BOTTOM}px`,
            }"
          />
        </div>
      </transition>
    </div>

    <div v-if="debug || status" class="ov-debug">
      <div v-if="status">{{ status }}</div>
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
  overflow: hidden;
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
.ov-row-label {
  color: #f1f1f1;
  font-size: 15px;
  font-weight: 800;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  white-space: nowrap;
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
