package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ExternalApiToken;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link ExternalApiToken} のリポジトリ。
 *
 * 用途:
 *  - 認証時: {@link #findByTokenHash(String)} でトークンハッシュからレコードを引く
 *  - 管理画面: {@link #findByUserOrderByCreatedAtDesc(User)} でユーザーの発行履歴を表示
 */
@Repository
public interface ExternalApiTokenRepository extends JpaRepository<ExternalApiToken, Long> {

    /** トークンの SHA-256 ハッシュで 1 件取得（認証時の主クエリ）。 */
    Optional<ExternalApiToken> findByTokenHash(String tokenHash);

    /** 自分が発行したトークン一覧（新しい順）。 */
    List<ExternalApiToken> findByUserOrderByCreatedAtDesc(User user);
}
