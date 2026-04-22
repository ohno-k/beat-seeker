package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.Friendship;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code Friendship}（成立済みフレンド関係）を扱うリポジトリ。
 *
 * フレンド関係は双方向だが、このテーブルでは「user から見た friend」という
 * 片方向レコードを 2 行で表現するのが一般的（user/friend 両視点で 1 件ずつ）。
 *
 * {@link JpaRepository}{@code <Friendship, Long>} を継承し、基本 CRUD は自動で提供される。
 *
 * 主要なクエリの目的:
 *  - 自分のフレンド一覧取得
 *  - 特定の友人との関係レコード取得（解除判定など）
 */
@Repository
public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    /**
     * 【メソッドの役割】 指定ユーザーが {@code user} 側として持つフレンド関係を全件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ?} に変換される。
     * 0 件なら空リスト。
     *
     * @param user フレンド一覧を取得したいユーザー
     * @return 該当する {@code Friendship} レコード一覧
     */
    List<Friendship> findByUser(User user);

    /**
     * 【メソッドの役割】 特定の user - friend ペアの関係レコードを 1 件取得する。
     *
     * 派生クエリメソッド: {@code WHERE user_id = ? AND friend_id = ?} に変換される。
     * 解除処理前の存在確認などで使う。該当なしなら {@link Optional#empty()}。
     *
     * @param user 視点側のユーザー
     * @param friend 相手ユーザー
     * @return フレンド関係（存在しなければ空）
     */
    Optional<Friendship> findByUserAndFriend(User user, User friend);
}
