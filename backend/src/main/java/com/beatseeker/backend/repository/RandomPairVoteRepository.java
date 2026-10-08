package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.RandomPairVote;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 {@link RandomPairVote}（RANDOM の 2 つの並びの「どっちが押しやすい？」の回答）を扱うリポジトリ。
 */
public interface RandomPairVoteRepository extends JpaRepository<RandomPairVote, Long> {

    /** 全回答を古い順に返す（学習用の書き出し）。 */
    List<RandomPairVote> findAllByOrderByIdAsc();

    /** ユーザーの回答数（比較画面の進み具合）。 */
    long countByUser(User user);

    /** ユーザーの回答のうち、指定の答え（SKIP など）の数。 */
    long countByUserAndChoice(User user, String choice);
}
