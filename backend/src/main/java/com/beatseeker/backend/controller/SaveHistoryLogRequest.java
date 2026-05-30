package com.beatseeker.backend.controller;

/**
 * 【DTO の役割】 スコア履歴スナップショットを保存する API のリクエストボディ。
 *
 * ブックマークレット or フロントエンドが差分付きのアップロード結果を
 * このレコード形式で送ってくる。サーバーは {@code ScoreHistoryLog} テーブルに
 * 「あるアップロード時点での集計ポイント」を蓄積する。
 */
public record SaveHistoryLogRequest(
        /** アップロード時点のトータル Beat ポイント。 */
        Double totalBeatPt,
        /** 今回のアップロードで増加した Beat ポイント分。 */
        Double beatPtIncrease,
        /** スコアが更新された譜面の件数。 */
        Integer updatedCount,
        /** 差分詳細の JSON 文字列（どの曲がどう伸びたか）。 */
        String diffJson,
        /** アップロード時点の精度ポイント合計。 */
        Double totalPrecisionPt,
        /** アップロード時点の RATE ポイント合計。 */
        Double totalRatePt,
        /** 今回到達したティア名（RATE）。 */
        String tierName,
        /** 直前のティア名。ティア昇降格判定に使う。 */
        String prevTierName,
        /**
         * 集計（上位100曲: BEAT/RATE）に INFINITAS 取得のベストが含まれるか。
         * TIER ランキング行の「INF」バッジ表示用。フロントが top-100 から算出して送る。
         */
        Boolean includesInfinitas) {
}
