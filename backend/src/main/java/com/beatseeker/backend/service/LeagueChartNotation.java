package com.beatseeker.backend.service;

/**
 * 【クラスの役割】 リーグモード関連のサービス/コントローラで共有する譜面表記の変換ヘルパー。
 *
 * 難易度コード（song_definitions.difficulty の "1".."4", "10"）と
 * 難易度名（scores.difficultyName の "BEGINNER".."LEGGENDARIA"）の相互変換、および
 * クリア種別文字列の優劣比較用 rank 変換を提供する。
 *
 * 注意: 同等の変換は {@code ScoreController} にも private メソッドとして存在する
 * （mapDifficultyCodeToName / getClearTypeRank）。ScoreController 側は既存挙動を
 * 変えないためそのままにしており、値域を変更する場合は両方を揃えること。
 */
public final class LeagueChartNotation {

    private LeagueChartNotation() {
        // static ユーティリティのためインスタンス化しない
    }

    /**
     * 【メソッドの役割】 難易度コードを難易度名に変換する。
     *
     * @param code "1"=BEGINNER, "2"=NORMAL, "3"=HYPER, "4"=ANOTHER, "10"=LEGGENDARIA
     * @return 難易度名。未知のコードは null
     */
    public static String codeToName(String code) {
        if (code == null) return null;
        return switch (code) {
            case "1" -> "BEGINNER";
            case "2" -> "NORMAL";
            case "3" -> "HYPER";
            case "4" -> "ANOTHER";
            case "10" -> "LEGGENDARIA";
            default -> null;
        };
    }

    /**
     * 【メソッドの役割】 難易度名を難易度コードに変換する。
     *
     * @param name "BEGINNER" / "NORMAL" / "HYPER" / "ANOTHER" / "LEGGENDARIA"
     * @return 難易度コード。未知の名前は null
     */
    public static String nameToCode(String name) {
        if (name == null) return null;
        return switch (name) {
            case "BEGINNER" -> "1";
            case "NORMAL" -> "2";
            case "HYPER" -> "3";
            case "ANOTHER" -> "4";
            case "LEGGENDARIA" -> "10";
            default -> null;
        };
    }

    /**
     * 【メソッドの役割】 クリア種別文字列を順序比較できる整数 rank に変換する。
     *
     * FULLCOMBO → EX HARD → HARD → CLEAR → EASY → ASSIST → FAILED → NO PLAY の順で
     * 大きいほど優秀とする慣習に従う（ScoreController.getClearTypeRank と同一の値域）。
     *
     * @param clearType "FULLCOMBO CLEAR" 等のクリア種別文字列
     * @return 0〜7 の整数 rank。未知の値・null は 0
     */
    public static int clearTypeRank(String clearType) {
        if (clearType == null) return 0;
        return switch (clearType) {
            case "FULLCOMBO CLEAR" -> 7;
            case "EX HARD CLEAR" -> 6;
            case "HARD CLEAR" -> 5;
            case "CLEAR" -> 4;
            case "EASY CLEAR" -> 3;
            case "ASSIST CLEAR" -> 2;
            case "FAILED" -> 1;
            default -> 0;
        };
    }
}
