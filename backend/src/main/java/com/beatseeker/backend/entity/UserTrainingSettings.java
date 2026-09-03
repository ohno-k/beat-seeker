package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 【エンティティの役割】 練習メニューのユーザー設定。
 *
 * 現実世界の概念: 「自分は週に何プレイするか」。人によって週 5 プレイから週 60 プレイまで
 * 幅があり、同じ献立を出しても消化できる量がまるで違う。ここを設定にして、
 * 提示する曲数をその人の実際のプレイ量に合わせる。
 * マッピング先テーブル: {@code user_training_settings}。
 *
 * 主キーはユーザー ID そのもの（1 ユーザー 1 行）。行が無いユーザーは既定値で扱うので、
 * 設定を触っていないユーザーのぶんを先回りして作る必要はない。
 */
@Entity
@Table(name = "user_training_settings")
@Data
@NoArgsConstructor
public class UserTrainingSettings {

    /** 主キー ＝ ユーザー ID。{@link User} と 1 対 1。 */
    @Id
    @Column(name = "user_id")
    private Long userId;

    /**
     * 1 週あたりの想定プレイ数。
     * 枠（計測 2 / 課題 6 / 埋め 4）は週 20 プレイを基準に決めてあり、
     * この値との比で各枠の曲数を伸縮させる。
     */
    @Column(nullable = false)
    private Integer weeklyPlays = 20;
}
