/**
 * 【ユーティリティの役割】 「大台」タブ用のマイルストーンライン計算。
 *
 * IIDX コミュニティでは、AA† の 3600 のように EX スコアの節目ライン（100 点刻み等）を
 * 「大台」と呼ぶ。このモジュールは、譜面の理論値（MAX = notes × 2）から
 * 「AAA ボーダー 〜 MAX」の範囲にある大台ラインを算出する。
 *
 * ルール:
 *  - AAA ボーダー = ceil(maxScore × 16 / 18)（スコアレート 8/9 ≈ 88.89% 以上）
 *  - AAA ボーダー以上・MAX 以下の 100 の倍数を列挙する
 *  - 100 点刻みだとライン数が 4 本未満になる譜面（低ノーツ曲）は 50 点刻みにフォールバック
 *
 * 例: AA(A) notes=1834, maxScore=3668 → AAA=3261 → [3300, 3400, 3500, 3600]（3600 を含む）
 */

/**
 * 指定譜面の大台ライン一覧を昇順で返す。
 *
 * @param maxScore 理論値（notes × 2）。0 以下なら空配列を返す。
 * @returns 大台ラインのスコア配列（昇順）
 */
export function computeMilestoneLines(maxScore: number): number[] {
    if (!maxScore || maxScore <= 0) return [];

    // AAA ボーダー（スコアレート 8/9 以上）。端数は切り上げてラインを AAA 以上に揃える。
    const aaa = Math.ceil((maxScore * 16) / 18);

    // step 刻みで [aaa, maxScore] 内の倍数を列挙する。
    const build = (step: number): number[] => {
        const lines: number[] = [];
        for (let s = Math.ceil(aaa / step) * step; s <= maxScore; s += step) {
            lines.push(s);
        }
        return lines;
    };

    const lines100 = build(100);
    // 100 点刻みで 4 本以上あればそれを採用。4 本未満なら 50 点刻みで細分化する
    // （50 点刻みでも 4 本未満になり得るが、その場合はそのまま受容する）。
    return lines100.length >= 4 ? lines100 : build(50);
}
