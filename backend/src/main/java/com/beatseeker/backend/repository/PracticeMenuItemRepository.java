package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.PracticeMenuItem;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 【リポジトリの役割】 練習メニュー項目（{@link PracticeMenuItem}）の永続化アクセス。
 *
 * 項目は原則メニュー経由（{@code PracticeMenu.items}、cascade + orphanRemoval）で
 * 出し入れするため、ここでは JpaRepository の標準操作しか使わない。
 * 採点で 1 件ずつ状態を更新する用途のために独立させてある。
 */
public interface PracticeMenuItemRepository extends JpaRepository<PracticeMenuItem, Long> {
}
