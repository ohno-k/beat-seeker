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
import { createWorker, type Worker as TesseractWorker } from 'tesseract.js';
import Fuse from 'fuse.js';
import { useI18n } from '../composables/useI18n';
import { songData, type SongDataEntry } from '../composables/useGameData';

const { t } = useI18n();

const emit = defineEmits<{
  (e: 'close'): void;
  (e: 'matched', song: SongDataEntry): void;
}>();

/** 状態マシン。UI の表示切替に使う。 */
type Status = 'idle' | 'initializing' | 'scanning' | 'matched' | 'error';
const status = ref<Status>('idle');

const videoRef = ref<HTMLVideoElement | null>(null);
const canvasRef = ref<HTMLCanvasElement | null>(null);

const errorMessage = ref('');
const recognizedText = ref('');
const lastMatch = ref<{ song: SongDataEntry; score: number } | null>(null);

/** OCR ワーカー本体。アンマウント時に terminate する必要がある。 */
let worker: TesseractWorker | null = null;
/** カメラストリーム。アンマウント時に tracks を stop する必要がある。 */
let stream: MediaStream | null = null;
/** 次回スキャンまでの setTimeout ハンドル。 */
let scanTimer: number | null = null;
/** 現在 OCR 実行中か（多重実行防止）。 */
let isRecognizing = false;

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

const openCamera = async () => {
  errorMessage.value = '';
  recognizedText.value = '';
  lastMatch.value = null;

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

    if (videoRef.value) {
      videoRef.value.srcObject = stream;
      await videoRef.value.play();
    }

    buildFuseIndex();
    status.value = 'scanning';
    startScanLoop();
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
  if (scanTimer !== null) {
    clearTimeout(scanTimer);
    scanTimer = null;
  }
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
 * スキャンループ。約 700ms 間隔で一コマずつ OCR に回す。
 * setTimeout チェーンにしているので OCR 実行時間分だけ間隔が伸びる（= 暴走しない）。
 */
const startScanLoop = () => {
  const tick = async () => {
    if (status.value !== 'scanning') return;
    try {
      await scanFrame();
    } catch (e) {
      // 個別の OCR 失敗はログだけ出して継続（偶発ノイズでループを止めない）
      console.warn('OCR frame failed:', e);
    }
    if (status.value === 'scanning') {
      scanTimer = window.setTimeout(tick, 700);
    }
  };
  tick();
};

/**
 * 1 フレームをキャプチャして OCR → ファジー検索。
 * 画面中央の帯（楽曲タイトルが大きく表示される領域）をクロップして OCR 精度を上げる。
 */
const scanFrame = async () => {
  if (isRecognizing) return;
  if (!videoRef.value || !canvasRef.value || !worker || !fuse) return;
  const video = videoRef.value;
  if (video.readyState < 2 || video.videoWidth === 0) return;

  const canvas = canvasRef.value;
  const vw = video.videoWidth;
  const vh = video.videoHeight;

  // 中央 80% × 上下 25%〜65% の帯をクロップ
  // （IIDX 選曲画面で曲名が出る位置を想定。画面外でも Fuse のしきい値で弾ける）
  const cropX = Math.floor(vw * 0.10);
  const cropY = Math.floor(vh * 0.25);
  const cropW = Math.floor(vw * 0.80);
  const cropH = Math.floor(vh * 0.40);

  canvas.width = cropW;
  canvas.height = cropH;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  ctx.drawImage(video, cropX, cropY, cropW, cropH, 0, 0, cropW, cropH);

  isRecognizing = true;
  let text = '';
  try {
    const result = await worker.recognize(canvas);
    text = (result.data.text || '').trim();
  } finally {
    isRecognizing = false;
  }
  if (!text) return;
  recognizedText.value = text.replace(/\s+/g, ' ').slice(0, 80);

  // 行ごと・全文ごとに検索して最良一致を拾う
  const candidates: string[] = [];
  const lines = text.split(/\r?\n/).map(l => l.trim()).filter(l => l.length >= 2);
  candidates.push(...lines);
  candidates.push(normalizeText(text));

  let best: { score: number; song: SongDataEntry } | null = null;
  for (const q of candidates) {
    if (q.length < 2) continue;
    const results = fuse.search(q);
    if (results.length === 0) continue;
    const r = results[0];
    if (r.score === undefined) continue;
    if (!best || r.score < best.score) {
      best = { score: r.score, song: r.item.song };
    }
  }
  if (best && best.score <= 0.3) {
    // score 0 = 完全一致, 1 = 全く一致しない。0.3 以下なら確信度高い
    lastMatch.value = {
      song: best.song,
      score: Math.max(0, Math.min(100, Math.round((1 - best.score) * 100))),
    };
    status.value = 'matched';
    cleanupResources();
  }
};

const retry = () => {
  lastMatch.value = null;
  recognizedText.value = '';
  errorMessage.value = '';
  status.value = 'idle';
  openCamera();
};

const confirmMatch = () => {
  if (lastMatch.value) {
    emit('matched', lastMatch.value.song);
  }
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
      <div class="bg-white dark:bg-slate-800 rounded-2xl shadow-2xl w-full max-w-lg overflow-hidden flex flex-col max-h-[90vh]">
        <!-- ヘッダ: タイトル + 閉じるボタン -->
        <div class="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700 shrink-0">
          <div>
            <h2 class="text-lg font-black text-slate-900 dark:text-white">{{ t('ocrSearch.title') }}</h2>
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
            <div class="w-20 h-20 rounded-2xl bg-blue-50 dark:bg-blue-900/30 flex items-center justify-center">
              <svg xmlns="http://www.w3.org/2000/svg" class="h-10 w-10 text-blue-600 dark:text-blue-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 9a2 2 0 012-2h.93a2 2 0 001.664-.89l.812-1.22A2 2 0 0110.07 4h3.86a2 2 0 011.664.89l.812 1.22A2 2 0 0018.07 7H19a2 2 0 012 2v9a2 2 0 01-2 2H5a2 2 0 01-2-2V9z" />
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 13a3 3 0 11-6 0 3 3 0 016 0z" />
              </svg>
            </div>
            <p class="text-sm text-slate-500 dark:text-slate-400 text-center">{{ t('ocrSearch.hint') }}</p>
            <button
              @click="openCamera"
              class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-bold rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 hover:-translate-y-0.5 transition-all"
            >
              <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z" />
              </svg>
              {{ t('ocrSearch.openCamera') }}
            </button>
          </div>

          <!-- カメラ起動中 / スキャン中 -->
          <div v-else-if="status === 'initializing' || status === 'scanning'" class="space-y-3">
            <div class="relative aspect-video bg-black rounded-xl overflow-hidden">
              <video
                ref="videoRef"
                autoplay
                playsinline
                muted
                class="absolute inset-0 w-full h-full object-cover"
              />
              <!-- スキャンエリアのオーバーレイ枠 -->
              <div class="absolute inset-0 pointer-events-none flex items-center justify-center">
                <div
                  class="border-2 border-blue-400 rounded-lg shadow-[0_0_0_9999px_rgba(0,0,0,0.35)]"
                  :style="{ width: '80%', height: '40%', marginTop: '-5%' }"
                ></div>
              </div>
              <!-- 状態バッジ -->
              <div class="absolute top-2 left-2 bg-black/70 text-white text-[11px] font-bold px-2.5 py-1 rounded-full flex items-center gap-2">
                <span class="inline-block w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"></span>
                {{ status === 'initializing' ? t('ocrSearch.initializing') : t('ocrSearch.scanning') }}
              </div>
            </div>
            <p v-if="recognizedText" class="text-[11px] font-mono text-slate-500 dark:text-slate-400 break-all bg-slate-50 dark:bg-slate-900/50 p-2 rounded-lg">
              {{ t('ocrSearch.recognized', { text: recognizedText }) }}
            </p>
          </div>

          <!-- マッチ成功 -->
          <div v-else-if="status === 'matched' && lastMatch" class="space-y-4">
            <div class="p-5 bg-gradient-to-br from-emerald-50 to-teal-50 dark:from-emerald-900/30 dark:to-teal-900/30 border border-emerald-200 dark:border-emerald-800 rounded-xl">
              <div class="flex items-start gap-3">
                <div class="w-10 h-10 shrink-0 rounded-full bg-emerald-500 flex items-center justify-center">
                  <svg xmlns="http://www.w3.org/2000/svg" class="h-6 w-6 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="3" d="M5 13l4 4L19 7" />
                  </svg>
                </div>
                <div class="flex-1 min-w-0">
                  <p class="text-xs font-bold text-emerald-600 dark:text-emerald-400 uppercase tracking-wider mb-1">
                    {{ lastMatch.score }}% match
                  </p>
                  <h3 class="text-lg font-black text-slate-900 dark:text-white break-words">{{ lastMatch.song.title }}</h3>
                  <p class="text-sm text-slate-600 dark:text-slate-300 truncate">{{ lastMatch.song.artist }}</p>
                  <p v-if="lastMatch.song.genre" class="text-xs text-slate-500 dark:text-slate-400 mt-1">{{ lastMatch.song.genre }}</p>
                </div>
              </div>
            </div>
            <div class="flex gap-2">
              <button
                @click="retry"
                class="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-white dark:bg-slate-700 text-slate-700 dark:text-slate-200 font-bold rounded-xl border border-slate-200 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-600 transition-all text-sm"
              >
                {{ t('ocrSearch.retry') }}
              </button>
              <button
                @click="confirmMatch"
                class="flex-1 flex items-center justify-center gap-2 px-4 py-2.5 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-bold rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 transition-all text-sm"
              >
                {{ t('ocrSearch.goToChart') }}
              </button>
            </div>
          </div>

          <!-- エラー -->
          <div v-else-if="status === 'error'" class="space-y-3">
            <div class="p-4 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 rounded-xl">
              <div class="flex items-start gap-3">
                <svg xmlns="http://www.w3.org/2000/svg" class="h-5 w-5 text-red-500 shrink-0 mt-0.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
                </svg>
                <p class="text-sm text-red-700 dark:text-red-300 font-medium">{{ errorMessage || t('ocrSearch.cameraError') }}</p>
              </div>
            </div>
            <button
              @click="retry"
              class="w-full flex items-center justify-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-indigo-600 text-white font-bold rounded-xl shadow-lg shadow-blue-500/20 hover:shadow-blue-500/40 transition-all"
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
