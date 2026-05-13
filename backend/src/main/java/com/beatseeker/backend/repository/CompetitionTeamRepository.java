package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link CompetitionTeam} (大会内 1 チーム) を扱うリポジトリ。
 */
public interface CompetitionTeamRepository extends JpaRepository<CompetitionTeam, Long> {

    /**
     * 【メソッドの役割】 指定大会の全チームを {@code teamOrder} 昇順で取得する。
     * 1 大会につき常に 5 件返る想定。
     *
     * @param competition 大会
     * @return 表示順のチームリスト
     */
    List<CompetitionTeam> findByCompetitionOrderByTeamOrderAsc(Competition competition);

    /**
     * 【メソッドの役割】 TL トークンからチームを引く。
     * TL 専用 URL ({@code /competition/tl/{token}}) の認証で使用。
     *
     * @param tlToken TL トークン
     * @return チーム (存在しなければ空)
     */
    Optional<CompetitionTeam> findByTlToken(String tlToken);
}
