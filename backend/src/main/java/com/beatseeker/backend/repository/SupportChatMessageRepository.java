package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.SupportChatMessage;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【Repository の役割】 ユーザー ⇄ 運営のお問い合わせチャット ({@link SupportChatMessage}) を扱うリポジトリ。
 */
public interface SupportChatMessageRepository extends JpaRepository<SupportChatMessage, Long> {

    /** 指定ユーザーのメッセージを古い順 (会話表示順) に取得する。 */
    List<SupportChatMessage> findByUserOrderByCreatedAtAsc(User user);

    /**
     * 指定ユーザー宛 (sender="admin") で、まだユーザーが読んでいないメッセージ件数。
     * ユーザー側のフローティングウィジェットの未読バッジ算出に使う。
     */
    long countByUserAndSenderAndReadByUserFalse(User user, String sender);
}
