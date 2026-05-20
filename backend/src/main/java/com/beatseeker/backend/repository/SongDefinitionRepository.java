package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.SongDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code SongDefinition}（曲・譜面の定義マスタ）を扱うリポジトリ。
 *
 * ノーツ数・レベル・ジャンル等の譜面メタ情報を保持する。
 * {@code DifficultyRank} と同様、{@code revision} カラムで
 * {@code "active"}（現行版）と {@code "draft"}（編集中）を共存させる。
 *
 * {@link JpaRepository}{@code <SongDefinition, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - リビジョン単位での取得／件数
 *  - リビジョン単位での一括削除
 *  - リビジョン名の切替
 */
public interface SongDefinitionRepository extends JpaRepository<SongDefinition, Long> {

    /**
     * 【メソッドの役割】 指定リビジョンの楽曲定義を全件取得する。
     *
     * 派生クエリメソッド: {@code WHERE revision = ?} に変換される。
     * 該当 0 件なら空リスト。
     *
     * @param revision リビジョン名
     * @return 楽曲定義一覧
     */
    List<SongDefinition> findByRevision(String revision);

    /**
     * 【メソッドの役割】 (title, difficulty, revision) のトリオで 1 件取得する。
     *
     * 楽曲 × 難易度 × リビジョンは一意である前提（同じ譜面の draft/active コピーを区別する）。
     *
     * @param title      楽曲タイトル
     * @param difficulty 難易度コード（"1".."4", "10"）
     * @param revision   リビジョン名（"active" / "draft"）
     * @return 見つかれば SongDefinition、なければ empty
     */
    Optional<SongDefinition> findByTitleAndDifficultyAndRevision(String title, String difficulty, String revision);

    /**
     * 【メソッドの役割】 (title, difficulty, revision) で 0..N 件取得する。
     *
     * テーブルには (title, difficulty, revision) の UNIQUE 制約が無いため、
     * 重複行が混入しているケースに備えて List 版を用意している。
     * 管理画面側の apply 処理で「重複した active 行を全件削除」する用途で使う。
     *
     * @param title      楽曲タイトル
     * @param difficulty 難易度コード
     * @param revision   リビジョン名
     * @return 一致した SongDefinition のリスト（0..N 件）
     */
    List<SongDefinition> findAllByTitleAndDifficultyAndRevision(String title, String difficulty, String revision);

    /**
     * 【メソッドの役割】 指定リビジョンの楽曲定義件数をカウントする。
     *
     * 派生クエリメソッド: {@code SELECT COUNT(*) WHERE revision = ?}。
     *
     * @param revision リビジョン名
     * @return 件数
     */
    long countByRevision(String revision);

    /**
     * 【メソッドの役割】 指定リビジョンの楽曲定義をまとめて削除する。
     *
     * {@link Modifying} は更新系クエリ実行の必須指定。
     * draft 破棄時などに使う。
     *
     * @param revision 削除対象リビジョン
     */
    @Modifying
    @Query("DELETE FROM SongDefinition s WHERE s.revision = :revision")
    void deleteByRevision(String revision);

    /**
     * 【メソッドの役割】 指定リビジョン名を別名に一括置換する。
     *
     * 典型的な用途: {@code "draft"} を {@code "active"} に昇格させて公開する。
     *
     * @param oldRevision 変更前リビジョン名
     * @param newRevision 変更後リビジョン名
     */
    @Modifying
    @Query("UPDATE SongDefinition s SET s.revision = :newRevision WHERE s.revision = :oldRevision")
    void updateRevision(String oldRevision, String newRevision);
}
