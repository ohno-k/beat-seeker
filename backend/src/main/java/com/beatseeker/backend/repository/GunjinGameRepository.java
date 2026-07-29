package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.GunjinGame;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 【リポジトリの役割】 軍人将棋の対局（{@link GunjinGame}）の永続化を担う。
 *
 * 入室コードによる照会が主。対局は beat-seeker のユーザーに紐づかないため、
 * ユーザー単位の検索メソッドは持たない。
 */
@Repository
public interface GunjinGameRepository extends JpaRepository<GunjinGame, Long> {

    /** 入室コードで対局を引く（大文字に正規化して保存しているので呼び出し側も大文字で渡す）。 */
    Optional<GunjinGame> findByRoomCode(String roomCode);

    /** 入室コードの衝突チェック。 */
    boolean existsByRoomCode(String roomCode);

    /** 指定時刻より前に最終更新された対局（放置部屋の掃除対象）。 */
    List<GunjinGame> findByUpdatedAtBefore(LocalDateTime threshold);
}
