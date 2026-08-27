package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code User}（ユーザーアカウント）を扱うリポジトリ。
 *
 * beat-seeker の認証・プロフィール・フレンド機能の中心となるエンティティ。
 *
 * {@link JpaRepository}{@code <User, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 各種ユニークキー（iidxId, email, パスワードリセットトークン, 支援者トークン）による取得
 *  - iidxId / 表示名 をまたいだ横断ユーザー検索
 *  - 全ユーザーの Push Subscription クリア（Push 通知の無効化）
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * 【メソッドの役割】 IIDX ID（例: "1234-5678"）でユーザーを取得する。
     *
     * 派生クエリメソッド: {@code WHERE iidx_id = ?}。認証時のログインキーとして使う。
     * 該当なしは {@link Optional#empty()}。
     *
     * @param iidxId IIDX ID
     * @return ユーザー（存在しなければ空）
     */
    Optional<User> findByIidxId(String iidxId);

    /**
     * 【メソッドの役割】 メールアドレスでユーザーを取得する。
     *
     * 派生クエリメソッド: {@code WHERE email = ?}。
     * パスワードリセット開始時や重複登録チェックで使う。
     *
     * @param email メールアドレス
     * @return ユーザー（存在しなければ空）
     */
    Optional<User> findByEmail(String email);

    /**
     * 【メソッドの役割】 パスワードリセット用トークンからユーザーを逆引きする。
     *
     * 派生クエリメソッド: {@code WHERE password_reset_token = ?}。
     * メール記載リンク内のトークンを検証する場面で使う。
     *
     * @param token パスワードリセットトークン
     * @return ユーザー（無効/期限切れなら空）
     */
    Optional<User> findByPasswordResetToken(String token);

    /**
     * 【メソッドの役割】 支援者トークンからユーザーを逆引きする。
     *
     * 派生クエリメソッド: {@code WHERE supporter_token = ?}。
     * 支援者限定機能の有効化リンクなどで使う。
     *
     * @param supporterToken 支援者トークン
     * @return ユーザー（存在しなければ空）
     */
    Optional<User> findBySupporterToken(String supporterToken);

    /**
     * 【メソッドの役割】 ユーザー検索（IIDX ID または表示名による OR 検索）。
     *
     * {@code query} は IIDX ID or 表示名で直接一致を狙い、
     * {@code variant} は IIDX ID のハイフン有無などを吸収するための別表記を想定。
     * 例: 入力 "12345678" でも "1234-5678" でヒットさせたい場合に使う。
     *
     * @param query ユーザー入力文字列（そのまま比較）
     * @param variant 補助的な別表記（例: ハイフン整形後の iidxId）
     * @return マッチしたユーザー一覧（0 件なら空リスト）
     */
    @Query("SELECT u FROM User u WHERE u.iidxId = :query OR u.iidxId = :variant OR u.displayName = :query")
    List<User> searchUsers(@org.springframework.data.repository.query.Param("query") String query,
            @org.springframework.data.repository.query.Param("variant") String variant);

    /**
     * 【メソッドの役割】 全ユーザーの Push Subscription を NULL でクリアする。
     *
     * VAPID キー再生成時など、登録済み Subscription がすべて無効化されたタイミングで呼ぶ。
     * {@link Modifying} は更新系宣言、{@link Transactional} で自己完結トランザクションを張る。
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.pushSubscription = NULL")
    void clearAllPushSubscriptions();

    /**
     * 【メソッドの役割】 全ユーザーの ID だけを ID 昇順で返す。
     *
     * ユーザー間スコア比較の総当たりバッチのように「誰がいるか」しか要らない処理向け。
     * User エンティティを全件ロードすると使わない列まで抱え込むので、ID だけを引く。
     *
     * @return 全ユーザー ID（昇順）
     */
    @Query("SELECT u.id FROM User u ORDER BY u.id")
    List<Long> findAllUserIds();
}
