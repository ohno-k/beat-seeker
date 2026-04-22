package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.DifficultyRank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 【Repository の役割】 {@code DifficultyRank}（非公式難易度表のランク定義）を扱うリポジトリ。
 *
 * beat-seeker では、現行版を {@code revision = "active"}、編集中を {@code "draft"} として
 * 同一テーブルに共存させている。リビジョン切替時は UPDATE で revision 値を差し替える。
 *
 * {@link JpaRepository}{@code <DifficultyRank, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - リビジョン単位での一覧取得
 *  - リビジョン単位での件数カウント
 *  - リビジョン単位での一括削除
 *  - リビジョン名の切替（active ⇔ draft 等）
 */
public interface DifficultyRankRepository extends JpaRepository<DifficultyRank, Long> {

    /**
     * 【メソッドの役割】 指定リビジョンのランク定義を {@code sortOrder} 昇順で取得する。
     *
     * 派生クエリメソッド: {@code findByRevisionOrderBySortOrderAsc} は
     * {@code WHERE revision = ? ORDER BY sort_order ASC} に変換される。
     * 0 件なら空リストを返す。
     *
     * @param revision リビジョン名（"active" / "draft" 等）
     * @return 表示順でソート済みのランク定義一覧
     */
    List<DifficultyRank> findByRevisionOrderBySortOrderAsc(String revision);

    /**
     * 【メソッドの役割】 指定リビジョンに属するランク定義の総件数を返す。
     *
     * 派生クエリメソッド: {@code SELECT COUNT(*) WHERE revision = ?} に変換される。
     * 存在チェックや管理画面の件数表示に使う想定。
     *
     * @param revision リビジョン名
     * @return 該当件数（0 以上）
     */
    long countByRevision(String revision);

    /**
     * 【メソッドの役割】 指定リビジョンのランク定義をすべて削除する。
     *
     * {@link Modifying} は更新系クエリであることの明示（必須）。
     * draft を破棄する、古い active を一掃する、といった用途。
     *
     * @param revision 削除対象リビジョン
     */
    @Modifying
    @Query("DELETE FROM DifficultyRank d WHERE d.revision = :revision")
    void deleteByRevision(String revision);

    /**
     * 【メソッドの役割】 指定リビジョンのレコードを別リビジョン名へ一括リネームする。
     *
     * 典型的な用途: {@code "draft"} を {@code "active"} に昇格させるリリース操作。
     *
     * @param oldRevision 変更前のリビジョン名
     * @param newRevision 変更後のリビジョン名
     */
    @Modifying
    @Query("UPDATE DifficultyRank d SET d.revision = :newRevision WHERE d.revision = :oldRevision")
    void updateRevision(String oldRevision, String newRevision);
}
