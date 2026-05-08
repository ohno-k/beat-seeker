package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ShareToken;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link ShareToken} のリポジトリ。
 *
 * URL 共有トークンの発行・参照・失効に使う。
 */
@Repository
public interface ShareTokenRepository extends JpaRepository<ShareToken, Long> {

    /** トークン文字列で検索（公開ビューがアクセスする際の起点）。 */
    Optional<ShareToken> findByToken(String token);

    /** 自分が発行したトークン一覧（新しい順）。 */
    List<ShareToken> findByUserOrderByCreatedAtDesc(User user);
}
