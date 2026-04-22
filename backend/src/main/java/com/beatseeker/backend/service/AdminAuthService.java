package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 【クラスの役割】 アプリケーション全体で共通利用する「管理者判定」のロジックを
 * 1 箇所に集約した Spring コンポーネント。
 *
 * <p>beat-seeker では Role 管理テーブルが未実装であるため、運用上は
 * <ul>
 *   <li>特定のユーザー ID（既定値: 18）</li>
 *   <li>特定の IIDX ID（既定値: {@code 5787-1145}）</li>
 * </ul>
 * のいずれかに一致したユーザーを管理者として扱う。従来は各 Controller に
 * {@code 18L} / {@code "5787-1145"} がハードコードされていたが、設定値の
 * 一元化と将来的なロールベース認可への置換をしやすくするため本サービスに統一した。
 *
 * <p>判定値は {@code application.yml} の {@code admin.user-id} /
 * {@code admin.iidx-id} から注入する。未設定でも従来どおり動くよう、
 * {@link Value} のデフォルト値にハードコード値を残している。
 */
@Component
public class AdminAuthService {

    /**
     * 管理者として扱うユーザーの DB 主キー ID。
     * <p>{@code application.yml} の {@code admin.user-id} から注入される。
     * 未設定時は従来のハードコード値 {@code 18} を使用する。
     */
    private final long adminUserId;

    /**
     * 管理者として扱う IIDX ID 文字列（例: {@code "5787-1145"}）。
     * <p>{@code application.yml} の {@code admin.iidx-id} から注入される。
     * 未設定時は従来のハードコード値 {@code "5787-1145"} を使用する。
     */
    private final String adminIidxId;

    /**
     * 【コンストラクタ】 設定値を Spring の {@link Value} 経由で注入する。
     *
     * @param adminUserId 管理者として扱うユーザー ID（未設定時 18）
     * @param adminIidxId 管理者として扱う IIDX ID（未設定時 {@code 5787-1145}）
     */
    public AdminAuthService(
            @Value("${admin.user-id:18}") long adminUserId,
            @Value("${admin.iidx-id:5787-1145}") String adminIidxId) {
        this.adminUserId = adminUserId;
        this.adminIidxId = adminIidxId;
    }

    /**
     * 【メソッドの役割】 ログイン済みユーザーが管理者か判定する。
     *
     * <p>ユーザー ID もしくは IIDX ID のいずれか一方でも設定値と一致すれば
     * 管理者として {@code true} を返す。{@code user} が {@code null} の場合は
     * 認証未完了とみなして {@code false} を返す。
     *
     * @param user 判定対象の {@link User} エンティティ（{@code null} 許容）
     * @return 管理者なら {@code true}
     */
    public boolean isAdmin(User user) {
        if (user == null) {
            return false;
        }
        return isAdminById(user.getId()) || isAdminByIidxId(user.getIidxId());
    }

    /**
     * 【メソッドの役割】 ユーザー ID 単体で管理者判定を行う。
     *
     * @param userId 判定対象のユーザー ID（{@code null} 可）
     * @return 設定値と一致すれば {@code true}
     */
    public boolean isAdminById(Long userId) {
        return userId != null && userId == adminUserId;
    }

    /**
     * 【メソッドの役割】 IIDX ID 文字列単体で管理者判定を行う。
     *
     * <p>JWT の {@code principal} に IIDX ID 文字列が格納されている
     * Controller で、User 取得前に早期判定する用途も想定している。
     *
     * @param iidxId 判定対象の IIDX ID（{@code null} 可）
     * @return 設定値と一致すれば {@code true}
     */
    public boolean isAdminByIidxId(String iidxId) {
        return iidxId != null && adminIidxId.equals(iidxId);
    }

    /**
     * 【メソッドの役割】 管理者のユーザー ID を返す。
     *
     * <p>「管理者宛にメール通知を送る」など、管理者エンティティを
     * {@code findById} で取得する必要がある呼び出し元のために公開している。
     *
     * @return 設定された管理者ユーザー ID
     */
    public long getAdminUserId() {
        return adminUserId;
    }

    /**
     * 【メソッドの役割】 管理者の IIDX ID を返す。
     *
     * @return 設定された管理者 IIDX ID
     */
    public String getAdminIidxId() {
        return adminIidxId;
    }
}
