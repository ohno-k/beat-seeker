package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.WikiSongSyncRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 BEMANIWiki 新曲同期の実行記録（{@link WikiSongSyncRun}）の永続化。
 */
@Repository
public interface WikiSongSyncRunRepository extends JpaRepository<WikiSongSyncRun, Long> {

    /** 直近 20 件（新しい順）。管理画面の履歴表示用。 */
    List<WikiSongSyncRun> findTop20ByOrderByIdDesc();

    /** ページのハッシュが記録されている直近の実行（前回からページが変わったかの比較元）。 */
    Optional<WikiSongSyncRun> findFirstByPageHashIsNotNullOrderByIdDesc();
}
