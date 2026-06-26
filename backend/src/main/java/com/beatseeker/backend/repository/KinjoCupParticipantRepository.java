package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.KinjoCupParticipant;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【リポジトリの役割】「きんじょー杯」参加者名簿（{@link KinjoCupParticipant}）の永続化を担う。
 *
 * - 公開ページ向けに登録順で全件を取得する。
 * - 二重登録チェック・削除のためにユーザー単位の照会を提供する。
 */
@Repository
public interface KinjoCupParticipantRepository extends JpaRepository<KinjoCupParticipant, Long> {

    /** 登録された全参加者を登録日時の昇順で返す（公開一覧の素材）。 */
    List<KinjoCupParticipant> findAllByOrderByCreatedAtAsc();

    /** 指定ユーザーが既に名簿に居るか（二重登録防止）。 */
    boolean existsByUser(User user);

    /** 指定ユーザーの名簿エントリ（存在すれば）。 */
    Optional<KinjoCupParticipant> findByUser(User user);
}
