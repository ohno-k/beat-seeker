package com.beatseeker.backend.service;

import java.util.List;

/**
 * 【クラスの役割】 総 BEAT-PT →「名前付きティア」（Beginner 〜 Legend）の対応表。
 *
 * <h3>なぜバックエンドに置くのか</h3>
 * ティア名の導出はフロントエンド {@code beatTier.ts} に一本化されており、
 * {@code VersionPtSnapshot} も「ティア名は保存しない」方針を取っている。
 * ただし練習メニューは
 *  - 「次のティアまで何 pt か」を目標として組む
 *  - 譜面 × ティアの集計をティア単位で切る
 * ためにサーバー側でもティア境界を知る必要がある。
 *
 * そこで <b>名前付きティアの閾値だけ</b> をここにミラーする。
 * 副ティア（Expert I〜V のような 5 分割）は練習メニューでは使わないので持たない。
 * 閾値は {@code beatTier.ts} の {@code BEAT_TIER_RANKS} および
 * {@code ScoreRepository.findChartTierBenchmarks()} の CASE 式と同じ値であること。
 *
 * <p>この 3 箇所は同じ数値を持つ意図的な重複なので、閾値を変える場合は必ず 3 つとも直すこと。
 */
public final class BeatTierScale {

    /** 1 ティア（名前付き）。{@code minPoints} 以上 = このティア。 */
    public record Tier(String name, double minPoints) {}

    /**
     * 名前付きティアの一覧。上位から降順に並べる。
     * {@code beatTier.ts} の BEAT_TIER_RANKS のブロック境界と一致させてある。
     */
    public static final List<Tier> TIERS = List.of(
            new Tier("Legend", 18000),
            new Tier("Mythic", 17500),
            new Tier("Ancient", 17000),
            new Tier("Master", 16500),
            new Tier("Elite", 16000),
            new Tier("Commander", 15500),
            new Tier("Veteran", 15000),
            new Tier("Expert", 14000),
            new Tier("Advanced", 13000),
            new Tier("Intermediate", 12000),
            new Tier("Novice", 10000),
            new Tier("Beginner", 0)
    );

    private BeatTierScale() {}

    /**
     * 【メソッドの役割】 総 BEAT-PT からティア名を返す。
     *
     * @param totalBeatPt 総 BEAT-PT（上位 100 譜面の合計）
     * @return ティア名。0 pt 以下でも "Beginner" を返す
     */
    public static String tierOf(double totalBeatPt) {
        for (Tier t : TIERS) {
            if (totalBeatPt >= t.minPoints()) return t.name();
        }
        return "Beginner";
    }

    /**
     * 【メソッドの役割】 現在の pt から見た「次の名前付きティア」を返す。
     *
     * @param totalBeatPt 総 BEAT-PT
     * @return 次のティア。すでに Legend なら null
     */
    public static Tier nextTierOf(double totalBeatPt) {
        Tier next = null;
        // 下位から見ていき、まだ届いていない中で最も低いものが「次」。
        for (int i = TIERS.size() - 1; i >= 0; i--) {
            Tier t = TIERS.get(i);
            if (totalBeatPt < t.minPoints()) {
                next = t;
                break;
            }
        }
        return next;
    }

    /**
     * 【メソッドの役割】 ティア名の並び順（Beginner = 0、Legend = 11）を返す。
     * 隣接ティアの判定に使う。未知の名前は -1。
     */
    public static int ordinalOf(String tierName) {
        for (int i = 0; i < TIERS.size(); i++) {
            if (TIERS.get(i).name().equals(tierName)) return TIERS.size() - 1 - i;
        }
        return -1;
    }

    /** 【メソッドの役割】 並び順からティアを引く。範囲外なら null。 */
    public static Tier byOrdinal(int ordinal) {
        int idx = TIERS.size() - 1 - ordinal;
        if (idx < 0 || idx >= TIERS.size()) return null;
        return TIERS.get(idx);
    }
}
