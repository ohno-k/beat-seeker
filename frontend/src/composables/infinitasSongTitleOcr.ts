/**
 * 【モジュールの役割】 INFINITAS リザルトの曲名 ROI を OCR し、(難易度, NOTES[, BPM]) で
 * 絞り込んだ候補配列の中から「読み取った曲名に最も近い」1 曲を選ぶ。
 *
 * ピクセル認識（[[file:infinitasResultRecognizer]]）は (難易度, NOTES) でほぼ一意に曲を特定
 * できるが、ノーツ数が同じ別曲が複数残るケースがある。そこで候補が 2 件以上のときだけ、
 * 確定直前に曲名 ROI を OCR し、文字列距離で最良候補を選ぶ。
 *
 * 設計方針:
 *  - tesseract.js ワーカーは**遅延初期化**（初回の matchSongTitleByOcr 呼び出し時に生成）。
 *    監視を始めても候補衝突が起きなければ一切ロードしない。
 *  - 前処理は [[file:OcrSearchModal]] の preprocessFrame と同じ大津法二値化 + 暗背景反転。
 *    Impact 系の太字曲名で精度が出る。OCR 対象 ROI は SONG_TITLE_FHD（画面下部中央）。
 *  - マッチングは Fuse.js ではなく**正規化レーベンシュタイン距離**。候補は既に数件に絞られて
 *    いるため全件スキャンで十分速く、依存も増やさない。OCR 結果が曲名そのものに近いほど
 *    距離が 0 に近づく。
 *
 * 注意: ワーカーは監視終了時に terminateSongTitleOcr() で解放すること。
 */
import { createWorker, PSM, type Worker as TesseractWorker } from 'tesseract.js';
import type { SongDataEntry } from './useGameData';
import { SONG_TITLE_FHD } from './infinitasResultRecognizer';

/**
 * OCR で曲名を当てにいくと判断する最低一致度（0〜1、高いほど一致）。
 * これ未満なら「OCR でも決められなかった」とみなし候補差し替えをしない（誤確定を避ける）。
 *
 * 正規化レーベンシュタイン類似度 = 1 - distance / max(len)。0.5 は「半分以上の文字が一致」相当。
 * OCR は数文字化けるのが普通なので、短い曲名で 1〜2 文字外しても拾える緩めの値にしている。
 */
const MIN_SIMILARITY = 0.5;

/**
 * OCR を 1 候補に確定するために要求する「2 位との差」。
 * 1 位と 2 位の類似度が拮抗している（差がこの値未満）ときは差し替えを見送る。
 * 似た曲名同士で OCR がどちらに転んでもおかしくない状況での誤確定を防ぐ。
 */
const MIN_MARGIN = 0.08;

/** 遅延初期化される OCR ワーカー。未初期化は null。 */
let worker: TesseractWorker | null = null;
/** 初期化中の Promise（多重初期化を防ぐ）。 */
let initPromise: Promise<TesseractWorker> | null = null;

/**
 * OCR ワーカーを取得する（初回は生成）。
 * OcrSearchModal と同じパラメータ: 単一ブロック + 英数記号ホワイトリスト。
 */
async function getWorker(): Promise<TesseractWorker> {
  if (worker) return worker;
  if (!initPromise) {
    initPromise = (async () => {
      const w = await createWorker('eng');
      await w.setParameters({
        tessedit_pageseg_mode: PSM.SINGLE_LINE,
        tessedit_char_whitelist:
          "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 '&!?().,-:/~*+",
      });
      worker = w;
      return w;
    })();
  }
  return initPromise;
}

/** OCR ワーカーを解放する。監視停止時に呼ぶ。 */
export function terminateSongTitleOcr(): void {
  if (worker) {
    worker.terminate().catch(() => {});
    worker = null;
  }
  initPromise = null;
}

/**
 * 曲名 ROI を OCR 用に前処理した canvas を返す。
 * OcrSearchModal.preprocessFrame と同じ手順: 2 倍拡大 → グレースケール → 大津法二値化 →
 * 暗背景なら反転（「白背景 + 黒文字」に正規化）。
 */
function preprocessTitleRoi(src: HTMLCanvasElement): HTMLCanvasElement | null {
  const srcCtx = src.getContext('2d', { willReadFrequently: true });
  if (!srcCtx) return null;

  const SCALE = 2;
  const out = document.createElement('canvas');
  out.width = SONG_TITLE_FHD.w * SCALE;
  out.height = SONG_TITLE_FHD.h * SCALE;
  const ctx = out.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;

  ctx.imageSmoothingEnabled = true;
  ctx.imageSmoothingQuality = 'high';
  ctx.drawImage(
    src,
    SONG_TITLE_FHD.x, SONG_TITLE_FHD.y, SONG_TITLE_FHD.w, SONG_TITLE_FHD.h,
    0, 0, out.width, out.height,
  );

  const imageData = ctx.getImageData(0, 0, out.width, out.height);
  const data = imageData.data;
  const pixelCount = data.length >> 2;

  // 輝度配列とヒストグラムを一度の走査で作る（BT.709 係数）
  const gray = new Uint8Array(pixelCount);
  const hist = new Uint32Array(256);
  let lumSum = 0;
  for (let i = 0, j = 0; i < data.length; i += 4, j++) {
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
  return out;
}

/**
 * 曲名比較用の正規化。OcrSearchModal.normalizeText と方針を揃える:
 *  - 小文字化、各種空白を 1 個のスペースに、前後トリム
 * さらに距離計算では記号差のブレを抑えるため英数字以外を除去する（subtitle の括弧など）。
 */
function normalizeForCompare(s: string): string {
  return s
    .toLowerCase()
    .replace(/[　\t\r\n]+/g, ' ')
    .replace(/[^a-z0-9]+/g, '')
    .trim();
}

/**
 * レーベンシュタイン距離（編集距離）。2 行 DP で O(n) メモリ。
 */
function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;

  let prev = new Array<number>(b.length + 1);
  let curr = new Array<number>(b.length + 1);
  for (let j = 0; j <= b.length; j++) prev[j] = j;

  for (let i = 1; i <= a.length; i++) {
    curr[0] = i;
    const ca = a.charCodeAt(i - 1);
    for (let j = 1; j <= b.length; j++) {
      const cost = ca === b.charCodeAt(j - 1) ? 0 : 1;
      curr[j] = Math.min(
        prev[j] + 1,      // 削除
        curr[j - 1] + 1,  // 挿入
        prev[j - 1] + cost, // 置換
      );
    }
    [prev, curr] = [curr, prev];
  }
  return prev[b.length];
}

/**
 * 正規化レーベンシュタイン類似度（0〜1、1 が完全一致）。
 */
function similarity(a: string, b: string): number {
  const na = normalizeForCompare(a);
  const nb = normalizeForCompare(b);
  if (na.length === 0 && nb.length === 0) return 1;
  const maxLen = Math.max(na.length, nb.length);
  if (maxLen === 0) return 0;
  return 1 - levenshtein(na, nb) / maxLen;
}

/**
 * 曲名 ROI を OCR し、候補配列から最も近い 1 曲を選ぶ。
 *
 * 判定:
 *  - 候補が 0〜1 件なら OCR 不要なので即 null（呼び出し側でガードする想定だが二重に防御）。
 *  - OCR テキストが空、最良一致が MIN_SIMILARITY 未満、または 1 位と 2 位の差が MIN_MARGIN
 *    未満（拮抗）なら null（差し替え見送り）。
 *  - それ以外は最良候補を返す。
 *
 * @param fhdCanvas 認識用 FHD canvas（呼び出し時点で現フレームが転写済みであること）。
 * @param candidates (難易度, NOTES[, BPM]) で絞り込んだ候補。
 * @returns 最良候補、または決められなければ null。
 */
export async function matchSongTitleByOcr(
  fhdCanvas: HTMLCanvasElement,
  candidates: SongDataEntry[],
): Promise<SongDataEntry | null> {
  if (candidates.length < 2) return null;

  const processed = preprocessTitleRoi(fhdCanvas);
  if (!processed) return null;

  let text = '';
  try {
    const w = await getWorker();
    const result = await w.recognize(processed);
    text = (result.data.text || '').trim();
  } catch (e) {
    console.warn('[song-title-ocr] recognition failed:', e);
    return null;
  }

  if (normalizeForCompare(text).length < 2) return null;

  // 各候補との類似度を計算し、降順に並べる。
  const scored = candidates
    .map(song => ({ song, score: similarity(text, song.title) }))
    .sort((a, b) => b.score - a.score);

  const best = scored[0];
  if (best.score < MIN_SIMILARITY) return null;

  // 2 位と拮抗しているなら誤確定を避けて見送る。
  const second = scored[1];
  if (second && best.score - second.score < MIN_MARGIN) return null;

  return best.song;
}
