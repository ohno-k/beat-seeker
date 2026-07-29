package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 【Repository の役割】 {@code LeagueSong}（リーグの週次課題曲）を扱うリポジトリ。
 *
 * 週 × 階級 × スロット（1..3）で 1 行。同一階級の全グループで共通の課題曲になる。
 * {@link JpaRepository}{@code <LeagueSong, Long>} を継承しており、基本 CRUD は自動提供。
 */
public interface LeagueSongRepository extends JpaRepository<LeagueSong, Long> {

    /**
     * 【メソッドの役割】 指定週の全課題曲を階級→スロット順で取得する。
     *
     * overview（全階級の課題曲一覧）と週次編成の過不足チェックに使う。
     *
     * @param week 対象週
     * @return 課題曲一覧（tier 昇順 → slot 昇順）
     */
    List<LeagueSong> findByWeekOrderByTierAscSlotAsc(LeagueWeek week);

    /**
     * 【メソッドの役割】 指定週・指定階級の課題曲 3 曲をスロット順で取得する。
     *
     * 順位計算とプレイヤー向け課題曲表示に使う。
     *
     * @param week 対象週
     * @param tier 階級
     * @return 課題曲一覧（slot 昇順、通常 3 件）
     */
    List<LeagueSong> findByWeekAndTierOrderBySlotAsc(LeagueWeek week, Integer tier);

    /**
     * 【メソッドの役割】 指定週・階級・グループの課題曲 3 曲をスロット順で取得する。
     *
     * 課題曲はグループごとに異なるため、順位計算・ライン計算・プレイヤー表示はこれを使う。
     *
     * @param week       対象週
     * @param tier       階級
     * @param groupIndex グループ番号
     * @return 課題曲一覧（slot 昇順、通常 3 件）
     */
    List<LeagueSong> findByWeekAndTierAndGroupIndexOrderBySlotAsc(LeagueWeek week, Integer tier, Integer groupIndex);

    /**
     * 【メソッドの役割】 指定週・階級・グループの課題曲を削除する（再抽選・掃除用）。
     *
     * @param week       対象週
     * @param tier       階級
     * @param groupIndex グループ番号
     */
    void deleteByWeekAndTierAndGroupIndex(LeagueWeek week, Integer tier, Integer groupIndex);

    /**
     * 【メソッドの役割】 指定階級で最近出題されたタイトルを取得する（抽選の重複回避用）。
     *
     * 直近 N 週（呼び出し側で since を計算）に同じ階級で出題済みのタイトルを、
     * スコア/BP 両ラダー・draft 週も含めて横断的に返す。これにより
     * 「先週と同じ曲」「同じ週の他ラダーと同じ曲」の両方を一度に除外できる。
     *
     * @param tier  階級
     * @param since この開始日時以降の週を対象にする（JST 壁時計）
     * @return 出題済みタイトルのリスト（重複あり得る）
     */
    @Query("SELECT ls.title FROM LeagueSong ls WHERE ls.tier = :tier AND ls.week.startsAt >= :since")
    List<String> findRecentTitlesByTier(@Param("tier") Integer tier, @Param("since") LocalDateTime since);

    /**
     * 【メソッドの役割】 指定週・指定階級の課題曲を削除する。
     *
     * 週次編成で階級が消滅した場合の掃除と、管理者の再抽選（redraw）に使う。
     *
     * @param week 対象週
     * @param tier 階級
     */
    void deleteByWeekAndTier(LeagueWeek week, Integer tier);

    /**
     * 【メソッドの役割】 指定週の課題曲を全て削除する。
     *
     * 事前編成（formDraft）の組み直しで、旧編成のグループ構成が変わっても課題曲を
     * 残さないよう、再編成前に週単位で一括削除するために使う。
     *
     * @param week 対象週
     */
    void deleteByWeek(LeagueWeek week);
}
