package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】「きんじょー杯」特設ページに掲載する参加者 1 人を表す。
 *
 * 現実世界の概念: 大会のドラフト選考の参考用に、主催者が手動で名簿に登録した
 * beat-seeker ユーザー。掲載する実力データ（総合力 / 段位 / アリーナ）は
 * 紐づく {@link User} から都度読み出すので、ここでは「誰を名簿に載せたか」だけを保持する。
 *
 * マッピング先テーブル: {@code kinjo_cup_participants}。
 *
 * 設計メモ:
 *  - {@link #user} に unique 制約を張り、同じユーザーを二重登録できないようにする。
 *  - 表示順は読み出し側で総合力(Beat-Pt)降順に並べ替えるため、ここでは並び順カラムを持たない。
 */
@Entity
@Table(name = "kinjo_cup_participants", uniqueConstraints = {
        @UniqueConstraint(name = "uk_kinjo_cup_participants_user", columnNames = "user_id")
}, indexes = {
        @Index(name = "idx_kinjo_cup_participants_user_id", columnList = "user_id")
})
@Data
@NoArgsConstructor
public class KinjoCupParticipant {

    /** 主キー。DB 採番。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 名簿に載っている beat-seeker ユーザー。実力データの取得元。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 主催者がドラフト選考の参考に書く自由メモ（任意）。全閲覧者に表示される。 */
    @Column(length = 2000)
    private String note;

    /** 名簿への登録日時。 */
    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
