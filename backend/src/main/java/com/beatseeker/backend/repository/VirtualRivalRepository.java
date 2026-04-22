package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.VirtualRival;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code VirtualRival}（仮想ライバル登録）を扱うリポジトリ。
 *
 * 実ユーザーとは別に、他機種や過去バージョンの成績を「ライバル」として
 * 擬似的に登録する機能。owner（登録者）× versionNum × prefectureFileNum の
 * 複合キーで一意になる。
 *
 * {@link JpaRepository}{@code <VirtualRival, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 自分が登録した仮想ライバル一覧の取得
 *  - 複合キーによる取得／削除
 */
@Repository
public interface VirtualRivalRepository extends JpaRepository<VirtualRival, Long> {
    /**
     * 【メソッドの役割】 指定オーナーが登録した仮想ライバルを全件取得する。
     *
     * 派生クエリメソッド: {@code WHERE owner_id = ?} に変換される。
     * 0 件なら空リスト。
     *
     * @param owner 登録者
     * @return 仮想ライバル一覧
     */
    List<VirtualRival> findByOwner(User owner);

    /**
     * 【メソッドの役割】 複合キー（オーナー × バージョン番号 × 都道府県ファイル番号）で一件取得する。
     *
     * 派生クエリメソッド: {@code WHERE owner_id = ? AND version_num = ? AND prefecture_file_num = ?}。
     * 登録済みかの存在確認に使う。未登録なら {@link Optional#empty()}。
     *
     * @param owner 登録者
     * @param versionNum バージョン番号
     * @param prefectureFileNum 都道府県データファイル番号
     * @return 該当レコード（なければ空）
     */
    Optional<VirtualRival> findByOwnerAndVersionNumAndPrefectureFileNum(
            User owner, Integer versionNum, Integer prefectureFileNum);

    /**
     * 【メソッドの役割】 複合キーに一致する仮想ライバル登録を削除する。
     *
     * 派生クエリメソッド名の {@code deleteBy...} は Spring Data が
     * {@code DELETE FROM ... WHERE ...} に変換する。該当なしの場合は何も起きない。
     *
     * @param owner 登録者
     * @param versionNum バージョン番号
     * @param prefectureFileNum 都道府県データファイル番号
     */
    void deleteByOwnerAndVersionNumAndPrefectureFileNum(
            User owner, Integer versionNum, Integer prefectureFileNum);
}
