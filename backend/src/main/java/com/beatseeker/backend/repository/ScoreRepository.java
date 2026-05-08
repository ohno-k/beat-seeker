package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code Score}（曲×譜面×ユーザーの成績レコード）を扱うリポジトリ。
 *
 * beat-seeker における最重要エンティティの一つで、スコア・クリアタイプ・DJ LEVEL・
 * PGREAT / GREAT 数などを保持する。
 *
 * {@link JpaRepository}{@code <Score, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - ユーザー単位での取得（アップロード順・曲別ベスト など）
 *  - 曲×譜面ごとのランキング（フレンド／自分／公開ユーザーの可視範囲を尊重）
 *  - ANOTHER / LEGGENDARIA 譜面に対する各種ランク・集計（順位・AAA 数・平均点など）
 *  - 非公式難易度表に基づく beat_pt シミュレーション（active ⇔ draft 差分）
 *  - {@code user_song_ranks} テーブルへの一括 INSERT / TRUNCATE（ランキングキャッシュ）
 */
@Repository
public interface ScoreRepository extends JpaRepository<Score, Long> {

    // ========================================================================
    // ネイティブクエリ共通 SQL 断片（DRY 化用の文字列定数）
    // ------------------------------------------------------------------------
    // 以下の定数は findAllSongRankingAggregates / calculateDifficultySimulation
    // で共通して使われる SQL 断片。物理 SQL を変えないまま、同一の文字列を
    // 一箇所にまとめて参照させることで重複を解消している。
    // コンパイル時定数 (static final String) なので @Query の value に文字列
    // 連結 (+) で埋め込んでも問題ない。
    // ========================================================================

    /**
     * 非公式難易度表のランク値 (11.0〜13.0) と beat_pt 計算に使う重みの対応表。
     * PostgreSQL の VALUES 句として使う想定で、WITH 句の内側に差し込む。
     */
    String WEIGHT_MAP_VALUES =
        "weight_map(rv, wt) AS ( " +
        "  VALUES ('11.0', 145), ('11.1', 147), ('11.2', 149), ('11.3', 151), ('11.4', 153), " +
        "  ('11.5', 155), ('11.6', 157), ('11.7', 159), ('11.8', 161), ('11.9', 163), " +
        "  ('12.0', 165), ('12.1', 167), ('12.2', 169), ('12.3', 171), ('12.4', 173), " +
        "  ('12.5', 175), ('12.6', 178), ('12.7', 181), ('12.8', 184), ('12.9', 187), ('13.0', 190) " +
        ")";

    /**
     * score から score_rate (パーセンテージ 0〜100) を算出する式。
     * notes が 0 の場合の 0 除算を防ぐため NULLIF でガードしている。
     * SELECT リスト内の式としてそのまま埋め込む想定。
     */
    String SCORE_RATE_FORMULA = "(s.score * 100.0 / NULLIF(sd.notes * 2.0, 0))";

    // 注: beat_pt の boost CASE 式も 2 クエリで類似しているが、
    //     findAllSongRankingAggregates は列 "score_rate" を、
    //     calculateDifficultySimulation は列 "b.score_rate" を参照しており、
    //     列別名が異なるため完全な同一文字列としての抽出はできない。
    //     さらに元の @Query 文字列内の空白配置を 1 文字たりとも変えない制約から、
    //     文字列定数での抽出は今回見送り、コメントで重複箇所を明示するに留める。

    /**
     * 【メソッドの役割】 指定ユーザーのスコアをアップロード日時の新しい順で取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? ORDER BY uploaded_at DESC}。
     * 最新アップロードの一覧表示用。0 件なら空リスト。
     *
     * @param user 対象ユーザー
     * @return スコア一覧（新しい順）
     */
    List<Score> findByUserOrderByUploadedAtDesc(User user);

    /**
     * 【メソッドの役割】 指定ユーザーのスコアをアップロード日時の古い順で取得する。
     *
     * 派生クエリメソッド: {@code ORDER BY uploaded_at ASC}。
     * 伸び率計算など、古い方から処理したい場面で使う。
     *
     * @param user 対象ユーザー
     * @return スコア一覧（古い順）
     */
    List<Score> findByUserOrderByUploadedAtAsc(User user);

    /**
     * 【メソッドの役割】 指定ユーザーの最新アップロード 1 件を取得する。
     *
     * 派生クエリメソッド: {@code ORDER BY uploaded_at DESC LIMIT 1}。
     * 「最新アップロードがいつか」を判定するのに使う。未登録なら {@link Optional#empty()}。
     *
     * @param user 対象ユーザー
     * @return 最新のスコア（なければ空）
     */
    Optional<Score> findFirstByUserOrderByUploadedAtDesc(User user);

    /**
     * 【メソッドの役割】 ユーザーの指定スナップショット（1 回のアップロードバッチ）に属する全スコアを返す。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? AND snapshot_id = ?}。
     * 同一アップロードから取り込んだレコード群をまとめて扱うのに使う。
     *
     * @param user 対象ユーザー
     * @param snapshotId スナップショット ID
     * @return 該当スコア一覧
     */
    List<Score> findByUserAndSnapshotId(User user, String snapshotId);

    /**
     * 【メソッドの役割】 指定ユーザーのスコアを全削除する。
     *
     * 派生クエリメソッド: {@code DELETE FROM scores WHERE user_id = ?} に変換される。
     * アカウント削除時や完全リセット時の用途。呼び出し側はトランザクション必須。
     *
     * @param user 対象ユーザー
     */
    void deleteByUser(User user);

    /**
     * 【メソッドの役割】 ユーザー×曲×難易度名で最新のスコアを 1 件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? AND title = ? AND difficulty_name = ? ORDER BY uploaded_at DESC LIMIT 1}。
     * 曲別ベスト（直近値）取得に使う。未登録なら {@link Optional#empty()}。
     *
     * @param user 対象ユーザー
     * @param title 曲タイトル
     * @param difficultyName 難易度名
     * @return 最新スコア（なければ空）
     */
    java.util.Optional<Score> findFirstByUserAndTitleAndDifficultyNameOrderByUploadedAtDesc(User user, String title, String difficultyName);

    /**
     * 【メソッドの役割】 ユーザー × 複数曲 × 複数難易度 の IN 検索でスコアをまとめて取得する。
     *
     * 曲名リスト／難易度リストのいずれも {@code IN (...)} 句にバインドされる。
     * まとめて取りたいときに N+1 を避けるためのユーティリティ。
     *
     * @param user 対象ユーザー
     * @param titles 曲名リスト
     * @param difficulties 難易度名リスト
     * @return 該当スコア一覧
     */
    @Query("SELECT s FROM Score s WHERE s.user = :user AND s.title IN :titles AND s.difficultyName IN :difficulties")
    List<Score> findByUserAndTitlesAndDifficulties(@Param("user") User user, @Param("titles") List<String> titles, @Param("difficulties") List<String> difficulties);

    /**
     * 【メソッドの役割】 全ユーザーの ANOTHER / LEGGENDARIA スコアを軽量に取得する。
     *
     * ネイティブ SQL。user 情報は含めずコンパクトに返す。集計バッチ処理で使用。
     * 返却キー: userId / title / difficultyName / difficultyLevel / score
     *
     * @return 集計結果リスト
     */
    @Query(value = "SELECT s.user_id as \"userId\", s.title as \"title\", s.difficulty_name as \"difficultyName\", s.difficulty_level as \"difficultyLevel\", s.score as \"score\" FROM scores s WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')", nativeQuery = true)
    List<Map<String, Object>> findAllUserAnotherAndLeggendariaScores();

    /**
     * 【メソッドの役割】 {@link #findAllUserAnotherAndLeggendariaScores()} のユーザー情報付き版。
     *
     * users テーブルを JOIN し、表示名・IIDX ID を含めて返す。
     * 返却キー: userId / displayName / iidxId / title / difficultyName / score
     *
     * @return 集計結果リスト
     */
    @Query(value = "SELECT s.user_id as \"userId\", u.display_name as \"displayName\", u.iidx_id as \"iidxId\", s.title as \"title\", s.difficulty_name as \"difficultyName\", s.score as \"score\" FROM scores s JOIN users u ON s.user_id = u.id WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')", nativeQuery = true)
    List<Map<String, Object>> findAllUserAnotherAndLeggendariaScoresWithUserInfo();

    /**
     * 【メソッドの役割】 2 譜面の両方をプレイしているユーザーの (scoreA, scoreB) ペアを返す。
     *
     * INNER JOIN を user_id でかけるため「両方プレイ済み」のユーザーだけが残る。
     * ノーツ数（max score 算出用）も同梱して返し、フロントエンドで score_rate を計算できるようにする。
     *
     * 返却キー:
     *   userId / displayName / privacyLevel / scoreA / scoreB
     *   notesA / notesB（max_score = notes * 2 で求められる）
     *
     * @param titleA 譜面 A の曲名
     * @param diffA  譜面 A の難易度名（"ANOTHER" / "LEGGENDARIA" など）
     * @param titleB 譜面 B の曲名
     * @param diffB  譜面 B の難易度名
     * @return ペア行リスト（プレイヤー数 = list.size()）
     */
    @Query(value =
        "SELECT u.id AS \"userId\", u.display_name AS \"displayName\", u.privacy_level AS \"privacyLevel\", " +
        "       sa.score AS \"scoreA\", sb.score AS \"scoreB\", " +
        "       sda.notes AS \"notesA\", sdb.notes AS \"notesB\" " +
        "FROM scores sa " +
        "JOIN scores sb ON sb.user_id = sa.user_id " +
        "JOIN users u ON u.id = sa.user_id " +
        "LEFT JOIN song_definitions sda " +
        "       ON sda.title = sa.title AND sda.difficulty = :diffCodeA AND sda.revision = 'active' " +
        "LEFT JOIN song_definitions sdb " +
        "       ON sdb.title = sb.title AND sdb.difficulty = :diffCodeB AND sdb.revision = 'active' " +
        "WHERE sa.title = :titleA AND sa.difficulty_name = :diffA AND sa.score > 0 " +
        "  AND sb.title = :titleB AND sb.difficulty_name = :diffB AND sb.score > 0",
        nativeQuery = true)
    List<Map<String, Object>> findPairScoreScatter(
            @Param("titleA") String titleA,
            @Param("diffA") String diffA,
            @Param("diffCodeA") String diffCodeA,
            @Param("titleB") String titleB,
            @Param("diffB") String diffB,
            @Param("diffCodeB") String diffCodeB);

    /**
     * 【メソッドの役割】 譜面 A と相関の強い譜面（B 候補）を上位 limit 件返す。
     *
     * - 集計対象は ANOTHER / LEGGENDARIA 譜面のみ
     * - 両方 A 以上でプレイしているサンプルのみを使う
     * - サンプル数 >= minN
     * - **飽和譜面除外**: B 側スコア率の標準偏差 >= minStddevPct (%)
     *   （CONCEPTUAL のような「全員ほぼ満点」譜面は σ≈0 で除外され、
     *     高 r でも「予測情報量がほぼゼロ」の候補が上位に残らなくなる）
     * - ピアソン相関 r で |r| 降順ソート
     *
     * 返却キー: title / difficultyName / n / r / stddevB
     *
     * @param titleA         譜面 A の曲名
     * @param diffA          譜面 A の難易度名
     * @param diffCodeA      譜面 A の難易度コード
     * @param minN           最小サンプル数
     * @param minStddevPct   B 側 score rate (%) の最低標準偏差。例 2.0 → σ(B%) >= 2%
     * @param limit          返す候補数の上限
     */
    /**
     * 【メソッドの役割】 指定譜面に対する全ユーザーのスコアを返す（軽量版）。
     * 返却キー: userId / score
     */
    @Query(value =
        "SELECT s.user_id AS \"userId\", s.score AS \"score\" " +
        "FROM scores s " +
        "WHERE s.title = :title AND s.difficulty_name = :difficultyName AND s.score > 0",
        nativeQuery = true)
    List<Map<String, Object>> findUserScoresForChart(
            @Param("title") String title,
            @Param("difficultyName") String difficultyName);

    /**
     * 【メソッドの役割】 指定ユーザー集合の ANOTHER / LEGGENDARIA 全スコアを返す。
     * 返却キー: userId / title / difficultyName / score
     */
    @Query(value =
        "SELECT s.user_id AS \"userId\", s.title AS \"title\", " +
        "       s.difficulty_name AS \"difficultyName\", s.score AS \"score\" " +
        "FROM scores s " +
        "WHERE s.user_id IN (:userIds) " +
        "  AND s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "  AND s.score > 0",
        nativeQuery = true)
    List<Map<String, Object>> findAnotherLeggScoresForUsers(
            @Param("userIds") java.util.Collection<Long> userIds);

    /**
     * 【メソッドの役割】 「譜面 A をプレイしているユーザー」の ANOTHER/LEGG 全スコアを 1 クエリで返す。
     *
     * Java 側の 2 段階フェッチを 1 クエリに統合し、user_id の IN リスト送信のコストを避ける。
     * 返却キー: userId / title / difficultyName / score
     */
    @Query(value =
        "SELECT s.user_id AS \"userId\", s.title AS \"title\", " +
        "       s.difficulty_name AS \"difficultyName\", s.score AS \"score\" " +
        "FROM scores s " +
        "WHERE s.user_id IN ( " +
        "  SELECT sa.user_id FROM scores sa " +
        "  WHERE sa.title = :titleA AND sa.difficulty_name = :diffA AND sa.score > 0 " +
        ") " +
        "AND s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "AND s.score > 0",
        nativeQuery = true)
    List<Map<String, Object>> findAnotherLeggScoresForChartAUsers(
            @Param("titleA") String titleA,
            @Param("diffA") String diffA);

    /**
     * 【メソッドの役割】 ANOTHER / LEGGENDARIA Lv11+ 譜面の (title, difficultyName, notes) を返す。
     *
     * 伸びしろランキングは Lv11+ プレイヤー向け機能なので、Lv10 以下を除外して
     * メモリ使用量を抑える（本番 OOM 対策）。
     */
    @Query(value =
        "SELECT title AS \"title\", " +
        "       CASE difficulty WHEN '4' THEN 'ANOTHER' WHEN '10' THEN 'LEGGENDARIA' END AS \"difficultyName\", " +
        "       notes AS \"notes\" " +
        "FROM song_definitions " +
        "WHERE revision = 'active' AND difficulty IN ('4', '10') " +
        "  AND level >= 11 " +
        "  AND notes IS NOT NULL AND notes > 0",
        nativeQuery = true)
    List<Map<String, Object>> findAllAnotherLeggChartNotes();

    /**
     * 【メソッドの役割】 全ユーザーの ANOTHER / LEGGENDARIA スコアを (userId, title, difficultyName, score) で返す。
     * ペア回帰キャッシュ（{@code PairRegressionService}）構築時に一度だけ呼ばれる。
     *
     * メモリ消費抑制のため、SQL 段階で「最小ノーツ譜面の A 相当」を粗くフィルタする。
     * 400 EX = 300 notes 譜面の A 相当（300 × 2 × 0.6667 ≒ 400）。これ未満は
     * どの譜面でも A 未満なので、回帰計算の対象から確実に外せる。
     * Java 側で各譜面の正確な notes に基づくレートフィルタを再適用する。
     */
    @Query(value =
        "SELECT s.user_id AS \"userId\", s.title AS \"title\", " +
        "       s.difficulty_name AS \"difficultyName\", s.score AS \"score\" " +
        "FROM scores s " +
        "WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "  AND s.difficulty_level >= 11 " +
        "  AND s.score >= 400",
        nativeQuery = true)
    List<Map<String, Object>> findAllAnotherLeggScores();

    /**
     * 【メソッドの役割】 指定ユーザーの ANOTHER / LEGGENDARIA スコアを返す。
     * 伸びしろ算出時、対象ユーザーの「自分の点」を引くのに使う。
     */
    @Query(value =
        "SELECT s.title AS \"title\", " +
        "       s.difficulty_name AS \"difficultyName\", " +
        "       s.score AS \"score\", " +
        "       s.difficulty_level AS \"difficultyLevel\" " +
        "FROM scores s " +
        "WHERE s.user_id = :userId " +
        "  AND s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "  AND s.score > 0",
        nativeQuery = true)
    List<Map<String, Object>> findUserAnotherLeggScores(@Param("userId") Long userId);

    /**
     * 【メソッドの役割】 指定曲×譜面の「スコアランキング」を、プライバシー設定を考慮して返す。
     *
     * ネイティブ SQL。ポイント:
     *  - users と JOIN し、各ユーザーのプライバシーレベルと最新 total_beat_pt（LEFT JOIN）を取得
     *  - {@code DISTINCT ON (user_id)} で score_history_logs から各ユーザーの最新スナップショットを取り出す
     *  - 非公開ユーザーも「順位算出のため」返却するが、閲覧者に可視でない場合は
     *    userId / iidxId / displayName など識別情報を NULL でマスクして返す。
     *      ・ 可視: privacy_level = 0、自分自身、または privacy_level = 1 かつ :friendIds に含まれる
     *      ・ 非可視: それ以外（= privacy_level = 2 全般、および privacy_level = 1 の非フレンド）
     *  - スコア降順で並べる
     *
     * 返却キー: userId / iidxId / displayName / privacyLevel / score / clearType / djLevel /
     *           pgreat / great / missCount / totalBeatPt
     *
     * @param title 曲タイトル
     * @param difficultyName 難易度名
     * @param myUserId 閲覧者自身の user.id
     * @param friendIds 閲覧者のフレンド ID 一覧
     * @return ランキング行のリスト
     */
    @Query(value =
        "WITH v AS (" +
        "  SELECT s.score, s.clear_type, s.dj_level, s.pgreat, s.great, s.miss_count, " +
        "         u.id as uid, u.iidx_id, u.display_name, COALESCE(u.privacy_level, 1) as pl, " +
        "         latest.total_beat_pt, " +
        "         (COALESCE(u.privacy_level, 1) = 0 " +
        "          OR u.id = :myUserId " +
        "          OR (COALESCE(u.privacy_level, 1) = 1 AND u.id IN (:friendIds))) as vis " +
        "  FROM scores s " +
        "  JOIN users u ON s.user_id = u.id " +
        "  LEFT JOIN (" +
        "    SELECT DISTINCT ON (user_id) user_id, total_beat_pt " +
        "    FROM score_history_logs " +
        "    ORDER BY user_id, uploaded_at DESC" +
        "  ) latest ON u.id = latest.user_id " +
        "  WHERE s.title = :title AND s.difficulty_name = :difficultyName" +
        ") " +
        "SELECT " +
        "  CASE WHEN vis THEN uid ELSE NULL END as \"userId\", " +
        "  CASE WHEN vis THEN iidx_id ELSE NULL END as \"iidxId\", " +
        "  CASE WHEN vis THEN display_name ELSE NULL END as \"displayName\", " +
        "  pl as \"privacyLevel\", " +
        "  score as \"score\", " +
        "  CASE WHEN vis THEN clear_type ELSE NULL END as \"clearType\", " +
        "  CASE WHEN vis THEN dj_level ELSE NULL END as \"djLevel\", " +
        "  CASE WHEN vis THEN pgreat ELSE NULL END as \"pgreat\", " +
        "  CASE WHEN vis THEN great ELSE NULL END as \"great\", " +
        "  CASE WHEN vis THEN miss_count ELSE NULL END as \"missCount\", " +
        "  CASE WHEN vis THEN COALESCE(total_beat_pt, 0) ELSE NULL END as \"totalBeatPt\" " +
        "FROM v " +
        "ORDER BY score DESC",
        nativeQuery = true)
    List<Map<String, Object>> findSongRanking(
            @Param("title") String title,
            @Param("difficultyName") String difficultyName,
            @Param("myUserId") Long myUserId,
            @Param("friendIds") List<Long> friendIds);

    /**
     * 【メソッドの役割】 管理者ユーザーの曲別順位を一覧化する（基準値確認用）。
     *
     * ネイティブ SQL。CTE の構成:
     *  - {@code best_scores}: 曲×譜面×ユーザー単位で最大スコアを抽出（ANOTHER/LEGGENDARIA 限定）
     *  - {@code all_ranks}: {@code RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC)}
     *    で順位を付け、PARTITION ごとの全参加者数を total として付与
     *  - 最後に {@code WHERE user_id = :adminUserId} で管理者分のみ抽出
     *
     * 返却キー: title / difficultyName / difficultyLevel / rank / total
     *
     * @param adminUserId 管理者ユーザー ID
     * @return 曲ごとの順位一覧
     */
    @Query(value =
        "WITH best_scores AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score" +
        "  FROM scores" +
        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0" +
        "  GROUP BY title, difficulty_name, difficulty_level, user_id" +
        "), " +
        "all_ranks AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, score," +
        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank," +
        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total" +
        "  FROM best_scores" +
        ") " +
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  rank as \"rank\", total as \"total\" " +
        "FROM all_ranks " +
        "WHERE user_id = :adminUserId " +
        "ORDER BY rank ASC, title ASC",
        nativeQuery = true)
    List<Map<String, Object>> findAdminSongRanks(@Param("adminUserId") Long adminUserId);

    /**
     * 【メソッドの役割】 任意ユーザーの曲別順位を一覧化する。
     *
     * クエリ構造は {@link #findAdminSongRanks(Long)} と同一で、対象ユーザー ID の扱いだけ異なる。
     * マイページの「曲別ランク」表示などに使う。
     *
     * @param userId 対象ユーザー ID
     * @return 曲ごとの順位一覧
     */
    @Query(value =
        "WITH best_scores AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score" +
        "  FROM scores" +
        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0" +
        "  GROUP BY title, difficulty_name, difficulty_level, user_id" +
        "), " +
        "all_ranks AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, score," +
        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank," +
        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total" +
        "  FROM best_scores" +
        ") " +
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  rank as \"rank\", total as \"total\" " +
        "FROM all_ranks " +
        "WHERE user_id = :userId " +
        "ORDER BY rank ASC, title ASC",
        nativeQuery = true)
    List<Map<String, Object>> findUserSongRanks(@Param("userId") Long userId);

    /**
     * 【メソッドの役割】 全ユーザー×全曲の順位をまとめて取得する。
     *
     * {@link #findAdminSongRanks(Long)} と同じ CTE 構成だが、WHERE を外して全件返す。
     * {@code user_song_ranks} テーブルへの再構築用途など、バッチ用のヘビークエリ。
     *
     * 返却キー: userId / title / difficultyName / difficultyLevel / rank / total
     *
     * @return 全ユーザー分の順位レコード
     */
    @Query(value =
        "WITH best_scores AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score" +
        "  FROM scores" +
        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0" +
        "  GROUP BY title, difficulty_name, difficulty_level, user_id" +
        "), " +
        "all_ranks AS (" +
        "  SELECT title, difficulty_name, difficulty_level, user_id, score," +
        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank," +
        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total" +
        "  FROM best_scores" +
        ") " +
        "SELECT user_id as \"userId\", title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  rank as \"rank\", total as \"total\" " +
        "FROM all_ranks " +
        "ORDER BY user_id, rank ASC, title ASC",
        nativeQuery = true)
    List<Map<String, Object>> findAllUserSongRanks();

    /**
     * 【メソッドの役割】 「BeatTier（total_beat_pt によるティア）別の曲×難易度ごとの平均スコア」を集計する。
     *
     * ネイティブ SQL（フラット化版）:
     *  - scores × users × song_definitions を 1 段で JOIN する
     *  - users.total_beat_pt の閾値から beat_tier（Legend／Mythic／…）と
     *    tier_level（ティア内小ランク）を CASE 式でその場に算出
     *  - song_definitions は ANOTHER → difficulty '4'、LEGGENDARIA → difficulty '10' のマッピング
     *  - {@code WHERE (s.score * 3) >= (sd.notes * 4)} は
     *    「スコア率 ≥ (notes*4 / 3) / (notes*2) = 66.66...%」の足切り（A 相当）
     *  - tier × 曲 × 譜面でグループ化し平均・人数をとり、
     *    最終 SELECT で曲×譜面単位に json_agg → ::text 化して返す
     *
     * 旧実装にあった {@code best_scores} の MAX(score) GROUP BY は、
     * scores テーブルの一意制約 (user_id, title, difficulty_name, difficulty_level)
     * により 1 グループ 1 行が保証されているため不要であり、削除済み。
     * ペイロードの増加に伴うクエリタイムアウト（statement_timeout 30s）回避のための最適化。
     *
     * 返却キー: title / difficultyName / difficultyLevel / tierData（JSON 文字列）
     *
     * @return 曲ごとの tier 別集計
     */
    @Query(value =
        "WITH agg_scores AS (" +
        "  SELECT s.title, s.difficulty_name, s.difficulty_level," +
        "    CASE" +
        "      WHEN u.total_beat_pt >= 18000 THEN 'Legend'" +
        "      WHEN u.total_beat_pt >= 17500 THEN 'Mythic'" +
        "      WHEN u.total_beat_pt >= 17000 THEN 'Ancient'" +
        "      WHEN u.total_beat_pt >= 16500 THEN 'Master'" +
        "      WHEN u.total_beat_pt >= 16000 THEN 'Elite'" +
        "      WHEN u.total_beat_pt >= 15500 THEN 'Commander'" +
        "      WHEN u.total_beat_pt >= 15000 THEN 'Veteran'" +
        "      WHEN u.total_beat_pt >= 14000 THEN 'Expert'" +
        "      WHEN u.total_beat_pt >= 13000 THEN 'Advanced'" +
        "      WHEN u.total_beat_pt >= 12000 THEN 'Intermediate'" +
        "      WHEN u.total_beat_pt >= 10000 THEN 'Novice'" +
        "      ELSE 'Beginner'" +
        "    END AS beat_tier," +
        "    CASE" +
        "      WHEN u.total_beat_pt >= 18000 THEN 0" +
        "      WHEN u.total_beat_pt >= 17500 THEN FLOOR((u.total_beat_pt - 17500)/100) + 1" +
        "      WHEN u.total_beat_pt >= 17000 THEN FLOOR((u.total_beat_pt - 17000)/100) + 1" +
        "      WHEN u.total_beat_pt >= 16500 THEN FLOOR((u.total_beat_pt - 16500)/100) + 1" +
        "      WHEN u.total_beat_pt >= 16000 THEN FLOOR((u.total_beat_pt - 16000)/100) + 1" +
        "      WHEN u.total_beat_pt >= 15500 THEN FLOOR((u.total_beat_pt - 15500)/100) + 1" +
        "      WHEN u.total_beat_pt >= 15000 THEN FLOOR((u.total_beat_pt - 15000)/100) + 1" +
        "      WHEN u.total_beat_pt >= 14000 THEN FLOOR((u.total_beat_pt - 14000)/200) + 1" +
        "      WHEN u.total_beat_pt >= 13000 THEN FLOOR((u.total_beat_pt - 13000)/200) + 1" +
        "      WHEN u.total_beat_pt >= 12000 THEN FLOOR((u.total_beat_pt - 12000)/200) + 1" +
        "      WHEN u.total_beat_pt >= 10000 THEN FLOOR((u.total_beat_pt - 10000)/400) + 1" +
        "      ELSE 0" +
        "    END AS tier_level," +
        "    ROUND(AVG(s.score)) AS avg_score," +
        "    COUNT(*) AS user_count" +
        "  FROM scores s" +
        "  JOIN users u ON s.user_id = u.id AND u.total_beat_pt > 0" +
        "  JOIN song_definitions sd" +
        "    ON sd.title = s.title" +
        "    AND sd.revision = 'active'" +
        "    AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10'))" +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA')" +
        "    AND s.difficulty_level IN (11, 12)" +
        "    AND s.score > 0" +
        // 足切り (s.score*3 >= sd.notes*4) はスコア率 66.66...% に相当。
        // findAllSongRankingAggregates / calculateDifficultySimulation の
        // "score_rate > 66.666" と同じ意味的閾値。
        "    AND (s.score * 3) >= (sd.notes * 4)" +
        "  GROUP BY s.title, s.difficulty_name, s.difficulty_level, beat_tier, tier_level" +
        ") " +
        "SELECT title as \"title\", difficulty_name as \"difficultyName\", difficulty_level as \"difficultyLevel\"," +
        "  json_agg(" +
        "    json_build_object(" +
        "      'beatTier', beat_tier," +
        "      'tierLevel', tier_level," +
        "      'avgScore', avg_score," +
        "      'userCount', user_count" +
        "    )" +
        "  )\\:\\:text as \"tierData\" " +
        "FROM agg_scores " +
        "GROUP BY title, difficulty_name, difficulty_level " +
        "ORDER BY title, difficulty_name",
        nativeQuery = true)
    List<Map<String, Object>> findRawSongScoresWithBeatTier();
    /**
     * 【メソッドの役割】 非公式難易度表（active）に基づく曲×譜面ごとの beat_pt 集計。
     *
     * ネイティブ SQL。CTE 概要:
     *  - {@code weight_map}: ランク値（11.0〜13.0）を beat_pt 計算用の重みにマップ
     *  - {@code song_ranks}: difficulty_ranks（revision='active'）と曲を結合し重みを付与
     *  - {@code scored_data}: scores と song_definitions を JOIN し
     *    {@code score_rate = score * 100 / (notes * 2)} を算出。
     *    LEGGENDARIA は title に {@code ' [L]'} を付与してランク表のキーと合わせる
     *  - {@code valid_scores}: score_rate > 66.666% に絞り、beat_pt を算式
     *      {@code POWER(rate/100, 1.3) * weight + weight * boost}
     *    （boost は rate 帯に応じた加算）で計算
     *  - {@code ranked_scores}: ユーザー単位で beat_pt 降順に {@code ROW_NUMBER()} を振る
     *  - 最終 SELECT で {@code rn <= 100}（各ユーザーの上位 100 曲）に絞り、
     *    曲×譜面×ランク値ごとの集計（人数・平均・最大）を返す
     *
     * 返却キー: title / difficultyName / informalRank / userCount / avgBeatPt / maxBeatPt
     *
     * @return 集計行のリスト
     */
    @Query(value =
        // 注意: weight_map 定義は calculateDifficultySimulation と共通。WEIGHT_MAP_VALUES 定数を参照。
        "WITH " + WEIGHT_MAP_VALUES + ", " +
        "song_ranks AS ( " +
        "  SELECT drs.song_title AS mapped_title, dr.rank_value, wm.wt AS weight " +
        "  FROM difficulty_ranks dr " +
        "  JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id " +
        "  LEFT JOIN weight_map wm ON wm.rv = SUBSTRING(dr.rank_value FROM '^\\d+\\.\\d+') " +
        "  WHERE dr.revision = 'active' " +
        "), " +
        "scored_data AS ( " +
        "  SELECT " +
        "    s.user_id, s.title, s.difficulty_name, " +
        "    sr.rank_value AS informal_rank, sr.weight, " +
        // 注意: score_rate 算式は calculateDifficultySimulation と共通。SCORE_RATE_FORMULA 定数を参照。
        "    " + SCORE_RATE_FORMULA + " AS score_rate " +
        "  FROM scores s " +
        "  JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "    AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "  JOIN song_ranks sr ON sr.mapped_title = (CASE WHEN s.difficulty_name = 'LEGGENDARIA' THEN s.title || ' [L]' ELSE s.title END) " +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0 " +
        "), " +
        "valid_scores AS ( " +
        "  SELECT " +
        "    user_id, title, difficulty_name, informal_rank, " +
        // 注意: 以下の boost CASE 式は calculateDifficultySimulation にも同様の式が存在。
        //       ただし参照する列名 (score_rate / b.score_rate) の違い、および元の @Query
        //       文字列を 1 文字たりとも変えない制約により、定数化は行わずコメントで明示。
        "    (POWER(score_rate / 100.0, 1.3) * weight) + " +
        "    (weight * CASE " +
        "      WHEN score_rate > 94.44 THEN 0.03 " +
        "      WHEN score_rate > 88.88 THEN 0.02 " +
        "      WHEN score_rate > 77.77 THEN 0.01 " +
        "      ELSE 0.0 END) AS beat_pt " +
        "  FROM scored_data " +
        "  WHERE score_rate > 66.666 AND weight IS NOT NULL " +
        "), " +
        "ranked_scores AS ( " +
        "  SELECT *, ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY beat_pt DESC) AS rn " +
        "  FROM valid_scores " +
        ") " +
        "SELECT " +
        "  title AS \"title\", difficulty_name AS \"difficultyName\", informal_rank AS \"informalRank\", " +
        "  COUNT(*) AS \"userCount\", ROUND(AVG(beat_pt)::numeric, 1) AS \"avgBeatPt\", ROUND(MAX(beat_pt)::numeric, 1) AS \"maxBeatPt\" " +
        "FROM ranked_scores " +
        "WHERE rn <= 100 " +
        "GROUP BY title, difficulty_name, informal_rank " +
        "ORDER BY \"userCount\" DESC, \"avgBeatPt\" DESC", nativeQuery = true)
    List<Map<String, Object>> findAllSongRankingAggregates();

    /**
     * 【メソッドの役割】 レベル 11/12 の ANOTHER/LEGGENDARIA について、曲×譜面ごとの平均スコアと
     * プレイ人数を集計する。
     *
     * シンプルな集計。{@code score > 0} で未プレイを除外、
     * {@code ROUND(AVG(score)::numeric, 1)} で小数第 1 位までの平均を返す。
     *
     * 返却キー: title / difficultyName / avgScore / playerCount
     *
     * @return 集計リスト
     */
    @Query(value =
        "SELECT title AS \"title\", difficulty_name AS \"difficultyName\", " +
        "  ROUND(AVG(score)::numeric, 1) AS \"avgScore\", COUNT(*) AS \"playerCount\" " +
        "FROM scores " +
        "WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "  AND difficulty_level IN (11, 12) " +
        "  AND score > 0 " +
        "GROUP BY title, difficulty_name " +
        "ORDER BY title, difficulty_name", nativeQuery = true)
    List<Map<String, Object>> findSongAvgScores();

    /**
     * 【メソッドの役割】 曲×譜面ごとに「MAX-（理論値の 17/18 相当）達成人数」と総プレイ人数を集計する。
     *
     * {@code score * 9 >= notes * 17} は数式変形すると「スコア率 ≥ 17/18 ≒ 94.44%」を意味し、
     * いわゆる「MAX-」相当の達成判定。
     * song_definitions（active）と JOIN し、譜面メタの notes を使って厳密判定する。
     *
     * 返却キー: title / difficultyName / maxMinusCount / totalCount
     *
     * @return 集計リスト
     */
    @Query(value =
        "SELECT s.title AS \"title\", s.difficulty_name AS \"difficultyName\", " +
        "  COUNT(CASE WHEN s.score * 9 >= sd.notes * 17 THEN 1 END) AS \"maxMinusCount\", " +
        "  COUNT(*) AS \"totalCount\" " +
        "FROM scores s " +
        "JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "  AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') " +
        "    OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "  AND s.difficulty_level IN (11, 12) " +
        "  AND s.score > 0 " +
        "  AND sd.level >= 11 " +
        "GROUP BY s.title, s.difficulty_name", nativeQuery = true)
    List<Map<String, Object>> findSongMaxMinusCounts();

    /**
     * 【メソッドの役割】 曲×譜面ごとに AAA（スコア率 ≥ 8/9 ≒ 88.88%）達成人数と総プレイ人数を集計する。
     *
     * 判定式 {@code score * 9 >= notes * 16} は「スコア率 ≥ 16/18 = 8/9」に等しい。
     * クエリ構造は {@link #findSongMaxMinusCounts()} と同じで、閾値だけが異なる。
     *
     * 返却キー: title / difficultyName / aaaCount / totalCount
     *
     * @return 集計リスト
     */
    @Query(value =
        "SELECT s.title AS \"title\", s.difficulty_name AS \"difficultyName\", " +
        "  COUNT(CASE WHEN s.score * 9 >= sd.notes * 16 THEN 1 END) AS \"aaaCount\", " +
        "  COUNT(*) AS \"totalCount\" " +
        "FROM scores s " +
        "JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "  AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') " +
        "    OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') " +
        "  AND s.difficulty_level IN (11, 12) " +
        "  AND s.score > 0 " +
        "  AND sd.level >= 11 " +
        "GROUP BY s.title, s.difficulty_name", nativeQuery = true)
    List<Map<String, Object>> findSongAaaCounts();

    /**
     * 【メソッドの役割】 非公式難易度表の draft 版を「仮適用」した場合の beat_pt 推移を
     * ユーザーごとにシミュレーションする。
     *
     * ネイティブ SQL。ロジック概要:
     *  - {@code weight_map}: ランク値 → 重みマッピング
     *  - {@code active_weights} / {@code draft_weights}: active と draft それぞれで曲別重みを算出
     *  - {@code changed_weights}: draft で重みが変化した曲（比率を保持）
     *  - {@code draft_only}: draft で新規追加された曲
     *  - {@code base_scores}: score_rate > 66.666% のスコア基礎集計（LEGGENDARIA の title マッピング含む）
     *  - {@code active_calc} / {@code active_ranked} / {@code sum_active}:
     *    active の重みで beat_pt を算出 → ユーザー上位 100 を集計
     *  - {@code draft_calc}: 既存曲は (active bg_pt × 重み比率) に補正、draft 新規曲は素の計算式で追加
     *  - {@code draft_ranked} / {@code sum_draft}: 同様に上位 100 を集計
     *  - 最終 SELECT: ユーザーごとに「現在 beat_pt」「draft 適用時 beat_pt」「差分」を並べる
     *
     * 返却キー: displayName / iidxId / currentBeatPt / simulatedBeatPt / ptDelta
     *
     * @return シミュレーション結果リスト
     */
    @Query(value =
        // 注意: weight_map 定義は findAllSongRankingAggregates と共通。WEIGHT_MAP_VALUES 定数を参照。
        "WITH " + WEIGHT_MAP_VALUES + ", " +
        "active_weights AS ( " +
        "  SELECT drs.song_title AS mapped_title, wm.wt AS weight " +
        "  FROM difficulty_ranks dr " +
        "  JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id " +
        "  INNER JOIN weight_map wm ON wm.rv = SUBSTRING(dr.rank_value FROM '^\\d+\\.\\d+') " +
        "  WHERE dr.revision = 'active' " +
        "), " +
        "draft_weights AS ( " +
        "  SELECT drs.song_title AS mapped_title, wm.wt AS weight " +
        "  FROM difficulty_ranks dr " +
        "  JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id " +
        "  INNER JOIN weight_map wm ON wm.rv = SUBSTRING(dr.rank_value FROM '^\\d+\\.\\d+') " +
        "  WHERE dr.revision = 'draft' " +
        "), " +
        "changed_weights AS ( " +
        "  SELECT dw.mapped_title, dw.weight::double precision / aw.weight::double precision AS weight_ratio " +
        "  FROM draft_weights dw " +
        "  JOIN active_weights aw ON dw.mapped_title = aw.mapped_title " +
        "  WHERE dw.weight != aw.weight " +
        "), " +
        "draft_only AS ( " +
        "  SELECT dw.mapped_title, dw.weight " +
        "  FROM draft_weights dw " +
        "  WHERE NOT EXISTS (SELECT 1 FROM active_weights aw WHERE aw.mapped_title = dw.mapped_title) " +
        "), " +
        "base_scores AS ( " +
        "  SELECT " +
        "    s.user_id, u.display_name, u.iidx_id, " +
        "    (CASE WHEN s.difficulty_name = 'LEGGENDARIA' THEN s.title || '[L]' ELSE s.title END) AS mapped_title, " +
        // 注意: score_rate 算式は findAllSongRankingAggregates と共通。SCORE_RATE_FORMULA 定数を参照。
        "    " + SCORE_RATE_FORMULA + " AS score_rate " +
        "  FROM scores s " +
        "  JOIN users u ON s.user_id = u.id " +
        "  JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "    AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0 " +
        "    AND s.difficulty_level >= 11 " +
        "    AND " + SCORE_RATE_FORMULA + " > 66.666 " +
        "), " +
        "active_calc AS ( " +
        "  SELECT b.user_id, b.display_name, b.iidx_id, b.mapped_title, " +
        "         (POWER(b.score_rate / 100.0, 1.3) * aw.weight) + " +
        // 注意: boost の CASE 式は findAllSongRankingAggregates と共通だが、ここでは b.score_rate
        //       列を参照するため BEAT_PT_BOOST_CASE 定数（列名 score_rate 参照）は使えない。
        //       列別名で揃えるために敢えて展開維持。
        "         (aw.weight * CASE WHEN b.score_rate > 94.44 THEN 0.03 WHEN b.score_rate > 88.88 THEN 0.02 WHEN b.score_rate > 77.77 THEN 0.01 ELSE 0.0 END) AS bg_pt " +
        "  FROM base_scores b " +
        "  JOIN active_weights aw ON b.mapped_title = aw.mapped_title " +
        "), " +
        "active_ranked AS ( " +
        "  SELECT user_id, display_name, iidx_id, bg_pt, " +
        "         ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY bg_pt DESC) AS rn " +
        "  FROM active_calc " +
        "), " +
        "sum_active AS ( " +
        "  SELECT user_id, display_name, iidx_id, SUM(bg_pt) as total_active " +
        "  FROM active_ranked WHERE rn <= 100 GROUP BY user_id, display_name, iidx_id " +
        "), " +
        "draft_calc AS ( " +
        "  SELECT ac.user_id, " +
        "         CASE WHEN cw.weight_ratio IS NOT NULL THEN ac.bg_pt * cw.weight_ratio ELSE ac.bg_pt END AS bg_pt " +
        "  FROM active_calc ac " +
        "  LEFT JOIN changed_weights cw ON ac.mapped_title = cw.mapped_title " +
        "  UNION ALL " +
        "  SELECT b.user_id, " +
        "         (POWER(b.score_rate / 100.0, 1.3) * donly.weight) + " +
        // 同上（b.score_rate 参照のため展開維持）。
        "         (donly.weight * CASE WHEN b.score_rate > 94.44 THEN 0.03 WHEN b.score_rate > 88.88 THEN 0.02 WHEN b.score_rate > 77.77 THEN 0.01 ELSE 0.0 END) AS bg_pt " +
        "  FROM base_scores b " +
        "  JOIN draft_only donly ON b.mapped_title = donly.mapped_title " +
        "), " +
        "draft_ranked AS ( " +
        "  SELECT user_id, bg_pt, " +
        "         ROW_NUMBER() OVER(PARTITION BY user_id ORDER BY bg_pt DESC) AS rn " +
        "  FROM draft_calc " +
        "), " +
        "sum_draft AS ( " +
        "  SELECT user_id, SUM(bg_pt) as total_draft " +
        "  FROM draft_ranked WHERE rn <= 100 GROUP BY user_id " +
        ") " +
        "SELECT a.display_name as \"displayName\", a.iidx_id as \"iidxId\", " +
        "       ROUND(a.total_active::numeric, 1) as \"currentBeatPt\", " +
        "       ROUND(COALESCE(d.total_draft, a.total_active)::numeric, 1) as \"simulatedBeatPt\", " +
        "       ROUND((COALESCE(d.total_draft, a.total_active) - a.total_active)::numeric, 1) as \"ptDelta\" " +
        "FROM sum_active a " +
        "LEFT JOIN sum_draft d ON a.user_id = d.user_id " +
        "ORDER BY \"simulatedBeatPt\" DESC", nativeQuery = true)
    List<Map<String, Object>> calculateDifficultySimulation();

    /**
     * 【メソッドの役割】 指定した非公式難易度（ランク値 "11.0"〜"13.0"）に属する全曲の合計 BEAT-PT で
     * ユーザーをランキング集計する。
     *
     * 計算方式は {@link #findAllSongRankingAggregates()} と同じ式（POWER + boost）だが、
     * 集計範囲をフォルダ単位に絞り、ユーザー上位 100 曲のキャップは適用しない。
     * これは「☆XX フォルダ単体のランキング」という用途のため、各フォルダ内の全プレイ曲を合算する。
     *
     * 平均スコアレートは（合計スコア / 合計最大スコア × 100）でフロントエンド側で算出可能なように
     * totalScore / totalMaxScore も同梱して返す。
     *
     * 返却キー:
     *   userId / displayName / iidxId / privacyLevel / lastUpdatedAt / isSupporter /
     *   totalBeatPt / totalScore / totalMaxScore / playedCount
     *
     * @param rank 非公式ランクの先頭部分（"11.0" 〜 "13.0"）。dr.rank_value の SUBSTRING と比較する
     * @return ユーザーごとの集計行（合計 BEAT-PT 降順）
     */
    @Query(value =
        "WITH " + WEIGHT_MAP_VALUES + ", " +
        "rank_songs AS ( " +
        "  SELECT drs.song_title AS mapped_title " +
        "  FROM difficulty_ranks dr " +
        "  JOIN difficulty_rank_songs drs ON dr.id = drs.difficulty_rank_id " +
        "  WHERE dr.revision = 'active' " +
        "    AND SUBSTRING(dr.rank_value FROM '^\\d+\\.\\d+') = :rank " +
        "), " +
        "weight_for_rank AS ( SELECT wt FROM weight_map WHERE rv = :rank ), " +
        "scored_data AS ( " +
        "  SELECT " +
        "    s.user_id, " +
        // 注意: difficulty_rank_songs.song_title は LEGGENDARIA 譜面の場合「タイトル + '[L]'」（スペース無し）で
        //       格納されているため、ここでも同じ結合キーに揃える。スペースを入れると 0 件マッチになる。
        "    (CASE WHEN s.difficulty_name = 'LEGGENDARIA' THEN s.title || '[L]' ELSE s.title END) AS mapped_title, " +
        "    s.score AS raw_score, " +
        "    sd.notes * 2 AS max_score, " +
        "    " + SCORE_RATE_FORMULA + " AS score_rate " +
        "  FROM scores s " +
        "  JOIN song_definitions sd ON s.title = sd.title AND sd.revision = 'active' " +
        "    AND ((s.difficulty_name = 'ANOTHER' AND sd.difficulty = '4') OR (s.difficulty_name = 'LEGGENDARIA' AND sd.difficulty = '10')) " +
        "  WHERE s.difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND s.score > 0 " +
        "), " +
        "in_rank AS ( " +
        "  SELECT sd.user_id, sd.raw_score, sd.max_score, sd.score_rate " +
        "  FROM scored_data sd " +
        "  JOIN rank_songs rs ON rs.mapped_title = sd.mapped_title " +
        "), " +
        "with_pt AS ( " +
        "  SELECT ir.user_id, ir.raw_score, ir.max_score, ir.score_rate, " +
        "    CASE WHEN ir.score_rate > 66.666 THEN " +
        "      (POWER(ir.score_rate / 100.0, 1.3) * w.wt) + " +
        "      (w.wt * CASE " +
        "        WHEN ir.score_rate > 94.44 THEN 0.03 " +
        "        WHEN ir.score_rate > 88.88 THEN 0.02 " +
        "        WHEN ir.score_rate > 77.77 THEN 0.01 " +
        "        ELSE 0.0 END) " +
        "    ELSE 0.0 END AS beat_pt " +
        "  FROM in_rank ir CROSS JOIN weight_for_rank w " +
        "), " +
        "user_totals AS ( " +
        "  SELECT user_id, " +
        "         SUM(beat_pt) AS total_pt, " +
        "         SUM(raw_score) AS total_score, " +
        "         SUM(max_score) AS total_max_score, " +
        "         COUNT(*) AS played_count " +
        "  FROM with_pt " +
        "  GROUP BY user_id " +
        "), " +
        "latest_log AS ( " +
        "  SELECT DISTINCT ON (user_id) user_id, uploaded_at " +
        "  FROM score_history_logs " +
        "  ORDER BY user_id, uploaded_at DESC " +
        ") " +
        "SELECT u.id AS \"userId\", u.display_name AS \"displayName\", u.iidx_id AS \"iidxId\", " +
        "       COALESCE(u.privacy_level, 1) AS \"privacyLevel\", " +
        "       ll.uploaded_at AS \"lastUpdatedAt\", " +
        "       COALESCE(u.is_supporter, false) AND COALESCE(u.show_supporter_border, true) AS \"isSupporter\", " +
        "       ROUND(ut.total_pt::numeric, 1) AS \"totalBeatPt\", " +
        "       ut.total_score AS \"totalScore\", " +
        "       ut.total_max_score AS \"totalMaxScore\", " +
        "       ut.played_count AS \"playedCount\" " +
        "FROM user_totals ut " +
        "JOIN users u ON ut.user_id = u.id " +
        "LEFT JOIN latest_log ll ON ll.user_id = u.id " +
        "ORDER BY ut.total_pt DESC, ut.played_count DESC", nativeQuery = true)
    List<Map<String, Object>> findRankingByInformalRank(@Param("rank") String rank);

    /**
     * 【メソッドの役割】 {@code user_song_ranks} テーブルへ、全ユーザーの曲別順位を一括 INSERT する。
     *
     * ネイティブ SQL の {@code INSERT ... SELECT}。
     *  - {@code best_scores}: ユーザー×曲×譜面単位で最大スコアを抽出
     *  - {@code all_ranks}: RANK() で順位、COUNT() OVER で参加者総数を付与
     *  - 最終 SELECT で user_song_ranks のカラム順に並べて NOW() を calculated_at に入れる
     *
     * 通常は直前に {@link #truncateUserSongRanks()} を呼んでから実行し、
     * ランキング表示用のキャッシュを再構築する。
     */
    @Modifying
    @Query(value =
        "INSERT INTO user_song_ranks (user_id, title, difficulty_name, difficulty_level, rank, total, calculated_at) " +
        "WITH best_scores AS ( " +
        "  SELECT title, difficulty_name, difficulty_level, user_id, MAX(score) AS score " +
        "  FROM scores " +
        "  WHERE difficulty_name IN ('ANOTHER', 'LEGGENDARIA') AND score > 0 " +
        "  GROUP BY title, difficulty_name, difficulty_level, user_id " +
        "), " +
        "all_ranks AS ( " +
        "  SELECT title, difficulty_name, difficulty_level, user_id, score, " +
        "    RANK() OVER (PARTITION BY title, difficulty_name ORDER BY score DESC) AS rank, " +
        "    COUNT(*) OVER (PARTITION BY title, difficulty_name) AS total " +
        "  FROM best_scores " +
        ") " +
        "SELECT user_id, title, difficulty_name, difficulty_level, rank, total, NOW() " +
        "FROM all_ranks", nativeQuery = true)
    void insertAllUserSongRanks();

    /**
     * 【メソッドの役割】 {@code user_song_ranks} テーブルを TRUNCATE（全削除）する。
     *
     * ネイティブ SQL。{@link #insertAllUserSongRanks()} と組み合わせて
     * 順位キャッシュを丸ごとリビルドするバッチで使う。
     * {@code TRUNCATE} は DELETE より高速だが、トランザクション／制約の扱いに注意。
     */
    @Modifying
    @Query(value = "TRUNCATE TABLE user_song_ranks", nativeQuery = true)
    void truncateUserSongRanks();
}
