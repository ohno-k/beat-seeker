package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@code ChartTendencyProfile}（譜面傾向プロファイル）を扱うリポジトリ。
 *
 * beat-seeker では、譜面ごとのノーツ種別割合（縦連／皿／ソフラン 等）を
 * プロファイル化して保持している。このリポジトリはその取得・検索を担当する。
 *
 * {@link JpaRepository}{@code <ChartTendencyProfile, String>} を継承。
 * 主キーが {@code String}（textage URL など）である点が他のリポジトリと異なる。
 *
 * 主要なクエリの目的:
 *  - タイトル + 難易度 / 難易度 / レベル による絞り込み
 *  - textage URL の前方一致による同曲・全難易度取得
 *  - タグ（tagsJson）部分一致検索
 *  - eff16 値による類似譜面検索
 */
public interface ChartTendencyProfileRepository extends JpaRepository<ChartTendencyProfile, String> {

    /**
     * 【メソッドの役割】 曲名と難易度名の組でプロファイルを一件取得する。
     *
     * 派生クエリメソッド: {@code findByTitleAndDifficulty} は
     * {@code WHERE title = ? AND difficulty = ?} に変換される。
     * 該当なしの場合は {@link Optional#empty()} を返す。
     *
     * @param title 曲タイトル
     * @param difficulty 難易度名（例: "ANOTHER"）
     * @return プロファイル（存在しなければ空）
     */
    Optional<ChartTendencyProfile> findByTitleAndDifficulty(String title, String difficulty);

    /**
     * 【メソッドの役割】 指定難易度に属する全プロファイルを返す。
     *
     * 派生クエリメソッド: {@code WHERE difficulty = ?} が生成される。
     * 0 件なら空リスト。
     *
     * @param difficulty 難易度名
     * @return 該当プロファイル一覧
     */
    List<ChartTendencyProfile> findByDifficulty(String difficulty);

    /**
     * 【メソッドの役割】 指定レベル値（例: 12）のプロファイルを全件返す。
     *
     * 派生クエリメソッド: {@code WHERE level = ?} に変換される。
     *
     * @param level 譜面レベル
     * @return 該当プロファイル一覧
     */
    List<ChartTendencyProfile> findByLevel(Integer level);

    /**
     * 【メソッドの役割】 レベル + 難易度 の両条件でプロファイルを絞り込む。
     *
     * 派生クエリメソッド: {@code WHERE level = ? AND difficulty = ?} に変換される。
     *
     * @param level 譜面レベル
     * @param difficulty 難易度名
     * @return 該当プロファイル一覧
     */
    List<ChartTendencyProfile> findByLevelAndDifficulty(Integer level, String difficulty);

    /**
     * 【メソッドの役割】 登録されている全プロファイルの主キー（textage URL）のみを取得する。
     *
     * エンティティ全体を取る必要がない軽量一覧用途（例: 差分同期判定）のために用意されている。
     *
     * @return textage URL の一覧
     */
    @Query("SELECT p.textage FROM ChartTendencyProfile p")
    List<String> findAllIds();

    /**
     * 【メソッドの役割】 ベース URL（クエリパラメータを含まない部分）で前方一致検索し、
     * 同一曲の全難易度のプロファイルを返す。
     *
     * JPQL の {@code LIKE :base || '%'} は文字列連結して前方一致パターンを作っている。
     * 難易度順でソートして返すため、UI でそのまま横並び表示できる。
     *
     * @param base ベース URL（例: "https://textage.cc/score/..."）
     * @return 同曲の各難易度プロファイル
     */
    @Query("SELECT p FROM ChartTendencyProfile p WHERE p.textage LIKE :base || '%' ORDER BY p.difficulty")
    List<ChartTendencyProfile> findByTextageBase(@Param("base") String base);

    /**
     * 【メソッドの役割】 指定タグを含むプロファイルを検索する。
     *
     * {@code tagsJson} に JSON 文字列として格納されているタグに対し、
     * {@code LIKE %:tag%} で部分一致検索する簡易実装。
     * 注意: 大規模化した場合は JSON 演算子やフルテキストインデックスへ切り替えた方がよい。
     *
     * @param tag 検索したいタグ文字列
     * @return 該当プロファイル一覧
     */
    @Query("SELECT p FROM ChartTendencyProfile p WHERE p.tagsJson LIKE %:tag%")
    List<ChartTendencyProfile> findByTagContaining(@Param("tag") String tag);

    /**
     * 【メソッドの役割】 eff16（難易度 16 付近の正規化指標）が指定範囲に収まる譜面を
     * 類似傾向譜面として返す。
     *
     * 指定難易度に絞ったうえで {@code dominantEff16 BETWEEN minEff AND maxEff} で範囲検索。
     * 傾向レコメンドなどで使用する。
     *
     * @param difficulty 難易度名
     * @param minEff eff16 下限
     * @param maxEff eff16 上限
     * @return 類似プロファイル一覧
     */
    @Query("""
            SELECT p FROM ChartTendencyProfile p
            WHERE p.difficulty = :difficulty
              AND p.dominantEff16 BETWEEN :minEff AND :maxEff
            """)
    List<ChartTendencyProfile> findByDifficultyAndEff16Range(
            @Param("difficulty") String difficulty,
            @Param("minEff") double minEff,
            @Param("maxEff") double maxEff);
}
