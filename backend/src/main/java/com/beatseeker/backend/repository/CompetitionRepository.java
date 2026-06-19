package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

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

    /**
     * 【メソッドの役割】 OBS ブラウザソース公開トークンで大会 1 件を引く。
     *
     * @param obsToken {@code regenerate-obs-token} で発行された 32 桁の UUID
     * @return 一致する大会 (発行済かつ未失効)。一致しない場合は空。
     */
    Optional<Competition> findByObsToken(String obsToken);

    /**
     * 【メソッドの役割】 観戦客向け対戦表公開トークンで大会 1 件を引く。
     *
     * @param spectatorToken {@code regenerate-spectator-token} で発行された 32 桁の UUID
     * @return 一致する大会 (発行済かつ未失効)。一致しない場合は空。
     */
    Optional<Competition> findBySpectatorToken(String spectatorToken);
}
