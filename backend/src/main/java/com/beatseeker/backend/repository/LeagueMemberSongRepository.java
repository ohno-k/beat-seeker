package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueMemberSong;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
