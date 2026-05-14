package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーが譜面ごとに設定している「オプション」（鍵盤乱・皿乱・正規 等）の
 * 同期データを保持する。
 *
 * 現実世界の概念: iidx-memo 等の連携アプリで管理しているオプション選択を beat-seeker に
 * 取り込んだもの。beat-seeker 単独では編集できず、連携アプリからの上書き（POST 同期）で
 * のみ更新される。
 *
 * マッピング先テーブル: {@code user_song_options}。
 *
 * 一意性制約: (user_id, title, difficulty_name) の 3 項目でユニーク。1 ユーザーにつき
 * 同じ譜面のオプション設定は 1 レコードだけ存在する。
 *
 * 主要フィールド:
 *  - {@link #optionsJson} … 文字列配列の JSON。例: {@code ["乱","鏡"]}
 *  - {@link #source}      … 同期元（例: "iidx-memo"）。将来複数連携を扱うため記録。
 *  - {@link #updatedAt}   … 直近の同期日時。
 */
@Entity
@Table(name = "user_song_options", uniqueConstraints = {
        @UniqueConstraint(columnNames = { "user_id", "title", "difficulty_name" })
}, indexes = {
        @Index(name = "idx_user_song_options_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
public class UserSongOption {

    /** 主キー。DB 採番。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** オプションを保持するユーザー。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 楽曲タイトル。{@link Score#title} と表記を揃える。 */
    @Column(nullable = false)
    private String title;

    /** 難易度名（"ANOTHER" / "LEGGENDARIA" / "HYPER" / "NORMAL" / "BEGINNER"）。 */
    @Column(name = "difficulty_name", nullable = false, length = 20)
    private String difficultyName;

    /**
     * オプションの JSON 配列文字列。例: {@code ["乱","鏡"]}。
     * 連携アプリから受け取った文字列をそのまま格納する。表示時は呼び出し側がパースする。
     */
    @Column(name = "options_json", columnDefinition = "TEXT", nullable = false)
    private String optionsJson;

    /** 同期元の識別子。例: "iidx-memo"。 */
    @Column(length = 40)
    private String source;

    /** 直近の同期日時。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();
}
