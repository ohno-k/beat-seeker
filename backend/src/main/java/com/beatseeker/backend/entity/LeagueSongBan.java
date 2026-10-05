package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 リーグの楽曲 BAN（課題曲に出てほしくない曲）の登録 1 曲分。
 *
 * 現実世界の概念: プレイヤーが DIVISION ごとに、その DIVISION の課題曲の候補（難易度帯のプール）から
 * 最大 {@code LeagueSongBanService.MAX_BANS} 曲まで選んでおく「出さないでほしい曲」の印。
 * 課題曲の抽選（月曜 0:00 の事前編成）では、グループのメンバーが<b>その卓の DIVISION</b> に登録した
 * BAN を除いて選ぶ（足りなければ BAN した人数が少ない曲から補う）。
 * 登録は週をまたいで残り、本人が変えるまで有効（2026-10-05 ユーザー判断）。
 * マッピング先テーブル: {@code league_song_bans}。
 *
 * タイトル単位で持つ（課題曲の抽選もタイトル単位で重複を避けるため）。難易度名は表示用。
 */
@Entity
@Table(name = "league_song_bans", uniqueConstraints = {
        @UniqueConstraint(name = "uk_league_song_bans_user_tier_title", columnNames = { "user_id", "tier", "title" })
}, indexes = {
        @Index(name = "idx_league_song_bans_tier_user", columnList = "tier, user_id")
})
@Data
@NoArgsConstructor
public class LeagueSongBan {

    /** 主キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登録したユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** どの DIVISION のプールに対する BAN か（0=LEGEND .. 10）。 */
    @Column(nullable = false)
    private Integer tier;

    /** 曲のタイトル。 */
    @Column(nullable = false)
    private String title;

    /** 難易度名（"ANOTHER" / "LEGGENDARIA"。表示用）。 */
    @Column(name = "difficulty_name", length = 16)
    private String difficultyName;

    /** 登録日時（JST 壁時計）。 */
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
