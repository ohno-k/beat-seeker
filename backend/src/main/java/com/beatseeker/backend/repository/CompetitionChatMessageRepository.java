package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionChatMessage;
import com.beatseeker.backend.entity.CompetitionTeam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 TL ⇄ 運営チャット ({@link CompetitionChatMessage}) を扱うリポジトリ。
 */
public interface CompetitionChatMessageRepository extends JpaRepository<CompetitionChatMessage, Long> {

    /** 指定チームのメッセージを古い順 (会話表示順) に取得する。 */
    List<CompetitionChatMessage> findByTeamOrderByCreatedAtAsc(CompetitionTeam team);

    /** 指定大会に属する全チームのメッセージを古い順に取得する (管理画面のスレッド一覧用)。 */
    List<CompetitionChatMessage> findByTeam_CompetitionOrderByCreatedAtAsc(Competition competition);
}
