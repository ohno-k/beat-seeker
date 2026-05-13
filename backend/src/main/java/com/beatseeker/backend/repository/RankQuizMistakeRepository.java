package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.RankQuizMistake;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link RankQuizMistake}（クイズ間違い履歴 = 復習プール）リポジトリ。
 *
 * 主な検索パターン:
 *  - 復習プール（mastered=false）の取得 → 次回セッションへ混ぜるため
 *  - 個別エントリの upsert 用キー検索 (user, title, difficultyName)
 */
public interface RankQuizMistakeRepository extends JpaRepository<RankQuizMistake, Long> {

    /**
     * 復習対象（mastered=false）を直近未出題順に並べて返す。
     * セッション組み立て時に N 件取り出して新規問題と混ぜる。
     */
    List<RankQuizMistake> findByUserAndMasteredFalseOrderByLastSeenAtAsc(User user);

    /** 復習対象の件数。UI 表示用。 */
    long countByUserAndMasteredFalse(User user);

    /** 個別エントリの upsert 用検索。 */
    Optional<RankQuizMistake> findByUserAndTitleAndDifficultyName(
            User user, String title, String difficultyName);
}
