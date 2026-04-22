package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.FriendRequest;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code FriendRequest}（フレンド申請）を扱うリポジトリ。
 *
 * 申請は「送信者 → 受信者」という方向性を持ち、{@code status} カラムで
 * {@code PENDING} / {@code ACCEPTED} / {@code REJECTED} のような状態を表現する。
 *
 * {@link JpaRepository}{@code <FriendRequest, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 受信者ごとのステータス別フィルタ（例: PENDING の申請だけ表示）
 *  - 送信者・受信者・ステータス三点での重複チェック
 */
@Repository
public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {

    /**
     * 【メソッドの役割】 指定受信者・指定ステータスの申請一覧を取得する。
     *
     * 派生クエリメソッド: {@code findByReceiverAndStatus} は
     * {@code WHERE receiver_id = ? AND status = ?} に変換される。
     * 0 件なら空リストを返す。
     *
     * @param receiver 申請を受け取ったユーザー
     * @param status ステータス（例 "PENDING"）
     * @return 該当申請のリスト
     */
    List<FriendRequest> findByReceiverAndStatus(User receiver, String status);

    /**
     * 【メソッドの役割】 送信者・受信者・ステータスの組で申請が存在するか調べる。
     *
     * 派生クエリメソッド: {@code WHERE sender_id = ? AND receiver_id = ? AND status = ?}。
     * 二重申請の防止などに使う。該当なしなら {@link Optional#empty()}。
     *
     * @param sender 申請送信者
     * @param receiver 申請受信者
     * @param status ステータス
     * @return 該当申請（なければ空）
     */
    Optional<FriendRequest> findBySenderAndReceiverAndStatus(User sender, User receiver, String status);
}
