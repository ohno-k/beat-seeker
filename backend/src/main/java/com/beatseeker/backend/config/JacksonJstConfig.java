package com.beatseeker.backend.config;

import com.beatseeker.backend.util.JstTime;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 【クラスの役割】 API が返す {@link LocalDateTime} を、必ず JST オフセット付き ISO 文字列
 * ({@code 2026-09-20T15:30:00+09:00}) としてシリアライズさせる Jackson 設定。
 *
 * <p>本番 (Render) の JVM は UTC、ローカル開発は JST で動くため、{@code LocalDateTime} を Jackson の
 * 既定どおりゾーン無し文字列 ({@code 2026-09-20T06:30:00}) で返すと、ブラウザの {@code new Date()} が
 * 端末ローカル時刻として解釈してしまい、本番では 9 時間ずれて表示される。ここで一括して
 * {@code +09:00} を付けることで、端末のタイムゾーンに関係なく JST が表示される。
 *
 * <p><b>前提</b>: 変換対象は「その JVM の壁時計」で記録された値 (= {@code LocalDateTime.now()})。
 * 一方、管理者が JST で入力した「JST 壁時計」の項目
 * ({@code Competition#deadlineAt} / {@code LeagueWeek#startsAt} など) にこの変換を掛けると
 * 本番で 9 時間ずれるため、それらはコントローラ側で {@link JstTime#fromJst(LocalDateTime)} により
 * 文字列化してから Map に入れること (文字列は本シリアライザを通らない)。
 *
 * <p>影響範囲は Spring が管理する {@code ObjectMapper} のみ。DB へ JSON 文字列を書き込む処理が使う
 * {@code new ObjectMapper()} には影響しない。
 */
@Configuration
public class JacksonJstConfig {

    /**
     * 【Bean の役割】 Spring Boot が組み立てる {@code ObjectMapper} に JST シリアライザを差し込む。
     *
     * @return {@link LocalDateTime} 用シリアライザを登録するカスタマイザ
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jstLocalDateTimeSerializer() {
        return builder -> builder.serializerByType(LocalDateTime.class, new JstLocalDateTimeSerializer());
    }

    /** サーバー壁時計の {@link LocalDateTime} を JST オフセット付き ISO 文字列で書き出すシリアライザ。 */
    static class JstLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(JstTime.toIsoString(value));
        }
    }
}
