package com.beatseeker.backend.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 team5 大会の「戦種別 (matchKind)」に関する定義を 1 箇所に集約した静的カタログ。
 *
 * <p>予選と決勝で構成が異なる:
 * <ul>
 *   <li>予選 1 matchup = 3 戦 (先鋒 → 中堅 → 大将)</li>
 *   <li>決勝 1 matchup = 7 戦 (先鋒 → 次鋒 → 五将 → 中堅 → 三将 → 副将 → 大将)</li>
 * </ul>
 *
 * <p>先鋒 / 中堅 / 大将 は予選・決勝で同じ kind コードを使い、Lv 帯も共通。獲得ポイントだけ
 * 予選と決勝で異なるため {@link #pointsPerSong(String, boolean)} で {@code isFinals} を渡して引く。
 *
 * <p>ここに集約する前は Lv 帯が {@code StrategyPoolService} / 各 View / PlayerController に、
 * 表示順とラベルが TL / Spectator コントローラにそれぞれ複製されていた。追加する戦種別が増えるたびに
 * 全箇所を直す必要があったため、単一の真実の源としてこのクラスを参照する。
 */
public final class CompetitionMatchKinds {

    private CompetitionMatchKinds() {}

    /** 予選 1 matchup ぶんの戦種別 (この順で生成・表示する)。 */
    public static final List<String> PRELIM = List.of("vanguard", "middle", "captain");

    /** 決勝 1 matchup ぶんの戦種別 (この順で生成・表示する)。 */
    public static final List<String> FINALS =
            List.of("vanguard", "second", "fifth", "middle", "third", "vice", "captain");

    /** 戦種別 → 日本語ラベル。 */
    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    /** 戦種別 → 選曲可能な Lv 帯。 */
    private static final Map<String, List<Integer>> LEVELS = new LinkedHashMap<>();
    /** 予選の 1 曲あたり獲得ポイント。 */
    private static final Map<String, Integer> PRELIM_POINTS = new LinkedHashMap<>();
    /** 決勝の 1 曲あたり獲得ポイント (決勝ルール: 先鋒4/次鋒4/五将5/中堅5/三将6/副将6/大将7)。 */
    private static final Map<String, Integer> FINALS_POINTS = new LinkedHashMap<>();

    static {
        LABELS.put("vanguard", "先鋒戦");
        LABELS.put("second", "次鋒戦");
        LABELS.put("fifth", "五将戦");
        LABELS.put("middle", "中堅戦");
        LABELS.put("third", "三将戦");
        LABELS.put("vice", "副将戦");
        LABELS.put("captain", "大将戦");

        LEVELS.put("vanguard", List.of(8, 9, 10));
        LEVELS.put("second", List.of(10));
        LEVELS.put("fifth", List.of(11));
        LEVELS.put("middle", List.of(11));
        LEVELS.put("third", List.of(12));
        LEVELS.put("vice", List.of(12));
        LEVELS.put("captain", List.of(12));

        PRELIM_POINTS.put("vanguard", 2);
        PRELIM_POINTS.put("middle", 3);
        PRELIM_POINTS.put("captain", 4);

        FINALS_POINTS.put("vanguard", 4);
        FINALS_POINTS.put("second", 4);
        FINALS_POINTS.put("fifth", 5);
        FINALS_POINTS.put("middle", 5);
        FINALS_POINTS.put("third", 6);
        FINALS_POINTS.put("vice", 6);
        FINALS_POINTS.put("captain", 7);
    }

    /**
     * 【メソッドの役割】 表示・生成順のインデックスを返す (先鋒 0 〜 大将 6)。
     *
     * <p>予選の 3 戦は決勝順の 0 / 3 / 6 に対応するため、同じ比較関数で予選も決勝も正しく並ぶ。
     * 未知の kind は末尾に寄せる。
     */
    public static int order(String kind) {
        int idx = FINALS.indexOf(kind);
        return idx < 0 ? 99 : idx;
    }

    /** 【メソッドの役割】 日本語ラベル (「先鋒戦」等) を返す。未知の kind はそのまま返す。 */
    public static String label(String kind) {
        return LABELS.getOrDefault(kind, kind);
    }

    /** 【メソッドの役割】 その戦で選曲可能な Lv 帯を返す。未知の kind は空リスト。 */
    public static List<Integer> levels(String kind) {
        return LEVELS.getOrDefault(kind, List.of());
    }

    /** 【メソッドの役割】 その戦の Lv 帯に収まっているか。選曲提出のサーバ側検証に使う。 */
    public static boolean isLevelInRange(int level, String kind) {
        return levels(kind).contains(level);
    }

    /**
     * 【メソッドの役割】 1 曲勝つごとにチームへ入るポイントを返す。
     *
     * @param kind     戦種別
     * @param isFinals 決勝 matchup の試合なら true (予選とはポイント表が異なる)
     */
    public static int pointsPerSong(String kind, boolean isFinals) {
        Map<String, Integer> table = isFinals ? FINALS_POINTS : PRELIM_POINTS;
        return table.getOrDefault(kind, 0);
    }

    /**
     * 【メソッドの役割】 決勝で「同じ選手を隣り合う 2 戦に起用していないか」を判定するための隣接チェック。
     *
     * <p>決勝は 1 チーム 4 人で 7 戦を回すため同一選手の 2 戦起用が前提だが、連続出場は禁止。
     */
    public static boolean isAdjacent(String kindA, String kindB) {
        int a = order(kindA);
        int b = order(kindB);
        if (a == 99 || b == 99) return false;
        return Math.abs(a - b) == 1;
    }
}
