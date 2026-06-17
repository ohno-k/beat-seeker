package com.beatseeker.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 【エンティティの役割】 ユーザーが楽曲詳細に登録したリザルト画像（スクリーンショット）のメタ情報。
 *
 * 現実世界の概念: 「あるユーザーが、ある曲・難易度に紐づけて保存したリザルト画像 1 枚」を 1 行で表す。
 * マッピング先テーブル: {@code result_images}。
 *
 * 重要: 画像のバイト列はこのテーブルには持たない。実体は Cloudflare R2（S3 互換ストレージ）に置き、
 * ここには R2 上のオブジェクトキー（{@link #objectKey}）と表示用メタ情報のみを保存する。
 * これにより DB の肥大化を避けつつ、譜面ごとに複数枚の画像を管理できる。
 *
 * 譜面の同定キー: (user_id, title, difficultyName)。
 *   UI 上は (曲名, 難易度名) で 1 譜面が一意に定まるため、この組で画像を束ねる。
 *   {@link #difficultyLevel} は表示・参照用のメタとして併せて保持する（同定キーには含めない）。
 */
@Entity
@Table(name = "result_images", indexes = {
        @Index(name = "idx_result_images_user_chart", columnList = "user_id, title, difficultyName")
})
@Data
@NoArgsConstructor
public class ResultImage {

    /** 主キー。DB 採番の代理キー。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** この画像の所有者。{@link User} への ManyToOne 関連。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 楽曲タイトル（譜面同定キーの一部）。 */
    private String title;
    /** 難易度名（"ANOTHER"/"LEGGENDARIA" など。譜面同定キーの一部）。 */
    private String difficultyName;
    /** 難易度レベル（★数）。表示用メタ。同定キーには含めない。 */
    private Integer difficultyLevel;

    /** R2 上のオブジェクトキー（例: {@code results/<userId>/<uuid>.webp}）。 */
    @Column(nullable = false, length = 512)
    private String objectKey;

    /** 画像の MIME タイプ（image/webp など）。 */
    @Column(length = 100)
    private String contentType;

    /** 画像のバイトサイズ。 */
    private Long sizeBytes;
    /** 画像の幅（px）。レイアウトのアスペクト比保持用（任意）。 */
    private Integer width;
    /** 画像の高さ（px）。 */
    private Integer height;

    /** 登録日時。 */
    @Column(nullable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();
}
