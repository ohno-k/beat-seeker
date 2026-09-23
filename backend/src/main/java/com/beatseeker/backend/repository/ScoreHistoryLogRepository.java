package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ScoreHistoryLog;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code ScoreHistoryLog}（スコア更新の履歴スナップショット）を扱うリポジトリ。
 *
 * ユーザーがスコアを更新した時点ごとに、そのときの合計 beat_pt / rate_pt / precision_pt 等を
 * スナップショットとして 1 レコード保存している。ランキング推移や差分計算に利用する。
 *
 * {@link JpaRepository}{@code <ScoreHistoryLog, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - ユーザーの履歴を時系列取得／最新 1 件取得
 *  - グローバルランキング（beat_pt / precision_pt / rate_pt）の算出
 *    いずれも「各ユーザーの最新スナップショットから順位付け」＋「前日との順位変動算出」を
 *    CTE（WITH 句）で行うネイティブクエリ
 *  - ARENA ランク別の平均 pt 集計
 *  - 任意ユーザーの最新 pt 取得
 */
public interface ScoreHistoryLogRepository extends JpaRepository<ScoreHistoryLog, Long> {
    /**
     * 【メソッドの役割】 指定ユーザーの履歴を古い順（昇順）で全件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? ORDER BY uploaded_at ASC}。
     * 推移グラフ描画などに使う。0 件なら空リスト。
     *
     * @param user 対象ユーザー
     * @return 古い順の履歴リスト
     */
    List<ScoreHistoryLog> findByUserOrderByUploadedAtAsc(User user);

    /**
     * 【メソッドの役割】 指定ユーザーの履歴を作品バージョンで絞って古い順に取得する。
     *
     * 成長記録ページの作品切り替え用。世代切り替え前に保存された行は {@code version} が null なので、
     * {@code legacyVersion}（= 33 Sparkle Shower）として扱う（COALESCE）。
     *
     * @param user          対象ユーザー
     * @param version       取得したい作品バージョン
     * @param legacyVersion version が null の行をどの作品として扱うか
     * @return 古い順の履歴リスト（0 件なら空）
     */
    @Query("SELECT l FROM ScoreHistoryLog l WHERE l.user = :user AND COALESCE(l.version, :legacyVersion) = :version ORDER BY l.uploadedAt ASC")
    List<ScoreHistoryLog> findByUserAndVersionOrderByUploadedAtAsc(@Param("user") User user,
                                                                   @Param("version") int version,
                                                                   @Param("legacyVersion") int legacyVersion);

    /**
     * 【メソッドの役割】 指定ユーザーの最新履歴（1 件）を取得する。
     *
     * 派生クエリメソッド: {@code ORDER BY uploaded_at DESC LIMIT 1}。
     * 直近の合計 pt を取り出すのに使う。履歴未作成なら {@link Optional#empty()}。
     *
     * @param user 対象ユーザー
     * @return 最新スナップショット（なければ空）
     */
    Optional<ScoreHistoryLog> findFirstByUserOrderByUploadedAtDesc(User user);

    /**
     * 【メソッドの役割】 直前の upload がサーバー側で作った、まだフロントが上書きしていない
     * 通常ログ（tag = null / clientConfirmed = false）を 1 件取得する。
     *
     * {@code /save-history-log} が「新しい行を足す」のではなく「upload が作った行を仕上げる」ために使う。
     * 取り違えを避けるため、呼び出し側は直近数分だけを対象にする（{@code since}）。
     *
     * @param user  対象ユーザー
     * @param since この時刻以降に作られた行だけを対象にする
     * @return 未確定の直近ログ（なければ空）
     */
    Optional<ScoreHistoryLog> findFirstByUserAndTagIsNullAndClientConfirmedFalseAndUploadedAtGreaterThanEqualOrderByUploadedAtDesc(
            User user, LocalDateTime since);

    /**
     * 【メソッドの役割】 グローバルランキング（beat_pt）を取得する。順位変動付き。
     *
     * ネイティブ SQL（PostgreSQL）。概要:
     *  - {@code current_ranks}: 全ユーザーについて「最新の履歴」を {@code DISTINCT ON} で抽出し、
     *    {@code total_beat_pt DESC} で順位を付与
     *  - {@code previous_ranks}: 同じことを「本日 0 時より前」に絞って行い、前日分の順位を算出
     *  - 最終 SELECT で users と JOIN し、表示名・IIDX ID・プライバシー・サポーターバッジ・
     *    前日差分（rank_pos 差）を返す
     *
     * {@code DISTINCT ON (user_id)} は PostgreSQL 固有で、先頭行のみ残す効率的な書き方。
     * 返却キー: userId / displayName / iidxId / privacyLevel / totalBeatPt / lastUpdatedAt /
     *           isSupporter / rankChange
     *
     * ■ 「> 0」の条件は最新行を選んだ<b>後</b>に掛ける（2026-09-15 変更）
     * 世代切り替え（新作稼働）時に全ユーザーへ「0 pt の履歴行」を 1 本入れてランキングを初期化する。
     * 条件を DISTINCT ON の内側に置くと「最新の非ゼロ行」＝前作の最終値を拾ってしまい初期化にならない。
     * 各ランキング（precision / rate）も同じ理由で外側に置いている。
     * 最新行が 0 pt のユーザー（＝新作でまだ 1 度もアップロードしていない）はランキングに出ない。
     *
     * @return ランキング配列（0 件でも空リスト）
     */
    @Query(value =
            "WITH current_ranks AS ( " +
            "    SELECT user_id, total_beat_pt, uploaded_at, " +
            "           RANK() OVER (ORDER BY total_beat_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_beat_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS latest " +
            "    WHERE total_beat_pt > 0 " +
            "), " +
            "previous_ranks AS ( " +
            "    SELECT user_id, " +
            "           RANK() OVER (ORDER BY total_beat_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_beat_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE uploaded_at < CURRENT_DATE " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS prev_latest " +
            "    WHERE total_beat_pt > 0 " +
            ") " +
            "SELECT u.id AS \"userId\", u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
            "       COALESCE(u.privacy_level, 1) AS \"privacyLevel\", " +
            "       cr.total_beat_pt AS \"totalBeatPt\", " +
            "       cr.uploaded_at AS \"lastUpdatedAt\", " +
            "       COALESCE(u.is_supporter, false) AS \"isSupporter\"," +
            "       CASE WHEN pr.rank_pos IS NULL THEN NULL " +
            "            ELSE (pr.rank_pos - cr.rank_pos)::integer END AS \"rankChange\" " +
            "FROM current_ranks cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "LEFT JOIN previous_ranks pr ON cr.user_id = pr.user_id " +
            "ORDER BY cr.rank_pos", nativeQuery = true)
    List<Map<String, Object>> getGlobalRanking();

    /**
     * 【メソッドの役割】 精度（precision_pt）のグローバルランキングを取得する。順位変動付き。
     *
     * ネイティブ SQL。構造は {@link #getGlobalRanking()} と同じ（現時点と前日で順位を出し差分を取る）。
     * 違い:
     *  - {@code WHERE total_precision_pt > 0} で 0 pt ユーザーを除外
     *  - 並び順キーが {@code total_precision_pt DESC}
     *
     * 返却キー: displayName / iidxId / totalPrecisionPt / isSupporter / rankChange
     *
     * @return 精度ランキング配列
     */
    @Query(value =
            "WITH current_ranks AS ( " +
            "    SELECT user_id, total_precision_pt, " +
            "           RANK() OVER (ORDER BY total_precision_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_precision_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS latest " +
            "    WHERE total_precision_pt > 0 " +
            "), " +
            "previous_ranks AS ( " +
            "    SELECT user_id, " +
            "           RANK() OVER (ORDER BY total_precision_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_precision_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE uploaded_at < CURRENT_DATE " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS prev_latest " +
            "    WHERE total_precision_pt > 0 " +
            ") " +
            "SELECT u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
            "       cr.total_precision_pt AS \"totalPrecisionPt\", " +
            "       COALESCE(u.is_supporter, false) AS \"isSupporter\"," +
            "       CASE WHEN pr.rank_pos IS NULL THEN NULL " +
            "            ELSE (pr.rank_pos - cr.rank_pos)::integer END AS \"rankChange\" " +
            "FROM current_ranks cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "LEFT JOIN previous_ranks pr ON cr.user_id = pr.user_id " +
            "ORDER BY cr.rank_pos", nativeQuery = true)
    List<Map<String, Object>> getPrecisionRanking();

    /**
     * 【メソッドの役割】 rate_pt（ティア rate 系）のグローバルランキングを取得する。順位変動付き。
     *
     * 構造は {@link #getGlobalRanking()} と同一。並び順キーと対象カラムが {@code total_rate_pt} に変わる。
     * 返却キー: userId / displayName / iidxId / privacyLevel / totalRatePt / lastUpdatedAt /
     *           isSupporter / rankChange
     *
     * @return レートランキング配列
     */
    @Query(value =
            "WITH current_ranks AS ( " +
            "    SELECT user_id, total_rate_pt, uploaded_at, " +
            "           RANK() OVER (ORDER BY total_rate_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_rate_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS latest " +
            "    WHERE total_rate_pt > 0 " +
            "), " +
            "previous_ranks AS ( " +
            "    SELECT user_id, " +
            "           RANK() OVER (ORDER BY total_rate_pt DESC) AS rank_pos " +
            "    FROM ( " +
            "        SELECT DISTINCT ON (user_id) user_id, total_rate_pt, uploaded_at " +
            "        FROM score_history_logs " +
            "        WHERE uploaded_at < CURRENT_DATE " +
            "        ORDER BY user_id, uploaded_at DESC " +
            "    ) AS prev_latest " +
            "    WHERE total_rate_pt > 0 " +
            ") " +
            "SELECT u.id AS \"userId\", u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
            "       COALESCE(u.privacy_level, 1) AS \"privacyLevel\", " +
            "       cr.total_rate_pt AS \"totalRatePt\", " +
            "       cr.uploaded_at AS \"lastUpdatedAt\", " +
            "       COALESCE(u.is_supporter, false) AS \"isSupporter\"," +
            "       CASE WHEN pr.rank_pos IS NULL THEN NULL " +
            "            ELSE (pr.rank_pos - cr.rank_pos)::integer END AS \"rankChange\" " +
            "FROM current_ranks cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "LEFT JOIN previous_ranks pr ON cr.user_id = pr.user_id " +
            "ORDER BY cr.rank_pos", nativeQuery = true)
    List<Map<String, Object>> getRateTierRanking();

    /**
     * 【メソッドの役割】 ARENA ランク（SS／S+／S／A など）ごとの平均 beat_pt とユーザー数を集計する。
     *
     * ネイティブ SQL。{@code DISTINCT ON (user_id)} で各ユーザーの最新履歴を取り、
     * users.arena_rank でグルーピングして平均を算出する。
     * {@code arena_rank IS NOT NULL AND arena_rank <> ''} で未設定ユーザーを除外している。
     *
     * 返却キー: arenaRank / avgBeatPt / userCount
     *
     * @return ARENA ランク別集計のリスト
     */
    @Query(value =
            "SELECT u.arena_rank AS \"arenaRank\", " +
            "       AVG(cr.total_beat_pt) AS \"avgBeatPt\", " +
            "       COUNT(*) AS \"userCount\" " +
            "FROM ( " +
            "    SELECT DISTINCT ON (user_id) user_id, total_beat_pt " +
            "    FROM score_history_logs " +
            "    ORDER BY user_id, uploaded_at DESC " +
            ") cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "WHERE u.arena_rank IS NOT NULL AND u.arena_rank <> '' AND cr.total_beat_pt > 0 " +
            "GROUP BY u.arena_rank " +
            "ORDER BY AVG(cr.total_beat_pt) DESC", nativeQuery = true)
    List<Map<String, Object>> getArenaRankAverageBeatPt();

    /**
     * 【メソッドの役割】 ARENA ランクごとの平均 rate_pt とユーザー数を集計する。
     *
     * 構造は {@link #getArenaRankAverageBeatPt()} と同じで、対象カラムが {@code total_rate_pt} に変わる。
     * 併せて {@code total_rate_pt > 0} のユーザーのみ対象とする。
     *
     * 返却キー: arenaRank / avgRatePt / userCount
     *
     * @return ARENA ランク別集計のリスト
     */
    @Query(value =
            "SELECT u.arena_rank AS \"arenaRank\", " +
            "       AVG(cr.total_rate_pt) AS \"avgRatePt\", " +
            "       COUNT(*) AS \"userCount\" " +
            "FROM ( " +
            "    SELECT DISTINCT ON (user_id) user_id, total_rate_pt " +
            "    FROM score_history_logs " +
            "    ORDER BY user_id, uploaded_at DESC " +
            ") cr " +
            "JOIN users u ON cr.user_id = u.id " +
            "WHERE u.arena_rank IS NOT NULL AND u.arena_rank <> '' AND cr.total_rate_pt > 0 " +
            "GROUP BY u.arena_rank " +
            "ORDER BY AVG(cr.total_rate_pt) DESC", nativeQuery = true)
    List<Map<String, Object>> getArenaRankAverageRatePt();

    /**
     * 【メソッドの役割】 指定ユーザーの {@code total_beat_pt} の最大値を返す。
     *
     * ネイティブ SQL。自己ベスト pt の参照用途（必ずしも最新 = 最大ではない場合がある）。
     * 履歴 0 件の場合は {@code null} が返る点に注意。
     *
     * @param userId ユーザー ID
     * @return 最大 beat_pt（履歴なしなら null）
     */
    @Query(value =
            "SELECT MAX(total_beat_pt) FROM score_history_logs " +
            "WHERE user_id = :userId", nativeQuery = true)
    Double getLatestTotalBeatPtByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * 【メソッドの役割】 指定ユーザーの {@code total_rate_pt} の最大値を返す。
     *
     * ネイティブ SQL。自己ベストレート pt 参照用途。履歴 0 件なら {@code null}。
     *
     * @param userId ユーザー ID
     * @return 最大 rate_pt（履歴なしなら null）
     */
    @Query(value =
            "SELECT MAX(total_rate_pt) FROM score_history_logs " +
            "WHERE user_id = :userId", nativeQuery = true)
    Double getLatestTotalRatePtByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    /**
     * 【メソッドの役割】 指定ユーザーの履歴を「期間 [startDate, endDate)」で昇順取得する。
     *
     * 派生クエリ: {@code WHERE user_id = ? AND uploaded_at >= ? AND uploaded_at < ? ORDER BY uploaded_at ASC}。
     * 月末振り返り（Spotify Wrapped 風）の月次集計で「当月分のスナップショット群」を抜き出すために使う。
     * endDate は「含まない」点に注意（月境界を [月初, 翌月初) で表現する想定）。
     *
     * @param user      対象ユーザー
     * @param startDate 期間開始（含む）
     * @param endDate   期間終了（含まない）
     * @return uploadedAt 昇順の履歴リスト（0 件なら空）
     */
    List<ScoreHistoryLog> findByUserAndUploadedAtGreaterThanEqualAndUploadedAtLessThanOrderByUploadedAtAsc(
            User user, LocalDateTime startDate, LocalDateTime endDate);

    /**
     * 【メソッドの役割】 指定ユーザーの履歴のうち、{@code uploadedAt < threshold} を満たす最新 1 件を返す。
     *
     * 月末振り返りで「先月末のスナップショット」「月初時点のベース値」を求めるのに使う。
     * 履歴が無ければ {@link Optional#empty()}。
     *
     * @param user      対象ユーザー
     * @param threshold 比較境界（このより前の最新スナップショットを取得）
     * @return 直近の履歴（無ければ空）
     */
    Optional<ScoreHistoryLog> findFirstByUserAndUploadedAtLessThanOrderByUploadedAtDesc(
            User user, LocalDateTime threshold);

    /**
     * 【メソッドの役割】 指定ユーザー ID の履歴のうち {@code uploadedAt >= since} を昇順で返す。
     *
     * 派生クエリ: {@code WHERE user_id = ? AND uploaded_at >= ? ORDER BY uploaded_at ASC}。
     * コスパ埋めレコメンドが「直近数日でスコア更新した譜面（挑戦済み）」を diffJson から拾うのに使う。
     * User エンティティを引かずに済むよう ID で引く。
     *
     * @param userId 対象ユーザーの DB 主キー
     * @param since  期間開始（含む）
     * @return uploadedAt 昇順の履歴リスト（0 件なら空）
     */
    List<ScoreHistoryLog> findByUser_IdAndUploadedAtGreaterThanEqualOrderByUploadedAtAsc(Long userId, LocalDateTime since);

    /**
     * 【メソッドの役割】 指定ユーザー ID に {@code uploadedAt < before} の履歴が 1 件でもあるか。
     *
     * 「期間内の最初の履歴がアカウント初回の一括取り込みかどうか」の判定に使う。
     * 初回取り込みは全譜面が差分として並ぶので、挑戦済み判定から外す必要がある。
     *
     * @param userId 対象ユーザーの DB 主キー
     * @param before 比較境界
     * @return より前の履歴があれば true
     */
    boolean existsByUser_IdAndUploadedAtLessThan(Long userId, LocalDateTime before);
}
