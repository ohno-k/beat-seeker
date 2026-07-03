<script setup lang="ts">
/**
 * 【コンポーネントの役割】 カメラ + OCR による曲検索モーダル。
 *
 * 動作フロー:
 *  1. ユーザーが「カメラを開く」を押す
 *  2. `getUserMedia` で背面カメラを起動し、`<video>` にストリームを流す
 *  3. Tesseract.js ワーカーを初期化（英語辞書）
 *  4. 約 700ms ごとに画面中央の領域をキャプチャ → OCR → 結果テキストで Fuse.js 検索
 *  5. 一致度が閾値を超えたらカメラを止めて `matched` を emit（親が楽曲詳細へ遷移）
 *
 * 依存:
 *  - `tesseract.js` — WebAssembly 版 OCR
 *  - `fuse.js` — 部分一致＋誤字許容のファジー検索
 *  - `songData` — 既存の楽曲一覧（title/artist/genre を含む）
 */
import { ref, onBeforeUnmount } from 'vue';
import { createWorker, PSM, type Worker as TesseractWorker } from 'tesseract.js';
import Fuse from 'fuse.js';
import { useI18n } from '../composables/useI18n';
import { songData, type SongDataEntry } from '../composables/useGameData';

const { t } = useI18n();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'matched', song: SongDataEntry): void;
}>();

/**
 * 状態マシン。UI の表示切替に使う。
 *  - idle: モーダル起動直後
 *  - initializing: カメラ／OCR ワーカー起動中
 *  - ready: カメラ映像ライブ、ユーザーのボタン押下待ち
 *  - capturing: ボタン押下後、OCR 実行中
 *  - candidates: 認識成功、候補一覧をユーザーに選ばせている
 *  - error: カメラ取得失敗等
 */
type Status = 'idle' | 'initializing' | 'ready' | 'capturing' | 'candidates' | 'error';
const status = ref<Status>('idle');

const videoRef = ref<HTMLVideoElement | null>(null);
const canvasRef = ref<HTMLCanvasElement | null>(null);

const errorMessage = ref('');
const recognizedText = ref('');
/** 認識結果の候補リスト。ユーザーが一覧から選ぶ。スコアは 0〜100 の百分率（高いほど一致）。 */
const matchCandidates = ref<{ song: SongDataEntry; score: number }[]>([]);
/** 前回の認識試行で「マッチなし」だった場合の UI 通知。 */
const noMatchMessage = ref('');

/** 候補として表示する件数の上限。画面を見てすぐ判別できる程度に絞る。 */
const MAX_CANDIDATES = 8;
/** この Fuse スコアを超える候補は切り捨てる（数値が大きい = 遠い）。ゆるめに設定して候補を確保。 */
const CANDIDATE_SCORE_CUTOFF = 0.55;

/**
 * OCR 対象となる青枠領域の位置・サイズ。video 要素のクライアント box（≒ モーダル内の映像エリア）
 * 基準の割合で指定する。
 *
 * 【重要】テンプレート側の青枠オーバーレイと `captureAndRecognize` のクロップ計算は
 * 必ずこの定数を共通で参照すること。映像の aspect と video 要素の aspect が一致しないケース
 * （`object-cover` で端が切れているケース）では、生の videoWidth/Height 基準で計算すると
 * 「見えていない外側の文字」まで OCR に渡ってしまい誤認識の原因になる。
 */
const CROP_TOP_PCT = 0.22;
const CROP_LEFT_PCT = 0.10;
const CROP_WIDTH_PCT = 0.80;
const CROP_HEIGHT_PCT = 0.40;

/** OCR ワーカー本体。アンマウント時に terminate する必要がある。 */
let worker: TesseractWorker | null = null;
/** カメラストリーム。アンマウント時に tracks を stop する必要がある。 */
let stream: MediaStream | null = null;

interface FuseEntry { key: string; song: SongDataEntry; }
let fuse: Fuse<FuseEntry> | null = null;

/**
 * Fuse.js のインデックスを構築する。
 *
 * song_data.json は「同じ曲の難易度別エントリ」が複数行あるため、
 * title でユニーク化して扱う（検索結果は ANOTHER 優先で差し替えてもよいが、
 * 今は最初に出会ったエントリをそのまま使う）。
 */
const buildFuseIndex = () => {
  const seen = new Set<string>();
  const entries: FuseEntry[] = [];
  // ANOTHER（code '4'）を優先して使いたいので、一度 ANOTHER を先に走査する
  const preferredOrder = ['4', '3', '2', '10', '1'];
  for (const diff of preferredOrder) {
    for (const s of songData.value) {
      if (s.difficulty !== diff) continue;
      const key = normalizeText(s.title);
      if (seen.has(key)) continue;
      seen.add(key);
      entries.push({ key, song: s });
    }
  }
  fuse = new Fuse(entries, {
    keys: [
      { name: 'song.title', weight: 2 },
      { name: 'key', weight: 2 },
      { name: 'song.artist', weight: 0.7 },
      { name: 'song.genre', weight: 0.5 },
    ],
    threshold: 0.45,
    includeScore: true,
    minMatchCharLength: 2,
    ignoreLocation: true,
  });
};

/**
 * OCR 結果と曲名の比較精度を上げるための正規化。
 *  - 前後の空白除去、小文字化
 *  - 複数スペース → 1 つ
 *  - よくある OCR 誤認識（0↔O, 1↔I/l）は補正しない（副作用の方が大きい）
 */
const normalizeText = (s: string): string => {
  return s.toLowerCase()
    .replace(/[　\t\r\n]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
};

/**
 * クロップしたフレームを OCR 用に前処理して canvas に焼き付ける。
 * Impact フォントのような太字曲名で精度を上げるため:
 *  1) 2 倍に拡大（Tesseract は 300dpi 相当が理想。スマホ映像は小さすぎることが多い）
 *  2) グレースケール化
 *  3) 大津法で閾値を自動算出して二値化
 *  4) 平均輝度が暗ければ反転（IIDX の「暗背景 + 明文字」→「白背景 + 黒文字」へ正規化）
 */
const preprocessFrame = (
  video: HTMLVideoElement,
  canvas: HTMLCanvasElement,
  cropX: number,
  cropY: number,
  cropW: number,
  cropH: number,
) => {
  const SCALE = 2;
  canvas.width = cropW * SCALE;
  canvas.height = cropH * SCALE;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;

  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(video, cropX, cropY, cropW, cropH, 0, 0, canvas.width, canvas.height);

  const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
  const data = imageData.data;
  const pixelCount = data.length >> 2;

  // 輝度配列とヒストグラムを一度の走査で作る
  const gray = new Uint8Array(pixelCount);
  const hist = new Uint32Array(256);
  let lumSum = 0;
  for (let i = 0, j = 0; i < data.length; i += 4, j++) {
    // BT.709 係数で輝度計算（赤緑青の視覚重み）
    const v = (data[i] * 0.2126 + data[i + 1] * 0.7152 + data[i + 2] * 0.0722) | 0;
    gray[j] = v;
    hist[v]++;
    lumSum += v;
  }

  // 大津法: クラス間分散が最大になる閾値を探す
  let sum = 0;
  for (let t = 0; t < 256; t++) sum += t * hist[t];
  let sumB = 0;
  let wB = 0;
  let maxVar = -1;
  let threshold = 127;
  for (let t = 0; t < 256; t++) {
    wB += hist[t];
    if (wB === 0) continue;
    const wF = pixelCount - wB;
    if (wF === 0) break;
    sumB += t * hist[t];
    const mB = sumB / wB;
    const mF = (sum - sumB) / wF;
    const between = wB * wF * (mB - mF) * (mB - mF);
    if (between > maxVar) {
      maxVar = between;
      threshold = t;
    }
  }

  // 平均輝度が暗い（背景が暗い）場合は反転して「白背景 + 黒文字」に揃える
  const invert = lumSum / pixelCount < 128;

  for (let i = 0, j = 0; i < data.length; i += 4, j++) {
    let v = gray[j] >= threshold ? 255 : 0;
    if (invert) v = 255 - v;
    data[i] = data[i + 1] = data[i + 2] = v;
    data[i + 3] = 255;
  }
  ctx.putImageData(imageData, 0, 0);
};

const openCamera = async () => {
  errorMessage.value = '';
  recognizedText.value = '';
  matchCandidates.value = [];

  // HTTPS 必須（localhost は例外）
  if (window.location.protocol !== 'https:' && window.location.hostname !== 'localhost' && window.location.hostname !== '127.0.0.1') {
    errorMessage.value = t('ocrSearch.httpsRequired');
    status.value = 'error';
    return;
  }

  try {
    status.value = 'initializing';
    // カメラ起動と OCR ワーカー初期化を並行で行う（初回起動時間を短縮）
    const [mediaStream, ocrWorker] = await Promise.all([
      navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: 'environment' },
          width: { ideal: 1280 },
          height: { ideal: 720 },
        },
        audio: false,
      }),
      // `eng` のみ。日本語タイトル対応は将来の拡張（辞書サイズが大きいため）
      createWorker('eng'),
    ]);
    stream = mediaStream;
    worker = ocrWorker;

    // OCR パラメータを Impact 曲名向けにチューニング:
    //  - PSM.SINGLE_BLOCK (6): 複数行ブロックとして扱う（副題込みで改行されるケースに対応）
    //  - 文字ホワイトリスト: IIDX 英字曲名で実用上十分な ASCII と記号のみ。記号ノイズを抑止
    await worker.setParameters({
      tessedit_pageseg_mode: PSM.SINGLE_BLOCK,
      tessedit_char_whitelist:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 '&!?().,-:/~*+",
    });

    if (videoRef.value) {
      videoRef.value.srcObject = stream;
      await videoRef.value.play();
    }

    buildFuseIndex();
    status.value = 'ready';
  } catch (e: any) {
    console.error('Camera/OCR init failed:', e);
    errorMessage.value = e?.message || t('ocrSearch.cameraError');
    status.value = 'error';
    cleanupResources();
  }
};

/**
 * カメラとワーカーの解放。状態は変更しない（呼び出し側で制御）。
 */
const cleanupResources = () => {
  if (stream) {
    stream.getTracks().forEach(track => track.stop());
    stream = null;
  }
  if (worker) {
    // terminate は Promise を返すが待たない（バックグラウンドで解放）
    worker.terminate().catch(() => {});
    worker = null;
  }
};

/**
 * ユーザーがシャッターボタンを押したときのハンドラ。
 * 現在のフレームを 1 枚だけキャプチャして OCR → ファジー検索。
 * OCR 結果を行単位と全文で Fuse に投げ、全クエリの上位結果を
 * 曲タイトルでユニーク化しつつスコア順に集約して候補リストを作る。
 */
const captureAndRecognize = async () => {
  if (status.value !== 'ready') return;
  if (!videoRef.value || !canvasRef.value || !worker || !fuse) return;
  const video = videoRef.value;
  if (video.readyState < 2 || video.videoWidth === 0) return;

  const canvas = canvasRef.value;
  const vw = video.videoWidth;
  const vh = video.videoHeight;

  // 【重要】画面に見えている青枠と同じ領域を OCR に渡すための座標変換。
  //
  // video 要素は `object-cover` で表示しているため、映像アスペクトが要素アスペクトと
  // 違うと映像の左右 or 上下が画面外に切れる。素直に videoWidth/videoHeight の割合で
  // クロップすると「ユーザーに見えていない領域」まで読み取ってしまい、枠外の UI 文字で
  // 誤マッチが起きる。object-cover の拡大率とオフセットを逆算して、青枠（コンテナ CSS
  // 基準の矩形）を video のピクセル座標に写像する。
  const containerW = video.clientWidth;
  const containerH = video.clientHeight;
  if (containerW === 0 || containerH === 0) return;

  // object-cover は「コンテナを完全に覆う」ようにスケールする = max(横倍率, 縦倍率)
  const scale = Math.max(containerW / vw, containerH / vh);
  const displayedW = vw * scale;
  const displayedH = vh * scale;
  // 映像の左上がコンテナ内のどこに来るか（負の値 = 画面外にはみ出している）
  const offX = (containerW - displayedW) / 2;
  const offY = (containerH - displayedH) / 2;

  // 青枠のコンテナ CSS 座標上の矩形（テンプレートのスタイルと同じ定数を参照）
  const boxLeft = containerW * CROP_LEFT_PCT;
  const boxTop = containerH * CROP_TOP_PCT;
  const boxW = containerW * CROP_WIDTH_PCT;
  const boxH = containerH * CROP_HEIGHT_PCT;

  // コンテナ CSS → 映像ピクセルへ変換。映像端でクランプ。
  const cropX = Math.max(0, Math.round((boxLeft - offX) / scale));
  const cropY = Math.max(0, Math.round((boxTop - offY) / scale));
  const cropW = Math.max(1, Math.min(vw - cropX, Math.round(boxW / scale)));
  const cropH = Math.max(1, Math.min(vh - cropY, Math.round(boxH / scale)));

  preprocessFrame(video, canvas, cropX, cropY, cropW, cropH);

  status.value = 'capturing';
  noMatchMessage.value = '';
  recognizedText.value = '';

  let text = '';
  try {
    const result = await worker.recognize(canvas);
    text = (result.data.text || '').trim();
  } catch (e) {
    console.warn('OCR failed:', e);
  }

  // 表示用テキストを更新（たとえマッチしなくても、何を読んだかは見せる）
  const cleanedForDisplay = text.replace(/\s+/g, ' ').trim().slice(0, 80);
  recognizedText.value = cleanedForDisplay;

  // 行ごとと全文の両方で検索する。曲名は 1 行で表示されることが多い一方、
  // 副題や改行を含むケースでは全文クエリが当たりやすい。
  const queries: string[] = [];
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(l => l.length >= 2);
  queries.push(...lines);
  const whole = normalizeText(text);
  if (whole.length >= 2) queries.push(whole);

  // タイトル+アーティスト単位でベストスコアだけを残す Map を構築。
  const pool = new Map<string, { song: SongDataEntry; score: number }>();
  for (const q of queries) {
    const results = fuse.search(q, { limit: MAX_CANDIDATES });
    for (const r of results) {
      if (r.score === undefined) continue;
      if (r.score > CANDIDATE_SCORE_CUTOFF) continue;
      const key = `${r.item.song.title}|${r.item.song.artist}`;
      const existing = pool.get(key);
      if (!existing || r.score < existing.score) {
        pool.set(key, { song: r.item.song, score: r.score });
      }
    }
  }

  const sorted = Array.from(pool.values()).sort((a, b) => a.score - b.score).slice(0, MAX_CANDIDATES);
  if (sorted.length === 0) {
    status.value = 'ready';
    noMatchMessage.value = t('ocrSearch.noMatchTryAgain');
    return;
  }

  // Fuse のスコア（0=完全一致 .. 1=遠い）を 0〜100 の一致度に変換して保持
  matchCandidates.value = sorted.map(({ song, score }) => ({
    song,
    score: Math.max(0, Math.min(100, Math.round((1 - score) * 100))),
  }));
  status.value = 'candidates';
  cleanupResources();
};

const retry = () => {
  matchCandidates.value = [];
  recognizedText.value = '';
  errorMessage.value = '';
  status.value = 'idle';
  openCamera();
};

/** 候補一覧からひとつを選んで確定。親に `matched` を伝える。 */
const pickCandidate = (c: { song: SongDataEntry; score: number }) => {
  emit('matched', c.song);
};

const closeModal = () => {
  cleanupResources();
  emit('close');
};

onBeforeUnmount(() => {
  cleanupResources();
});
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/80 backdrop-blur-sm p-4 animate-fade-in"
      @click.self="closeModal"
    >
      <div class="bg-white dark:bg-slate-800 rounded-md shadow-xl w-full max-w-lg overflow-hidden flex flex-col max-h-[90vh]">
        <!-- ヘッダ: タイトル + 閉じるボタン -->
        <div class="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <div>
            <h2 class="text-lg font-bold text-slate-900 dark:text-white">{{ t('ocrSearch.title') }}</h2>
            <p class="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{{ t('ocrSearch.subtitle') }}</p>
          </div>
          <button
            @click="closeModal"
            class="shrink-0 w-8 h-8 flex items-center justify-center rounded-lg text-slate-400 hover:text-slate-600 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            aria-label="close"
          >
            <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <!-- 本体 -->
        <div class="p-6 space-y-4 overflow-y-auto">
          <!-- 初期状態: カメラ起動ボタン -->
          <div v-if="status === 'idle'" class="flex flex-col items-center py-4 space-y-4">
            <div class="w-20 h-20 rounded-md bg-blue-50 dark:bg-blue-900/30 flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-10 w-10 text-blue-600 dark:text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
            </div>
            <p class="text-sm text-slate-500 dark:text-slate-400 text-center">{{ t('ocrSearch.hint') }}</p>
            <button
              @click="openCamera"
              class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 text-white font-bold rounded-md transition-all"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
              {{ t('ocrSearch.openCamera') }}
            </button>
          </div>

          <!-- カメラ映像 + シャッターボタン（ready/capturing/initializing 共通） -->
          <div v-else-if="status === 'initializing' || status === 'ready' || status === 'capturing'" class="space-y-3">
            <div class="relative aspect-video bg-black rounded-md overflow-hidden">
              <video
                ref="videoRef"
                autoplay
                playsinline
                muted
                class="absolute inset-0 w-full h-full object-cover"
              />
              <!-- スキャンエリアのオーバーレイ枠（CROP_*_PCT 定数と同じ位置・サイズに揃える） -->
              <div class="absolute inset-0 pointer-events-none">
                <div
                  class="absolute border-2 border-blue-400 rounded-lg shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]"
                  :style="{
                    top: `${CROP_TOP_PCT * 100}%`,
                    left: `${CROP_LEFT_PCT * 100}%`,
                    width: `${CROP_WIDTH_PCT * 100}%`,
                    height: `${CROP_HEIGHT_PCT * 100}%`,
                  }"
                ></div>
              </div>
              <!-- 状態バッジ -->
              <div class="absolute top-2 left-2 bg-black/70 text-white text-[11px] font-bold px-2.5 py-1 rounded flex items-center gap-2">
                {{ status === 'initializing' ? t('ocrSearch.initializing') : (status === 'capturing' ? t('ocrSearch.capturing') : t('ocrSearch.ready')) }}
              </div>
            </div>

            <!-- シャッターボタン -->
            <button
              @click="captureAndRecognize"
              :disabled="status !== 'ready'"
              class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 text-white font-bold rounded-md transition-all disabled:opacity-60 disabled:cursor-not-allowed"
            >
              <svg v-if="status === 'capturing'" xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 animate-spin" viewBox="0 0 24 24" fill="none">
                <circle cx="12" cy="12" r="10" stroke="currentColor" stroke-width="3" stroke-opacity="0.3" />
                <path d="M4 12a8 8 0 018-8" stroke="currentColor" stroke-width="3" stroke-linecap="round" />
              </svg>
              <svg v-else xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
              {{ status === 'capturing' ? t('ocrSearch.capturing') : t('ocrSearch.capture') }}
            </button>

            <!-- マッチ失敗メッセージ -->
            <div
              v-if="noMatchMessage"
              class="p-3 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-md text-sm text-amber-700 dark:text-amber-300 font-medium"
            >
              {{ noMatchMessage }}
            </div>

            <p v-if="recognizedText" class="text-[11px] font-mono text-slate-500 dark:text-slate-400 break-all bg-slate-50 dark:bg-slate-900/50 p-2 rounded-lg">
              {{ t('ocrSearch.recognized', { text: recognizedText }) }}
            </p>
          </div>

          <!-- 候補一覧: スコア順でカード表示し、ユーザーにタップで選ばせる -->
          <div v-else-if="status === 'candidates'" class="space-y-3">
            <div class="flex items-baseline justify-between">
              <p class="text-sm font-bold text-slate-700 dark:text-slate-200">{{ t('ocrSearch.selectSong') }}</p>
              <p class="text-[11px] text-slate-500 dark:text-slate-400">{{ matchCandidates.length }}</p>
            </div>

            <p v-if="recognizedText" class="text-[11px] font-mono text-slate-500 dark:text-slate-400 break-all bg-slate-50 dark:bg-slate-900/50 p-2 rounded-lg">
              {{ t('ocrSearch.recognized', { text: recognizedText }) }}
            </p>

            <div class="space-y-2 max-h-[55vh] overflow-y-auto -mx-1 px-1">
              <button
                v-for="(c, i) in matchCandidates"
                :key="`${c.song.title}|${c.song.artist}|${i}`"
                @click="pickCandidate(c)"
                class="w-full text-left p-3 rounded-md border transition-all"
                :class="i === 0
                  ? 'bg-emerald-50 dark:bg-emerald-900/30 border-emerald-200 dark:border-emerald-800 hover:bg-emerald-100 dark:hover:bg-emerald-900/40'
                  : 'bg-white dark:bg-slate-700/40 border-slate-200 dark:border-slate-600 hover:border-blue-300 dark:hover:border-blue-500 hover:bg-blue-50/40 dark:hover:bg-slate-700'"
              >
                <div class="flex items-start gap-3">
                  <div
                    class="shrink-0 min-w-[3rem] h-8 flex items-center justify-center rounded-lg text-xs font-bold tabular-nums"
                    :class="i === 0
                      ? 'bg-emerald-500 text-white'
                      : 'bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300'"
                  >
                    {{ c.score }}%
                  </div>
                  <div class="flex-1 min-w-0">
                    <h4 class="text-sm font-bold text-slate-900 dark:text-white break-words leading-tight">{{ c.song.title }}</h4>
                    <p class="text-xs text-slate-600 dark:text-slate-300 truncate mt-0.5">{{ c.song.artist }}</p>
                    <p v-if="c.song.genre" class="text-[11px] text-slate-500 dark:text-slate-400 truncate">{{ c.song.genre }}</p>
                  </div>
                </div>
              </button>
            </div>

            <button
              @click="retry"
              class="w-full flex items-center justify-center gap-2 px-4 py-2.5 bg-white dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-bold rounded-md border border-slate-200 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-600 transition-all text-sm"
            >
              {{ t('ocrSearch.retry') }}
            </button>
          </div>

          <!-- エラー -->
          <div v-else-if="status === 'error'" class="space-y-3">
            <div class="p-4 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-md">
              <div class="flex items-start gap-3">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-red-500 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
                <p class="text-sm text-red-700 dark:text-red-300 font-medium">{{ errorMessage || t('ocrSearch.cameraError') }}</p>
              </div>
            </div>
            <button
              @click="retry"
              class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-blue-700 hover:bg-blue-800 dark:bg-blue-600 dark:hover:bg-blue-500 text-white font-bold rounded-md transition-all"
            >
              {{ t('ocrSearch.retry') }}
            </button>
          </div>

          <!-- 非表示の作業用キャンバス -->
          <canvas ref="canvasRef" class="hidden"></canvas>
        </div>
      </div>
    </div>
  </Teleport>
</template>
