package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 成立したフレンド関係（片方向の 1 リンク）。
 *
 * 現実世界の概念: 「ユーザー A からユーザー B を友達として登録している」状態。
 * 双方向のフレンド関係を表現するには (A→B) と (B→A) の 2 行が必要。
 * {@link FriendRequest} が ACCEPTED に遷移した際にこのレコードが作成される。
 * マッピング先テーブル: {@code friendships}。
 *
 * 一意性制約: (user_id, friend_id) の組でユニーク。同一ペアの重複登録を防ぐ。
 *
 * 主要な関連:
 *  - {@link #user}   … 関係の起点ユーザー（ManyToOne）。
 *  - {@link #friend} … フレンドとして登録された相手（ManyToOne）。
 */
@Entity
@Table(name = "friendships", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "friend_id" })
})
@Data
@NoArgsConstructor
public class Friendship {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 関係の起点（"自分" 側）のユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** フレンドとして登録された相手ユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "friend_id", nullable = false)
    private User friend;

    /** フレンド関係が成立した日時。作成後は不変。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
