/**
 * 【ユーティリティの役割】 IIDX の作品バージョンに関する定数と、
 * スコア CSV から作品バージョンを自動判定するためのラベル対応表を提供する。
 *
 * ■ なぜ CSV から作品バージョンを判定できるのか
 * 公式のスコア CSV には「バージョン」列があるが、これは *その CSV を出力した作品* ではなく
 * *各楽曲の初出作品名*（例: "1st&substream", "EPOLIS"）である。
 * 30 RESIDENT の CSV に 31 EPOLIS 初出の曲が載ることは原理的にあり得ないので、
 *
 *     CSV 内に出現する初出バージョンの最大値 ＝ その CSV を出力した作品
 *
 * とみなしている。
 *
 * ■ 判定の限界（把握したうえで許容している）
 * 公式 CSV に載るのは *プレーした曲だけ* で、未プレー曲は行ごと出力されない。
 * したがって上の等式は厳密には「以上」であって「＝」ではなく、次の 2 点に注意がいる。
 *  - 作品が歯抜けになるのは正常。ある作品の曲を 1 曲もプレーしていなければその作品名は
 *    一度も現れない。「間に空の作品があるから不完全な CSV」という判定はしてはいけない。
 *  - 現行作の曲を 1 曲もプレーしていないユーザーの CSV は、前作以前と判定され得る。
 *    実際には新曲を 1 曲も踏まないユーザーはまれなので、確認ダイアログ（過去作の取り込みは
 *    必ずユーザーの同意を取る）でカバーする方針にしている。
 *
 * ■ ラベル表が「全 33 作ぶん」必要な理由
 * 対応作品（30〜33）の 4 つだけを持つと、未対応の新作（34 以降）が出たときに
 * 未知のラベルを黙って無視して「最大値 = 33」と誤判定し、新作のスコアを現行作に
 * 混ぜてしまう。表を閉じた集合にしておけば「未知のラベル ＝ 未対応の新作」と断定でき、
 * 安全側（取り込み拒否）に倒せる。
 *
 * 表記は実際の公式 CSV（33 Sparkle Shower）から抽出した確定値。
 * 大文字小文字が不規則（"tricoro" / "copula" は小文字始まり、"Sparkle Shower" は
 * 全大文字ではない）なので、突き合わせは trim + 小文字化して行う。
 *
 * バックエンド側の対応表は {@code backend/.../service/IidxVersions.java}。
 */

/** 現行作のバージョン番号。現行作のスコアは通常の `scores` テーブルで管理される。 */
export const CURRENT_VERSION = 33;

/** 過去作として取り込みを受け付ける下限バージョン。 */
export const MIN_PAST_VERSION = 30;

/** 過去作として取り込みを受け付ける上限バージョン（現行作の 1 つ前）。 */
export const MAX_PAST_VERSION = CURRENT_VERSION - 1;

/**
 * バージョン番号 → 公式 CSV の「バージョン」列に現れるラベル。
 * 1st&substream 〜 現行作までを網羅した閉じた集合。
 */
export const VERSION_LABELS: Record<number, string> = {
    1: '1st&substream',
    2: '2nd style',
    3: '3rd style',
    4: '4th style',
    5: '5th style',
    6: '6th style',
    7: '7th style',
    8: '8th style',
    9: '9th style',
    10: '10th style',
    11: 'IIDX RED',
    12: 'HAPPY SKY',
    13: 'DistorteD',
    14: 'GOLD',
    15: 'DJ TROOPERS',
    16: 'EMPRESS',
    17: 'SIRIUS',
    18: 'Resort Anthem',
    19: 'Lincle',
    20: 'tricoro',
    21: 'SPADA',
    22: 'PENDUAL',
    23: 'copula',
    24: 'SINOBUZ',
    25: 'CANNON BALLERS',
    26: 'Rootage',
    27: 'HEROIC VERSE',
    28: 'BISTROVER',
    29: 'CastHour',
    30: 'RESIDENT',
    31: 'EPOLIS',
    32: 'Pinky Crush',
    33: 'Sparkle Shower',
};

/** ラベル（小文字化・trim 済み） → バージョン番号 の逆引き。判定処理の実体。 */
export const LABEL_TO_VERSION: Record<string, number> = Object.entries(VERSION_LABELS)
    .reduce((acc, [num, label]) => {
        acc[label.trim().toLowerCase()] = Number(num);
        return acc;
    }, {} as Record<string, number>);

/** 表示用の短縮ラベル。歴代テーブルの作品バッジなど、幅が取れない箇所で使う。 */
export const VERSION_SHORT: Record<number, string> = {
    30: 'RE',
    31: 'EP',
    32: 'PC',
    33: 'SS',
};

/**
 * 取り込み対象として UI に提示する作品の一覧（新しい順）。
 * `current` が true の作品は通常のスコア取り込み経路（`/api/scores/upload`）に回る。
 */
export const SUPPORTED_VERSIONS = [33, 32, 31, 30].map(num => ({
    num,
    name: VERSION_LABELS[num],
    short: VERSION_SHORT[num],
    current: num === CURRENT_VERSION,
}));

/** バージョン番号 → 作品名。未知の番号は "IIDX {num}" にフォールバックする。 */
export function versionName(num: number | null | undefined): string {
    if (num == null) return '';
    return VERSION_LABELS[num] ?? `IIDX ${num}`;
}

/** バージョン番号 → 短縮ラベル。未定義なら番号そのものを返す。 */
export function versionShort(num: number | null | undefined): string {
    if (num == null) return '';
    return VERSION_SHORT[num] ?? String(num);
}

/**
 * バージョン番号 → バッジ用の Tailwind クラス。
 * 現行作だけをアプリのプライマリカラー（blue）にして、過去作と視覚的に区別する。
 */
export function versionBadgeClass(num: number | null | undefined): string {
    switch (num) {
        case 33: return 'text-blue-700 bg-blue-100 border-blue-300 dark:text-blue-300 dark:bg-blue-900/40 dark:border-blue-700';
        case 32: return 'text-pink-700 bg-pink-100 border-pink-300 dark:text-pink-300 dark:bg-pink-900/40 dark:border-pink-700';
        case 31: return 'text-amber-700 bg-amber-100 border-amber-300 dark:text-amber-300 dark:bg-amber-900/40 dark:border-amber-700';
        case 30: return 'text-slate-700 bg-slate-100 border-slate-300 dark:text-slate-300 dark:bg-slate-700/60 dark:border-slate-600';
        default: return 'text-slate-600 bg-slate-100 border-slate-300 dark:text-slate-400 dark:bg-slate-700/60 dark:border-slate-600';
    }
}

/**
 * バージョン番号 → グラフ用の塗り色（16 進）。
 *
 * バッジ（{@link versionBadgeClass}）と同系統の色相を保ちつつ、
 * ライト／ダーク両方の背景で「明度帯・彩度下限・P/D/T 色覚での隣接分離・
 * 背景コントラスト 3:1」を満たす 1 セットに揃えてある（検証済み）。
 * RESIDENT だけバッジのグレーではなくティールを充てているのは、
 * 円グラフでは無彩色が「その他・非強調」の意味を持ってしまうため。
 */
export const VERSION_CHART_COLOR: Record<number, string> = {
    33: '#3b82f6',
    32: '#db2777',
    31: '#d97706',
    30: '#0d9488',
};

/** バージョン番号 → グラフ用の塗り色。未定義の作品はスレートにフォールバックする。 */
export function versionChartColor(num: number | null | undefined): string {
    if (num == null) return '#64748b';
    return VERSION_CHART_COLOR[num] ?? '#64748b';
}

/** 過去作テーブルへの保存を許可するバージョンか（現行作は対象外）。 */
export function isSupportedPastVersion(num: number | null | undefined): boolean {
    return num != null && num >= MIN_PAST_VERSION && num <= MAX_PAST_VERSION;
}
