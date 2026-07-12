package com.beatseeker.backend.service;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【Component の役割】 BEAT-PT / RATE-PT の単曲あたり計算ロジックを集約する共通ユーティリティ。
 *
 * 責務:
 *  - 非公式難易度値 → weight のテーブル {@link #WEIGHTS} を保持
 *  - RATE-PT のスコア率しきい値表 {@link #SCORE_RATE_THRESHOLDS} を保持
 *  - BEAT-PT の単曲計算 {@link #calculatePoints(double, String)} を提供
 *  - RATE-PT の単曲計算 {@link #calculateScoreRateTierPoints(double)} を提供
 *  - 非公式難易度文字列から weight を引く {@link #getWeight(String)} を提供
 *
 * 本クラスは {@link ScoreRecalculationService} と {@link TopRankersBeatPtService} で
 * 重複していた定数・計算ロジックをそのまま一箇所にまとめたもので、計算式・戻り値は
 * どちらの元実装とも完全に一致する（1 ビットも変えていない）。
 */
@Component
public class BeatPtCalculator {

    /**
     * 非公式難易度値（"11.0"〜"13.1"）→ weight のマップ。
     * beatTier.ts と完全に同じ値を生成する。
     * 11.0 から 0.1 刻みで 22 段階、初期 weight 145 から
     * 12.49 未満は +2、以上は +3 ずつ増える（13.1 = 193）。
     */
    public static final Map<String, Integer> WEIGHTS = new HashMap<>();

    static {
        int weight = 145;
        for (int i = 0; i <= 21; i++) {
            double rankValue = 11.0 + i * 0.1;
            String rank = String.format(Locale.US, "%.1f", rankValue);
            WEIGHTS.put(rank, weight);
            weight += (rankValue >= 12.49) ? 3 : 2;
        }
    }

    /**
     * RATE-PT のスコア率しきい値表。
     * [0]=スコア率(%), [1]=ポイント。しきい値間はピースワイズ線形で補間する。
     */
    public static final double[][] SCORE_RATE_THRESHOLDS = {
            {77.77, 1.0},
            {88.89, 2.0},
            {94.44, 4.0},
            {97.22, 8.0},
            {98.61, 16.0},
            {99.31, 32.0},
            {99.65, 64.0},
            {99.83, 128.0},
            {99.91, 256.0},
            {100.0, 512.0}
    };

    /**
     * 非公式難易度文字列（"12.3" / "12.3 TOP" のような付随テキスト）から weight を引く。
     * 正規表現で最初の小数を取り出し、WEIGHTS マップを検索する。
     */
    public int getWeight(String informalRank) {
        if (informalRank == null || informalRank.isEmpty()) return 0;
        Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(informalRank);
        String key = m.find() ? m.group(1) : informalRank;
        return WEIGHTS.getOrDefault(key, 0);
    }

    /**
     * BEAT-PT 単曲あたりの点数を計算する。
     * base = (scoreRate/100)^1.3 × weight に、77.77/88.88/94.44 を超えるごとに weight×0.01 のボーナスを加算。
     * scoreRate ≤ 66.666 や weight = 0 は 0pt とする。
     */
    public double calculatePoints(double scoreRate, String informalRank) {
        if (informalRank == null) return 0.0;
        int weight = getWeight(informalRank);
        if (weight == 0 || scoreRate <= 66.666) return 0.0;

        double basePoints = Math.pow(scoreRate / 100.0, 1.3) * weight;

        double bonus = 0;
        if (scoreRate > 77.77) bonus += weight * 0.01;
        if (scoreRate > 88.88) bonus += weight * 0.01;
        if (scoreRate > 94.44) bonus += weight * 0.01;

        return basePoints + bonus;
    }

    /**
     * RATE-PT 単曲あたりの点数を、{@link #SCORE_RATE_THRESHOLDS} を使ってピースワイズ線形補間で計算する。
     * scoreRate が 77.77% 未満なら 0、100% 以上なら 512pt。
     */
    public double calculateScoreRateTierPoints(double scoreRate) {
        if (scoreRate <= 0 || scoreRate < SCORE_RATE_THRESHOLDS[0][0]) return 0.0;
        double lastRate = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1][0];
        double lastPt = SCORE_RATE_THRESHOLDS[SCORE_RATE_THRESHOLDS.length - 1][1];
        if (scoreRate >= lastRate) return lastPt;

        for (int i = 0; i < SCORE_RATE_THRESHOLDS.length - 1; i++) {
            double loRate = SCORE_RATE_THRESHOLDS[i][0];
            double loPt = SCORE_RATE_THRESHOLDS[i][1];
            double hiRate = SCORE_RATE_THRESHOLDS[i + 1][0];
            double hiPt = SCORE_RATE_THRESHOLDS[i + 1][1];

            if (scoreRate < hiRate) {
                double t = (scoreRate - loRate) / (hiRate - loRate);
                return loPt + t * (hiPt - loPt);
            }
        }
        return 0.0;
    }
}
