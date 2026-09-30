package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.TextagePageSource;
import org.springframework.data.jpa.repository.JpaRepository;

/** 譜面再生用に保存した textage ページのスクリプト（{@link TextagePageSource}）。 */
public interface TextagePageSourceRepository extends JpaRepository<TextagePageSource, String> {
}
