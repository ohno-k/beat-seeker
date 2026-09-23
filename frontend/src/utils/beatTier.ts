/**
 * 【ユーティリティの役割】 beat-seeker 独自の段位システム「BEAT-Tier」の計算ロジックをまとめたモジュール。
 *
 * BEAT-Tier 概要:
 *  1. 1 譜面ごとの BEAT-PT = (scoreRate / 100)^1.3 × 非公式ランク別 Weight
 *     （AA / AAA / MAX- 超過時にはそれぞれ weight × 1% のボーナス）
 *     Weight の基準値: 11.0 → 150, 11.1 → 152, …, 12.4 → 178, 12.5 → 182, …, 13.0 → 202
 *  2. 総合 BEAT-PT = 上位 100 譜面分の合計
 *  3. ランク（段位相当） = 総合 BEAT-PT を 52 段階 + Beginner に分類
 *     - Novice 1（= 入門）= 10,000pt
 *     - Legend（最上位）= 18,000pt（理論値 ~18,464pt に近い）
 *     - Expert 以上は現行の理論最大を超える領域
 *
 * 主な公開 API:
 *  - {@link calculatePoints}         … 1 譜面の BEAT-PT を算出
 *  - {@link getMaxPoints}            … ランク別の最大 BEAT-PT を返す
 *  - {@link calculateTotalPoints}    … 上位 100 譜面の合計 BEAT-PT を返す
 *  - {@link getRankInfo}             … 総合 BEAT-PT から現在ランクを判定
 *  - {@link getNextRankInfo}         … 次ランクまでの進捗（%）を計算
 *  - Folder 系（{@link getFolderRankInfo} ほか）  … 難易度フォルダ単位でのランク判定
 *  - Rate-Tier 系（{@link RATE_TIER_RANKS} ほか） … 別方式の「Score Rate Tier」用
 *
 * 使い方:
 * ```ts
 * const pt   = calculatePoints(99.5, '12.3');
 * const rank = getRankInfo(calculateTotalPoints(allRecords));
 * ```
 */

// ── マジックナンバー定数（値は本ファイル内で既に使われていたものを定数化したもの） ──

/**
 * 非公式ランクの Weight 増分が +2 → +3 に切り替わる閾値。
 * 本来の切り替わり点は ☆12.5 だが、浮動小数点誤差で 12.5 が 12.499...
 * と表現されるケースを避けるため、実用上 0.01 だけ手前の 12.49 で判定する。
 */
const BEAT_PT_WEIGHT_STEP_THRESHOLD = 12.49;

/**
 * 【BEAT-PT 計算】 score rate (%) に対するべき乗カーブの指数。
 * 1.3 乗カーブにすることで、高スコアほど急激に BEAT-PT が伸びるようにしている。
 */
const BEAT_PT_SCORE_RATE_EXPONENT = 1.3;

/**
 * 【BEAT-PT 計算】 score rate を 0〜1 に正規化するための分母（%）。
 */
const SCORE_RATE_NORMALIZE_DIVISOR = 100;

/**
 * 【BEAT-PT 計算】 AA / AAA / MAX- 超過時に加点されるボーナス 1 段階分の重み係数。
 * 基準 weight × 1% = 0.01 が 3 種類加算されるため、最大 +3% になる。
 */
const BEAT_PT_BONUS_RATE = 0.01;

/**
 * 【BEAT-PT 計算】 理論上の最大 BEAT-PT = weight × 1.03。
 * AA / AAA / MAX- 3 種ボーナス（各 +1%）を合算したピーク値。
 */
const MAX_POINTS_MULTIPLIER = 1.03;

/**
 * 【BEAT-PT 計算】 DJ LEVEL C の下限 score rate（%）。2/3 ≒ 66.666% に相当。
 * これ以下では BEAT-PT が付かない（= 捨てスコア扱い）。
 */
export const SCORE_RATE_TIER_C_MIN = 66.666;

/**
 * 【BEAT-PT 計算】 AA 到達 score rate（%）。超えると +1% ボーナス。
 */
const SCORE_RATE_TIER_AA = 77.77;

/**
 * 【BEAT-PT 計算】 AAA 到達 score rate（%）。超えると +1% ボーナス。
 */
const SCORE_RATE_TIER_AAA = 88.88;

/**
 * 【BEAT-PT 計算】 MAX- 到達 score rate（%）。超えると +1% ボーナス。
 */
const SCORE_RATE_TIER_MAX_MINUS = 94.44;

/**
 * 【FOLDER 用】 単曲ティアの**ブロック境界**の必要 score rate（☆ × 11 ブロック）。
 *
 * 列の並びは {@link FOLDER_BLOCK_ORDER} と同じ:
 *   [Legend, Mythic 1, Ancient 1, Master 1, Elite 1, Commander 1,
 *    Veteran 1, Expert 1, Advanced 1, Intermediate 1, Novice 1]
 * 各ブロック内の 5 サブティア（V〜I）は、上の境界から下の境界までを
 * レート空間で 5 等分して作る（{@link getFolderRankThresholdRateAt}）。
 *
 * 2026-09-22 実測較正。較正元は Sparkle Shower(33) 終了時点の past_scores
 * （1,081 人 / 1,315 譜面 / 760,514 レコード）、☆の所属は active（第6版）:
 *  - **Legend** = その☆に属する譜面の「歴代全国レコード rate」の p10。
 *    （backend/src/main/resources/top-rankers-data/0/00_全国.csv.gz）
 *    人類が誰も到達していない Legend を作らないための外部基準で、ここだけ
 *    beat-seeker の分布から独立している。歴代全国レコードの 89.5% がこの線を超える。
 *  - **Mythic 1 以下** = 譜面ごとに「その譜面で上位 Q%」に当たる rate を求め、
 *    ☆内の中央値を採ったもの。Q = 1.2 / 3.8 / 8.0 / 13.5 / 20.5 / 28.5 /
 *    37.5 / 47.5 / 60.0 / 80.0 (%)。
 *  - ☆方向の単調性は重み付き PAVA で担保した。実際に値が動いたのは
 *    Novice 1 の ☆13.0 / 13.1（64.37 / 65.77 → 64.99）だけで、
 *    残りは実測がそのまま単調に並んだ。
 *
 * 旧実装（Legend ラインの制御点 7 本 + offsetScale の制御点 6 本 + 累乗カーブ）は、
 * 同じ行が☆によって上位 0.06%〜0.52%（Legend）や上位 47%〜81%（Novice 1）と
 * バラバラの順位を指していた。この表はその歪みを取り、全☆で同じ行が同じ順位を指す。
 *
 * 母集団に注意: パーセンタイルは「beat-seeker 登録者のうちその譜面にスコアがある人」
 * （1 譜面あたり中央 452 人、申告段位は皆伝 65.5%）で、IIDX 全国の分布ではない。
 *
 * 再較正の手順は docs/単曲ティア較正.md を参照。
 */
const BLOCK_BOUND_RATES: Record<string, readonly number[]> = {
    //        Legend  Mythic1 Ancient1 Master1  Elite1  Comm1   Vete1   Expe1   Adva1   Inte1   Novi1
    '11.0': [ 99.93,  99.66,  99.43,  99.06,  98.76,  98.23,  97.59,  96.81,  95.70,  94.15,  89.52], // 23 譜面
    '11.1': [ 99.86,  99.57,  99.22,  98.87,  98.42,  97.81,  97.10,  96.21,  94.98,  93.07,  87.55], // 33 譜面
    '11.2': [ 99.80,  99.42,  98.99,  98.57,  98.07,  97.47,  96.62,  95.60,  94.25,  92.11,  85.94], // 45 譜面
    '11.3': [ 99.80,  99.38,  98.93,  98.39,  97.80,  97.11,  96.20,  95.12,  93.60,  91.31,  84.28], // 48 譜面
    '11.4': [ 99.72,  99.25,  98.77,  98.21,  97.60,  96.81,  95.83,  94.72,  93.19,  90.71,  83.47], // 78 譜面
    '11.5': [ 99.68,  99.17,  98.58,  98.03,  97.37,  96.52,  95.53,  94.33,  92.58,  90.05,  82.48], // 75 譜面
    '11.6': [ 99.68,  99.11,  98.53,  97.91,  97.17,  96.26,  95.17,  93.88,  92.05,  89.65,  81.96], // 85 譜面
    '11.7': [ 99.65,  99.06,  98.37,  97.69,  96.91,  95.95,  94.82,  93.47,  91.61,  89.15,  81.20], // 84 譜面
    '11.8': [ 99.51,  98.86,  98.15,  97.45,  96.62,  95.56,  94.43,  93.00,  91.11,  88.68,  80.63], // 97 譜面
    '11.9': [ 99.45,  98.75,  98.00,  97.15,  96.34,  95.22,  94.03,  92.47,  90.54,  87.87,  79.50], // 92 譜面
    '12.0': [ 99.31,  98.52,  97.69,  96.92,  95.97,  94.80,  93.49,  91.95,  90.15,  87.21,  79.35], // 78 譜面
    '12.1': [ 99.31,  98.40,  97.43,  96.59,  95.65,  94.39,  92.94,  91.32,  89.43,  86.13,  78.13], // 84 譜面
    '12.2': [ 99.25,  98.16,  97.22,  96.25,  95.23,  93.92,  92.42,  90.79,  88.92,  85.50,  77.31], // 88 譜面
    '12.3': [ 99.06,  97.96,  96.92,  95.83,  94.74,  93.34,  91.88,  90.24,  88.09,  84.23,  75.93], // 84 譜面
    '12.4': [ 98.94,  97.54,  96.33,  95.17,  93.99,  92.52,  90.95,  89.47,  87.23,  83.60,  75.54], // 77 譜面
    '12.5': [ 98.57,  97.27,  95.98,  94.77,  93.55,  92.06,  90.42,  88.89,  86.20,  82.24,  74.33], // 70 譜面
    '12.6': [ 98.49,  96.81,  95.37,  94.09,  92.69,  91.16,  89.77,  87.80,  84.84,  80.95,  73.18], // 57 譜面
    '12.7': [ 98.12,  96.14,  94.64,  93.15,  91.72,  89.92,  88.68,  86.27,  83.67,  79.97,  72.65], // 51 譜面
    '12.8': [ 97.55,  95.16,  93.37,  91.81,  90.32,  88.70,  86.25,  84.16,  81.14,  77.60,  69.75], // 45 譜面
    '12.9': [ 96.70,  93.66,  91.23,  89.84,  87.55,  84.61,  82.48,  79.93,  76.81,  72.68,  66.26], // 12 譜面
    '13.0': [ 95.68,  92.55,  90.51,  88.54,  85.81,  83.61,  81.05,  78.43,  75.56,  72.66,  64.99], //  5 譜面
    '13.1': [ 94.95,  91.32,  89.44,  87.32,  84.52,  82.17,  79.98,  77.87,  74.98,  71.67,  64.99], //  4 譜面
};

/** 【FOLDER 用】 {@link BLOCK_BOUND_RATES} の列の並び（高い順）。 */
export const FOLDER_BLOCK_ORDER = [
    'Legend', 'Mythic', 'Ancient', 'Master', 'Elite', 'Commander',
    'Veteran', 'Expert', 'Advanced', 'Intermediate', 'Novice',
] as const;

/**
 * 【Folder Legend】 Legend 判定 score rate の対応範囲下限（非公式ランク）。
 */
const LEGEND_RANK_MIN = 11.0;

/**
 * 【Folder Legend】 Legend 判定 score rate の対応範囲上限（非公式ランク）。
 */
const LEGEND_RANK_MAX = 13.1;

/**
 * 【内部ヘルパー】 非公式ランク文字列から数値部分を取り出す。
 * `"12.0 (IIDX 32)"` のような注記付きも受け付ける。取れなければ null。
 */
function parseInformalRankValue(informalRank: string | undefined): number | null {
    if (!informalRank) return null;
    const m = informalRank.match(/(\d+\.\d+)/);
    if (!m) return null;
    const v = parseFloat(m[1]);
    return Number.isFinite(v) ? v : null;
}

/**
 * 【内部ヘルパー】 非公式ランク → その☆のブロック境界レート 11 本。
 *
 * 表に無い☆（0.1 刻みから外れた中間値が将来出た場合）は隣接 2 本を線形補間する。
 * 対応範囲（☆11.0〜13.1）の外と、数値が取れないもの（Uncategorized 等）は null。
 */
function folderBlockBounds(informalRank: string | undefined): readonly number[] | null {
    const v = parseInformalRankValue(informalRank);
    if (v === null || v < LEGEND_RANK_MIN || v > LEGEND_RANK_MAX) return null;

    const exact = BLOCK_BOUND_RATES[v.toFixed(1)];
    if (exact) return exact;

    const lo = Math.floor(v * 10) / 10;
    const a = BLOCK_BOUND_RATES[lo.toFixed(1)];
    if (!a) return null;
    const b = BLOCK_BOUND_RATES[(lo + 0.1).toFixed(1)];
    if (!b) return a;
    const t = (v - lo) / 0.1;
    return a.map((x, i) => x + (b[i] - x) * t);
}

/**
 * 【総合 BEAT-PT】 合計対象となる上位譜面数。譜面数が多いユーザー同士を公平に比較するため
 * 上位 100 譜面で打ち切る。
 */
const TOP_CHART_LIMIT = 100;

/**
 * 【表示丸め】 小数第 1 位まで表示するための丸め倍率。× 10 → round → / 10。
 */
const ROUND_TO_ONE_DECIMAL = 10;

/**
 * 【ランクブロック】 1 つのランク名ブロックを I〜V の 5 段階サブティアに分割する数。
 */
const TIERS_PER_RANK_BLOCK = 5;

/**
 * 【進捗率】 計算結果をクランプするときの上限値（% 表示）。
 */
const PROGRESS_PCT_MAX = 100;

/**
 * ランク 1 段階分を表す情報。BEAT-Tier / Rate-Tier 双方で使われる共通型。
 */
export interface RankInfo {
    /** ランク名（例: 'Master', 'Legend', 'Novice'） */
    name: string;
    /** 同一ランク内のサブティア（1=最下位、5=最上位）。Legend/Beginner は undefined */
    tier?: number; // 1-5
    /** このランクに到達する最小ポイント */
    minPoints: number;
    /** UI 表示に使う Tailwind カラークラス */
    color: string;
}

/**
 * Folder（難易度フォルダ）ごとの総合ランク情報。必ず tier / description を伴う点が {@link RankInfo} と異なる。
 */
export interface FolderRankInfo {
    name: string;
    tier: number;
    color: string;
    /** UI のサブ表記に使う説明文（例: '達人'） */
    description: string;
}

/**
 * 非公式ランク別の Weight テーブル。`"11.0"` から `"13.1"` までを 0.1 刻みで保持する。
 *
 * 構築ルール:
 *  - 開始値: 11.0 → 145（ただし下のループは 0..21 のうち初回で 11.0 を登録するため、実際の 11.0 の値は 145）
 *  - ステップ: 11.0〜12.4 は +2、12.5〜13.1 は +3（= 高難度ほど加速する傾斜）→ 13.1 = 193
 *
 * NOTE: マジックナンバーが集中しているため、バランス調整時はここを編集する。
 */
export const WEIGHTS: Record<string, number> = {};
let weight = 145;
for (let i = 0; i <= 21; i++) {
    const rankValue = 11.0 + i * 0.1;
    const rank = rankValue.toFixed(1);
    WEIGHTS[rank] = weight;
    // 12.5 への遷移以降は刻み幅を 3 に増やす。12.49 を閾値にしているのは浮動小数誤差対策。
    weight += (rankValue >= BEAT_PT_WEIGHT_STEP_THRESHOLD) ? 3 : 2;
}

/**
 * 【関数の役割】 非公式ランク文字列から Weight を引く。
 *
 * 入力は `"12.0"` のような単純な値のほか、`"12.0 (IIDX 32)"` のような注記付きも
 * 正規表現で数値部分のみ抽出して対応する。
 *
 * @param informalRank 非公式ランク文字列（undefined 可）
 * @returns            対応 Weight。未定義 / 範囲外なら 0。
 */
export function getWeight(informalRank: string | undefined): number {
    if (!informalRank) return 0;

    // "12.0 (IIDX 32)" のような付加情報を許容するため数値部分だけを抜き出す
    const match = informalRank.match(/(\d+\.\d+)/);
    const key = match ? match[1] : informalRank;

    return WEIGHTS[key] || 0;
}

/**
 * 【関数の役割】 指定ランクで得られる理論上の最大 BEAT-PT を返す。
 *
 * AA / AAA / MAX- それぞれの超過ボーナス（各 1%）を積んだ合計 1.03 倍が上限。
 *
 * @param informalRank 非公式ランク文字列
 * @returns            最大 BEAT-PT（実数）
 */
export function getMaxPoints(informalRank: string | undefined): number {
    const weight = getWeight(informalRank);
    return weight * MAX_POINTS_MULTIPLIER; // 基準 weight + 3%（AA / AAA / MAX- の 3 種ボーナス合計）
}

/**
 * 【関数の役割】 各フォルダの Legend（最高ランク）到達に必要な score rate を返す。
 *
 * {@link BLOCK_BOUND_RATES} の先頭列（= その☆に属する譜面の歴代全国レコードの p10）。
 * 人類が誰も到達していない Legend を作らないための外部基準。
 *
 * @param informalRank 非公式ランク文字列
 * @returns            Legend 判定用の score rate（%）。範囲外は 0。
 */
export function getFolderLegendRate(informalRank: string | undefined): number {
    const bounds = folderBlockBounds(informalRank);
    return bounds ? bounds[0] : 0;
}

/**
 * 【関数の役割】 Legend 相当 score rate を BEAT-PT に変換して返す。
 *
 * フォルダ内「Legend の 1 譜面あたり要求 BEAT-PT」として利用される。
 *
 * @param informalRank 非公式ランク文字列
 * @returns            Legend 閾値の 1 譜面あたり BEAT-PT。未定義時は 0。
 */
export function getLegendPtPerSong(informalRank: string | undefined): number {
    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return 0;
    return calculatePoints(legendRate, informalRank);
}

/**
 * 【関数の役割】 1 譜面分の BEAT-PT を算出する中核関数。
 *
 * 計算式:
 *   base = (scoreRate / 100)^1.3 × weight
 *   bonus = (scoreRate > 77.77 ? +1% : 0) + (>88.88 ? +1% : 0) + (>94.44 ? +1% : 0)
 *   return base + weight × bonus
 *
 * 66.666% 以下はスコア率として意味を持たない（DJ LEVEL C 未満）と見なして 0 を返す。
 *
 * @param scoreRate    score rate（%）。例: 99.5
 * @param informalRank 非公式ランク文字列
 * @returns            1 譜面あたりの BEAT-PT（実数）
 */
export function calculatePoints(scoreRate: number, informalRank: string | undefined): number {
    const weight = getWeight(informalRank);
    // weight が取れない / score rate が C 未満 → 加点対象外
    if (weight === 0 || scoreRate <= SCORE_RATE_TIER_C_MIN) return 0;

    // 基本点: 1.3 乗カーブで高スコアに比重を置く
    let basePoints = Math.pow(scoreRate / SCORE_RATE_NORMALIZE_DIVISOR, BEAT_PT_SCORE_RATE_EXPONENT) * weight;

    // ボーナス: AA / AAA / MAX- それぞれ超過するごとに +1% ずつ加算
    let bonus = 0;
    if (scoreRate > SCORE_RATE_TIER_AA) bonus += weight * BEAT_PT_BONUS_RATE; // AA 超え
    if (scoreRate > SCORE_RATE_TIER_AAA) bonus += weight * BEAT_PT_BONUS_RATE; // AAA 超え
    if (scoreRate > SCORE_RATE_TIER_MAX_MINUS) bonus += weight * BEAT_PT_BONUS_RATE; // MAX- 超え

    return basePoints + bonus;
}

/**
 * BEAT-Tier の全ランク定義（52 段階 + Beginner）。
 *
 * - Novice 1（Novice の一番下）= 10,000pt が「この段位システムの入門ライン」。
 * - Legend = 18,000pt（理論上の最大値 ~18,464pt に迫る領域）。
 * - 上位ランクほどポイント幅が狭く、到達難度が増していく設計。
 *
 * 各ブロックは {@link generateTieredRanks} で 5 ティア（I〜V）に分割される。
 */
export const RANKS: RankInfo[] = [
    { name: 'Legend', minPoints: 18000, color: 'text-amber-500 font-bold' },

    ...generateTieredRanks('Mythic', 17500, 18000, 'text-purple-600'),  // 500
    ...generateTieredRanks('Ancient', 17000, 17500, 'text-indigo-600'),  // 500
    ...generateTieredRanks('Master', 16500, 17000, 'text-red-600'),     // 500
    ...generateTieredRanks('Elite', 16000, 16500, 'text-orange-600'),    // 500
    ...generateTieredRanks('Commander', 15500, 16000, 'text-yellow-700'), // 500
    ...generateTieredRanks('Veteran', 15000, 15500, 'text-emerald-600'), // 500
    ...generateTieredRanks('Expert', 14000, 15000, 'text-teal-600'),    // 1000
    ...generateTieredRanks('Advanced', 13000, 14000, 'text-cyan-600'),    // 1000
    ...generateTieredRanks('Intermediate', 12000, 13000, 'text-blue-600'),    // 1500
    ...generateTieredRanks('Novice', 10000, 12000, 'text-slate-600'),   // 2000

    { name: 'Beginner', minPoints: 0, color: 'text-slate-400' },
];

/**
 * 【関数の役割】 ランク名からフォルダ表示用の Tailwind カラークラスを引く。
 *
 * UI のカード背景色・ボーダー色・テキスト色を一括で返す。
 *
 * @param rankName ランク名（大小混在を許容）
 * @returns        Tailwind クラス文字列
 */
export const getFolderColorClass = (rankName: string): string => {
    switch (rankName.toLowerCase()) {
        case 'legend': return 'bg-gradient-to-r from-amber-200 to-yellow-400 border-amber-400 text-amber-900 font-bold';
        case 'mythic': return 'bg-purple-100 border-purple-300 text-purple-800 font-bold';
        case 'ancient': return 'bg-indigo-100 border-indigo-300 text-indigo-800 font-bold';
        case 'master': return 'bg-red-50 border-red-200 text-red-700';
        case 'elite': return 'bg-orange-50 border-orange-200 text-orange-700';
        case 'commander': return 'bg-yellow-50 border-yellow-200 text-yellow-700';
        case 'veteran': return 'bg-emerald-50 border-emerald-200 text-emerald-700';
        case 'expert': return 'bg-teal-50 border-teal-200 text-teal-700';
        case 'advanced': return 'bg-cyan-50 border-cyan-200 text-cyan-700';
        case 'intermediate': return 'bg-blue-50 border-blue-200 text-blue-700';
        case 'novice': return 'bg-slate-100 border-slate-300 text-slate-700';
        default: return 'bg-slate-50 border-slate-200 text-slate-800';
    }
};

/**
 * 【関数の役割】 譜面レコード群から総合 BEAT-PT を算出する（上位 100 譜面の和）。
 *
 * 手順:
 *  1. 0 以下の BEAT-PT を除外
 *  2. 降順ソート
 *  3. 上位 100 譜面で合計
 *  4. 小数第 2 位以下を切り捨て（表示のブレ防止）
 *
 * @param scores beatTierPoints プロパティを持つレコード配列
 * @returns      総合 BEAT-PT（小数第 1 位まで）
 */
export const calculateTotalPoints = (scores: { beatTierPoints: number }[]): number => {
    const validScores = scores.filter(s => s.beatTierPoints && s.beatTierPoints > 0);
    validScores.sort((a, b) => b.beatTierPoints - a.beatTierPoints);
    const top100 = validScores.slice(0, TOP_CHART_LIMIT);
    const sum = top100.reduce((acc, score) => acc + score.beatTierPoints, 0);
    // 小数第 1 位までに丸める（× 10 → 四捨五入 → ÷ 10）
    return Math.round(sum * ROUND_TO_ONE_DECIMAL) / ROUND_TO_ONE_DECIMAL;
};

/**
 * 【関数の役割】 Overall Tier（概観表示用）のランクを総合 BEAT-PT から判定する。
 *
 * {@link RANKS} とは別体系の、ざっくりとした大分類バッジ向け。
 * ラベルに日本語の説明文（description）も含める。
 *
 * @param totalPoints 総合 BEAT-PT
 * @returns           {@link FolderRankInfo}
 */
export const getOverallRankInfo = (totalPoints: number): FolderRankInfo => {
    if (totalPoints >= 100000) return { name: 'Legend', tier: 5, color: 'text-amber-500', description: '神話の領域' };
    if (totalPoints >= 80000) return { name: 'Mythic', tier: 4, color: 'text-purple-500', description: '伝説のプレイヤー' };
    if (totalPoints >= 60000) return { name: 'Ancient', tier: 4, color: 'text-indigo-500', description: '古都の猛者' };
    if (totalPoints >= 45000) return { name: 'Master', tier: 3, color: 'text-red-500', description: '達人' };
    if (totalPoints >= 30000) return { name: 'Elite', tier: 3, color: 'text-orange-500', description: '熟練者' };
    if (totalPoints >= 25000) return { name: 'Commander', tier: 2, color: 'text-yellow-600', description: '指揮官' };
    if (totalPoints >= 20000) return { name: 'Veteran', tier: 2, color: 'text-emerald-500', description: '歴戦の勇者' };
    if (totalPoints >= 10000) return { name: 'Expert', tier: 2, color: 'text-teal-500', description: '上級者' };
    if (totalPoints >= 5000) return { name: 'Advanced', tier: 1, color: 'text-cyan-500', description: '中級者' };
    if (totalPoints >= 2000) return { name: 'Intermediate', tier: 1, color: 'text-blue-500', description: '初級者' };
    if (totalPoints >= 500) return { name: 'Novice', tier: 0, color: 'text-slate-600', description: '見習い' };
    return { name: 'Beginner', tier: 0, color: 'text-slate-500', description: '駆け出し' };
};

/**
 * 【内部ヘルパー】 1 つのランク名を 5 段階のサブティアに分割して配列を生成する。
 *
 * 例: `generateTieredRanks('Master', 16500, 17000, ...)` →
 *   `[Master V(17000 の手前), Master IV, Master III, Master II, Master I(16500)]`
 *
 * 返す配列は tier の大きい順（＝高いランクが先頭）で並ぶ。
 *
 * @param name  ランク名（例: 'Master'）
 * @param start このランクブロックの下限ポイント
 * @param end   このランクブロックの上限ポイント（次ランクの下限）
 * @param color Tailwind カラークラス
 */
function generateTieredRanks(name: string, start: number, end: number, color: string): RankInfo[] {
    const tiers: RankInfo[] = [];
    const step = (end - start) / TIERS_PER_RANK_BLOCK;
    for (let i = TIERS_PER_RANK_BLOCK; i >= 1; i--) {
        tiers.push({
            name,
            tier: i,
            minPoints: start + (i - 1) * step,
            color
        });
    }
    return tiers;
}

/**
 * 【関数の役割】 総合 BEAT-PT から現在の BEAT-Tier ランクを判定する。
 *
 * {@link RANKS} を minPoints 降順にソートし、最初に条件を満たしたものを返す。
 *
 * @param totalPoints 総合 BEAT-PT
 * @returns           現在のランク情報
 */
export function getRankInfo(totalPoints: number): RankInfo {
    // 上位ランクから順に探索するため minPoints 降順でソート
    const sortedRanks = [...RANKS].sort((a, b) => b.minPoints - a.minPoints);
    return sortedRanks.find(r => totalPoints >= r.minPoints) || RANKS[RANKS.length - 1];
}

/**
 * 【関数の役割】 次ランクまでの進捗率（0〜100%）と次ランク情報を返す。
 *
 * 既に Legend の場合は `progress: 100`、`nextRank: undefined` を返す。
 *
 * @param totalPoints 総合 BEAT-PT
 * @returns           `{ nextRank?, progress }` の組
 */
export function getNextRankInfo(totalPoints: number): { nextRank?: RankInfo; progress: number } {
    // 手順1: minPoints 昇順にソート → reverse して降順配列を得る。
    const sortedRanksAsc = [...RANKS].sort((a, b) => a.minPoints - b.minPoints);
    const reversedRanks = [...sortedRanksAsc].reverse();

    // 手順2: 降順配列の先頭から走査し、最初に totalPoints ≧ minPoints を満たしたランクが現在ランク。
    const currentRankIndexInReversed = reversedRanks.findIndex(r => totalPoints >= r.minPoints);

    // 手順3: 見つからない（Beginner=0 があるので通常ありえない）or 既に Legend の場合は 100% とみなす。
    //         reversedRanks[0] が最高ランク（Legend）なので index === 0 は「もう上がない」を示す。
    if (currentRankIndexInReversed === -1 || currentRankIndexInReversed === 0) {
        return { progress: PROGRESS_PCT_MAX };
    }

    // 手順4: 現在ランクと、その 1 つ上（index - 1）のランクを特定。
    const currentRank = reversedRanks[currentRankIndexInReversed];
    const nextRank = reversedRanks[currentRankIndexInReversed - 1];

    // 手順5: 次ランクまでの幅と現在位置から進捗率（0〜100）を算出。範囲外はクランプ。
    const range = nextRank.minPoints - currentRank.minPoints;
    const currentProgress = totalPoints - currentRank.minPoints;

    return {
        nextRank,
        progress: Math.min(PROGRESS_PCT_MAX, Math.max(0, (currentProgress / range) * PROGRESS_PCT_MAX))
    };
}

/**
 * 【関数の役割】 ランク一覧をランク名でグルーピングして返す（UI の段位表用）。
 *
 * 戻り値の形: `{ 'Master': [RankInfo×5], 'Elite': [RankInfo×5], ... }`
 * 各グループ内はサブティア昇順に並べる。
 */
export function getGroupedRanks() {
    const groups: Record<string, RankInfo[]> = {};
    RANKS.forEach(r => {
        if (!groups[r.name]) groups[r.name] = [];
        groups[r.name].push(r);
    });
    // Ensure tiers are sorted within groups (usually they are already)
    Object.keys(groups).forEach(name => {
        groups[name].sort((a, b) => (a.tier || 0) - (b.tier || 0));
    });
    return groups;
}

/**
 * 【内部ヘルパー】 Rate-Tier 用に、1 つのランク名を 5 段階のサブティアへ「等比」分割する。
 *
 * Rate-Tier の大ティア境界は 25 → 50 → 100 → … と 2 倍ずつ伸びる等比数列なので、
 * ブロック内を {@link generateTieredRanks}（等差）で刻むと、1 段上がるのに必要な
 * 伸び率が段ごとにばらつく。例えば Master 3200〜6400 を等差で刻むと
 * I→II は +20% 必要なのに IV→V は +12.5% で済み、下の段ほど昇格が重かった。
 *
 * そこで公比 `(end / start) ^ (1/5)`（= ブロック幅が 2 倍なら約 +14.87%）の等比で刻み、
 * どのサブティアでも「次の段まで同じ伸び率」＝対数グラフ上で等間隔になるようにする。
 * 等比分割の閾値は等差分割より必ず低くなる（相加相乗平均の関係）ため、
 * この方式へ切り替えてもサブティアが下がる人は出ない。
 *
 * 例: `generateGeometricTieredRanks('Master', 3200, 6400, ...)` →
 *   `[Master V(5571.5), Master IV(4850.3), Master III(4222.4), Master II(3675.8), Master I(3200)]`
 *
 * 返す配列は tier の大きい順（＝高いランクが先頭）で並ぶ。
 *
 * @param name  ランク名（例: 'Master'）
 * @param start このランクブロックの下限ポイント（= サブティア I の閾値）
 * @param end   このランクブロックの上限ポイント（次ランクの下限）
 * @param color Tailwind カラークラス
 */
function generateGeometricTieredRanks(name: string, start: number, end: number, color: string): RankInfo[] {
    // 公比 = ブロック幅の 5 乗根。start × ratio^5 = end（次ランクの下限）になる。
    const ratio = Math.pow(end / start, 1 / TIERS_PER_RANK_BLOCK);
    const tiers: RankInfo[] = [];
    for (let i = TIERS_PER_RANK_BLOCK; i >= 1; i--) {
        tiers.push({
            name,
            tier: i,
            // pt の表示が小数第 1 位までなので、閾値も 0.1 pt 単位に丸めて表示と揃える。
            minPoints: Math.round(start * Math.pow(ratio, i - 1) * 10) / 10,
            color
        });
    }
    return tiers;
}

/**
 * Rate-Tier の全ランク定義。
 *
 * 閾値は 25 から始まり 2 倍ずつ伸びる等比数列: 25 → 50 → 100 → ... → 25600（理論最大）。
 * BEAT-Tier が「上位 100 譜面の累積」なのに対し、こちらは 1 譜面の score rate から
 * 指数的に伸びるポイントを与える体系。
 *
 * 大ティアの中のサブティア I〜V も {@link generateGeometricTieredRanks} で等比に刻む。
 * 大ティアもサブティアも対数グラフ上で等間隔になり、「次のサブティアまでに必要な伸び率」が
 * どの段でも同じ（約 +14.87%）になる。
 */
export const RATE_TIER_RANKS: RankInfo[] = [
    { name: 'Legend', minPoints: 25600, color: 'text-amber-500 font-bold' },

    ...generateGeometricTieredRanks('Mythic', 12800, 25600, 'text-purple-600'),
    ...generateGeometricTieredRanks('Ancient', 6400, 12800, 'text-indigo-600'),
    ...generateGeometricTieredRanks('Master', 3200, 6400, 'text-red-600'),
    ...generateGeometricTieredRanks('Elite', 1600, 3200, 'text-orange-600'),
    ...generateGeometricTieredRanks('Commander', 800, 1600, 'text-yellow-700'),
    ...generateGeometricTieredRanks('Veteran', 400, 800, 'text-emerald-600'),
    ...generateGeometricTieredRanks('Expert', 200, 400, 'text-teal-600'),
    ...generateGeometricTieredRanks('Advanced', 100, 200, 'text-cyan-600'),
    ...generateGeometricTieredRanks('Intermediate', 50, 100, 'text-blue-600'),
    ...generateGeometricTieredRanks('Novice', 25, 50, 'text-slate-600'),

    { name: 'Beginner', minPoints: 0, color: 'text-slate-400' },
];

/**
 * 【関数の役割】 Rate-Tier 版の getGroupedRanks。UI の段位表示で使う。
 */
export function getGroupedRateTierRanks() {
    const groups: Record<string, RankInfo[]> = {};
    RATE_TIER_RANKS.forEach(r => {
        if (!groups[r.name]) groups[r.name] = [];
        groups[r.name].push(r);
    });
    Object.keys(groups).forEach(name => {
        groups[name].sort((a, b) => (a.tier || 0) - (b.tier || 0));
    });
    return groups;
}

/**
 * 【関数の役割】 Rate-Tier の総合ポイントから現在ランクを判定する。
 *
 * @param totalPoints Rate-Tier の合計ポイント
 */
export function getRateTierRankInfo(totalPoints: number): RankInfo {
    const sorted = [...RATE_TIER_RANKS].sort((a, b) => b.minPoints - a.minPoints);
    return sorted.find(r => totalPoints >= r.minPoints) ?? RATE_TIER_RANKS[RATE_TIER_RANKS.length - 1];
}

/**
 * ティアアイコンの「前作ティアの外枠」に渡す props（RankIcon の frameRankName / frameTier）。
 *
 * 外枠は前作（世代切り替え時のスナップショット）の最終 PT から導いたティアの色で光り、
 * サブティア 1〜5 で光量が変わる。今作でティアが上がっても前作のまま固定。
 * 前作の記録が無い（null / 0）人は外枠なし（空オブジェクトを返すので v-bind してもプロパティが付かない）。
 *
 * @param previousPt 前作の最終 BEAT-PT または RATE-PT（API の previousBeatPt / previousRatePt）
 * @param kind       'beat'（BEAT-TIER 尺度）か 'rate'（RATE-TIER 尺度）
 */
export function previousTierFrame(
    previousPt: number | null | undefined,
    kind: 'beat' | 'rate' = 'beat',
): { frameRankName?: string; frameTier?: number } {
    if (previousPt == null || !(previousPt > 0)) return {};
    const info = kind === 'rate' ? getRateTierRankInfo(previousPt) : getRankInfo(previousPt);
    return { frameRankName: info.name, frameTier: info.tier };
}

/**
 * 【関数の役割】 Rate-Tier 版の getNextRankInfo。
 * ロジックは BEAT-Tier 側と同一で、対象テーブルを {@link RATE_TIER_RANKS} に差し替えただけ。
 */
export function getNextRateTierRankInfo(totalPoints: number): { nextRank?: RankInfo; progress: number } {
    const sortedAsc = [...RATE_TIER_RANKS].sort((a, b) => a.minPoints - b.minPoints);
    const reversed = [...sortedAsc].reverse();
    const currentIdx = reversed.findIndex(r => totalPoints >= r.minPoints);
    if (currentIdx === -1 || currentIdx === 0) return { progress: PROGRESS_PCT_MAX };
    const currentRank = reversed[currentIdx];
    const nextRank = reversed[currentIdx - 1];
    const range = nextRank.minPoints - currentRank.minPoints;
    const progress = Math.min(PROGRESS_PCT_MAX, Math.max(0, (totalPoints - currentRank.minPoints) / range * PROGRESS_PCT_MAX));
    return { nextRank, progress };
}

/**
 * Score Rate Tier: 1 譜面あたりの score rate → ポイントの閾値テーブル。
 *
 * 全 ANOTHER / LEGGENDARIA（全レベル）に一律で適用される。
 * 閾値ポイントは 1 → 2 → 4 → 8 → ... と倍加する「対数スケール」で設計されており、
 * 100% に限りなく近いほど指数的に加点される。
 *
 * 線形補完ルール:
 *  - rate が閾値間にある場合は隣接 2 点で線形補間
 *  - 最小閾値未満は 0 pt
 *  - 100% 時は 512 pt
 */
export const SCORE_RATE_THRESHOLDS: { rate: number; points: number }[] = [
    { rate: 77.77, points: 1 },
    { rate: 88.89, points: 2 },
    { rate: 94.44, points: 4 },
    { rate: 97.22, points: 8 },
    { rate: 98.61, points: 16 },
    { rate: 99.31, points: 32 },
    { rate: 99.65, points: 64 },
    { rate: 99.83, points: 128 },
    { rate: 99.91, points: 256 },
    { rate: 100, points: 512 },
];

/**
 * 【関数の役割】 1 譜面の score rate から Score Rate Tier ポイントを算出する。
 *
 * {@link SCORE_RATE_THRESHOLDS} の閾値テーブルに対して線形補間を行う。
 *
 * @param scoreRate score rate（%）
 * @returns         Score Rate Tier ポイント（実数）
 */
export function calculateScoreRateTierPoints(scoreRate: number): number {
    // 最小閾値未満（= 低スコア）は 0 pt
    if (scoreRate <= 0 || scoreRate < SCORE_RATE_THRESHOLDS[0].rate) return 0;
    const last = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1];
    // 100% 以上はテーブル最終値（512pt）でキャップ
    if (scoreRate >= last.rate) return last.points;
    // 隣接 2 点で線形補間
    for (let i = 0; i < SCORE_RATE_THRESHOLDS.length - 1; i++) {
        const lo = SCORE_RATE_THRESHOLDS[i];
        const hi = SCORE_RATE_THRESHOLDS[i + 1];
        if (scoreRate < hi.rate) {
            const t = (scoreRate - lo.rate) / (hi.rate - lo.rate);
            return lo.points + t * (hi.points - lo.points);
        }
    }
    return 0;
}

/**
 * 【FOLDER 用】 サブランクブロック定義（高い順）。Mythic=最上位ブロック、Novice=最下位ブロック。
 * 各ブロックは内部で 5 サブティア（V〜I）に分解される。Legend は単一ティアでこのリスト外。
 */
const FOLDER_RANK_BLOCKS: { name: string; color: string }[] = [
    { name: 'Mythic',       color: 'text-purple-600' },
    { name: 'Ancient',      color: 'text-indigo-600' },
    { name: 'Master',       color: 'text-red-600' },
    { name: 'Elite',        color: 'text-orange-600' },
    { name: 'Commander',    color: 'text-yellow-700' },
    { name: 'Veteran',      color: 'text-emerald-600' },
    { name: 'Expert',       color: 'text-teal-600' },
    { name: 'Advanced',     color: 'text-cyan-600' },
    { name: 'Intermediate', color: 'text-blue-600' },
    { name: 'Novice',       color: 'text-slate-600' },
];

/**
 * 【関数の役割】 その☆のティア階段の総スパン（Legend − Novice 1、%ポイント）を返す。
 *
 * 旧実装では制御点表からの幾何補間で求めていたが、現在は {@link BLOCK_BOUND_RATES} の
 * 両端の差そのもの。☆11.0 で約 10.4pt、☆13.1 で約 30.0pt と、難しい譜面ほど広くなる。
 *
 * @param informalRank 非公式ランク文字列
 * @returns            スパン（%ポイント）。対応範囲外は 0。
 */
export function getFolderRankOffsetMax(informalRank: string | undefined): number {
    const bounds = folderBlockBounds(informalRank);
    return bounds ? bounds[0] - bounds[bounds.length - 1] : 0;
}

/**
 * フォルダランクの定義表。
 *
 * 並び順: Legend → Mythic V → … → Mythic I → Ancient V → … → Novice I（計 51 件）。
 * **この配列の添字がそのまま {@link getFolderRankThresholdRateAt} の index になる**
 * （0 = Legend、5 = Mythic 1、10 = Ancient 1、…、50 = Novice 1）。
 *
 * 閾値そのものは☆ごとに {@link BLOCK_BOUND_RATES} から引くため、ここには持たない。
 */
export const FOLDER_RANK_DEFS: { name: string; tier?: number; color: string }[] = [
    { name: 'Legend', color: 'text-amber-500 font-bold' },
    ...FOLDER_RANK_BLOCKS.flatMap(block =>
        Array.from({ length: TIERS_PER_RANK_BLOCK }, (_, tierWithinBlockIdx) => ({
            name: block.name,
            tier: TIERS_PER_RANK_BLOCK - tierWithinBlockIdx, // 5,4,3,2,1
            color: block.color,
        }))
    ),
];

/**
 * 【関数の役割】 {@link FOLDER_RANK_DEFS} の添字 index（0 = Legend 〜 50 = Novice 1）に対応する
 * 必要 score rate を返す。
 *
 * ブロック境界（index が 5 の倍数）は {@link BLOCK_BOUND_RATES} の実測値そのもの。
 * ブロック内のサブティアは、上の境界から下の境界までをレート空間で 5 等分した位置。
 *
 * @param index        FOLDER_RANK_DEFS の添字
 * @param informalRank 非公式ランク文字列
 * @returns            必要 score rate（%）。対応範囲外・添字不正は 0。
 */
export function getFolderRankThresholdRateAt(index: number, informalRank: string | undefined): number {
    const bounds = folderBlockBounds(informalRank);
    if (!bounds || index < 0 || index >= FOLDER_RANK_DEFS.length) return 0;
    if (index === 0) return bounds[0];

    const blockIdx = Math.floor((index - 1) / TIERS_PER_RANK_BLOCK);
    const withinBlock = (index - 1) % TIERS_PER_RANK_BLOCK; // 0 = tier V … 4 = tier I
    const upper = bounds[blockIdx];
    const lower = bounds[blockIdx + 1];
    return upper - (upper - lower) * (withinBlock + 1) / TIERS_PER_RANK_BLOCK;
}

/**
 * 【関数の役割】 score rate を「ティア階段上の連続位置」に変換する（0 = Legend、50 = Novice 1）。
 *
 * ティアの整数添字を線形補間で埋めた値なので、☆をまたいで並べてもティア順になり、
 * 同じティアの中では次のティアに近いものが小さい値になる。並び替え専用。
 * Novice 1 に届かない（= Beginner）場合と対応範囲外は {@link Number.POSITIVE_INFINITY}。
 *
 * @param scoreRate    その譜面の score rate（%）
 * @param informalRank 非公式ランク文字列
 */
export function getFolderRankIndexByRate(scoreRate: number, informalRank: string | undefined): number {
    const bounds = folderBlockBounds(informalRank);
    if (!bounds || !(scoreRate > 0)) return Number.POSITIVE_INFINITY;
    if (scoreRate >= bounds[0]) return 0;

    for (let k = 0; k < bounds.length - 1; k++) {
        const upper = bounds[k];
        const lower = bounds[k + 1];
        if (scoreRate >= lower) {
            const span = upper - lower;
            const t = span > 0 ? (upper - scoreRate) / span : 1;
            return (k + t) * TIERS_PER_RANK_BLOCK;
        }
    }
    return Number.POSITIVE_INFINITY;
}

/**
 * 【関数の役割】 フォルダ（= 同じ非公式ランクの曲群）ごとのランクを
 * 総合 BEAT-PT から判定する。
 *
 * 手順:
 *  - Legend 判定用 score rate を {@link getFolderLegendRate} で取得
 *  - {@link FOLDER_RANK_DEFS} を先頭から見て、各ランクの threshold point と比較
 *  - threshold point = 1 譜面要求 BEAT-PT × 曲数
 *  - 最初に totalPoints ≧ threshold を満たしたランクを返す
 *
 * @param totalPoints  フォルダ内総合 BEAT-PT
 * @param informalRank 非公式ランク（例: '12.1'）
 * @param songCount    フォルダ内の曲数
 */
export function getFolderRankInfo(totalPoints: number, informalRank: string | undefined, songCount: number): RankInfo {
    if (!informalRank || songCount <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    if (getFolderLegendRate(informalRank) <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    for (let i = 0; i < FOLDER_RANK_DEFS.length; i++) {
        const thresholdRate = getFolderRankThresholdRateAt(i, informalRank);
        // BEAT-PT 換算では C 帯（66.666%）以下は calculatePoints が 0 を返して順序が壊れるため、
        // そこから下のティアはこのフォルダには存在しないものとして扱う。
        if (thresholdRate <= SCORE_RATE_TIER_C_MIN) break;
        const thresholdPoints = calculatePoints(thresholdRate, informalRank) * songCount;
        if (totalPoints >= thresholdPoints) {
            const def = FOLDER_RANK_DEFS[i];
            return {
                name: def.name,
                tier: def.tier,
                minPoints: thresholdPoints,
                color: def.color
            };
        }
    }

    // どのランクにも届かなかった場合は Beginner
    return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };
}

/**
 * 【関数の役割】 次のフォルダランクまでの進捗率と次ランク情報を返す。
 *
 * 閾値は score rate ベースで定められており、1 譜面あたりの要求 BEAT-PT × 曲数 が
 * そのランクの合計閾値になる。
 *
 * @param totalPoints  フォルダ内総合 BEAT-PT
 * @param informalRank 非公式ランク
 * @param songCount    フォルダ内曲数
 */
export function getNextFolderRankInfo(totalPoints: number, informalRank: string | undefined, songCount: number): { nextRank?: RankInfo; progress: number } {
    if (!informalRank || songCount <= 0) return { progress: 0 };

    if (getFolderLegendRate(informalRank) <= 0) return { progress: 0 };

    // 手順1: ランクごとの閾値ポイント配列を先に組み立てる。
    const thresholds: { def: typeof FOLDER_RANK_DEFS[0]; points: number }[] = [];
    for (let i = 0; i < FOLDER_RANK_DEFS.length; i++) {
        const thresholdRate = getFolderRankThresholdRateAt(i, informalRank);
        if (thresholdRate <= SCORE_RATE_TIER_C_MIN) break;
        thresholds.push({ def: FOLDER_RANK_DEFS[i], points: calculatePoints(thresholdRate, informalRank) * songCount });
    }

    if (thresholds.length === 0) return { progress: 0 };

    // 手順2: 現在ランクの index を特定（高い順に先頭から探す）。
    let currentIdx = -1;
    for (let i = 0; i < thresholds.length; i++) {
        if (totalPoints >= thresholds[i].points) {
            currentIdx = i;
            break;
        }
    }

    // 既に Legend（配列先頭）に到達しているなら 100%
    if (currentIdx === 0) return { progress: PROGRESS_PCT_MAX };

    // 手順3: 次ランクの index を決定。
    //   - まだどのランクにも届いていない場合（currentIdx=-1）は配列最下位ランクが目標。
    //   - それ以外は 1 つ上のランク。
    const nextIdx = currentIdx === -1 ? thresholds.length - 1 : currentIdx - 1;
    const currentThreshold = currentIdx === -1 ? 0 : thresholds[currentIdx].points;
    const nextThreshold = thresholds[nextIdx].points;
    const nextDef = thresholds[nextIdx].def;

    const nextRank: RankInfo = {
        name: nextDef.name,
        tier: nextDef.tier,
        minPoints: nextThreshold,
        color: nextDef.color
    };

    const range = nextThreshold - currentThreshold;
    const progress = range > 0
        ? Math.min(PROGRESS_PCT_MAX, Math.max(0, (totalPoints - currentThreshold) / range * PROGRESS_PCT_MAX))
        : 0;

    return { nextRank, progress };
}

/**
 * 【関数の役割】 フォルダ内の「平均 score rate」だけでランクを判定する別系統の関数。
 *
 * 総合 BEAT-PT を使わないので、曲数の影響を受けずに純粋な精度で判定できる。
 *
 * @param averageRate  フォルダ内スコアの平均 rate（%）
 * @param informalRank 非公式ランク
 */
export function getFolderRankInfoByRate(averageRate: number, informalRank: string | undefined): RankInfo {
    if (!informalRank || averageRate <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };
    if (getFolderLegendRate(informalRank) <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    // レート判定では C 帯での打ち切りを行わない。☆12.9 以上は Novice 1 の実測値が
    // 66.666% を下回るが、そこは実際に人が居る領域なのでティアを与える。
    for (let i = 0; i < FOLDER_RANK_DEFS.length; i++) {
        const thresholdRate = getFolderRankThresholdRateAt(i, informalRank);
        if (averageRate >= thresholdRate) {
            const def = FOLDER_RANK_DEFS[i];
            return { name: def.name, tier: def.tier, minPoints: thresholdRate, color: def.color };
        }
    }

    return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };
}

/**
 * 【関数の役割】 指定した目標フォルダランク (name + tier) に到達するために必要な
 * score rate（%）を、対象譜面の非公式ランクに合わせて返す。
 *
 * 各フォルダ（非公式ランク）ごとに Legend rate と offsetScale が異なるため、
 * 同じ「Master III」でも要求 rate は譜面によって変わる。プロフィールの単曲ティア
 * 目標設定で「あと何点」を算出する際に使う。
 *
 * @param name         目標ランク名（例: 'Master', 'Legend'）
 * @param tier         目標サブティア（1〜5）。Legend は undefined。
 * @param informalRank 対象譜面の非公式ランク
 * @returns            到達に必要な score rate（%）。判定不能時は 0。
 */
export function getFolderRankThresholdRate(name: string, tier: number | undefined, informalRank: string | undefined): number {
    const index = FOLDER_RANK_DEFS.findIndex(d => d.name === name && d.tier === tier);
    if (index < 0) return 0;
    return getFolderRankThresholdRateAt(index, informalRank);
}

/**
 * 【関数の役割】 平均 score rate ベースでの「次ランクまでの進捗」を返す。
 *
 * 返される nextRank には通常の {@link RankInfo} に加えて `minRate`（次ランクの要求 rate）が付く。
 *
 * @param averageRate  フォルダ内スコアの平均 rate（%）
 * @param informalRank 非公式ランク
 */
export function getNextFolderRankInfoByRate(averageRate: number, informalRank: string | undefined): { nextRank?: RankInfo & { minRate: number }; progress: number } {
    if (!informalRank || averageRate <= 0) return { progress: 0 };

    if (getFolderLegendRate(informalRank) <= 0) return { progress: 0 };

    // レート判定なので C 帯での打ち切りはしない（getFolderRankInfoByRate と揃える）。
    const thresholds: { def: typeof FOLDER_RANK_DEFS[0]; rate: number }[] = [];
    for (let i = 0; i < FOLDER_RANK_DEFS.length; i++) {
        thresholds.push({ def: FOLDER_RANK_DEFS[i], rate: getFolderRankThresholdRateAt(i, informalRank) });
    }

    if (thresholds.length === 0) return { progress: 0 };

    let currentIdx = -1;
    for (let i = 0; i < thresholds.length; i++) {
        if (averageRate >= thresholds[i].rate) {
            currentIdx = i;
            break;
        }
    }

    if (currentIdx === 0) return { progress: PROGRESS_PCT_MAX };

    const nextIdx = currentIdx === -1 ? thresholds.length - 1 : currentIdx - 1;
    // まだ Novice 1 にも届いていない場合の進捗の起点。ティア階段の下にもう 1 ブロック分
    // （Intermediate 1 − Novice 1 と同じ幅）の助走区間があるものとして測る。
    const bottomRate = thresholds[thresholds.length - 1].rate;
    const floorRate = Math.max(0, bottomRate - (thresholds[thresholds.length - 1 - TIERS_PER_RANK_BLOCK].rate - bottomRate));
    const currentRate = currentIdx === -1 ? floorRate : thresholds[currentIdx].rate;
    const nextRate = thresholds[nextIdx].rate;
    const nextDef = thresholds[nextIdx].def;

    const nextRank = {
        name: nextDef.name,
        tier: nextDef.tier,
        minPoints: nextRate,
        minRate: nextRate,
        color: nextDef.color,
    };

    const range = nextRate - currentRate;
    const progress = range > 0
        ? Math.min(PROGRESS_PCT_MAX, Math.max(0, (averageRate - currentRate) / range * PROGRESS_PCT_MAX))
        : 0;

    return { nextRank, progress };
}
