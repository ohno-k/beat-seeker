package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link CompetitionParticipant} (大会参加者) を扱うリポジトリ。
 */
public interface CompetitionParticipantRepository extends JpaRepository<CompetitionParticipant, Long> {

    /**
     * 【メソッドの役割】 指定チームの参加者を作成順で取得する。
     * 1 チーム 4 人想定。
     */
    List<CompetitionParticipant> findByTeamOrderByCreatedAtAsc(CompetitionTeam team);

    /**
     * 【メソッドの役割】 指定大会の全参加者を作成順で取得する。
     * 1 大会 20 人想定。
     */
    List<CompetitionParticipant> findByCompetitionOrderByCreatedAtAsc(Competition competition);

    /**
     * 【メソッドの役割】 参加者の招待トークンから 1 件引く。
     * 自選曲提出 URL ({@code /competition/player/{token}}) の認証で使用。
     */
    Optional<CompetitionParticipant> findByInviteToken(String inviteToken);
}
