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

    // ── 副ティア（Master I 〜 Master V のような 5 分割） ──────────────────

    /** 1 つの名前付きティアを 5 分割した副ティアの数。{@code beatTier.ts} の分割数と同じ。 */
    public static final int SUB_TIER_COUNT = 5;

    /** 副ティアのローマ数字表記。{@code beatTier.ts} の表示に合わせる。 */
    private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};

    /**
     * 副ティア 1 つぶん。
     *
     * @param tierName  名前付きティア名（例 "Master"）
     * @param level     1〜5
     * @param label     表示名（例 "Master III"）
     * @param minPoints この副ティアに入る下限 pt
     */
    public record SubTier(String tierName, int level, String label, double minPoints) {}

    /**
     * 【メソッドの役割】 総 BEAT-PT から副ティアを求める。
     *
     * 名前付きティアの幅（Master なら 500、Expert なら 1000、Novice なら 2000）を
     * 5 等分し、下から何番目かを 1〜5 で返す。
     * {@code beatTier.ts} の {@code generateTieredRanks} および
     * {@code ScoreRepository.findChartTierBenchmarks()} の tier_level 算出と同じ刻み。
     *
     * <p>Legend（上限なし）と Beginner（0 pt 始まり）は分割しないので level 0 を返す。
     *
     * @return 副ティア。分割しないティアでは level = 0、label はティア名のみ
     */
    public static SubTier subTierOf(double totalBeatPt) {
        String name = tierOf(totalBeatPt);
        Tier tier = byName(name);
        Tier upper = upperOf(name);
        // Legend は上が無く、Beginner は下限 0 で幅が定義できないため分割しない。
        if (tier == null || upper == null || "Beginner".equals(name)) {
            return new SubTier(name, 0, name, tier == null ? 0 : tier.minPoints());
        }
        double step = (upper.minPoints() - tier.minPoints()) / SUB_TIER_COUNT;
        int level = (int) Math.floor((totalBeatPt - tier.minPoints()) / step) + 1;
        level = Math.max(1, Math.min(SUB_TIER_COUNT, level));
        return new SubTier(name, level, name + " " + ROMAN[level - 1],
                tier.minPoints() + (level - 1) * step);
    }

    /**
     * 【メソッドの役割】 現在の pt から見た「次の副ティア」を返す。
     *
     * 同じティア内の次の段（Master III → Master IV）があればそれ、
     * 5 段目まで来ていれば次のティアの 1 段目（Master V → Ancient I）を返す。
     *
     * @return 次の副ティア。Legend に到達していれば null
     */
    public static SubTier nextSubTierOf(double totalBeatPt) {
        SubTier current = subTierOf(totalBeatPt);
        Tier tier = byName(current.tierName());
        Tier upper = upperOf(current.tierName());
        if (tier == null || upper == null) return null; // Legend

        if (current.level() > 0 && current.level() < SUB_TIER_COUNT) {
            double step = (upper.minPoints() - tier.minPoints()) / SUB_TIER_COUNT;
            int nextLevel = current.level() + 1;
            return new SubTier(current.tierName(), nextLevel,
                    current.tierName() + " " + ROMAN[nextLevel - 1],
                    tier.minPoints() + (nextLevel - 1) * step);
        }
        // このティアの最上段（または分割しない Beginner）にいるので、次はひとつ上のティアの 1 段目。
        return subTierOf(upper.minPoints());
    }

    /** 名前からティアを引く。未知の名前は null。 */
    public static Tier byName(String tierName) {
        for (Tier t : TIERS) {
            if (t.name().equals(tierName)) return t;
        }
        return null;
    }

    /** ひとつ上のティアを返す。最上位（Legend）なら null。 */
    private static Tier upperOf(String tierName) {
        int ord = ordinalOf(tierName);
        if (ord < 0) return null;
        return byOrdinal(ord + 1);
    }
}
