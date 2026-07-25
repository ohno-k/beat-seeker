package com.beatseeker.backend.service;

/**
 * 【クラスの役割】 リーグモードの固定 DIVISION（11 階級）の定義と、BEAT-TIER からの初回配属マッピング。
 *
 * DIVISION は「DIVISION LEGEND」（tier=0、最上位）と「DIVISION 1〜10」（tier=1..10）の
 * 固定 11 階級。動的な階級数ではなく固定制で、昇降格はこの範囲内でのみ移動する。
 *
 * 初回参加時の配属は BEAT-TIER（beatTier.ts の RANKS）をサブティア単位で参照する。
 * 大分類ブロック 1:1 だと最上位帯が数人しか居らず成立しないため、本番の BEAT-PT 分布
 * （2026-07 時点・直近 90 日アクティブ 576 人）を基に、各 DIVISION の候補人口が
 * なだらかなピラミッドになるサブティア境界を採用している:
 *
 * <pre>
 *   DIVISION LEGEND ← Legend 〜 Mythic 4        (>= 17800pt)  候補 ~6 人
 *   DIVISION 1      ← Mythic 3 〜 Mythic 1      (>= 17500pt)  候補 ~14 人
 *   DIVISION 2      ← Ancient 5 〜 Ancient 2    (>= 17100pt)  候補 ~26 人
 *   DIVISION 3      ← Ancient 1 〜 Master 4     (>= 16800pt)  候補 ~38 人
 *   DIVISION 4      ← Master 3 〜 Elite 5       (>= 16400pt)  候補 ~45 人
 *   DIVISION 5      ← Elite 4 〜 Elite 1        (>= 16000pt)  候補 ~58 人
 *   DIVISION 6      ← Commander 5 〜 1          (>= 15500pt)  候補 ~69 人
 *   DIVISION 7      ← Veteran 5 〜 1            (>= 15000pt)  候補 ~56 人
 *   DIVISION 8      ← Expert 5 〜 Expert 3      (>= 14400pt)  候補 ~72 人
 *   DIVISION 9      ← Expert 2 〜 Advanced 3    (>= 13400pt)  候補 ~66 人
 *   DIVISION 10     ← Advanced 2 以下（Beginner 含む） (< 13400pt)
 * </pre>
 *
 * しきい値はフロントの {@code frontend/src/utils/beatTier.ts} の RANKS のサブティア
 * minPoints（{@code start + (i-1) * step}）と揃えること。BEAT-TIER 側の改定や
 * ユーザー分布の大きな変化があればこのマッピングも見直す。
 * フロントの説明モーダル（LeagueInfoModal.vue）の対応表も同時に更新すること。
 */
public final class LeagueDivision {

    /** 最上位 DIVISION（DIVISION LEGEND）の tier 値。 */
    public static final int LEGEND = 0;

    /** 最下位 DIVISION（DIVISION 10）の tier 値。これより下へは降格しない。 */
    public static final int LOWEST = 10;

    private LeagueDivision() {
        // static ユーティリティのためインスタンス化しない
    }

    /**
     * 【メソッドの役割】 tier 値が有効な DIVISION 範囲（0..10）か判定する。
     *
     * @param tier 検査対象（null 可）
     * @return 有効なら true
     */
    public static boolean isValid(Integer tier) {
        return tier != null && tier >= LEGEND && tier <= LOWEST;
    }

    /**
     * 【メソッドの役割】 総合 BEAT-PT から初回配属 DIVISION を返す。
     *
     * @param totalBeatPt users.total_beat_pt（null は 0 扱い = DIVISION 10）
     * @return tier 値（0=LEGEND .. 10）
     */
    public static int forBeatPt(Double totalBeatPt) {
        double pt = totalBeatPt != null ? totalBeatPt : 0.0;
        if (pt >= 17800) return 0;   // Legend 〜 Mythic 4
        if (pt >= 17500) return 1;   // Mythic 3 〜 Mythic 1
        if (pt >= 17100) return 2;   // Ancient 5 〜 Ancient 2
        if (pt >= 16800) return 3;   // Ancient 1 〜 Master 4
        if (pt >= 16400) return 4;   // Master 3 〜 Elite 5
        if (pt >= 16000) return 5;   // Elite 4 〜 Elite 1
        if (pt >= 15500) return 6;   // Commander 5 〜 1
        if (pt >= 15000) return 7;   // Veteran 5 〜 1
        if (pt >= 14400) return 8;   // Expert 5 〜 Expert 3
        if (pt >= 13400) return 9;   // Expert 2 〜 Advanced 3
        return LOWEST;               // Advanced 2 以下（Beginner 含む）
    }
}
