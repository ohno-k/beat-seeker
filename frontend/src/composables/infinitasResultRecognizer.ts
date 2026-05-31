/**
 * 【役割】 INFINITAS / アーケード IIDX のリザルト画面から、固定座標の
 * ピクセル照合方式でスコアと MISS COUNT を抽出する。
 *
 * 参考実装: kaktuswald/inf-notebook (Python) の recog.py
 *  - リザルトパネルを FHD 内のオフセット(=パネル原点)で切り出し
 *  - パネル内ローカル座標で各要素(score/miss/clear_type/dj_level)の矩形を定義
 *  - 数字は固定ピッチの 4 桁スロット。各桁の bitmap パターンをハッシュ化して辞書照合
 *
 * Tesseract OCR がフォントを読めないために崩壊するのを、テンプレート照合で置換する。
 *
 * 制約:
 *  - 入力は実機由来の **ネイティブ FHD (1920x1080)** であること（縮小ズームは精度低下）
 *  - パネル原点は playSide × ゲーム(INFINITAS/Arcade) ごとに 1 組保持
 *  - 数字テンプレ辞書はサンプル画像から事前構築（[[file:digit_table.json]]）
 */
import digitTable from './digit_table.json';
import notesDigitTable from './notes_digit_table.json';
import notesEmptyHashes from './notes_empty_hashes.json';
import playerLabelTemplates from './player_label_templates.json';
import pgreatDigitTable from './pgreat_digit_table.json';
import spdpTemplates from './spdp_templates.json';
import bpmDigitTable from './bpm_digit_table.json';

/** 認識対象のプレイサイド。 */
export type RecognizerPlaySide = '1P' | '2P';

/**
 * パネル原点(=リザルトパネル左上の FHD 上座標)。X はサイドで固定、Y は UI バリエーションで
 * ±数 px 動くため `detectPanelY0()` で実測する。基準値は NEWER UI の Y0=63。
 *
 * INFINITAS には少なくとも 2 つの UI バリエーションがある:
 *  - OLDER: score 数字が y≈556 から（Y0=66）
 *  - NEWER: score 数字が y≈553 から（Y0=63）
 */
export const PANEL_ORIGINS: Record<RecognizerPlaySide, { x: number; y: number }> = {
  '1P': { x: 0,    y: 63 },
  '2P': { x: 1351, y: 63 },
};

/** Y0 自動推定で試す候補（基準 63 を中心に ±5px）。 */
const PANEL_Y0_CANDIDATES = [61, 62, 63, 64, 65, 66, 67, 68];

/**
 * パネル Y0 を「digit_table に直接一致する桁が最も多くなる Y0」で推定する。
 *
 * SCORE ラベルの位置を見る方式はノイズに弱く OLD/NEW を取り違えたため、より堅牢な
 * 自己検証方式に変更: 各候補 Y0 で score/miss の 16 セルをハッシュ化し、組み込み/学習辞書に
 * **完全一致**するセル数を数え、最大の Y0 を採用する。正しい Y0 では桁が辞書に直接当たり、
 * 3px ずれると当たらないため、ラベル位置に依存せず確実に合焦する。
 *
 * （score/miss の ROI と digit hash は後段の定義を参照。関数呼び出しは実行時なので前方参照で問題ない。）
 */
function countDirectDigitHits(canvas: HTMLCanvasElement, originX: number, y0: number): number {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return 0;
  const regions = [
    PANEL_LOCAL_ROI.scoreBest, PANEL_LOCAL_ROI.scoreCurrent,
    PANEL_LOCAL_ROI.missCountBest, PANEL_LOCAL_ROI.missCountCurrent,
  ];
  let hits = 0;
  for (const roi of regions) {
    const cellW = Math.floor(roi.w / 4);
    for (let i = 0; i < 4; i++) {
      const data = ctx.getImageData(originX + roi.x + i * cellW, y0 + roi.y, cellW, roi.h).data;
      const h = computeDigitHash(data, cellW, roi.h);
      if (DIGIT_HASH_MAP.has(h) || LEARNED_DIGIT_MAP.has(h)) { hits++; continue; }
      // 明るい背景で固定 hash が潰れる画面でも Y0 を正しく合焦させるため、適応 hash の直接一致も数える。
      const a = computeDigitHashAdaptive(data, cellW, roi.h);
      if (a !== null && (DIGIT_HASH_MAP.has(a) || LEARNED_DIGIT_MAP.has(a))) hits++;
    }
  }
  return hits;
}

function detectPanelY0(canvas: HTMLCanvasElement, side: RecognizerPlaySide): number {
  const originX = PANEL_ORIGINS[side].x;
  let bestY0 = PANEL_ORIGINS[side].y, bestHits = -1;
  for (const y0 of PANEL_Y0_CANDIDATES) {
    const hits = countDirectDigitHits(canvas, originX, y0);
    if (hits > bestHits) { bestHits = hits; bestY0 = y0; }
  }
  return bestY0;
}

/**
 * パネル内ローカル座標での要素矩形。
 * inf-notebook の details3.2.res から抽出した値をそのまま使用。
 */
export const PANEL_LOCAL_ROI = {
  /** 「クリアタイプ」行の 1px 厚の色帯。BEST 列。色の最頻値でクリアタイプを判別。 */
  clearTypeBest:    { x: 216, y: 363, w: 102, h: 1 },
  /** 同 CURRENT 列。 */
  clearTypeCurrent: { x: 378, y: 363, w: 102, h: 1 },
  /** DJ LEVEL の小数字（白固定）の領域。BEST 列。 */
  djLevelBest:      { x: 262, y: 426, w:  38, h: 19 },
  /** 同 CURRENT 列。 */
  djLevelCurrent:   { x: 424, y: 426, w:  38, h: 19 },
  /** EX SCORE 4 桁の領域。BEST 列。digit=4。 */
  scoreBest:        { x: 195, y: 490, w: 144, h: 18 },
  /** 同 CURRENT 列。 */
  scoreCurrent:     { x: 357, y: 490, w: 144, h: 18 },
  /** MISS COUNT 4 桁の領域。BEST 列。 */
  missCountBest:    { x: 195, y: 558, w: 144, h: 18 },
  /** 同 CURRENT 列。 */
  missCountCurrent: { x: 357, y: 558, w: 144, h: 18 },
} as const;

/** 各桁のハッシュサンプリング y インデックス(step 2, 9 行)。 */
const HASH_Y_INDICES = [1, 3, 5, 7, 9, 11, 13, 15, 17];
/** 各桁のハッシュサンプリング x インデックス(step 4, 8 列)。 */
const HASH_X_INDICES = [4, 8, 12, 16, 20, 24, 28, 32];
/** 二値化しきい値（max(R,G,B) がこれ以上なら「文字あり」）。 */
const BRIGHTNESS_THRESHOLD = 180;
/** ハミング距離による曖昧マッチの最大距離（hex 文字数換算）。 */
const HAMMING_MAX_DISTANCE = 3;

/**
 * 適応的二値化（明るい背景の救済）用パラメータ。
 *
 * 固定しきい値 {@link BRIGHTNESS_THRESHOLD}=180 は「暗い背景＋明るい数字」前提。EXTRA STAGE RESULT の
 * ように鮮やかなキャラ絵が半透明パネル越しに透ける演出では CURRENT 列の背景自体が明るく(～190)、
 * 空セルも数字も全ビット 1 に潰れてハッシュが辞書に当たらない（＝スコアが読めず classify が skip して
 * サイレント無視＝「読み込んでもらいにくい」の主因）。
 *
 * 固定方式で読めなかったセルに限り、セル内サンプル点の min/max から相対しきい値
 * `min + (max-min)*RATIO` で二値化し直す。背景(～190)と数字(～255)の差が残っていれば暗背景時と
 * 同じビットパターンへ正規化され、既存 digit 辞書にそのまま当たる（実測: bright 背景の "2046" を全桁復元）。
 */
const ADAPTIVE_THRESHOLD_RATIO = 0.4;
/** 適応二値化を使う最小コントラスト。これ未満は一様セル（数字なし）とみなし不使用。 */
const ADAPTIVE_CONTRAST_MIN = 45;
/**
 * 適応二値化を使う最小背景明度（セル内サンプルの min）。これ未満（＝暗背景セル）は固定方式で十分なので
 * 適応を使わない。これにより通常の暗背景画面では挙動が一切変わらず（固定で読めなければ従来通り null）、
 * 明るい背景で潰れたセルだけを救済する。
 */
const ADAPTIVE_MIN_BACKGROUND = 120;

/** Score / MISS COUNT 用 digit 辞書（白/シアン、cellW=36, cellH=18）。 */
const DIGIT_HASH_MAP = new Map<string, number>(
  Object.entries(digitTable as Record<string, number>),
);

/**
 * NOTES 数表示用 digit 辞書。譜面情報行 "1492 NOTES" の 4 桁。
 * フォントが score 列とは別系統（小型・サンセリフ寄り）なので別テーブル。
 *
 * Cell size: 18×25。HASH サンプル位置も score 用とは別の専用座標。
 */
const NOTES_DIGIT_HASH_MAP = new Map<string, number>(
  Object.entries(notesDigitTable as Record<string, number>),
);
/**
 * NOTES 用ハッシュサンプリング (12 行 × 8 列 = 96 ビット → 24 hex 文字)。
 *
 * 旧サイズ (48 bit) では "4" と "9" がたまたま同じ hash を返す衝突が発生したため拡張。
 * 倍密度になり、観測 40 サンプル中での衝突は 0 件まで縮小した。
 */
const NOTES_HASH_Y_INDICES = [1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23];
const NOTES_HASH_X_INDICES = [2, 4, 6, 8, 10, 12, 14, 16];
const NOTES_BRIGHTNESS_THRESHOLD = 140;

/**
 * RGBA 連続バッファから 1 桁分のハッシュを算出する。
 *
 * inf-notebook 流: 9×8=72 セルをグレースケール明度で二値化し、4 ビットごとに hex 文字へパック。
 * 結果は 18 文字の hex 文字列（72/4）。同じ数字でもフォント色や ±1px の揺らぎでハッシュは変わるため、
 * 辞書には 1 数字あたり複数エントリを格納してある。
 *
 * @param rgba 桁セル内の RGBA バッファ。stride は cellWidth*4。
 * @param cellWidth 桁セル横幅 (px)。通常 36。
 * @param cellHeight 桁セル縦幅 (px)。通常 18。
 */
export function computeDigitHash(rgba: Uint8ClampedArray, cellWidth: number, cellHeight: number): string {
  const bits: number[] = [];
  for (const y of HASH_Y_INDICES) {
    if (y >= cellHeight) break;
    for (const x of HASH_X_INDICES) {
      if (x >= cellWidth) break;
      const offset = (y * cellWidth + x) * 4;
      const r = rgba[offset], g = rgba[offset + 1], b = rgba[offset + 2];
      const brightness = Math.max(r, g, b);
      bits.push(brightness >= BRIGHTNESS_THRESHOLD ? 1 : 0);
    }
  }
  // 4 ビットごとに hex 文字へ
  let hex = '';
  for (let i = 0; i < bits.length; i += 4) {
    const v = ((bits[i] || 0) << 3) | ((bits[i + 1] || 0) << 2) | ((bits[i + 2] || 0) << 1) | (bits[i + 3] || 0);
    hex += v.toString(16);
  }
  return hex;
}

/**
 * {@link computeDigitHash} の適応二値化版。明るい背景で潰れたセルの救済専用。
 *
 * サンプル点の min/max から相対しきい値で二値化する。暗背景セル（min が低い）や一様セル
 * （コントラストが小さい＝数字なし）には使わず null を返す。詳細は {@link ADAPTIVE_THRESHOLD_RATIO}。
 *
 * @returns 18 文字 hex（救済対象セル）/ null（暗背景・一様セルで適応不要）。
 */
export function computeDigitHashAdaptive(rgba: Uint8ClampedArray, cellWidth: number, cellHeight: number): string | null {
  const vals: number[] = [];
  for (const y of HASH_Y_INDICES) {
    if (y >= cellHeight) break;
    for (const x of HASH_X_INDICES) {
      if (x >= cellWidth) break;
      const offset = (y * cellWidth + x) * 4;
      vals.push(Math.max(rgba[offset], rgba[offset + 1], rgba[offset + 2]));
    }
  }
  let mn = 255, mx = 0;
  for (const v of vals) { if (v < mn) mn = v; if (v > mx) mx = v; }
  // 暗背景セルは固定方式で十分（＝救済不要）。一様セルは数字なし。どちらも適応しない。
  if (mn < ADAPTIVE_MIN_BACKGROUND) return null;
  if (mx - mn < ADAPTIVE_CONTRAST_MIN) return null;
  const threshold = mn + (mx - mn) * ADAPTIVE_THRESHOLD_RATIO;
  let hex = '';
  for (let i = 0; i < vals.length; i += 4) {
    const v = (((vals[i] ?? 0) >= threshold ? 1 : 0) << 3)
            | (((vals[i + 1] ?? 0) >= threshold ? 1 : 0) << 2)
            | (((vals[i + 2] ?? 0) >= threshold ? 1 : 0) << 1)
            | ((vals[i + 3] ?? 0) >= threshold ? 1 : 0);
    hex += v.toString(16);
  }
  return hex;
}

/** hex 文字列のハミング距離（位置不一致数）。 */
function hexHammingDistance(a: string, b: string): number {
  if (a.length !== b.length) return Math.max(a.length, b.length);
  let d = 0;
  for (let i = 0; i < a.length; i++) {
    if (a[i] !== b[i]) d++;
  }
  return d;
}

/**
 * 学習辞書を localStorage に永続化する小さなストア。
 *
 *  - 「score 系」と「notes 系」で別キーに分けて保存（cell 寸法とフォントが違うため衝突を避ける）
 *  - 同一 hash で既知の digit と矛盾するエントリは捨てる（誤学習を 1 段ガード）
 *  - JSON 破損やストレージアクセス不能時は黙って no-op
 */
const LEARNED_DIGIT_KEY = 'beat-seeker:learned-digit-hashes';
const LEARNED_NOTES_KEY = 'beat-seeker:learned-notes-hashes';

function loadLearnedStore(key: string): Map<string, number> {
  if (typeof localStorage === 'undefined') return new Map();
  try {
    const raw = localStorage.getItem(key);
    if (!raw) return new Map();
    const obj = JSON.parse(raw);
    return new Map(Object.entries(obj).map(([k, v]) => [k, Number(v)]));
  } catch {
    return new Map();
  }
}

function saveLearnedStore(key: string, store: Map<string, number>): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.setItem(key, JSON.stringify(Object.fromEntries(store)));
  } catch { /* quota or disabled */ }
}

const LEARNED_DIGIT_MAP = loadLearnedStore(LEARNED_DIGIT_KEY);
const LEARNED_NOTES_MAP = loadLearnedStore(LEARNED_NOTES_KEY);

/**
 * 1 桁分のハッシュから数字を引く。完全一致 → ハミング距離 ≤ HAMMING_MAX_DISTANCE の順で照合。
 *
 * 組み込みテーブル ([[file:digit_table.json]]) → 学習テーブル (localStorage) の順に探索する。
 * 学習テーブルは「組み込みで読めなかったケースをユーザー確認で覚えた」ものなので、
 * 組み込みヒット時はそれを優先（誤学習のフォールバックを避ける）。
 *
 * @returns 0-9 の数字、または null (未知パターン)。
 */
export function lookupDigit(hash: string): number | null {
  const direct = DIGIT_HASH_MAP.get(hash);
  if (direct !== undefined) return direct;
  const learned = LEARNED_DIGIT_MAP.get(hash);
  if (learned !== undefined) return learned;
  // 曖昧マッチ（学習分も含めて全探索）
  let bestDist = HAMMING_MAX_DISTANCE + 1;
  let bestDigit: number | null = null;
  const probe = (k: string, d: number) => {
    const dist = hexHammingDistance(hash, k);
    if (dist < bestDist) {
      bestDist = dist;
      bestDigit = d;
    }
  };
  for (const [k, d] of DIGIT_HASH_MAP) probe(k, d);
  for (const [k, d] of LEARNED_DIGIT_MAP) probe(k, d);
  return bestDigit;
}

/**
 * 1 桁セルの RGBA から数字を引く。まず固定二値化で照合し、読めなければ明るい背景セルに限って
 * 適応二値化で再挑戦する（{@link computeDigitHashAdaptive}）。暗背景セルでは適応が null を返すため
 * 従来挙動（固定で読めなければ未知=null）と完全に一致し、回帰しない。
 */
function lookupDigitCell(rgba: Uint8ClampedArray, cellWidth: number, cellHeight: number): number | null {
  const fixed = lookupDigit(computeDigitHash(rgba, cellWidth, cellHeight));
  if (fixed !== null) return fixed;
  const adaptiveHash = computeDigitHashAdaptive(rgba, cellWidth, cellHeight);
  if (adaptiveHash === null) return null;
  return lookupDigit(adaptiveHash);
}

/**
 * パネル内ローカル ROI を FHD 上の矩形に変換する。
 */
export function panelLocalToFhd(
  origin: { x: number; y: number },
  roi: { x: number; y: number; w: number; h: number },
): { x: number; y: number; w: number; h: number } {
  return { x: origin.x + roi.x, y: origin.y + roi.y, w: roi.w, h: roi.h };
}

/**
 * 4 桁数字領域（score / miss_count）から数値を復元する。
 *
 * 右詰めで描画されるため、左側の空セル（背景）は null となり、それより右の連続した有効桁を結合する。
 *
 * @param canvas ソース画像（FHD 想定）。
 * @param origin 該当 playSide × ゲームのパネル原点。
 * @param panelRoi PANEL_LOCAL_ROI の score* / missCount* のいずれか。
 * @param nDigits 桁数。score も miss_count も 4。
 * @returns 数値、または null（全桁未知）。
 */
export function recognizeNumberField(
  canvas: HTMLCanvasElement,
  origin: { x: number; y: number },
  panelRoi: { x: number; y: number; w: number; h: number },
  nDigits = 4,
): number | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  const fhd = panelLocalToFhd(origin, panelRoi);
  const cellWidth = Math.floor(panelRoi.w / nDigits);
  const digits: (number | null)[] = [];
  for (let i = 0; i < nDigits; i++) {
    const dx = fhd.x + i * cellWidth;
    const dy = fhd.y;
    const data = ctx.getImageData(dx, dy, cellWidth, fhd.h).data;
    digits.push(lookupDigitCell(data, cellWidth, fhd.h));
  }
  // 右詰めで読み取る。左から null が並ぶうちは「空」、最初の数字以降は全部数字として連結。
  let started = false;
  let value = 0;
  let anyDigit = false;
  for (const d of digits) {
    if (d === null) {
      if (started) {
        // 桁の途中で読めなかった → 0 として扱うのではなく失敗扱い
        return null;
      }
      continue;
    }
    started = true;
    anyDigit = true;
    value = value * 10 + d;
  }
  return anyDigit ? value : null;
}

/**
 * 譜面情報行 ("ANOTHER 12 SP 1492 NOTES") の NOTES 数を読み取る。
 *
 * フォントは score 列とは別で小さい。専用の hash テーブル ([[file:notes_digit_table.json]]) で照合。
 *
 * @returns 4 桁以下の正の整数、または null。
 */
export const NOTES_AREA_FHD = { x: 1005, y: 1040, w: 78, h: 25 };
const NOTES_CELL_WIDTH = 18;
const NOTES_CELL_STRIDE = 20;

function computeNotesDigitHash(rgba: Uint8ClampedArray, cellWidth: number, cellHeight: number): string {
  const bits: number[] = [];
  for (const y of NOTES_HASH_Y_INDICES) {
    if (y >= cellHeight) continue;
    for (const x of NOTES_HASH_X_INDICES) {
      if (x >= cellWidth) continue;
      const o = (y * cellWidth + x) * 4;
      const brightness = Math.max(rgba[o], rgba[o + 1], rgba[o + 2]);
      bits.push(brightness >= NOTES_BRIGHTNESS_THRESHOLD ? 1 : 0);
    }
  }
  let hex = '';
  for (let i = 0; i < bits.length; i += 4) {
    const v = ((bits[i] || 0) << 3) | ((bits[i + 1] || 0) << 2) | ((bits[i + 2] || 0) << 1) | (bits[i + 3] || 0);
    hex += v.toString(16);
  }
  return hex;
}

/**
 * NOTES 領域左側の「数字無し」セル(パネル装飾だけが映る)の hash 集合。
 *
 * 4 桁 NOTES の右詰め表現で例えば 746 は実セルが " 746" となり、
 * 先頭セルはパネル chrome だけ映る。chrome の hash がたまたま "3" の hash と
 * Hamming 距離 3 以内に来ると、誤って "3" と読まれて 3746 のような誤読を起こす。
 * 既知の chrome hash を「数字無し」セットとして先頭で除外する。
 */
const NOTES_EMPTY_HASH_SET = new Set<string>(notesEmptyHashes as string[]);
/** 学習で追加された「数字無し」hash。ユーザー確定値の左側に空セルがあった場合に蓄積。 */
const LEARNED_NOTES_EMPTY_SET = new Set<string>();

/**
 * Empty/chrome 用の Hamming しきい値。digit テーブルの近傍とぶつかると digit を誤って捨てるので、
 * digit 用の `HAMMING_MAX_DISTANCE` より厳しめに。実測で chrome ↔ "3" が距離 3 で
 * 衝突する事例があったため、ここを 1 に絞って exact または ±1 ビットの誤差だけ許す。
 */
const NOTES_EMPTY_MAX_DISTANCE = 1;

function lookupNotesDigit(hash: string): number | null {
  // 1) 既知の chrome/empty パターンならここで null 確定（Hamming 落ちで誤マッチさせない）
  if (NOTES_EMPTY_HASH_SET.has(hash) || LEARNED_NOTES_EMPTY_SET.has(hash)) return null;
  for (const empty of NOTES_EMPTY_HASH_SET) {
    if (hexHammingDistance(hash, empty) <= NOTES_EMPTY_MAX_DISTANCE) return null;
  }
  const direct = NOTES_DIGIT_HASH_MAP.get(hash);
  if (direct !== undefined) return direct;
  const learned = LEARNED_NOTES_MAP.get(hash);
  if (learned !== undefined) return learned;
  let bestDist = HAMMING_MAX_DISTANCE + 1;
  let bestDigit: number | null = null;
  const probe = (k: string, d: number) => {
    const dist = hexHammingDistance(hash, k);
    if (dist < bestDist) {
      bestDist = dist;
      bestDigit = d;
    }
  };
  for (const [k, d] of NOTES_DIGIT_HASH_MAP) probe(k, d);
  for (const [k, d] of LEARNED_NOTES_MAP) probe(k, d);
  return bestDigit;
}

/**
 * 1 桁分の hash と認識結果を 1 セルずつ持つ詳細記録。auto-learn 用にユーザーへ伝える。
 *
 * `hashes` の各要素はその cell の生 hash 文字列、`predicted` は同じ index の認識結果(null=未知)。
 * 4 桁 ROI (score / miss / notes) を読むたびにこれを返し、確認モーダルで「ユーザーが確定した値」と
 * 突き合わせて未知 hash を学習辞書に追加する。
 */
export interface NumberFieldRecord {
  /** 値（右詰め連結）。digits の中身を素直に組み立てたもの。 */
  value: number | null;
  /** N 桁ぶんの hash 文字列。 */
  hashes: string[];
  /** N 桁ぶんの認識結果（null=未知）。 */
  predicted: (number | null)[];
}

/** 数字 ROI の hash 一覧をついでに返す版。auto-learn を回すための情報を返す。 */
export function recognizeNumberFieldWithRecord(
  canvas: HTMLCanvasElement,
  origin: { x: number; y: number },
  panelRoi: { x: number; y: number; w: number; h: number },
  nDigits = 4,
): NumberFieldRecord {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const empty: NumberFieldRecord = { value: null, hashes: [], predicted: [] };
  if (!ctx) return empty;
  const fhd = panelLocalToFhd(origin, panelRoi);
  const cellWidth = Math.floor(panelRoi.w / nDigits);
  const hashes: string[] = [];
  const predicted: (number | null)[] = [];
  for (let i = 0; i < nDigits; i++) {
    const data = ctx.getImageData(fhd.x + i * cellWidth, fhd.y, cellWidth, fhd.h).data;
    // hashes は auto-learn 用に固定二値化の生 hash を保持（未知セルの学習対象はこちら）。
    // predicted は明るい背景の救済込みで読む（適応二値化フォールバック）。
    hashes.push(computeDigitHash(data, cellWidth, fhd.h));
    predicted.push(lookupDigitCell(data, cellWidth, fhd.h));
  }
  let started = false, val = 0, any = false, broken = false;
  for (const d of predicted) {
    if (d === null) {
      if (started) { broken = true; break; }
      continue;
    }
    started = true; any = true;
    val = val * 10 + d;
  }
  return { value: any && !broken ? val : null, hashes, predicted };
}

/** notes 用の record 版。score 系より cell が狭い分、別の hash 関数を使う。 */
export function recognizeNotesWithRecord(canvas: HTMLCanvasElement): NumberFieldRecord {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const empty: NumberFieldRecord = { value: null, hashes: [], predicted: [] };
  if (!ctx) return empty;
  const hashes: string[] = [];
  const predicted: (number | null)[] = [];
  for (let i = 0; i < 4; i++) {
    const dx = NOTES_AREA_FHD.x + i * NOTES_CELL_STRIDE;
    const data = ctx.getImageData(dx, NOTES_AREA_FHD.y, NOTES_CELL_WIDTH, NOTES_AREA_FHD.h).data;
    const h = computeNotesDigitHash(data, NOTES_CELL_WIDTH, NOTES_AREA_FHD.h);
    hashes.push(h);
    predicted.push(lookupNotesDigit(h));
  }
  let started = false, val = 0, any = false, broken = false;
  for (const d of predicted) {
    if (d === null) {
      if (started) { broken = true; break; }
      continue;
    }
    started = true; any = true;
    val = val * 10 + d;
  }
  return { value: any && !broken ? val : null, hashes, predicted };
}

export function recognizeNotesCount(canvas: HTMLCanvasElement): number | null {
  return recognizeNotesWithRecord(canvas).value;
}

/* ───────────────────────── プレイ中 BPM 読み取り ─────────────────────────
 *
 * リザルトではなく **プレイ画面**の右下/中央下に出る「○○○ BPM」を読む。曲特定の補助情報
 * （難易度 × NOTES × BPM で song_data を一意化する）として useInfinitasMonitor が常時呼ぶ。
 *
 * フォントは score/notes/pgreat いずれとも別系統（高さ約20px・固定ピッチ36px・右詰め3桁）。
 * 専用テーブル [[file:bpm_digit_table.json]] で照合する。digit 間の最小ハミング距離が 2 しかない
 * ため、score/notes のような Hamming フォールバックは **使わず完全一致のみ**。1 桁でも未知なら
 * 「読めなかった (null)」とする。誤った BPM は曲を取り違えさせるので、無理に読むより捨てる。
 *
 * パネル位置はプレイ形態で変わる。既知レイアウト（右下=1P SP, 中央下）を順に試し、3 桁すべてが
 * 完全一致した最初のレイアウトを採用する。未知レイアウトは後から候補を足せる。
 */

/** BPM digit テーブル（プレイ画面専用フォント、cellW=36, cellH=24, th=120）。 */
const BPM_DIGIT_MAP = new Map<string, number>(
  Object.entries(bpmDigitTable as Record<string, number>),
);

/** BPM セル寸法とサンプリンググリッド(8 行 × 6 列 = 48 bit → 12 hex)。 */
const BPM_CELL_W = 36;
const BPM_CELL_H = 24;
const BPM_DIGIT_PITCH = 36;
/** BPM 数字帯の上端 Y（FHD）。プレイ画面の「BPM」ラベル上の数字。 */
const BPM_AREA_Y = 964;
/** 二値化しきい値。数字は純白なので max(R,G,B) >= 120 で十分。 */
const BPM_BRIGHTNESS_THRESHOLD = 120;
const BPM_HASH_Y_INDICES = [1, 4, 7, 10, 13, 16, 19, 22];
const BPM_HASH_X_INDICES = [3, 9, 15, 21, 27, 33];

/**
 * 既知の BPM パネル右端 X 座標（FHD）。右詰め描画なので右端基準で 3 桁を切る。
 * プレイ画面レイアウトで BPM パネルの横位置が変わる（実測: 同一曲 BPM=163 が下記に出る）。
 *
 * 1P（プレイフィールドが左寄り → BPM パネルは右〜中央）:
 *  - 1493: 右下（標準配置）
 *  - 1284: 中央やや右
 *  - 1076: 中央下
 * 2P（プレイフィールドが右寄り → BPM パネルは左へずれる）:
 *  - 950: 中央
 *  - 742: 中央やや左
 *  - 533: 左下
 *
 * 各候補を順に試し、3 桁が完全一致した最初の位置を採用する。完全一致辞書なので他レイアウトの
 * 候補を混ぜても誤検出はほぼ起きない（提供サンプル＋既存リザルト/選曲画面で誤検出ゼロを確認）。
 * 候補をこの配列に足せば自動で試行対象になる。
 */
const BPM_PANEL_RIGHT_CANDIDATES = [1493, 1284, 1076, 950, 742, 533];

function computeBpmDigitHash(rgba: Uint8ClampedArray, cellWidth: number, cellHeight: number): string {
  const bits: number[] = [];
  for (const y of BPM_HASH_Y_INDICES) {
    if (y >= cellHeight) continue;
    for (const x of BPM_HASH_X_INDICES) {
      if (x >= cellWidth) continue;
      const o = (y * cellWidth + x) * 4;
      const brightness = Math.max(rgba[o], rgba[o + 1], rgba[o + 2]);
      bits.push(brightness >= BPM_BRIGHTNESS_THRESHOLD ? 1 : 0);
    }
  }
  let hex = '';
  for (let i = 0; i < bits.length; i += 4) {
    const v = ((bits[i] || 0) << 3) | ((bits[i + 1] || 0) << 2) | ((bits[i + 2] || 0) << 1) | (bits[i + 3] || 0);
    hex += v.toString(16);
  }
  return hex;
}

/** BPM 1 桁を完全一致のみで引く。曖昧マッチはしない（誤読防止）。 */
function lookupBpmDigit(hash: string): number | null {
  const d = BPM_DIGIT_MAP.get(hash);
  return d === undefined ? null : d;
}

/** BPM 数字の縦位置揺らぎ吸収。UI バリエーションで ±1〜2px ずれるため Y を微小スライドして試す。 */
const BPM_Y_OFFSETS = [0, -1, 1, -2, 2];

/**
 * 指定した右端 X・Y オフセットでBPM 3 桁スロットを読み、数値化する。
 * 桁は右端から左へ pos=0,1,2。空セル（パネル背景）は null になる。右詰めなので
 * 「左から null が続き、最初に数字が出たらそれ以降は全桁数字」を満たす並びのみ採用。
 *
 * @param yOff BPM_AREA_Y に加える縦オフセット（フォントの縦揺らぎ補正）。
 * @returns 2〜3 桁の正整数、または null（3 桁読めない/不正な並び）。
 */
function readBpmAtRight(ctx: CanvasRenderingContext2D, rightX: number, yOff: number): number | null {
  const y0 = BPM_AREA_Y + yOff;
  if (y0 < 0) return null;
  const predicted: (number | null)[] = [];
  for (let pos = 0; pos < 3; pos++) {
    const xr = rightX - pos * BPM_DIGIT_PITCH;
    const xl = xr - BPM_CELL_W;
    if (xl < 0) return null;
    const data = ctx.getImageData(xl, y0, BPM_CELL_W, BPM_CELL_H).data;
    const hash = computeBpmDigitHash(data, BPM_CELL_W, BPM_CELL_H);
    predicted.push(lookupBpmDigit(hash));
  }
  // predicted は [最右, 中, 最左]。読み順に直す（最左→最右）
  const ordered = predicted.slice().reverse();
  let started = false, val = 0, any = false;
  for (const d of ordered) {
    if (d === null) {
      if (started) return null; // 桁の途中で欠け = 不正
      continue; // 左の空セル
    }
    started = true; any = true;
    val = val * 10 + d;
  }
  if (!any) return null;
  // BPM の妥当域でガード（IIDX は概ね 30〜1000）。範囲外は誤読とみなす。
  if (val < 30 || val > 999) return null;
  return val;
}

/**
 * プレイ画面から BPM を読み取る。既知パネル位置 × 縦オフセットを順に試し、最初に妥当値が
 * 取れたものを返す。リザルト画面・選曲画面など BPM 表示が無いフレームでは null。
 *
 * @returns BPM 値、または null。
 */
export function recognizeBpm(canvas: HTMLCanvasElement): number | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  for (const rightX of BPM_PANEL_RIGHT_CANDIDATES) {
    for (const yOff of BPM_Y_OFFSETS) {
      const bpm = readBpmAtRight(ctx, rightX, yOff);
      if (bpm != null) return bpm;
    }
  }
  return null;
}

/** 学習辞書の種類。score 系（大型フォント）・notes 系（細字）・pgreat 系（JUDGE 小型フォント）。 */
export type LearnKind = 'score' | 'notes' | 'pgreat';
const LEARNED_PGREAT_KEY = 'beat-seeker:learned-pgreat-hashes';

/**
 * ユーザー確定値から未知 digit hash を学習する。
 *
 * 確認モーダルでユーザーが値を編集 → 「登録」を押した時に呼び出す想定。次に同じ rendering の桁が
 * 出てきたら一発で読めるようになる。
 *
 * 動作:
 *  - record.hashes と確定値の各桁を右詰めでアラインメント
 *  - 組み込み辞書に既知の hash はスキップ（誤上書き防止）
 *  - 既存学習エントリと矛盾する場合もスキップ（誤入力で辞書を壊さない）
 *  - 新規 hash のみ追加し、確定後に localStorage へ永続化
 *
 * @param record 元の認識結果（hashes 配列付き）
 * @param confirmedValue ユーザーが確定した最終値（0 〜 9999）
 * @param kind 'score' (digit_table 互換) か 'notes' (notes_table 互換)
 * @returns 追加された (hash, digit) ペアの数
 */
export function learnFromConfirmation(
  record: NumberFieldRecord,
  confirmedValue: number | null,
  kind: LearnKind,
): number {
  if (confirmedValue == null || confirmedValue < 0 || !record.hashes.length) return 0;
  const builtin = kind === 'score' ? DIGIT_HASH_MAP : kind === 'notes' ? NOTES_DIGIT_HASH_MAP : PGREAT_DIGIT_MAP;
  const learned = kind === 'score' ? LEARNED_DIGIT_MAP : kind === 'notes' ? LEARNED_NOTES_MAP : LEARNED_PGREAT_MAP;
  const storeKey = kind === 'score' ? LEARNED_DIGIT_KEY : kind === 'notes' ? LEARNED_NOTES_KEY : LEARNED_PGREAT_KEY;
  const n = record.hashes.length;
  const digitsStr = String(Math.floor(confirmedValue)).padStart(n, ' ');
  // 桁数が枠より多い（5 桁以上の score 等）は範囲外なので学習しない
  if (digitsStr.length > n) return 0;
  let added = 0;
  for (let i = 0; i < n; i++) {
    const ch = digitsStr[i];
    if (ch === ' ') continue; // 左側パディング = 空セル。学習しない（背景パターンが入るとノイズ）
    const digit = Number(ch);
    if (Number.isNaN(digit)) continue;
    const h = record.hashes[i];
    if (!h) continue;
    if (builtin.has(h)) continue;            // 組み込みに既知なら触らない
    const existing = learned.get(h);
    if (existing !== undefined && existing !== digit) continue; // 矛盾する学習は捨てる
    if (existing === digit) continue;        // 既に学習済み
    learned.set(h, digit);
    added++;
  }
  if (added > 0) {
    saveLearnedStore(storeKey, learned);
  }
  return added;
}

/** デバッグ用: 学習辞書の現在のエントリ数を返す。 */
export function getLearnedStats(): { score: number; notes: number } {
  return { score: LEARNED_DIGIT_MAP.size, notes: LEARNED_NOTES_MAP.size };
}

/**
 * 譜面情報行のラベル（ANOTHER / LEGGENDARIA）を色サンプリングで判定する。
 *
 *  - ANOTHER ラベル: 赤系 (R≈220, G≈110, B≈120) — B が R より大幅に低い
 *  - LEGGENDARIA ラベル: ピンク・マゼンタ系 (R≈230, G≈110, B≈240) — B が R と同程度に高い
 *
 * 判別は青チャネルの強度。
 */
export function recognizeDifficulty(canvas: HTMLCanvasElement): 'ANOTHER' | 'LEGGENDARIA' | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  // ラベル左半分から色を集める（x=740..800, y=1045..1062）
  const data = ctx.getImageData(740, 1045, 60, 17).data;
  let rSum = 0, bSum = 0, count = 0;
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i], g = data[i + 1], b = data[i + 2];
    const m = Math.max(r, g, b);
    if (m < 100) continue;                                  // 背景は除外
    if (Math.min(r, g, b) > m - 30) continue;               // 灰色っぽいのも除外（彩度低）
    rSum += r; bSum += b; count++;
  }
  if (count === 0) return null;
  const avgR = rSum / count;
  const avgB = bSum / count;
  return (avgB > 150 && avgB > avgR - 30) ? 'LEGGENDARIA' : 'ANOTHER';
}

// ───────────────────────── CLEAR TYPE（色帯 + CYAN グリフ幅） ─────────────────────────
/**
 * クリアタイプ判定。OCR を使わず、クリアタイプ・ハイライトの「色」で分類する（inf-notebook 方式）。
 *
 * パネル内ローカル y=363 の 1px 帯（BEST x=216..318 / CURRENT x=378..480）の彩度の高い画素の
 * 中央値 RGB を 3 行ぶん多数決して色クラスへ。各クリアタイプは固有の発光色を持つ:
 *   NO PLAY=灰, ASSIST=紫, EASY=緑, EX-HARD=金, HARD=ピンク赤, FAILED=橙赤。
 * CLEAR と FULLCOMBO は両方シアンで色では区別できないため、文字グリフの「点灯幅」で割る:
 *   "F-COMBO"(7文字) は帯の左端から幅 ~102px いっぱい、"CLEAR"(5文字) は中央寄りで幅 ~82px。
 */
export type ClearTypeColumn = 'best' | 'current';
const CLEAR_BAND_X: Record<ClearTypeColumn, [number, number]> = {
  best: [216, 318],
  current: [378, 480],
};
/** クリアタイプ色クラス → CSV 互換のクリアタイプ表記。CYAN は別途グリフ幅で分岐。 */
const COLOR_TO_CLEAR: Record<string, string> = {
  'NO PLAY': 'NO PLAY',
  'A-CLEAR': 'ASSIST CLEAR',
  'E-CLEAR': 'EASY CLEAR',
  'EXH-CLEAR': 'EX HARD CLEAR',
  'H-CLEAR': 'HARD CLEAR',
  'FAILED': 'FAILED',
};
/** CYAN 帯のうち、点灯幅がこの値以上なら F-COMBO（7文字でバンドを埋める）、未満なら CLEAR。 */
const FCOMBO_MIN_LIT_WIDTH = 92;

/** 中央値 RGB を色クラスへ分類する。 */
function classifyClearColor(r: number, g: number, b: number): string {
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
  if (mx - mn < 35) return 'NO PLAY';
  if (b >= r && b >= g) {
    if (r > g + 10 && r > 70) return 'A-CLEAR';
    return 'CYAN';
  }
  if (g >= r && g >= b) return 'E-CLEAR';
  if (g > 150 && b < g) return 'EXH-CLEAR';
  if (g - b > 8) return 'FAILED';
  return 'H-CLEAR';
}

/** 1 行ぶんの彩度の高い画素の中央値 RGB を取って色クラスを返す。画素が少なければ NO PLAY 扱い。 */
function clearRowColorClass(ctx: CanvasRenderingContext2D, x: number, y: number, w: number): string {
  const data = ctx.getImageData(x, y, w, 1).data;
  const rs: number[] = [], gs: number[] = [], bs: number[] = [];
  for (let i = 0; i < data.length; i += 4) {
    const r = data[i], g = data[i + 1], b = data[i + 2];
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
    if (mx >= 110 && mx - mn >= 30) { rs.push(r); gs.push(g); bs.push(b); }
  }
  if (rs.length < 8) return 'NO PLAY';
  const med = (a: number[]) => { const s = [...a].sort((p, q) => p - q); return s[s.length >> 1]; };
  return classifyClearColor(med(rs), med(gs), med(bs));
}

/** CYAN 帯のクリアタイプ文字の点灯幅（左端〜右端の連続範囲）を返す。 */
function cyanLitWidth(ctx: CanvasRenderingContext2D, x: number, y: number, w: number): number {
  const data = ctx.getImageData(x, y, w, 14).data;  // y..y+13
  let left = -1, right = -1;
  for (let col = 0; col < w; col++) {
    let lit = false;
    for (let row = 0; row < 14; row++) {
      const o = (row * w + col) * 4;
      const r = data[o], g = data[o + 1], b = data[o + 2];
      const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
      if (mx >= 150 && mx - mn >= 25) { lit = true; break; }
    }
    if (lit) { if (left < 0) left = col; right = col; }
  }
  return left < 0 ? 0 : right - left + 1;
}

/**
 * クリアタイプを認識する。
 * @param origin パネル原点（resolvePanelOrigin で得たもの。y は OLD/NEW で変わる）。
 * @param column 'best' か 'current'。
 */
export function recognizeClearType(
  canvas: HTMLCanvasElement,
  origin: { x: number; y: number },
  column: ClearTypeColumn,
): string | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  const [x0, x1] = CLEAR_BAND_X[column];
  const x = origin.x + x0;
  const w = x1 - x0;
  // y=363 周辺 3 行を多数決
  const counts: Record<string, number> = {};
  for (const dy of [362, 363, 364]) {
    const c = clearRowColorClass(ctx, x, origin.y + dy, w);
    counts[c] = (counts[c] || 0) + 1;
  }
  let cls = 'NO PLAY', best = -1;
  for (const k in counts) if (counts[k] > best) { best = counts[k]; cls = k; }
  if (cls === 'CYAN') {
    const litW = cyanLitWidth(ctx, x, origin.y + 358, w);
    return litW >= FCOMBO_MIN_LIT_WIDTH ? 'FULLCOMBO CLEAR' : 'CLEAR';
  }
  return COLOR_TO_CLEAR[cls] ?? null;
}

// ───────────────────────── PGREAT（JUDGE 先頭 GREAT 行） ─────────────────────────
/**
 * PGREAT 認識。JUDGE 内訳の先頭 GREAT 行（= PERFECT GREAT）の数値を読む。
 * great は別途 `EX_SCORE - 2*PGREAT` で逆算するため、ここで読むのは PGREAT のみ。
 *
 * フォントは SCORE 列と別系統（小型）。専用 hash テーブル（[[file:pgreat_digit_table.json]]）を使用。
 * 行位置が OLD/NEW UI や 2P で ±数 px 動くため、白画素の帯を検出して auto-align する。
 */
const PGREAT_HASH_Y_INDICES = [1, 3, 5, 7, 9, 11, 13, 15];
const PGREAT_HASH_X_INDICES = [3, 6, 9, 12, 15, 18, 21, 24];
const PGREAT_CELL_W = 28;
const PGREAT_CELL_H = 20;
const PGREAT_HAMMING_MAX = 2;
const PGREAT_DIGIT_MAP = new Map<string, number>(
  Object.entries(pgreatDigitTable as Record<string, number>),
);
/** 学習で追加された PGREAT hash（auto-learn 用）。 */
const LEARNED_PGREAT_MAP = loadLearnedStore(LEARNED_PGREAT_KEY);

function computePgreatHash(rgba: Uint8ClampedArray, cellW: number, cellH: number): string {
  const bits: number[] = [];
  for (const y of PGREAT_HASH_Y_INDICES) {
    if (y >= cellH) continue;
    for (const x of PGREAT_HASH_X_INDICES) {
      if (x >= cellW) continue;
      const o = (y * cellW + x) * 4;
      const brightness = Math.max(rgba[o], rgba[o + 1], rgba[o + 2]);
      bits.push(brightness >= BRIGHTNESS_THRESHOLD ? 1 : 0);
    }
  }
  let hex = '';
  for (let i = 0; i < bits.length; i += 4) {
    hex += (((bits[i] || 0) << 3) | ((bits[i + 1] || 0) << 2) | ((bits[i + 2] || 0) << 1) | (bits[i + 3] || 0)).toString(16);
  }
  return hex;
}

const PGREAT_EMPTY_HASH = '0000000000000000';

function lookupPgreatDigit(hash: string): number | null {
  if (hash === PGREAT_EMPTY_HASH) return null;
  const direct = PGREAT_DIGIT_MAP.get(hash) ?? LEARNED_PGREAT_MAP.get(hash);
  if (direct !== undefined) return direct;
  let bestDist = PGREAT_HAMMING_MAX + 1, bestDigit: number | null = null;
  const probe = (k: string, d: number) => { const dist = hexHammingDistance(hash, k); if (dist < bestDist) { bestDist = dist; bestDigit = d; } };
  for (const [k, d] of PGREAT_DIGIT_MAP) probe(k, d);
  for (const [k, d] of LEARNED_PGREAT_MAP) probe(k, d);
  return bestDigit;
}

/**
 * PGREAT 行を auto-align する。探索窓内で「白画素 ≥ 4 の行が連続する最も背の高い帯」を数字行とみなし、
 * その上端と右端を返す。OLD/NEW の縦ずれと 2P の横ずれを吸収する。
 */
function autoAlignPgreat(ctx: CanvasRenderingContext2D, originX: number): { top: number; right: number } | null {
  const sx0 = originX + 380, sx1 = originX + 505;
  const w = sx1 - sx0;
  // y=778..819 をスキャン
  const yTop = 778, yBot = 820;
  const region = ctx.getImageData(sx0, yTop, w, yBot - yTop).data;
  const rowWhite: number[] = [];
  for (let r = 0; r < yBot - yTop; r++) {
    let cnt = 0;
    for (let c = 0; c < w; c++) {
      const o = (r * w + c) * 4;
      if (Math.max(region[o], region[o + 1], region[o + 2]) >= BRIGHTNESS_THRESHOLD) cnt++;
    }
    rowWhite.push(cnt);
  }
  let best: [number, number] | null = null;
  let y = 0;
  while (y < rowWhite.length) {
    if (rowWhite[y] >= 4) {
      let y2 = y;
      while (y2 < rowWhite.length && rowWhite[y2] >= 4) y2++;
      if (!best || (y2 - y) > (best[1] - best[0])) best = [y, y2];
      y = y2;
    } else y++;
  }
  if (!best) return null;
  // 帯内の各列の白画素数 → 右端
  const colWhite = new Array(w).fill(0);
  for (let r = best[0]; r < best[1]; r++) {
    for (let c = 0; c < w; c++) {
      const o = (r * w + c) * 4;
      if (Math.max(region[o], region[o + 1], region[o + 2]) >= BRIGHTNESS_THRESHOLD) colWhite[c]++;
    }
  }
  let rightCol = -1;
  for (let c = w - 1; c >= 0; c--) if (colWhite[c] >= 4) { rightCol = c; break; }
  if (rightCol < 0) return null;
  return { top: yTop + best[0] - 2, right: sx0 + rightCol + 1 };
}

/**
 * PGREAT 数値を認識する。読めなければ null。
 * @param originX パネル原点 x（1P=0, 2P=1351）。
 */
export function recognizePgreat(canvas: HTMLCanvasElement, originX: number): number | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  const al = autoAlignPgreat(ctx, originX);
  if (!al) return null;
  const digits: (number | null)[] = [];
  for (let i = 0; i < 4; i++) {
    const cr = al.right - (3 - i) * PGREAT_CELL_W;
    const cl = cr - PGREAT_CELL_W;
    if (cl < 0) { digits.push(null); continue; }
    const data = ctx.getImageData(cl, al.top, PGREAT_CELL_W, PGREAT_CELL_H).data;
    digits.push(lookupPgreatDigit(computePgreatHash(data, PGREAT_CELL_W, PGREAT_CELL_H)));
  }
  let started = false, val = 0, any = false;
  for (const d of digits) {
    if (d === null) { if (started) return null; continue; }
    started = true; any = true; val = val * 10 + d;
  }
  return any ? val : null;
}

/** PGREAT を hash 記録付きで認識する（auto-learn 用）。 */
export function recognizePgreatWithRecord(canvas: HTMLCanvasElement, originX: number): NumberFieldRecord {
  const empty: NumberFieldRecord = { value: null, hashes: [], predicted: [] };
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return empty;
  const al = autoAlignPgreat(ctx, originX);
  if (!al) return empty;
  const hashes: string[] = [];
  const predicted: (number | null)[] = [];
  for (let i = 0; i < 4; i++) {
    const cr = al.right - (3 - i) * PGREAT_CELL_W;
    const cl = cr - PGREAT_CELL_W;
    if (cl < 0) { hashes.push(''); predicted.push(null); continue; }
    const data = ctx.getImageData(cl, al.top, PGREAT_CELL_W, PGREAT_CELL_H).data;
    const h = computePgreatHash(data, PGREAT_CELL_W, PGREAT_CELL_H);
    hashes.push(h);
    predicted.push(lookupPgreatDigit(h));
  }
  let started = false, val = 0, any = false, broken = false;
  for (const d of predicted) {
    if (d === null) { if (started) { broken = true; break; } continue; }
    started = true; any = true; val = val * 10 + d;
  }
  return { value: any && !broken ? val : null, hashes, predicted };
}

/** JUDGE の行ピッチ(px)。GREAT/GREAT/GOOD/BAD/POOR は等間隔 28px で並ぶ（実測）。 */
const JUDGE_ROW_PITCH = 28;

/** (top, right) で 4 桁右詰め行を PGREAT フォントで読み、{value, directHits} を返す。 */
function readPgreatRowAt(ctx: CanvasRenderingContext2D, top: number, right: number): { value: number | null; directHits: number } {
  const digits: (number | null)[] = [];
  let directHits = 0;
  for (let i = 0; i < 4; i++) {
    const cr = right - (3 - i) * PGREAT_CELL_W;
    const cl = cr - PGREAT_CELL_W;
    if (cl < 0) { digits.push(null); continue; }
    const data = ctx.getImageData(cl, top, PGREAT_CELL_W, PGREAT_CELL_H).data;
    const h = computePgreatHash(data, PGREAT_CELL_W, PGREAT_CELL_H);
    if (PGREAT_DIGIT_MAP.has(h) || LEARNED_PGREAT_MAP.has(h)) directHits++;
    digits.push(lookupPgreatDigit(h));
  }
  let started = false, val = 0, any = false;
  for (const d of digits) {
    if (d === null) { if (started) return { value: null, directHits }; continue; }
    started = true; any = true; val = val * 10 + d;
  }
  return { value: any ? val : null, directHits };
}

/**
 * 1 行を ±3px の縦ずらしで micro-align して読む。直接ヒット数が最大の y を採用する。
 * OLD/NEW UI や行ごとの数 px ドリフトを吸収する。
 */
function readJudgeRowAligned(ctx: CanvasRenderingContext2D, expectedTop: number, right: number): number | null {
  let best: { value: number | null; directHits: number; dy: number } | null = null;
  for (let dy = -3; dy <= 3; dy++) {
    const r = readPgreatRowAt(ctx, expectedTop + dy, right);
    if (!best || r.directHits > best.directHits) best = { ...r, dy };
  }
  return best ? best.value : null;
}

/** JUDGE 内訳（PGREAT / GREAT / GOOD / BAD / POOR）。読めない行は null。 */
export interface JudgeResult {
  pgreat: number | null;
  great: number | null;
  good: number | null;
  bad: number | null;
  poor: number | null;
}

/**
 * JUDGE 内訳の 5 行すべてを認識する。
 *
 * 先頭行(PGREAT)を auto-align で合わせ、以降は固定ピッチ 28px で下に 4 行。
 * すべて同じ小型フォント(PGREAT と同じ hash テーブル)で右詰め読み取り。
 */
export function recognizeJudge(canvas: HTMLCanvasElement, originX: number): JudgeResult {
  const empty: JudgeResult = { pgreat: null, great: null, good: null, bad: null, poor: null };
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return empty;
  const al = autoAlignPgreat(ctx, originX);
  if (!al) return empty;
  const rows = [0, 1, 2, 3, 4].map(k => readJudgeRowAligned(ctx, al.top + k * JUDGE_ROW_PITCH, al.right));
  return { pgreat: rows[0], great: rows[1], good: rows[2], bad: rows[3], poor: rows[4] };
}

// ───────────────────────── SP / DP（中央ロゴのテンプレ照合） ─────────────────────────
/**
 * SP / DP 判定。画面中央の "SP"/"DP" ロゴ（FHD x=948,y=1041,37x24, 1P/2P 共通位置）を
 * 二値化してテンプレ照合し、argmax で判定する。OCR 不要。
 */
type SpdpTemplates = {
  roi: { x: number; y: number; w: number; h: number };
  maxShift: number;
  SP: { template_hex: string; mask_hex: string };
  DP: { template_hex: string; mask_hex: string };
};
const SPDP = spdpTemplates as SpdpTemplates;
const SPDP_SP_T = unpackTemplate(SPDP.SP.template_hex, SPDP.roi.w, SPDP.roi.h);
const SPDP_SP_M = unpackTemplate(SPDP.SP.mask_hex, SPDP.roi.w, SPDP.roi.h);
const SPDP_DP_T = unpackTemplate(SPDP.DP.template_hex, SPDP.roi.w, SPDP.roi.h);
const SPDP_DP_M = unpackTemplate(SPDP.DP.mask_hex, SPDP.roi.w, SPDP.roi.h);

/** ROI を「自身の min/max 中点」で二値化（明背景/暗背景どちらでも安定）。 */
function binarizeSpdpRoi(ctx: CanvasRenderingContext2D): Uint8Array {
  const { x, y, w, h } = SPDP.roi;
  const data = ctx.getImageData(x, y, w, h).data;
  let mn = 255, mx = 0;
  const br = new Uint8Array(w * h);
  for (let i = 0, p = 0; i < data.length; i += 4, p++) {
    const m = Math.max(data[i], data[i + 1], data[i + 2]);
    br[p] = m;
    if (m < mn) mn = m;
    if (m > mx) mx = m;
  }
  const thr = (mn + mx) >> 1;
  const out = new Uint8Array(w * h);
  for (let p = 0; p < br.length; p++) out[p] = br[p] >= thr ? 1 : 0;
  return out;
}

/** ±maxShift のずらし照合で最大一致率を返す。 */
function spdpMatch(bin: Uint8Array, tmpl: Uint8Array, mask: Uint8Array): number {
  const { w, h, } = SPDP.roi;
  const ms = SPDP.maxShift;
  let best = 0;
  for (let dy = -ms; dy <= ms; dy++) {
    for (let dx = -ms; dx <= ms; dx++) {
      let ok = 0, tot = 0;
      for (let y = 0; y < h; y++) {
        const sy = y + dy;
        for (let x = 0; x < w; x++) {
          if (mask[y * w + x] === 0) continue;
          tot++;
          const sx = x + dx;
          if (sy >= 0 && sy < h && sx >= 0 && sx < w && bin[sy * w + sx] === tmpl[y * w + x]) ok++;
        }
      }
      if (tot && ok / tot > best) best = ok / tot;
    }
  }
  return best;
}

/** SP / DP を判定する。判定不能時は null。 */
export function recognizePlayMode(canvas: HTMLCanvasElement): 'SP' | 'DP' | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  const bin = binarizeSpdpRoi(ctx);
  const sp = spdpMatch(bin, SPDP_SP_T, SPDP_SP_M);
  const dp = spdpMatch(bin, SPDP_DP_T, SPDP_DP_M);
  // 両方とも低すぎる場合は不明（リザルト外フレーム等）
  if (Math.max(sp, dp) < 0.6) return null;
  return sp >= dp ? 'SP' : 'DP';
}

/** 結果オブジェクト。 */
export interface RecognizedResult {
  scoreBest: number | null;
  scoreCurrent: number | null;
  missCountBest: number | null;
  missCountCurrent: number | null;
  notesCount: number | null;
  difficulty: 'ANOTHER' | 'LEGGENDARIA' | null;
  /** JUDGE 内訳（今回プレイ）。PGREAT/GREAT/GOOD/BAD/POOR。 */
  pgreat: number | null;
  great: number | null;
  good: number | null;
  bad: number | null;
  poor: number | null;
  /** SP / DP プレイモード。 */
  playMode: 'SP' | 'DP' | null;
  /** クリアタイプ（CSV 互換表記）。 */
  clearTypeBest: string | null;
  clearTypeCurrent: string | null;
}

/**
 * 「リザルト画面か否か」を、画面端の固定 UI ラベル「PLAYER 01」/「PLAYER 02」のビットマップ
 * テンプレ照合で判定する。
 *
 * 方式は inf-notebook の `get_playside()` 相当（[recog.py:401-408](https://github.com/kaktuswald/inf-notebook/blob/master/recog.py#L401)）:
 *  - 事前に複数サンプルから「全部で一致するビット」を抽出してテンプレ + マスクを構築
 *  - 推論時はその領域を 200 しきい値で二値化し、マスク=1 のビットのみ `template と完全一致`
 *  - 1P は実画像で y 方向 ±10px のずれが観測されたため、±12px のスライド許容で照合
 *  - 2P はサンプル間で位置がほぼ安定（96.5% bit 一致）なので固定位置で照合
 *
 * 検出は digit hash の成否と完全に独立。スコアの値が読めなくてもラベルがあれば検出され、
 * 結果としてユーザーが確認モーダルで値を確定 → auto-learn が回る流れが成立する。
 */
/**
 * PLAYER ラベル一致率のしきい値。
 *
 * 当初 0.99 にしていたが、EXTRA STAGE RESULT のように背景が鮮やか（明るい）な演出だと
 * 二値化（max(R,G,B)≥200）に背景ノイズが乗り、ローカル画像でも 0.9923、画面共有経由の
 * 映像劣化込みでは 0.99 を割って検出漏れする事例が出た。
 *
 * 実測のマージン:
 *  - リザルト画面の「正しい側」一致率: 最低 0.965（2P リザルト・EXTRA STAGE 含む）
 *  - プレイ中など非リザルト画面: 最高 0.716
 * 間に大きなギャップがあるため 0.93 に下げる。リザルトは確実に拾い、非リザルトは
 * まだ 0.21 のマージンで弾ける。1P/2P 両方がしきい値を超える場合は高い方を採る
 * （2P リザルトは 1P テンプレにも 0.965 当たるが 2P=1.000 なので正しく 2P 判定される）。
 */
const PLAYER_LABEL_MATCH_THRESHOLD = 0.93;

type PackedTemplate = {
  x: number; y: number; w: number; h: number;
  max_y_shift: number;
  template_hex: string;
  mask_hex: string;
};

/** hex 文字列を 2D ビット配列 (Uint8Array, length=w*h) に展開する。 */
function unpackTemplate(hexStr: string, w: number, h: number): Uint8Array {
  const bits = new Uint8Array(w * h);
  for (let i = 0, bitIdx = 0; i < hexStr.length && bitIdx < bits.length; i += 2) {
    const v = parseInt(hexStr.slice(i, i + 2), 16);
    for (let j = 7; j >= 0 && bitIdx < bits.length; j--, bitIdx++) {
      bits[bitIdx] = (v >> j) & 1;
    }
  }
  return bits;
}

const TEMPLATES: Record<RecognizerPlaySide, PackedTemplate> =
  playerLabelTemplates as Record<RecognizerPlaySide, PackedTemplate>;

const UNPACKED_TEMPLATE: Record<RecognizerPlaySide, Uint8Array> = {
  '1P': unpackTemplate(TEMPLATES['1P'].template_hex, TEMPLATES['1P'].w, TEMPLATES['1P'].h),
  '2P': unpackTemplate(TEMPLATES['2P'].template_hex, TEMPLATES['2P'].w, TEMPLATES['2P'].h),
};
const UNPACKED_MASK: Record<RecognizerPlaySide, Uint8Array> = {
  '1P': unpackTemplate(TEMPLATES['1P'].mask_hex, TEMPLATES['1P'].w, TEMPLATES['1P'].h),
  '2P': unpackTemplate(TEMPLATES['2P'].mask_hex, TEMPLATES['2P'].w, TEMPLATES['2P'].h),
};

/** 二値化しきい値 (max(R,G,B) >= 200 を「明」とする)。 */
const PLAYER_LABEL_BIN_THRESHOLD = 200;

/**
 * Canvas の指定領域を取得して、各ピクセルを max(R,G,B) >= 閾値 で二値化した Uint8Array を返す。
 */
function getBinarizedRegion(
  canvas: HTMLCanvasElement, x: number, y: number, w: number, h: number,
): Uint8Array | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  const data = ctx.getImageData(x, y, w, h).data;
  const out = new Uint8Array(w * h);
  for (let i = 0, p = 0; i < data.length; i += 4, p++) {
    const m = Math.max(data[i], data[i + 1], data[i + 2]);
    out[p] = m >= PLAYER_LABEL_BIN_THRESHOLD ? 1 : 0;
  }
  return out;
}

/**
 * 二値化済みのソースのうち、`(template, mask)` と一致するビットの割合を返す。
 *
 * `mask[i] === 0` のビットは don't care（自動一致扱い）。
 * `mask[i] === 1` のビットは `src[i] === template[i]` を要求。
 *
 * 戻り値: 0..1 の比率。1.0 = 全マスクビットが一致。
 */
function matchScore(src: Uint8Array, template: Uint8Array, mask: Uint8Array): number {
  let ok = 0;
  for (let i = 0; i < src.length; i++) {
    if (mask[i] === 0 || src[i] === template[i]) ok++;
  }
  return ok / src.length;
}

/**
 * 1P 用: y 方向に ±max_y_shift px スライドさせて最大の一致率を返す。
 * INFINITAS の 1P 側 UI は実画像で ±10px 程度の縦ブレが観測されるため。
 */
function matchScore1P(canvas: HTMLCanvasElement): number {
  const t = TEMPLATES['1P'];
  // テンプレ周辺の縦に広い領域を一気に取って、内部で y オフセットを変えて照合する。
  const yStart = t.y - t.max_y_shift;
  const window = getBinarizedRegion(canvas, t.x, yStart, t.w, t.h + 2 * t.max_y_shift);
  if (!window) return 0;
  let best = 0;
  const tmpl = UNPACKED_TEMPLATE['1P'];
  const mask = UNPACKED_MASK['1P'];
  const cellSize = t.w * t.h;
  for (let off = 0; off <= 2 * t.max_y_shift; off++) {
    const start = off * t.w;
    const slice = window.subarray(start, start + cellSize);
    if (slice.length < cellSize) continue;
    const s = matchScore(slice, tmpl, mask);
    if (s > best) best = s;
  }
  return best;
}

/** 2P 用: 固定位置照合。 */
function matchScore2P(canvas: HTMLCanvasElement): number {
  const t = TEMPLATES['2P'];
  const bin = getBinarizedRegion(canvas, t.x, t.y, t.w, t.h);
  if (!bin) return 0;
  return matchScore(bin, UNPACKED_TEMPLATE['2P'], UNPACKED_MASK['2P']);
}

export function detectResultScreen(canvas: HTMLCanvasElement): RecognizerPlaySide | null {
  const s1 = matchScore1P(canvas);
  const s2 = matchScore2P(canvas);
  const pass1 = s1 >= PLAYER_LABEL_MATCH_THRESHOLD;
  const pass2 = s2 >= PLAYER_LABEL_MATCH_THRESHOLD;
  // 両方しきい値超なら高いほうを採用（理論上はほぼ起こらない）。
  if (pass1 && pass2) return s1 >= s2 ? '1P' : '2P';
  if (pass1) return '1P';
  if (pass2) return '2P';
  return null;
}

/**
 * パネル全体から score / miss_count / notes / 難易度を抽出する高レベル API。
 *
 * @param canvas FHD ソース画像が描画されている canvas。
 * @param playSide '1P' か '2P'。
 */
export function recognizeResultPanel(
  canvas: HTMLCanvasElement,
  playSide: RecognizerPlaySide,
): RecognizedResult {
  // Y0 を digit_table 直接ヒット数で推定して切替
  const origin = { x: PANEL_ORIGINS[playSide].x, y: detectPanelY0(canvas, playSide) };
  const judge = recognizeJudge(canvas, origin.x);
  return {
    scoreBest:        recognizeNumberField(canvas, origin, PANEL_LOCAL_ROI.scoreBest),
    scoreCurrent:     recognizeNumberField(canvas, origin, PANEL_LOCAL_ROI.scoreCurrent),
    missCountBest:    recognizeNumberField(canvas, origin, PANEL_LOCAL_ROI.missCountBest),
    missCountCurrent: recognizeNumberField(canvas, origin, PANEL_LOCAL_ROI.missCountCurrent),
    notesCount:       recognizeNotesCount(canvas),
    difficulty:       recognizeDifficulty(canvas),
    pgreat:           judge.pgreat,
    great:            judge.great,
    good:             judge.good,
    bad:              judge.bad,
    poor:             judge.poor,
    playMode:         recognizePlayMode(canvas),
    clearTypeBest:    recognizeClearType(canvas, origin, 'best'),
    clearTypeCurrent: recognizeClearType(canvas, origin, 'current'),
  };
}

/** record 版でも同じく Y0 自動切替を効かせる用のヘルパー。useInfinitasMonitor から使う。 */
export function resolvePanelOrigin(canvas: HTMLCanvasElement, playSide: RecognizerPlaySide): { x: number; y: number } {
  return { x: PANEL_ORIGINS[playSide].x, y: detectPanelY0(canvas, playSide) };
}

/**
 * EX SCORE と NOTES 数から DJ LEVEL を算出する。
 *
 * IIDX の DJ LEVEL は (score / max_score) の比率で決まる。max_score は notes × 2。
 *  - AAA: ≥ 8/9
 *  - AA : ≥ 7/9
 *  - A  : ≥ 6/9 (= 2/3)
 *  - B  : ≥ 5/9
 *  - C  : ≥ 4/9
 *  - D  : ≥ 3/9 (= 1/3)
 *  - E  : ≥ 2/9
 *  - F  : それ未満
 *
 * @param score EX SCORE 数値（0 以上）。
 * @param notes 譜面のノーツ数。max_score = notes * 2。
 * @returns 'F' / 'E' / 'D' / 'C' / 'B' / 'A' / 'AA' / 'AAA'、または計算不能なら null。
 */
export function computeDjLevel(score: number | null, notes: number | null): string | null {
  if (score == null || notes == null || notes <= 0) return null;
  const max = notes * 2;
  if (score < 0 || score > max) return null;
  const ratio = score / max;
  if (ratio >= 8 / 9) return 'AAA';
  if (ratio >= 7 / 9) return 'AA';
  if (ratio >= 6 / 9) return 'A';
  if (ratio >= 5 / 9) return 'B';
  if (ratio >= 4 / 9) return 'C';
  if (ratio >= 3 / 9) return 'D';
  if (ratio >= 2 / 9) return 'E';
  return 'F';
}

/**
 * 曲名 ROI の base64 dataURL を切り出して返す。
 *
 * 将来的に inf-notebook 流の trie テンプレートを構築する用のスナップショット。
 * 現状の使い道としては、確認モーダルにそのまま映してユーザーが視認で曲名を確かめる用途も兼ねる。
 *
 * 曲名は画面下部中央、譜面情報行のすぐ上にある。playSide 不問で同一座標。
 * 実測で曲名テキストは y≈965..990。少し余白を持たせ y=958..996 を切り出す。
 * （旧 y=945 は上にずれてタイトル上端しか映っていなかった。）
 */
export const SONG_TITLE_FHD = { x: 560, y: 958, w: 800, h: 38 };

export function captureSongTitleSnapshot(canvas: HTMLCanvasElement): string | null {
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  if (!ctx) return null;
  const data = ctx.getImageData(SONG_TITLE_FHD.x, SONG_TITLE_FHD.y, SONG_TITLE_FHD.w, SONG_TITLE_FHD.h);
  const tmp = document.createElement('canvas');
  tmp.width = SONG_TITLE_FHD.w;
  tmp.height = SONG_TITLE_FHD.h;
  const tmpCtx = tmp.getContext('2d');
  if (!tmpCtx) return null;
  tmpCtx.putImageData(data, 0, 0);
  return tmp.toDataURL('image/png');
}
