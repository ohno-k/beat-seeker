package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 架空（バーチャル）ライバル登録。
 *
 * 現実世界の概念: ユーザーが「過去バージョンの誰か」や「県別トッププレイヤー」を
 * 仮想的にライバル登録してスコア比較する機能。実在ユーザーではなく、
 * 外部スコアデータ（version × 都道府県）を指す参照。
 * マッピング先テーブル: {@code virtual_rivals}。
 *
 * 一意性制約: (owner_id, version_num, prefecture_file_num) の組でユニーク。
 *   同一ユーザーが同じバージョン × 同じ都道府県のライバルを重複登録できない。
 *
 * 主要な関連:
 *  - {@link #owner} … このライバル設定を保持するユーザーへの ManyToOne。
 */
@Entity
@Table(name = "virtual_rivals", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "owner_id", "version_num", "prefecture_file_num" })
})
@Data
@NoArgsConstructor
public class VirtualRival {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** このライバル設定の所有者（登録したユーザー）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /** IIDX バージョン番号（例: 30=RESIDENT, 31=EPOLIS）。 */
    @Column(name = "version_num", nullable = false)
    private Integer versionNum;

    /** 都道府県ファイル番号（データファイル上の都道府県識別子）。 */
    @Column(name = "prefecture_file_num", nullable = false)
    private Integer prefectureFileNum;

    /** バージョン名（表示用、例: "RESIDENT"）。 */
    @Column(name = "version_name", length = 64)
    private String versionName;

    /** 都道府県名（表示用、例: "東京都"）。 */
    @Column(name = "prefecture_name", length = 64)
    private String prefectureName;

    /** 登録日時。作成後は不変。 */
    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
