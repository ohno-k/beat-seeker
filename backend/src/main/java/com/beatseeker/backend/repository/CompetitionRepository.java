package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 {@link Competition} (大会本体) を扱うリポジトリ。
 *
 * 主催ユーザーごとの大会一覧取得が主用途。1 ユーザーが主催している大会は
 * せいぜい数件 (同時進行は稀) なので、ページング無しの List 返却で問題ない想定。
 */
public interface CompetitionRepository extends JpaRepository<Competition, Long> {

    /**
     * 【メソッドの役割】 指定ユーザーが主催する大会を作成日時の新しい順で取得する。
     *
     * @param createdBy 主催ユーザー
     * @return 大会リスト (新しい順、0 件なら空 List)
     */
    List<Competition> findByCreatedByOrderByCreatedAtDesc(User createdBy);
}
