package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.VersionPtSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 【リポジトリの役割】 過去作の最終 PT アーカイブ（{@link VersionPtSnapshot}）へのアクセス。
 *
 * 注意: ここで取得したデータは現行作の集計に混ぜてはならない（{@link VersionPtSnapshot} の不変条件）。
 */
public interface VersionPtSnapshotRepository extends JpaRepository<VersionPtSnapshot, Long> {

    /** 指定バージョンの件数。スナップショットが撮影済みかの判定に使う。 */
    long countByVersion(Integer version);

    /** 指定バージョンを BEAT-PT の高い順に返す。 */
    List<VersionPtSnapshot> findByVersionOrderByTotalBeatPtDesc(Integer version);

    /** 指定バージョン・指定ユーザーの 1 行（一意制約 (version, user_id) により高々 1 件）。 */
    java.util.Optional<VersionPtSnapshot> findByVersionAndUserId(Integer version, Long userId);

    /** アーカイブが存在する作品バージョンの一覧（新しい順）。 */
    @Query("SELECT DISTINCT s.version FROM VersionPtSnapshot s ORDER BY s.version DESC")
    List<Integer> findArchivedVersions();

    /**
     * 【メソッドの役割】 過去作の最終ランキング行（現行ランキング API と同じキー名）を返す。
     *
     * 表示名・IIDX ID・プライバシー・サポーターは現在の users を優先する（表示名変更や公開設定の
     * 変更が過去作の順位表にも反映されるのが利用者の期待に沿う）。退会済みでスナップショットだけ
     * 残っている場合は撮影時の値にフォールバックする。
     * 返却キー: userId / displayName / iidxId / privacyLevel / totalBeatPt / totalRatePt /
     *           beatRank / rateRank / lastUpdatedAt / isSupporter
     */
    @Query(value =
            "SELECT s.user_id AS \"userId\", " +
            "       COALESCE(u.display_name, s.display_name) AS \"displayName\", " +
            "       COALESCE(u.iidx_id, s.iidx_id) AS \"iidxId\", " +
            "       COALESCE(u.privacy_level, s.privacy_level, 1) AS \"privacyLevel\", " +
            "       s.total_beat_pt AS \"totalBeatPt\", " +
            "       s.total_rate_pt AS \"totalRatePt\", " +
            "       s.beat_rank AS \"beatRank\", " +
            "       s.rate_rank AS \"rateRank\", " +
            "       s.last_uploaded_at AS \"lastUpdatedAt\", " +
            "       COALESCE(u.is_supporter, false) AS \"isSupporter\" " +
            "FROM version_pt_snapshots s " +
            "LEFT JOIN users u ON u.id = s.user_id " +
            "WHERE s.version = :version " +
            "ORDER BY s.total_beat_pt DESC", nativeQuery = true)
    List<java.util.Map<String, Object>> findArchivedRankingRows(@Param("version") int version);

    /**
     * 指定ユーザーの「歴代最高 BEAT-PT」。アーカイブが 1 件も無ければ null。
     *
     * リーグの初回参加時に、現行作の BEAT-PT と突き合わせて高いほうで DIVISION を決めるために使う
     * （新作初日は現行作の BEAT-PT が 0 のため、これが無いと全員最下位階級から始まってしまう）。
     */
    @Query("SELECT MAX(s.totalBeatPt) FROM VersionPtSnapshot s WHERE s.userId = :userId")
    Double findMaxBeatPtByUserId(@Param("userId") Long userId);
}
