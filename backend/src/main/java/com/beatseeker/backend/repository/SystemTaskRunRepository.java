package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.SystemTaskRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【リポジトリの役割】 一度きり実行タスクの実行記録（{@link SystemTaskRun}）へのアクセス。
 */
public interface SystemTaskRunRepository extends JpaRepository<SystemTaskRun, Long> {

    /** タスク識別子で 1 件引く。未実行なら空。 */
    Optional<SystemTaskRun> findByTaskKey(String taskKey);

    /** 識別子が指定の接頭辞で始まる記録を、開始日時の昇順で返す（管理画面の状況確認用）。 */
    List<SystemTaskRun> findByTaskKeyStartingWithOrderByStartedAtAsc(String prefix);
}
