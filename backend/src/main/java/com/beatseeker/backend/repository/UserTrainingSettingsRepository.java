package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.UserTrainingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【リポジトリの役割】 練習メニューのユーザー設定（{@link UserTrainingSettings}）へのアクセス。
 *
 * 主キーがユーザー ID なので、標準の {@code findById} / {@code save} で足りる。
 * 行が無い場合は既定値で扱う契約（呼び出し側が {@code orElse} で埋める）。
 */
public interface UserTrainingSettingsRepository extends JpaRepository<UserTrainingSettings, Long> {
}
