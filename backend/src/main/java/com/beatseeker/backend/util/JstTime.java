package com.beatseeker.backend.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 【クラスの役割】 DB の {@link LocalDateTime} (サーバー TZ の壁時計) を、フロントが {@code new Date()} で
 * 正しく解釈できる「JST オフセット付き ISO 8601 文字列」に変換するユーティリティ。
 *
 * <p>本番 (Render) の JVM は UTC、ローカル開発は JST で動く。{@code LocalDateTime} を Jackson にそのまま
 * 渡すとタイムゾーン無しの文字列 ({@code 2026-09-12T06:30:00}) になり、ブラウザ側で「ローカル時刻」と
 * 解釈されて本番では 9 時間ずれて表示される。
 * そこで {@link ZoneId#systemDefault()} で一度その JVM の瞬間に戻し、同一瞬間の JST に変換してから
 * {@code 2026-09-12T15:30:00+09:00} 形式で返す。{@code ActivityController} / {@code TimelineController} と同じ方式。
 */
public final class JstTime {

    /** 日本時間。 */
    public static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** ISO 8601 (オフセット付き)。フロントの {@code new Date()} がそのままパースできる。 */
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private JstTime() {}

    /**
     * サーバー TZ の {@link LocalDateTime} を JST オフセット付き ISO 文字列に変換する。
     *
     * @param serverLocal DB から読んだ日時 (JVM のデフォルト TZ の壁時計)。null 可
     * @return 例: {@code 2026-09-12T15:30:00.123456+09:00}。入力が null なら null
     */
    public static String toIsoString(LocalDateTime serverLocal) {
        if (serverLocal == null) return null;
        return serverLocal.atZone(ZoneId.systemDefault()).withZoneSameInstant(JST).format(ISO_OFFSET);
    }
}
