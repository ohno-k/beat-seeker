package com.beatseeker.backend.controller;

/**
 * 【DTO の役割】 管理者用「ポイント再計算」エンドポイントに渡すリクエスト。
 *
 * 曲データ／難易度表データをクライアント側で保持している JSON 文字列として
 * まとめて送信し、サーバーは全ユーザーのスコアに対してポイントを再計算する。
 */
public record RecalculatePointsRequest(
        /** 全楽曲メタデータの JSON 文字列。 */
        String songDataJson,
        /** 難易度表（tier 配置情報）の JSON 文字列。 */
        String difficultyTableJson
) {}
