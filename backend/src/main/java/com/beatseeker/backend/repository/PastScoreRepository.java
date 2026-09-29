package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.PastScore;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;

/**
 * 【リポジトリの役割】 過去作スコア（{@link PastScore}）の永続化アクセス。
 *
 * 本リポジトリはランキングや BEAT-PT の集計には一切関与しない。
 * 「本人の過去スコアを引く / 取り込む / 作品ごと消す」だけを担う。
 * 例外: 前作（アーカイブのある作品）の曲別ランキング {@link #findArchivedSongRanking}。
 *   現行作の集計には混ざらず、作品を指定したときだけ単独で使う。
 */
public interface PastScoreRepository extends JpaRepository<PastScore, Long> {

    /**
     * 指定ユーザー・指定作品の全レコードを取得する。
     * 取り込み時にベストレコードマージの突き合わせ元として使う。
     */
    List<PastScore> findByUserAndVersion(User user, Integer version);

    /**
     * 【メソッドの役割】 ユーザー × 複数曲 × 複数難易度 の IN 検索で過去作スコアを取得する。
     *
     * {@code ScoreRepository#findByUserAndTitlesAndDifficulties} の過去作版。
     * リーグの課題曲選定で「歴代自己ベスト」を組み立てる際に、現行作のスコアと突き合わせる。
     * 作品をまたいで同じ譜面が複数行返るため、呼び出し側で EX の最大値に集約すること。
     *
     * 未プレー（score = 0）は最初から除外する。
     *
     * @param user         対象ユーザー
     * @param titles       曲名リスト
     * @param difficulties 難易度名リスト
     * @return 該当スコア一覧（作品バージョンは問わない）
     */
    @Query("SELECT p FROM PastScore p WHERE p.user = :user AND p.title IN :titles " +
           "AND p.difficultyName IN :difficulties AND p.score > 0")
    List<PastScore> findByUserAndTitlesAndDifficulties(@Param("user") User user,
                                                       @Param("titles") List<String> titles,
                                                       @Param("difficulties") List<String> difficulties);

    /**
     * 指定ユーザーの過去作スコアを全件取得する（作品昇順 → 曲名昇順）。
     * 歴代ベスト表示用の一括取得に使う。
     */
    List<PastScore> findByUserOrderByVersionAscTitleAsc(User user);

    /** 指定ユーザー・指定作品のレコード件数。 */
    long countByUserAndVersion(User user, Integer version);

    /**
     * 指定ユーザーに過去作スコアが 1 件でもあるか。
     * リーグの参加ゲート（{@code app.league.require-past-scores-to-join}）の判定に使う。
     */
    boolean existsByUser(User user);

    /**
     * 指定ユーザー・指定作品のレコードを全削除する。
     * 取り込みミスのリカバリ手段。テーブルが分かれているため既存集計への影響はない。
     */
    @Modifying
    void deleteByUserAndVersion(User user, Integer version);

    /** ユーザー削除時などに使う一括削除。 */
    @Modifying
    void deleteByUser(User user);

    /**
     * 作品ごとのサマリ（譜面数 / 最終取り込み日時 / CSV 上の最終プレー日時）を返す。
     *
     * 返り値 1 行の配列レイアウト: [0]=version(Integer), [1]=count(Long),
     * [2]=importedAt(LocalDateTime), [3]=lastPlayedAt(String)
     */
    @Query("SELECT p.version, COUNT(p), MAX(p.importedAt), MAX(p.lastPlayedAt) " +
            "FROM PastScore p WHERE p.user = :user GROUP BY p.version ORDER BY p.version DESC")
    List<Object[]> findSummaryByUser(@Param("user") User user);

    /**
     * 【メソッドの役割】 過去作（アーカイブのある作品）の指定曲×譜面の曲別ランキングを、
     * プライバシー設定を考慮して返す。
     *
     * {@code ScoreRepository#findSongRanking} の過去作版。返却キーも同じにしてあるので、
     * フロントは現行作と同じ描画ロジックで表示できる。違いは次の 2 点だけ:
     *  - スコアの出どころが {@code past_scores}（version 指定）
     *  - totalBeatPt が {@code version_pt_snapshots}（その作品の最終 BEAT-PT）
     *
     * 可視性はランキングを見る「今」の関係で決める（現在の privacy_level / フレンド）。
     * 非可視ユーザーも順位算出のため返すが、識別情報は NULL でマスクする。
     * pgreatGreat（PGREAT+GREAT）は散布図の全ユーザー回帰線用に、score と同じく匿名値としてマスクしない。
     *
     * @param version        作品バージョン（例: 33）
     * @param title          曲タイトル
     * @param difficultyName 難易度名
     * @param myUserId       閲覧者自身の user.id
     * @param friendIds      閲覧者のフレンド ID 一覧（空なら -1 を 1 件入れて渡す）
     * @param seeAll         true なら公開設定に関係なく全員を可視にする（管理者用）
     * @return ランキング行のリスト（スコア降順）
     */
    @Query(value =
        "WITH v AS (" +
        "  SELECT p.score, p.clear_type, p.dj_level, p.pgreat, p.great, p.miss_count, " +
        "         u.id as uid, u.iidx_id, u.display_name, COALESCE(u.privacy_level, 1) as pl, " +
        "         snap.total_beat_pt, " +
        "         (:seeAll = true " +
        "          OR COALESCE(u.privacy_level, 1) = 0 " +
        "          OR u.id = :myUserId " +
        "          OR (COALESCE(u.privacy_level, 1) = 1 AND u.id IN (:friendIds))) as vis " +
        "  FROM past_scores p " +
        "  JOIN users u ON p.user_id = u.id " +
        "  LEFT JOIN version_pt_snapshots snap ON snap.user_id = u.id AND snap.version = :version " +
        "  WHERE p.version = :version AND p.title = :title AND p.difficulty_name = :difficultyName " +
        "    AND p.score > 0" +
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
        "  (pgreat + great) as \"pgreatGreat\", " +
        "  CASE WHEN vis THEN COALESCE(total_beat_pt, 0) ELSE NULL END as \"totalBeatPt\" " +
        "FROM v " +
        "ORDER BY score DESC",
        nativeQuery = true)
    List<Map<String, Object>> findArchivedSongRanking(
            @Param("version") int version,
            @Param("title") String title,
            @Param("difficultyName") String difficultyName,
            @Param("myUserId") Long myUserId,
            @Param("friendIds") List<Long> friendIds,
            @Param("seeAll") boolean seeAll);
}
