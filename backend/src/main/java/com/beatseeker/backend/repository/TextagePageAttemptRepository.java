package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TextagePageAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 【Repository の役割】 {@link TextagePageAttempt}（textage のページを最後に取りに行った記録）の読み書き。
 * 件数は textage のページ数（数千）程度なので、同期のたびに全件読んで並び替えに使う。
 */
@Repository
public interface TextagePageAttemptRepository extends JpaRepository<TextagePageAttempt, String> {
}
