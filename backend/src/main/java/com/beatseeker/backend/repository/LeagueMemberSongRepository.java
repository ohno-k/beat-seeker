package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueMemberSong;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 【Repository の役割】 {@code LeagueMemberSong}（締め済み週の曲別確定結果）を扱うリポジトリ。
 *
 * 書き込みは週の締め（{@code closeWeek}）、読み出しは過去週の順位表表示。
 * {@link JpaRepository}{@code <LeagueMemberSong, Long>} を継承しており、基本 CRUD は自動提供。
 */
public interface LeagueMemberSongRepository extends JpaRepository<LeagueMemberSong, Long> {

    /**
     * 【メソッドの役割】 指定メンバー群の曲別結果をまとめて取得する。
     *
     * 1 グループ分の順位表を組み立てるときに、メンバーごとの N+1 クエリを避けるために使う。
     *
     * @param members 対象メンバー（1 グループ分）
     * @return 曲別結果（該当なしなら空リスト）
     */
    List<LeagueMemberSong> findByMemberIn(Collection<LeagueMember> members);

    /**
     * 【メソッドの役割】 指定メンバー群の曲別結果を削除する。
     *
     * 締め処理をやり直したときに古い行が残らないようにする（再実行の冪等性）。
     *
     * @param members 対象メンバー
     */
    void deleteByMemberIn(Collection<LeagueMember> members);

    /**
     * 【メソッドの役割】 締め済み週について「課題曲を 1 曲以上プレーしたメンバー」の人数を週ごとに数える。
     *
     * 管理者の全リーグ履歴で「有効ありには届かなかったが、少なくとも遊んだ人が何人いたか」を出すために使う。
     * 凍結値（{@code participated}）は週の締め時にだけ書き込まれるので、この 1 クエリで締め済み週ぶんが
     * すべて揃う（進行中・編成前の週は凍結行そのものが無いため結果に現れない。呼び出し側でライブ計算する）。
     *
     * <p>{@code participated} 列より前に締まった週の行は null（＝不明）なので、
     * {@code valid} で代替する（有効化できた人は必ず遊んでいる）。{@code LeagueStandingsService} の
     * 凍結読み出しと同じフォールバック。
     *
     * @param ladder ラダー種別
     * @return {@code [週ID, 課題曲を1曲以上プレーした人数]} の配列一覧
     */
    @Query("SELECT ms.member.week.id, COUNT(DISTINCT ms.member.id) FROM LeagueMemberSong ms " +
           "WHERE ms.member.week.ladderType = :ladder " +
           "AND (ms.participated = true OR (ms.participated IS NULL AND ms.valid = true)) " +
           "GROUP BY ms.member.week.id")
    List<Object[]> countPlayedMembersByWeek(@Param("ladder") String ladder);
}
