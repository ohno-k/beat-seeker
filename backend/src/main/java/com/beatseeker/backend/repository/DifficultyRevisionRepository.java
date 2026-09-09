package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.DifficultyRevision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【リポジトリの役割】 難易度表の改訂履歴（{@link DifficultyRevision}、更新履歴の「第N版」）へのアクセス。
 */
public interface DifficultyRevisionRepository extends JpaRepository<DifficultyRevision, Long> {

    /** 全版を版数の昇順（第1版 → 最新）で返す。更新履歴ページの描画順。 */
    List<DifficultyRevision> findAllByOrderByEditionAsc();

    /** 最新版を 1 件返す。次の版数（+1）を決めるために使う。未記録なら空。 */
    Optional<DifficultyRevision> findTopByOrderByEditionDesc();
}
