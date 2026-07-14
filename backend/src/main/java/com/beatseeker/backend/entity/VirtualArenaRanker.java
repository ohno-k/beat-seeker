package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 【エンティティの役割】 e-amusement GATE のアリーナクラス TOP RANKER ランキング
 * （上位1000人）から取り込んだ「仮想プレイヤー」を表す。
 *
 * 現実世界の概念: アリーナランキングに載っている 1 人の IIDX プレイヤーで、
 * プレイデータを公開している人物のスコアを beat-seeker 側に写し取ったもの。
 * beat-seeker に会員登録している実ユーザー（{@link User} / {@code users}）とは別物であり、
 * ランキングページの専用トグルでのみ表示される。
 * マッピング先テーブル: {@code virtual_arena_rankers}。
 *
 * 隔離方針: 実ユーザーの {@code users} には入れない。理由は
 *  - グローバルランキングが {@code score_history_logs × users} の JOIN で生成されるため、
 *    {@code users} に入れると常時ランキングへ混入し、専用トグルで制御できない
 *  - {@code users.iidx_id} のユニーク制約が、将来この人物が本登録したときに衝突する
 * 既存の「県別TOP」仮想ユーザ（{@code TopRankersBeatPtService}）と同じく、
 * 専用テーブル + 専用サービス + 専用エンドポイントで隔離する。
 *
 * 主要な関連:
 *  - {@link #scores} … このプレイヤーの譜面別ベストスコア（{@link VirtualArenaRankerScore}）への OneToMany。
 */
@Entity
@Table(name = "virtual_arena_rankers",
        indexes = { @Index(name = "idx_virtual_arena_rankers_iidx_id", columnList = "iidx_id") })
@Data
@NoArgsConstructor
public class VirtualArenaRanker {

    /** 主キー。DB 側で自動採番される代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** IIDX ID（例: "1234-5678"）。アリーナランキングから取得した自然キー。ユニーク制約あり。 */
    @Column(name = "iidx_id", unique = true, length = 9, nullable = false)
    private String iidxId;

    /** DJ ネーム（アリーナランキング表示名）。 */
    @Column(name = "dj_name", length = 64)
    private String djName;

    /** アリーナクラス（例: "A5"、"B2"）。ランキング行のアイコンから取得。 */
    @Column(name = "arena_class", length = 10)
    private String arenaClass;

    /** アリーナランキング上の順位（1〜1000）。 */
    @Column(name = "rank_pos")
    private Integer rankPos;

    /** アリーナポイント（ランキング表示値、任意）。 */
    @Column(name = "arena_point")
    private Integer arenaPoint;

    /** スクレイプで取り込んだ日時（バッチ実行時刻）。 */
    @Column(name = "scraped_at")
    private LocalDateTime scrapedAt;

    /** レコード作成日時。作成後は不変。 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * このプレイヤーの譜面別ベストスコア。
     * {@link VirtualArenaRankerScore#ranker} で双方向マッピング。親削除時に一緒に削除される。
     */
    @OneToMany(mappedBy = "ranker", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VirtualArenaRankerScore> scores = new ArrayList<>();
}
