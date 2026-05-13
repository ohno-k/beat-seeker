package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.RankQuizProgress;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 【Repository の役割】 {@link RankQuizProgress}（クイズ進捗）リポジトリ。
 *
 * 1 ユーザー 1 レコード（user_id ユニーク）の前提。
 * 取得は常に「ユーザー → 進捗」方向の検索のみ。
 */
public interface RankQuizProgressRepository extends JpaRepository<RankQuizProgress, Long> {

    /**
     * 【メソッドの役割】 指定ユーザーの進捗レコードを取得する。
     * 未作成（クイズ未プレイ）なら {@link Optional#empty()}。
     */
    Optional<RankQuizProgress> findByUser(User user);
}
