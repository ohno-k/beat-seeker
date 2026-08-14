package com.beatseeker.backend.repository;

import com.beatseeker.backend.entity.VirtualArenaRanker;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 【Repository の役割】 {@link VirtualArenaRanker}（アリーナ仮想プレイヤー）を扱うリポジトリ。
 *
 * {@link JpaRepository}{@code <VirtualArenaRanker, Long>} を継承しており、基本 CRUD は自動提供。
 * 集計サービス {@code VirtualArenaRankerService} が起動時/再計算時にまとめ読みして
 * BEAT-PT / RATE-PT をメモリ集計するため、scores を含めた一括取得メソッドを用意している。
 */
public interface VirtualArenaRankerRepository extends JpaRepository<VirtualArenaRanker, Long> {

    /**
     * 【メソッドの役割】 IIDX ID で 1 件取得する（スクレイプ時の upsert 判定・重複回避用）。
     *
     * @param iidxId IIDX ID（"1234-5678" 形式）
     * @return 該当プレイヤー（無ければ空）
     */
    Optional<VirtualArenaRanker> findByIidxId(String iidxId);

    /**
     * 【メソッドの役割】 IIDX ID の存在チェック。
     *
     * @param iidxId IIDX ID
     * @return 存在すれば {@code true}
     */
    boolean existsByIidxId(String iidxId);

    /**
     * 【メソッドの役割】 全プレイヤーを scores 込みで取得する（N+1 回避のため EntityGraph でまとめ読み）。
     *
     * 集計サービスが起動時に一括ロードして BEAT-PT / RATE-PT をメモリ計算する用途。
     *
     * @return 全アリーナ仮想プレイヤー（scores 初期化済み）
     */
    @EntityGraph(attributePaths = { "scores" })
    List<VirtualArenaRanker> findAllBy();

    /**
     * 【メソッドの役割】 IIDX ID で 1 件を scores 込みで取得する。
     *
     * プロフィール表示（{@code VirtualArenaRankerService.getProfile}）が要求時に
     * 1 人分だけ読むための入口。全員分のプロフィールを常駐キャッシュすると
     * スコア行ごとの Map がヒープを大量に占めるため、都度この経路で組み立てる。
     * トランザクション外から呼ばれるので EntityGraph で scores を確実に初期化しておく。
     *
     * @param iidxId IIDX ID（"1234-5678" 形式）
     * @return 該当プレイヤー（scores 初期化済み。無ければ空）
     */
    @EntityGraph(attributePaths = { "scores" })
    Optional<VirtualArenaRanker> findWithScoresByIidxId(String iidxId);
}
