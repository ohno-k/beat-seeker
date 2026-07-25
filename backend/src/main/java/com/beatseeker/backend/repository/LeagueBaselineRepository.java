package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueBaseline;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * 【Repository の役割】 {@code LeagueBaseline}（週開始時点のスコア状態スナップショット）を扱うリポジトリ。
 *
 * 「週内プレー必須」判定の基準値。週 × ユーザー × 譜面 × source で 1 行。
 * {@link JpaRepository}{@code <LeagueBaseline, Long>} を継承しており、基本 CRUD は自動提供。
 */
public interface LeagueBaselineRepository extends JpaRepository<LeagueBaseline, Long> {

    /**
     * 【メソッドの役割】 指定週の複数ユーザー分のベースラインを一括取得する。
     *
     * グループ順位計算で人数分の個別クエリを避けるためのバルク取得。
     *
     * @param week  対象週
     * @param users 対象ユーザー群（通常はグループの 8 人前後）
     * @return ベースライン一覧
     */
    List<LeagueBaseline> findByWeekAndUserIn(LeagueWeek week, Collection<User> users);

    /**
     * 【メソッドの役割】 指定週のベースラインを全削除する。
     *
     * 編成のやり直し（テスト・障害復旧）時に古いスナップショットが残らないようにする防御用。
     *
     * @param week 対象週
     */
    void deleteByWeek(LeagueWeek week);
}
