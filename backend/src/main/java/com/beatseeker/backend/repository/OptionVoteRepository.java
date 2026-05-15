package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.OptionVote;
import com.beatseeker.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code OptionVote}（曲×譜面に対するオプション投票）を扱うリポジトリ。
 *
 * ユーザーが曲ごとの推奨オプション（RANDOM / MIRROR 等）に投票したデータを保持する。
 * **複数選択対応**: 1 ユーザーが同一譜面に複数オプションを投票できる（複数行）。
 *
 * {@link JpaRepository}{@code <OptionVote, Long>} を継承しており、基本 CRUD は自動提供。
 *
 * 主要なクエリの目的:
 *  - 曲×譜面単位での全投票取得（集計用）
 *  - ユーザーがその譜面に投票しているオプション一覧取得
 *  - 個別オプションの追加 / 削除（複数選択 toggle 用）
 *  - 楽曲×譜面に投票したユニークユーザー数（バーチャート分母用）
 *  - iidx-memo 同期時に既存票を全消しするための削除メソッド
 */
public interface OptionVoteRepository extends JpaRepository<OptionVote, Long> {

    /**
     * 【メソッドの役割】 指定曲・指定難易度に対する全投票を取得する。
     *
     * 集計 UI で割合を出すのに使う。1 ユーザーが複数オプションに投票していれば、
     * その分だけ複数行が返る。0 件なら空リスト。
     */
    List<OptionVote> findByTitleAndDifficultyName(String title, String difficultyName);

    /**
     * 【メソッドの役割】 ユーザーがその譜面に投票している全オプション行を返す。
     *
     * 複数選択対応のため戻り値はリスト。0 件なら空リスト。
     */
    List<OptionVote> findByUserAndTitleAndDifficultyName(User user, String title, String difficultyName);

    /**
     * 【メソッドの役割】 ユーザー × 譜面 × 単一オプション で 1 行を引く（重複投票チェック用）。
     */
    Optional<OptionVote> findByUserAndTitleAndDifficultyNameAndOptionType(
            User user, String title, String difficultyName, String optionType);

    /**
     * 【メソッドの役割】 指定譜面に投票したユニークユーザー数を返す。
     *
     * 複数選択時の totalVotes 算出用。1 ユーザーが 3 オプションに投票していても 1 と数える。
     */
    @Query("SELECT COUNT(DISTINCT v.user.id) FROM OptionVote v " +
           "WHERE v.title = :title AND v.difficultyName = :difficultyName")
    long countDistinctUsersByTitleAndDifficultyName(@Param("title") String title,
                                                    @Param("difficultyName") String difficultyName);

    /**
     * 【メソッドの役割】 指定ユーザー × 譜面 × オプション の 1 行を削除する。
     */
    @Modifying
    @Query("DELETE FROM OptionVote v WHERE v.user = :user AND v.title = :title " +
           "AND v.difficultyName = :difficultyName AND v.optionType = :optionType")
    void deleteByUserAndTitleAndDifficultyNameAndOptionType(@Param("user") User user,
                                                            @Param("title") String title,
                                                            @Param("difficultyName") String difficultyName,
                                                            @Param("optionType") String optionType);

    /**
     * 【メソッドの役割】 指定ユーザー × 譜面 の全オプション行を削除する。
     *
     * iidx-memo 同期で既存票を全リプレースする際や、UI で「全部解除」する際に使う。
     */
    @Modifying
    @Query("DELETE FROM OptionVote v WHERE v.user = :user AND v.title = :title " +
           "AND v.difficultyName = :difficultyName")
    void deleteByUserAndTitleAndDifficultyName(@Param("user") User user,
                                               @Param("title") String title,
                                               @Param("difficultyName") String difficultyName);
}
