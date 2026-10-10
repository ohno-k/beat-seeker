/**
 * 【ファイルの役割】 IIDX アーケードのプレー画面（キャプチャボードの映像）を読み取る純粋関数群。
 *
 * 配信オーバーレイ（ObsScoreOverlayView）から使う。DOM や外部ライブラリに依存しないので、
 * Node から直接 import してサンプル画像で検証できる。
 *
 * 座標はすべて 1920×1080 の実測値（ZINRAI、1080p 録画のフレームから測定）。
 * 解像度が違う映像は {@link scaleOf} で比例換算する。
 *
 * 読み取る項目:
 *  - グラフ配置（1P/2P × 遠め/近め の 4 種）… スコアグラフの AAA/AA/A 目盛り線の位置で判別
 *  - YOU の EX スコア … 4 桁固定の 7 セグメント風フォントを、7 つの画の有無で読む
 *  - 難易度 … 曲名パネルの PLAYER 01/02 札の色
 *  - 曲名・アーティストの領域 … OCR は呼び出し側（tesseract.js）が行う
 *
 * 2P の配置は「1P を左右反転した位置」という前提で、実画面ではまだ検証していない。
 */

/** getImageData の戻り値と同じ形（Node の pngjs でも作れるよう最小限にしている）。 */
export interface RgbaFrame {
  width: number;
  height: number;
  data: Uint8ClampedArray | Uint8Array;
}

export type PlaySide = '1P' | '2P';
export type GraphPlacement = 'far' | 'near';
export type DifficultyName = 'BEGINNER' | 'NORMAL' | 'HYPER' | 'ANOTHER' | 'LEGGENDARIA';

/** 1 つのグラフ配置の幾何情報（1920×1080 基準）。 */
export interface PlayLayout {
  id: string;
  side: PlaySide;
  placement: GraphPlacement;
  /** スコアグラフの目盛り線の左端・右端 x。 */
  graphX0: number;
  graphX1: number;
  /** 目盛り線の y（AAA = 8/9、AA = 7/9、A = 6/9）。 */
  aaaY: number;
  aaY: number;
  aY: number;
  /** 0 点の y と理論値の y（グラフは一次関数）。 */
  zeroY: number;
  fullY: number;
  /** 曲名パネル（EXTRA STAGE / 曲名 / アーティスト / 難易度札）の左端 x。 */
  titlePanelX0: number;
}

const BASE_W = 1920;
const BASE_H = 1080;
/** 曲名パネルの幅。2P の位置を左右反転で求めるのに使う。 */
const TITLE_PANEL_W = 963;

const LAYOUTS_1P: PlayLayout[] = [
  {
    id: '1P-far', side: '1P', placement: 'far',
    graphX0: 1507, graphX1: 1914,
    aaaY: 402.5, aaY: 462.5, aY: 521.5,
    zeroY: 878.7, fullY: 343.2,
    titlePanelX0: 545,
  },
  {
    id: '1P-near', side: '1P', placement: 'near',
    graphX0: 544, graphX1: 951,
    aaaY: 256.5, aaY: 333.5, aY: 409.5,
    zeroY: 868.7, fullY: 180.2,
    titlePanelX0: 957,
  },
];

function mirrorLayout(l: PlayLayout): PlayLayout {
  return {
    ...l,
    id: l.id.replace('1P', '2P'),
    side: '2P',
    graphX0: BASE_W - 1 - l.graphX1,
    graphX1: BASE_W - 1 - l.graphX0,
    titlePanelX0: BASE_W - (l.titlePanelX0 + TITLE_PANEL_W),
  };
}

export const PLAY_LAYOUTS: readonly PlayLayout[] = [...LAYOUTS_1P, ...LAYOUTS_1P.map(mirrorLayout)];

/** 1920×1080 基準の座標を実フレームの座標へ換算する倍率。 */
export function scaleOf(frame: RgbaFrame): { sx: number; sy: number } {
  return { sx: frame.width / BASE_W, sy: frame.height / BASE_H };
}

function px(frame: RgbaFrame, x: number, y: number): [number, number, number] {
  const xi = Math.min(frame.width - 1, Math.max(0, Math.round(x)));
  const yi = Math.min(frame.height - 1, Math.max(0, Math.round(y)));
  const i = (yi * frame.width + xi) * 4;
  return [frame.data[i], frame.data[i + 1], frame.data[i + 2]];
}

function luma(r: number, g: number, b: number): number {
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/**
 * 目盛り線の色。スコアが届く前は青みの明るい線、届いたあとは黄色の線になる
 * （実測: 黄色 = (226,234,61)）。
 */
function isGradeLineColor(r: number, g: number, b: number): boolean {
  const blue = b >= 150 && b > r + 10 && b >= g;
  const yellow = r >= 170 && g >= 170 && b <= 130 && Math.abs(r - g) < 50;
  return blue || yellow;
}

/** 線の上下この距離の画素が線の色なら「細い線」ではない（一面の青い BGA などを除く）。 */
const LINE_THIN_CHECK_PX = 6;

/**
 * 【関数の役割】 基準座標 y 付近（±2px）に目盛り線があるか、x0..x1 のうち線が見える列の割合を返す。
 *
 * 列ごとに「±2px のどこかが線の色」かつ「上下 6px は線の色ではない」ものを数える。
 * 後者の条件で、BGA が一面の青になる場面を線と取り違えない。
 * スコアの棒は線を隠すので、割合は棒の本数と高さに応じて下がる（棒 2 本で最大 5 割ほど隠れる）。
 */
function lineRatio(frame: RgbaFrame, x0: number, x1: number, baseY: number): number {
  const { sx, sy } = scaleOf(frame);
  const isLineAt = (x: number, y: number) => {
    const [r, g, b] = px(frame, x * sx, y * sy);
    return isGradeLineColor(r, g, b);
  };
  let hit = 0;
  let n = 0;
  for (let x = x0 + 6; x <= x1 - 6; x += 3) {
    n++;
    let onLine = false;
    for (let dy = -2; dy <= 2 && !onLine; dy++) onLine = isLineAt(x, baseY + dy);
    if (!onLine) continue;
    if (isLineAt(x, baseY - LINE_THIN_CHECK_PX) || isLineAt(x, baseY + LINE_THIN_CHECK_PX)) continue;
    hit++;
  }
  return n ? hit / n : 0;
}

/** 3 本の線それぞれで、線が見える列の割合がこれ以上ならプレー画面とみなす。 */
const MIN_LINE_RATIO = 0.25;

/** 【診断用】 配置ごとの AAA/AA/A の検出率。テストモードの表示と調査に使う。 */
export function gradeLineRatios(frame: RgbaFrame, layout: PlayLayout): number[] {
  return [layout.aaaY, layout.aaY, layout.aY].map(y => lineRatio(frame, layout.graphX0, layout.graphX1, y));
}

/**
 * 【関数の役割】 フレームがプレー画面かどうかと、そのグラフ配置を判定する。
 *
 * 4 種の配置それぞれで AAA/AA/A の 3 本が揃っているかを見て、最も揃っている配置を返す。
 * 3 本とも {@link MIN_LINE_RATIO} 以上でなければプレー画面ではない（選曲画面・リザルト等）とみなして null。
 */
export function detectPlayLayout(frame: RgbaFrame): { layout: PlayLayout; confidence: number } | null {
  let best: { layout: PlayLayout; confidence: number } | null = null;
  for (const layout of PLAY_LAYOUTS) {
    const min = Math.min(...gradeLineRatios(frame, layout));
    if (min < MIN_LINE_RATIO) continue;
    if (!best || min > best.confidence) best = { layout, confidence: min };
  }
  return best;
}

// ---------------------------------------------------------------------------
// EX スコア（YOU の右の数字）
// ---------------------------------------------------------------------------

/** 桁の枠: グラフ左端からの相対位置。4 桁とも幅 34、間隔 40、高さ 22。 */
const SCORE_CELL_OFFSET_X = 241;
const SCORE_CELL_PITCH = 40;
const SCORE_CELL_Y = 51;
/** 先頭のゼロは灰色（輝度 100〜160）で描かれるので、それより低いしきい値にする。 */
const DIGIT_INK_LUMA = 90;

/**
 * 7 つの画の判定矩形（桁の枠内の相対座標 [x0, y0, x1, y1]、両端含む）。
 * a=上 b=右上 c=右下 d=下 e=左下 f=左上 g=中。
 *
 * 点ではなく矩形の塗りの割合で見るのは、次のずれを吸収するため。
 *  - 中央の横棒の高さが数字ごとに違う（5 は 8〜10 行目、2 は 9〜12 行目、4 は 11〜13 行目）
 *  - キャプチャの環境によって桁全体が 1〜2px ずれる（2026-10-05 の録画は右に 2px）
 * 2・3・5 の角の短い出っ張り（2 の左上、5 の左下など）は 1〜2 行しかないので、割合のしきい値で落ちる。
 */
const SEGMENT_RECTS: Record<'a' | 'b' | 'c' | 'd' | 'e' | 'f' | 'g', [number, number, number, number]> = {
  a: [12, 0, 22, 3],
  b: [27, 5, 33, 8],
  c: [27, 13, 33, 16],
  d: [12, 18, 22, 21],
  e: [1, 13, 7, 16],
  f: [1, 5, 7, 8],
  g: [12, 8, 22, 13],
};
const SEGMENT_ORDER = ['a', 'b', 'c', 'd', 'e', 'f', 'g'] as const;
/** 判定矩形のうち、この割合以上が塗られていれば画が点いているとみなす。 */
const SEGMENT_FILL_RATIO = 0.4;

/**
 * 画の点灯パターン（a〜g の順のビット列）→ 数字。
 * 0〜9 すべて実画面で確認済み（2026-10-05 の録画で 3・4・9 を確認）。
 * このフォントの 1 は中央の縦棒なので a・g・d の矩形だけに掛かる。
 * 7 は左上に短い縦棒があるので f も点く。
 */
const DIGIT_PATTERNS: Record<string, number> = {
  '1111110': 0,
  '1001001': 1,
  '1101101': 2,
  '1111001': 3,
  '0110011': 4,
  '1011011': 5,
  '1011111': 6,
  '1110010': 7,
  '1111111': 8,
  '1111011': 9,
};

function segmentLit(frame: RgbaFrame, cellX: number, cellY: number, seg: (typeof SEGMENT_ORDER)[number]): boolean {
  const { sx, sy } = scaleOf(frame);
  const [x0, y0, x1, y1] = SEGMENT_RECTS[seg];
  let ink = 0;
  let n = 0;
  for (let y = y0; y <= y1; y++) {
    for (let x = x0; x <= x1; x++) {
      const [r, g, b] = px(frame, (cellX + x) * sx, (cellY + y) * sy);
      n++;
      if (luma(r, g, b) > DIGIT_INK_LUMA) ink++;
    }
  }
  return ink / n >= SEGMENT_FILL_RATIO;
}

/** 1 桁を読む。パターンが一致しなければ null。 */
export function readDigitAt(frame: RgbaFrame, cellX: number, cellY: number): number | null {
  const bits = SEGMENT_ORDER.map(s => (segmentLit(frame, cellX, cellY, s) ? '1' : '0')).join('');
  const d = DIGIT_PATTERNS[bits];
  return d === undefined ? null : d;
}

/**
 * 【関数の役割】 YOU の EX スコア（4 桁）を読む。1 桁でも読めなければ null。
 */
export function readCurrentExScore(frame: RgbaFrame, layout: PlayLayout): number | null {
  let value = 0;
  for (let i = 0; i < 4; i++) {
    const d = readDigitAt(frame, layout.graphX0 + SCORE_CELL_OFFSET_X + SCORE_CELL_PITCH * i, SCORE_CELL_Y);
    if (d === null) return null;
    value = value * 10 + d;
  }
  return value;
}

// ---------------------------------------------------------------------------
// 曲名パネル（難易度札・曲名・アーティスト）
// ---------------------------------------------------------------------------

/** 曲名パネル左端からの相対矩形（1920×1080 基準）。 */
export interface Rect { x: number; y: number; w: number; h: number }

const BADGE_1P: Rect = { x: 165, y: 115, w: 175, h: 18 };
const BADGE_2P: Rect = { x: 620, y: 115, w: 175, h: 18 };
/** 曲名とアーティストの行。左右はパネル内側の斜めの枠線（x≒105〜140 と 820〜860）を避けている。 */
const TITLE_RECT: Rect = { x: 145, y: 37, w: 673, h: 33 };
const ARTIST_RECT: Rect = { x: 145, y: 71, w: 673, h: 24 };

/** 実フレーム座標の矩形の平均色。 */
function meanColor(frame: RgbaFrame, rect: Rect): [number, number, number] {
  let r = 0, g = 0, b = 0, n = 0;
  for (let y = rect.y; y < rect.y + rect.h; y += 2) {
    for (let x = rect.x; x < rect.x + rect.w; x += 3) {
      const c = px(frame, x, y);
      r += c[0]; g += c[1]; b += c[2]; n++;
    }
  }
  return [r / n, g / n, b / n];
}

/**
 * 【関数の役割】 札の平均色 → 難易度。暗い（未選択の札）なら null。
 * 実測: HYPER = (126,90,27)、ANOTHER = (127,35,34)、空の札 = (35,35,35)。
 * NORMAL（青）・LEGGENDARIA（紫）・BEGINNER（緑）は色相からの推定で、実画面では未確認。
 */
export function classifyBadgeColor([r, g, b]: [number, number, number]): DifficultyName | null {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  if (max < 70 || max - min < 30) return null;
  if (r >= g && r >= b) {
    // 赤系: g が b より十分大きければ金（HYPER）、そうでなければ赤（ANOTHER）
    return g > b * 2 ? 'HYPER' : 'ANOTHER';
  }
  if (b >= r && b >= g) return r > g ? 'LEGGENDARIA' : 'NORMAL';
  return 'BEGINNER';
}

/** パネル相対の矩形を、フレームの実座標の矩形にする。 */
export function panelRectToFrame(frame: RgbaFrame, layout: PlayLayout, rect: Rect): Rect {
  const { sx, sy } = scaleOf(frame);
  return {
    x: Math.round((layout.titlePanelX0 + rect.x) * sx),
    y: Math.round(rect.y * sy),
    w: Math.round(rect.w * sx),
    h: Math.round(rect.h * sy),
  };
}

export interface ChartHeader {
  difficulty: DifficultyName | null;
  /** 札が点いている側。グラフ配置の side と食い違えば配置の判定を疑う。 */
  badgeSide: PlaySide | null;
  titleRect: Rect;
  artistRect: Rect;
}

/** 【関数の役割】 曲名パネルから難易度と、OCR に回す曲名・アーティストの矩形を取る。 */
export function readChartHeader(frame: RgbaFrame, layout: PlayLayout): ChartHeader {
  const toFrame = (r: Rect) => panelRectToFrame(frame, layout, r);
  const d1 = classifyBadgeColor(meanColor(frame, toFrame(BADGE_1P)));
  const d2 = classifyBadgeColor(meanColor(frame, toFrame(BADGE_2P)));
  const badgeSide: PlaySide | null = d1 && !d2 ? '1P' : d2 && !d1 ? '2P' : null;
  return {
    difficulty: badgeSide === '1P' ? d1 : badgeSide === '2P' ? d2 : null,
    badgeSide,
    titleRect: toFrame(TITLE_RECT),
    artistRect: toFrame(ARTIST_RECT),
  };
}

/**
 * 【関数の役割】 曲名の矩形を 24×6 の輝度格子に縮めた指紋。曲が替わったか（OCR をやり直すか）の判定に使う。
 */
export function titleFingerprint(frame: RgbaFrame, rect: Rect): number[] {
  const cols = 24;
  const rows = 6;
  const out: number[] = [];
  for (let j = 0; j < rows; j++) {
    for (let i = 0; i < cols; i++) {
      let sum = 0;
      let n = 0;
      const x0 = rect.x + (rect.w * i) / cols;
      const y0 = rect.y + (rect.h * j) / rows;
      for (let y = y0; y < y0 + rect.h / rows; y += 2) {
        for (let x = x0; x < x0 + rect.w / cols; x += 2) {
          const [r, g, b] = px(frame, x, y);
          sum += Math.max(r, g, b);
          n++;
        }
      }
      out.push(n ? sum / n : 0);
    }
  }
  return out;
}

/** 指紋の平均絶対差。おおむね 8 を超えたら別の曲名とみなす。 */
export function fingerprintDistance(a: number[], b: number[]): number {
  if (a.length !== b.length || a.length === 0) return Number.POSITIVE_INFINITY;
  let s = 0;
  for (let i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
  return s / a.length;
}

/**
 * 【関数の役割】 OCR 用に、実フレーム座標の矩形を「白地に黒文字」の 2 値画像にして拡大する。
 *
 * 曲名は紫のグラデーション、アーティストは白なので、輝度ではなく RGB の最大値で明るさを測る。
 * しきい値は矩形内の大津の方法で決める。tesseract は白地黒文字で x-height 30px 程度以上が読みやすい。
 */
export function binarizeForOcr(frame: RgbaFrame, rect: Rect, upscale = 2): RgbaFrame {
  const w = Math.max(1, Math.round(rect.w));
  const h = Math.max(1, Math.round(rect.h));
  const v = new Uint8Array(w * h);
  const hist = new Array<number>(256).fill(0);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const [r, g, b] = px(frame, rect.x + x, rect.y + y);
      const m = Math.max(r, g, b);
      v[y * w + x] = m;
      hist[m]++;
    }
  }
  // 大津の方法
  const total = w * h;
  let sumAll = 0;
  for (let i = 0; i < 256; i++) sumAll += i * hist[i];
  let sumB = 0, wB = 0, bestVar = -1, thr = 128;
  for (let t = 0; t < 256; t++) {
    wB += hist[t];
    if (wB === 0) continue;
    const wF = total - wB;
    if (wF === 0) break;
    sumB += t * hist[t];
    const mB = sumB / wB;
    const mF = (sumAll - sumB) / wF;
    const between = wB * wF * (mB - mF) * (mB - mF);
    if (between > bestVar) { bestVar = between; thr = t; }
  }
  // 双線形で拡大してから 2 値化すると、最近傍で拡大するより字の輪郭が滑らかになる。
  // 字が画像の端に接していると tesseract が行を見失うので、周囲に白い余白を足す。
  const pad = Math.round(h * upscale * 0.4);
  const W = w * upscale + pad * 2;
  const H = h * upscale + pad * 2;
  const out = new Uint8ClampedArray(W * H * 4).fill(255);
  const at = (x: number, y: number) => v[Math.min(h - 1, Math.max(0, y)) * w + Math.min(w - 1, Math.max(0, x))];
  for (let y = 0; y < h * upscale; y++) {
    const fy = (y + 0.5) / upscale - 0.5;
    const y0 = Math.floor(fy);
    const ty = fy - y0;
    for (let x = 0; x < w * upscale; x++) {
      const fx = (x + 0.5) / upscale - 0.5;
      const x0 = Math.floor(fx);
      const tx = fx - x0;
      const val =
        at(x0, y0) * (1 - tx) * (1 - ty) + at(x0 + 1, y0) * tx * (1 - ty) +
        at(x0, y0 + 1) * (1 - tx) * ty + at(x0 + 1, y0 + 1) * tx * ty;
      if (val > thr) {
        const o = ((y + pad) * W + (x + pad)) * 4;
        out[o] = 0; out[o + 1] = 0; out[o + 2] = 0;
      }
    }
  }
  return { width: W, height: H, data: out };
}

// ---------------------------------------------------------------------------
// グラフ座標
// ---------------------------------------------------------------------------

/** 【関数の役割】 スコアレート（%）→ グラフ上の y（1920×1080 基準）。 */
export function rateToGraphY(layout: PlayLayout, ratePct: number): number {
  const t = Math.max(0, Math.min(100, ratePct)) / 100;
  return layout.zeroY - (layout.zeroY - layout.fullY) * t;
}
