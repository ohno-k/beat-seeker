package com.beatseeker.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 【クラスの役割】 {@code @Scheduled} タイマータスクの有効/無効を設定で切り替える構成クラス。
 *
 * 従来は {@code BackendApplication} に {@code @EnableScheduling} が直付けされていたが、
 * 「ローカル PC から本番 DB に接続して調査する」ユースケース（application-prod-db.yml）では
 * 定期ジョブが本番データを書き換える事故になり得るため、ここに分離した。
 *
 * 危険な定期ジョブの例:
 *  - SongRankBatchService: 毎日 03:00 に user_song_ranks を TRUNCATE → 全再構築
 *  - LeagueScheduler: 金曜/月曜にリーグの draft 作成・週締め・昇降格を実行
 *
 * 切り替え方法: {@code app.scheduling.enabled=false} を設定すると
 * この構成クラスごと無効になり、アプリ内の全 {@code @Scheduled} が起動しなくなる。
 * 未設定（本番・通常のローカル開発）はこれまで通り有効（matchIfMissing = true）。
 *
 * 副作用: 無効化中はキャッシュ系サービス（SongAvgScoreRatesCacheService 等）の
 * 定期リフレッシュも止まるため、該当エンドポイントは空データを返す（表示のみの影響）。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
