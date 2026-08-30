package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.VersionPtSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Map;

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
     * 【メソッドの役割】 アーカイブされている作品の一覧を、人数と撮影日時つきで返す（新しい順）。
     *
     * 過去作ランキング画面の作品セレクタを組み立てるのに使う。
     * 返却キー: version / userCount / capturedAt
     */
    @Query(value =
            "SELECT version AS \"version\", COUNT(*) AS \"userCount\", MAX(captured_at) AS \"capturedAt\" " +
            "FROM version_pt_snapshots GROUP BY version ORDER BY version DESC", nativeQuery = true)
    List<Map<String, Object>> findArchiveVersionSummaries();

    /**
     * 【メソッドの役割】 指定作品のアーカイブを、順位表として 1 画面ぶんまとめて返す。
     *
     * 表示名と IIDX ID は<b>撮影時点の値</b>（{@code version_pt_snapshots} 側）を使う。
     * 当時の順位表の見え方が、後からの改名で変わってはならないため
     * （{@link com.beatseeker.backend.entity.VersionPtSnapshot} の設計方針）。
     *
     * 一方 <b>公開範囲とサポーター表示は現在の {@code users} の値</b>を使う。こちらは「今どう見せたいか」の
     * 設定であって当時の記録ではないので、利用者が後から変えた設定に従うのが期待に沿う。
     * 退会などで users 側が消えていても行は残したいので LEFT JOIN にしてある。
     *
     * 並び順は BEAT-PT の順位。RATE-PT 順は同じ行を使ってフロント側で並べ替える
     * （どちらの順位も 1 行に入っているため、作品ごとに 1 回取れば足りる）。
     *
     * 返却キー: userId / displayName / iidxId / totalBeatPt / totalRatePt / totalKenbanPt /
     *           totalSaraPt / beatRank / rateRank / lastUploadedAt / privacyLevel / isSupporter
     *
     * @param version 作品バージョン番号
     * @return 順位表の行（該当が無ければ空リスト）
     */
    @Query(value =
            "SELECT s.user_id AS \"userId\", s.display_name AS \"displayName\", s.iidx_id AS \"iidxId\", " +
            "       s.total_beat_pt AS \"totalBeatPt\", s.total_rate_pt AS \"totalRatePt\", " +
            "       s.total_kenban_pt AS \"totalKenbanPt\", s.total_sara_pt AS \"totalSaraPt\", " +
            "       s.beat_rank AS \"beatRank\", s.rate_rank AS \"rateRank\", " +
            "       s.last_uploaded_at AS \"lastUploadedAt\", " +
            "       COALESCE(u.privacy_level, 1) AS \"privacyLevel\", " +
            "       COALESCE(u.is_supporter, false) AND COALESCE(u.show_supporter_border, true) AS \"isSupporter\" " +
            "FROM version_pt_snapshots s " +
            "LEFT JOIN users u ON u.id = s.user_id " +
            "WHERE s.version = :version " +
            "ORDER BY s.beat_rank, s.total_beat_pt DESC", nativeQuery = true)
    List<Map<String, Object>> findArchiveRanking(@Param("version") Integer version);

    /**
     * 【メソッドの役割】 <b>前作</b>（＝アーカイブ済みで最も新しい作品）の BEAT-PT を、ユーザー ID 付きで全件返す。
     *
     * ティアアイコンの外枠（前作の到達点を示す発光）を描くための供給源。フロントは受け取った
     * BEAT-PT を {@code beatTier.ts} の {@code getRankInfo} に通してティアとサブティアを導出する。
     * ティア名を保存せず PT だけを持つのは {@link com.beatseeker.backend.entity.VersionPtSnapshot} の
     * 設計方針どおり（閾値を調整したときに実体と表示がずれないようにするため）。
     *
     * アーカイブが 1 件も無い間は空リストを返す。その場合フロントでは誰にも外枠が付かない。
     *
     * IIDX ID も返すのは、閲覧中のユーザーを {@code userId} ではなく IIDX ID でしか
     * 特定できない画面（他ユーザーのダッシュボードなど）があるため。どちらの鍵でも引ける。
     *
     * 返却キー: userId / iidxId / totalBeatPt
     */
    @Query(value =
            "SELECT user_id AS \"userId\", iidx_id AS \"iidxId\", total_beat_pt AS \"totalBeatPt\" " +
            "FROM version_pt_snapshots " +
            "WHERE version = (SELECT MAX(version) FROM version_pt_snapshots)", nativeQuery = true)
    List<Map<String, Object>> findPreviousVersionBeatPt();

    /**
     * 指定ユーザーの「歴代最高 BEAT-PT」。アーカイブが 1 件も無ければ null。
     *
     * リーグの初回参加時に、現行作の BEAT-PT と突き合わせて高いほうで DIVISION を決めるために使う
     * （新作初日は現行作の BEAT-PT が 0 のため、これが無いと全員最下位階級から始まってしまう）。
     */
    @Query("SELECT MAX(s.totalBeatPt) FROM VersionPtSnapshot s WHERE s.userId = :userId")
    Double findMaxBeatPtByUserId(@Param("userId") Long userId);
}
