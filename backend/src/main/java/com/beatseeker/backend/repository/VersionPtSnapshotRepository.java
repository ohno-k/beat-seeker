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

    /** アーカイブが存在する作品バージョンの一覧（新しい順）。 */
    @Query("SELECT DISTINCT s.version FROM VersionPtSnapshot s ORDER BY s.version DESC")
    List<Integer> findArchivedVersions();

    /**
     * 指定ユーザーの「歴代最高 BEAT-PT」。アーカイブが 1 件も無ければ null。
     *
     * リーグの初回参加時に、現行作の BEAT-PT と突き合わせて高いほうで DIVISION を決めるために使う
     * （新作初日は現行作の BEAT-PT が 0 のため、これが無いと全員最下位階級から始まってしまう）。
     */
    @Query("SELECT MAX(s.totalBeatPt) FROM VersionPtSnapshot s WHERE s.userId = :userId")
    Double findMaxBeatPtByUserId(@Param("userId") Long userId);
}
