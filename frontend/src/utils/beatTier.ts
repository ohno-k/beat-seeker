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
const SCORE_RATE_TIER_C_MIN = 66.666;

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
 * 【Folder Legend】 ☆値 → Legend 到達 score rate（%）の制御点表（区間線形補間）。
 *
 * 2026-06 データ再調整: リポジトリ同梱の歴代トップランカーデータ
 * （top-rankers-data/0/ = 歴代・全国＋47都道府県）から各フォルダの
 * 「歴代全国レコードの score rate 分布」を集計し、その下位25%点
 * （= 歴代レコードの約75%が到達できる水準）に Legend を揃えた。
 * 旧 t^4 カーブは ☆11.2〜12.1 帯で歴代レコードの中央値すら下回って
 * おり（例: ☆11.8 要求 99.88% vs 歴代中央値 99.71%）、「人類未到達の
 * Legend」が多数発生していたための是正。☆12.5・☆12.9 は旧カーブと
 * 同値で再合流させ、☆13.0 のみ歴代中央値 95.54% が旧閾値 95.80% に
 * 届かなかったため 95.30%（歴代p25）へ引き下げ。
 */
const LEGEND_RATE_CONTROL: readonly { v: number; rate: number }[] = [
    { v: 11.0, rate: 99.95 },
    { v: 11.5, rate: 99.79 },
    { v: 12.0, rate: 99.46 },
    { v: 12.5, rate: 98.66 }, // 旧 t^4 カーブと同値（ここで旧式と再合流）
    { v: 12.9, rate: 96.58 }, // 旧 t^4 カーブと同値（維持）
    { v: 13.0, rate: 95.30 }, // 歴代p25。旧 95.80 では歴代中央値が未到達だった
];

/**
 * 【Folder Legend】 Legend 判定 score rate の対応範囲下限（非公式ランク）。
 */
const LEGEND_RANK_MIN = 11.0;

/**
 * 【Folder Legend】 Legend 判定 score rate の対応範囲上限（非公式ランク）。
 */
const LEGEND_RANK_MAX = 13.0;

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
 * 非公式ランク別の Weight テーブル。`"11.0"` から `"13.0"` までを 0.1 刻みで保持する。
 *
 * 構築ルール:
 *  - 開始値: 11.0 → 145（ただし下のループは 0..20 のうち初回で 11.0 を登録するため、実際の 11.0 の値は 145）
 *  - ステップ: 11.0〜12.4 は +2、12.5〜13.0 は +3（= 高難度ほど加速する傾斜）
 *
 * NOTE: マジックナンバーが集中しているため、バランス調整時はここを編集する。
 */
export const WEIGHTS: Record<string, number> = {};
let weight = 145;
for (let i = 0; i <= 20; i++) {
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
 * {@link LEGEND_RATE_CONTROL}（歴代トップランカーデータの下位25%点に
 * フィットさせた制御点表）を区間線形補間して返す。
 *  - 制御点間は線形なので隣接難易度間の落差がなめらか
 *  - 歴代レコードが原則 Legend に到達できる水準を全フォルダで保証
 *
 * @param informalRank 非公式ランク文字列
 * @returns            Legend 判定用の score rate（%）。範囲外は 0。
 */
export function getFolderLegendRate(informalRank: string | undefined): number {
    if (!informalRank) return 0;
    const match = informalRank.match(/(\d+\.\d+)/);
    const rankValue = match ? parseFloat(match[1]) : 0;
    // 対応範囲は ☆11.0〜☆13.0 のみ
    if (rankValue < LEGEND_RANK_MIN || rankValue > LEGEND_RANK_MAX) return 0;

    const ctrl = LEGEND_RATE_CONTROL;
    if (rankValue <= ctrl[0].v) return ctrl[0].rate;
    if (rankValue >= ctrl[ctrl.length - 1].v) return ctrl[ctrl.length - 1].rate;
    for (let i = 0; i < ctrl.length - 1; i++) {
        const lo = ctrl[i];
        const hi = ctrl[i + 1];
        if (rankValue >= lo.v && rankValue <= hi.v) {
            const t = (rankValue - lo.v) / (hi.v - lo.v);
            return lo.rate + t * (hi.rate - lo.rate);
        }
    }
    return ctrl[ctrl.length - 1].rate;
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
    { name: 'Legend', minPoints: 18000, color: 'text-amber-500 font-black' },

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
 * 【KENBAN/SARA-TIER】 譜面の皿率がこの値以上なら完全に皿曲扱い、0% なら完全に鍵盤曲扱い。
 * 間は線形で按分する。
 *
 * IIDX の高難度 ANOTHER 譜面はほぼ全てが皿率 5〜15% 帯に分布しており、閾値が低すぎると
 * 「ほんの少し皿が混ざっただけの鍵盤譜面」も KENBAN 評価が大きく削られてしまう。
 * 100 人規模の上位プレイヤー実測で T=30 のとき KENBAN/BEAT 比が 0.90 前後で安定したため、
 * これを採用している。
 */
export const SCRATCH_FULL_THRESHOLD_PCT = 30;

/**
 * 【KENBAN-TIER pt 補正倍率】 独立 top-100 の生 KENBAN-PT に掛けて BEAT-PT 同等のスケールに揃える係数。
 *
 * 鍵盤特化プレイヤーが BEAT 以上のティアに、皿特化プレイヤーが大きく下がる設計。
 */
export const KENBAN_PT_MULTIPLIER = 1.125;

/**
 * 【SARA-TIER pt 補正倍率】 SARA 側は皿曲の絶対数が少ないため生 ratio が低めに出る。
 *
 * 皿特化プレイヤーは BEAT 以上のティアに、鍵盤特化プレイヤーは大きく下がる設計。
 */
export const SARA_PT_MULTIPLIER = 1.5;

/**
 * 【関数の役割】 譜面の皿率から鍵盤側の貢献重み（0〜1）を返す。
 *
 * scratchPct = 0 → 1.0（純鍵盤）、scratchPct ≥ 20% → 0（皿曲扱い）。
 * scratchPct が null/undefined の譜面（傾向プロファイル未生成）は 0 を返し、
 * KENBAN-TIER の集計対象から除外される。
 *
 * @param scratchPct 皿率（%）。null/undefined は不明扱い
 * @returns          0〜1 の重み
 */
export function getKenbanWeight(scratchPct: number | null | undefined): number {
    if (scratchPct == null) return 0;
    const w = 1 - scratchPct / SCRATCH_FULL_THRESHOLD_PCT;
    return Math.max(0, Math.min(1, w));
}

/**
 * 【関数の役割】 譜面の皿率から皿側の貢献重み（0〜1）を返す。
 *
 * {@link getKenbanWeight} と対をなす。scratchPct = 0 → 0（純鍵盤）、≥ 20% → 1（皿曲）。
 *
 * @param scratchPct 皿率（%）。null/undefined は不明扱い（0 を返す）
 * @returns          0〜1 の重み
 */
export function getSaraWeight(scratchPct: number | null | undefined): number {
    if (scratchPct == null) return 0;
    const w = scratchPct / SCRATCH_FULL_THRESHOLD_PCT;
    return Math.max(0, Math.min(1, w));
}

/**
 * 譜面タイプの分類（皿率ベース）。UI のバッジ表示で使う。
 *
 *  - `kenban`  : 皿率 < 5%
 *  - `balance` : 皿率 5〜15%
 *  - `sara`    : 皿率 ≥ 15%
 *  - `unknown` : 傾向プロファイル未生成（皿率不明）
 */
export type ChartType = 'kenban' | 'balance' | 'sara' | 'unknown';

/** 譜面タイプ分類の境界値。`getChartType` の判定に使う。 */
export const CHART_TYPE_THRESHOLDS = { kenbanMax: 5, saraMin: 15 } as const;

/**
 * 【関数の役割】 譜面の皿率からタイプ分類を返す。バッジ表示・フィルタ等で使用する。
 *
 * @param scratchPct 皿率（%）。null/undefined は `unknown`
 */
export function getChartType(scratchPct: number | null | undefined): ChartType {
    if (scratchPct == null) return 'unknown';
    if (scratchPct < CHART_TYPE_THRESHOLDS.kenbanMax) return 'kenban';
    if (scratchPct < CHART_TYPE_THRESHOLDS.saraMin)   return 'balance';
    return 'sara';
}

/**
 * 1 譜面分の貢献度計算結果（KENBAN/SARA-TIER の内訳表示用）。
 *
 * `contribution = beatTierPoints × weight` で、KENBAN/SARA-TIER の合計に算入される値。
 */
export interface SideContribution {
    title: string;
    difficultyName: string;
    informalRank: string | undefined;
    scoreRate: number;
    beatTierPoints: number;
    scratchPct: number | undefined;
    weight: number;
    contribution: number;
}

/**
 * 【関数の役割】 全プレイ譜面について「鍵盤側 or 皿側」の貢献 pt を計算し、
 * 上位 N 譜面（既定 100）の合計を返す。
 *
 * 計算手順:
 *  1. 譜面ごとに `beatTierPoints × kenban_w(scratchPct)` または `× sara_w(scratchPct)` を算出
 *  2. 0 を超える譜面のみ降順ソート
 *  3. 上位 N（既定 100）を合計
 *  4. 小数第 1 位まで丸めて返す
 *
 * 鍵盤側 / 皿側を **独立に** top-N するため、それぞれの「最大限の実力」を測れる。
 * （BEAT-TIER の top-100 集合に縛られないので、片方に偏った譜面集合でも下限を引かない）
 *
 * @param scores      `{ title, difficultyName, beatTierPoints }` を持つ全譜面レコード
 * @param scratchMap  `"<title>|<difficultyName>"` → 皿率(%) の Map
 * @param side        `'kenban'` で鍵盤側集計、`'sara'` で皿側集計
 * @returns           総合ポイント（小数第 1 位まで）
 */
export const calculateWeightedTotalPoints = (
    scores: { title: string; difficultyName: string; beatTierPoints: number }[],
    scratchMap: Map<string, number>,
    side: 'kenban' | 'sara'
): number => {
    const getWeightFn = side === 'kenban' ? getKenbanWeight : getSaraWeight;
    const multiplier  = side === 'kenban' ? KENBAN_PT_MULTIPLIER : SARA_PT_MULTIPLIER;
    const weighted = scores
        .filter(s => s.beatTierPoints > 0)
        .map(s => {
            const pct = scratchMap.get(`${s.title}|${s.difficultyName}`);
            return s.beatTierPoints * getWeightFn(pct);
        })
        .filter(pt => pt > 0)
        .sort((a, b) => b - a);
    const top = weighted.slice(0, TOP_CHART_LIMIT);
    const sum = top.reduce((acc, pt) => acc + pt, 0) * multiplier;
    return Math.round(sum * ROUND_TO_ONE_DECIMAL) / ROUND_TO_ONE_DECIMAL;
};

/**
 * 【関数の役割】 KENBAN/SARA-TIER の内訳モーダル表示用に、貢献度上位 N 譜面を
 * `SideContribution[]` として返す。
 *
 * {@link calculateWeightedTotalPoints} と同じスコアリングで上位 N を切り出すが、
 * こちらは合計ではなく譜面メタ情報込みの配列を返す。
 *
 * @param scores      全譜面レコード（title/difficultyName/beatTierPoints/informalRank/scoreRate を含む）
 * @param scratchMap  `"<title>|<difficultyName>"` → 皿率(%) の Map
 * @param side        `'kenban'` で鍵盤側、`'sara'` で皿側
 * @param limit       上位件数（既定 100）
 * @returns           貢献 pt 降順に並んだ配列
 */
export const buildTopSideContributions = (
    scores: { title: string; difficultyName: string; beatTierPoints: number; informalRank: string | undefined; scoreRate: number }[],
    scratchMap: Map<string, number>,
    side: 'kenban' | 'sara',
    limit: number = TOP_CHART_LIMIT
): SideContribution[] => {
    const getWeightFn = side === 'kenban' ? getKenbanWeight : getSaraWeight;
    const multiplier  = side === 'kenban' ? KENBAN_PT_MULTIPLIER : SARA_PT_MULTIPLIER;
    return scores
        .filter(s => s.beatTierPoints > 0)
        .map<SideContribution>(s => {
            const pct = scratchMap.get(`${s.title}|${s.difficultyName}`);
            const weight = getWeightFn(pct);
            // contribution には BEAT 比 1 補正倍率を適用する（モーダル列合計 = カード表示 pt と一致させるため）
            return {
                title: s.title,
                difficultyName: s.difficultyName,
                informalRank: s.informalRank,
                scoreRate: s.scoreRate,
                beatTierPoints: s.beatTierPoints,
                scratchPct: pct,
                weight,
                contribution: s.beatTierPoints * weight * multiplier,
            };
        })
        .filter(c => c.contribution > 0)
        .sort((a, b) => b.contribution - a.contribution)
        .slice(0, limit);
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
 * Rate-Tier の全ランク定義。
 *
 * 閾値は 25 から始まり 2 倍ずつ伸びる等比数列: 25 → 50 → 100 → ... → 25600（理論最大）。
 * BEAT-Tier が「上位 100 譜面の累積」なのに対し、こちらは 1 譜面の score rate から
 * 指数的に伸びるポイントを与える体系。
 */
export const RATE_TIER_RANKS: RankInfo[] = [
    { name: 'Legend', minPoints: 25600, color: 'text-amber-500 font-black' },

    ...generateTieredRanks('Mythic', 12800, 25600, 'text-purple-600'),
    ...generateTieredRanks('Ancient', 6400, 12800, 'text-indigo-600'),
    ...generateTieredRanks('Master', 3200, 6400, 'text-red-600'),
    ...generateTieredRanks('Elite', 1600, 3200, 'text-orange-600'),
    ...generateTieredRanks('Commander', 800, 1600, 'text-yellow-700'),
    ...generateTieredRanks('Veteran', 400, 800, 'text-emerald-600'),
    ...generateTieredRanks('Expert', 200, 400, 'text-teal-600'),
    ...generateTieredRanks('Advanced', 100, 200, 'text-cyan-600'),
    ...generateTieredRanks('Intermediate', 50, 100, 'text-blue-600'),
    ...generateTieredRanks('Novice', 25, 50, 'text-slate-600'),

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

/** 【FOLDER 用】 サブランク総数 = 10 ブロック × 5 ティア = 50。 */
const FOLDER_SUB_RANK_COUNT = FOLDER_RANK_BLOCKS.length * TIERS_PER_RANK_BLOCK;

/**
 * 【FOLDER 用】 オフセット生成カーブの指数。
 * offset_norm(i) = 1 − (1 − i/N)^p 形式の累乗カーブを使う（[0, 1] 正規化）。
 *  - p > 1 にすると Legend 側ほど隣接ランク間の差が大きく、Novice 側ほど詰まる。
 *  - p < 1 にすると逆に Novice 側ほど段差が大きく、上位ランクは詰まる。
 *  - p = 0.5 で「高スコア帯ほど 1 点の重みが大きい」感覚に合わせ、Legend 周辺は僅差、
 *    Novice 末端で大きく開く bottom-heavy 配置。
 */
const FOLDER_RANK_OFFSET_POWER = 0.5;

/**
 * 【FOLDER 用】 ☆値 → offsetScale（Legend → Novice 1 の総スパン%）の制御点表。
 *
 * 2026-06 データ再調整: 歴代県別トップデータ（top-rankers-data/0/01〜47）から
 * 各譜面の「47都道府県トップスコアの中央値」を集計し、その中央値が
 * Ancient 1（i=10, offset_norm≈0.1056）に一致するよう各☆のスパンを逆算した。
 * これにより「Legend=歴代級 / Mythic=全国トップ・強豪県1位級 /
 * Ancient=県1位級」という序列が全フォルダで一貫する
 * （旧テーブルでは県トップ中央値の到達ティアが Ancient 1〜Master 2 まで
 * ☆帯によってばらついていた）。
 * ☆12.9・☆13.0 は前回調整（☆12.9 引き締め・☆13.0 緩和）の意図を尊重して旧値を維持。
 *
 * バランス調整時はこのテーブルを編集する。
 */
const FOLDER_OFFSET_CONTROL: readonly { v: number; scale: number }[] = [
    { v: 11.0, scale: 4.10 },  // 県トップ中央値 99.58% → Ancient 1 アンカー
    { v: 11.5, scale: 9.20 },  // 同 98.77%
    { v: 12.0, scale: 14.85 }, // 同 97.82%
    { v: 12.5, scale: 22.50 }, // 同 96.16%
    { v: 12.9, scale: 28.00 }, // 旧値維持（前回の引き締め意図を尊重）
    { v: 13.0, scale: 35.00 }, // 旧値維持（高難度の厳しさ緩和）
];

/**
 * 【FOLDER 用】 全難易度のティア閾値を一律に「~1 サブティア上」へシフトする全体ブースト係数。
 *
 * 効果:
 *  - scale × BOOST により、同じスコアに対する T_norm（=gap/scale）が縮み、結果としてより高いティアに割り振られる。
 *  - 1.00 = シフト無し。1.05 で中央〜下位帯（Master〜Novice）が約 1 サブティア上、上位帯（Mythic）は約 0.2 サブティア上にシフト。
 *  - offset_norm が bottom-heavy（power=0.5）のため、ブースト効果は上位ほど小さく下位ほど大きくなる非対称特性を持つ。
 *
 * チューニング目安:
 *  - 0.5 サブティア上げ: 1.025
 *  - 1.0 サブティア上げ: 1.05  ← 採用値
 *  - 1.5 サブティア上げ: 1.07
 *  - 2.0 サブティア上げ: 1.10
 */
const FOLDER_OFFSET_GLOBAL_BOOST = 1.05;

/**
 * 【関数の役割】 難易度に応じた最大 offset スパン(%)を制御点表からの**区間幾何補間**で返し、
 * 全体ブースト係数 {@link FOLDER_OFFSET_GLOBAL_BOOST} を乗じて返す。
 * 区間ごとに `lo.scale × (hi.scale / lo.scale)^t` で結ぶ。[☆11.0, ☆12.2] は旧式
 * （LOW=6, HIGH=28 の幾何補間）と完全一致、[☆12.2, ☆12.9] は ☆12.7 を中心に bell 状に
 * 圧縮、☆13.0 端は独立調整値。BOOST はその上に一律に作用する。
 */
export function getFolderRankOffsetMax(informalRank: string | undefined): number {
    const last = FOLDER_OFFSET_CONTROL[FOLDER_OFFSET_CONTROL.length - 1];
    let scale: number;
    if (!informalRank) {
        scale = last.scale;
    } else {
        const m = informalRank.match(/(\d+\.\d+)/);
        const v = m ? parseFloat(m[1]) : LEGEND_RANK_MAX;
        if (v <= FOLDER_OFFSET_CONTROL[0].v) {
            scale = FOLDER_OFFSET_CONTROL[0].scale;
        } else if (v >= last.v) {
            scale = last.scale;
        } else {
            scale = last.scale;
            for (let i = 0; i < FOLDER_OFFSET_CONTROL.length - 1; i++) {
                const lo = FOLDER_OFFSET_CONTROL[i];
                const hi = FOLDER_OFFSET_CONTROL[i + 1];
                if (v >= lo.v && v <= hi.v) {
                    const t = (v - lo.v) / (hi.v - lo.v);
                    scale = lo.scale * Math.pow(hi.scale / lo.scale, t);
                    break;
                }
            }
        }
    }
    return scale * FOLDER_OFFSET_GLOBAL_BOOST;
}

/**
 * 【関数の役割】 サブランク順位 i (1〜50) に対応する正規化 offset [0, 1] を返す。
 * 実際の % offset は consumer 側で `getFolderRankOffsetMax(informalRank)` を掛けて算出する。
 * i=0 は Legend で常に 0。
 */
function computeFolderRankNormalizedOffset(i: number): number {
    if (i <= 0) return 0;
    const t = i / FOLDER_SUB_RANK_COUNT;
    return 1 - Math.pow(1 - t, FOLDER_RANK_OFFSET_POWER);
}

/**
 * フォルダランクのオフセット定義表（自動生成）。
 *
 * `offset` は **正規化値 [0, 1]** を保持する（0=Legend、1=Novice 1）。
 * 実際の score rate 閾値は `legendRate − offset × getFolderRankOffsetMax(informalRank)`。
 * このスケール係数を ☆11.0 では小、☆13.0 では大に取ることで、低難度では上位ランクが密集し、
 * 高難度では Legend と Mythic 1 が大きく開く設計が成立する。
 *
 * 並び順: Legend → Mythic V → Mythic I → Ancient V → ... → Novice I の順。
 * Legend のベース rate は {@link getFolderLegendRate} で決まる。
 */
export const FOLDER_RANK_DEFS: { offset: number; name: string; tier?: number; color: string }[] = [
    { offset: 0, name: 'Legend', color: 'text-amber-500 font-black' },
    ...FOLDER_RANK_BLOCKS.flatMap((block, blockIdx) =>
        Array.from({ length: TIERS_PER_RANK_BLOCK }, (_, tierWithinBlockIdx) => {
            const i = blockIdx * TIERS_PER_RANK_BLOCK + tierWithinBlockIdx + 1;
            const tier = TIERS_PER_RANK_BLOCK - tierWithinBlockIdx; // 5,4,3,2,1
            return {
                offset: computeFolderRankNormalizedOffset(i),
                name: block.name,
                tier,
                color: block.color,
            };
        })
    ),
];

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

    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    const offsetScale = getFolderRankOffsetMax(informalRank);
    for (const def of FOLDER_RANK_DEFS) {
        const thresholdRate = legendRate - def.offset * offsetScale;
        // score rate が C 帯（66.666%）以下まで落ちた時点で以降は Beginner 扱い
        if (thresholdRate <= SCORE_RATE_TIER_C_MIN) break;
        const thresholdPoints = calculatePoints(thresholdRate, informalRank) * songCount;
        if (totalPoints >= thresholdPoints) {
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

    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return { progress: 0 };

    // 手順1: ランクごとの閾値ポイント配列を先に組み立てる。
    const offsetScale = getFolderRankOffsetMax(informalRank);
    const thresholds: { def: typeof FOLDER_RANK_DEFS[0]; points: number }[] = [];
    for (const def of FOLDER_RANK_DEFS) {
        const thresholdRate = legendRate - def.offset * offsetScale;
        if (thresholdRate <= SCORE_RATE_TIER_C_MIN) break;
        thresholds.push({ def, points: calculatePoints(thresholdRate, informalRank) * songCount });
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

    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };

    const offsetScale = getFolderRankOffsetMax(informalRank);
    for (const def of FOLDER_RANK_DEFS) {
        const thresholdRate = legendRate - def.offset * offsetScale;
        if (thresholdRate <= SCORE_RATE_TIER_C_MIN) break;
        if (averageRate >= thresholdRate) {
            return { name: def.name, tier: def.tier, minPoints: thresholdRate, color: def.color };
        }
    }

    return { name: 'Beginner', minPoints: 0, color: 'text-slate-400' };
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

    const legendRate = getFolderLegendRate(informalRank);
    if (legendRate <= 0) return { progress: 0 };

    const offsetScale = getFolderRankOffsetMax(informalRank);
    const thresholds: { def: typeof FOLDER_RANK_DEFS[0]; rate: number }[] = [];
    for (const def of FOLDER_RANK_DEFS) {
        const thresholdRate = legendRate - def.offset * offsetScale;
        if (thresholdRate <= SCORE_RATE_TIER_C_MIN) break;
        thresholds.push({ def, rate: thresholdRate });
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
    const currentRate = currentIdx === -1 ? SCORE_RATE_TIER_C_MIN : thresholds[currentIdx].rate;
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
