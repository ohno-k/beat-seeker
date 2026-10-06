package com.beatseeker.backend.config;

import com.beatseeker.backend.util.DisplayNames;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.Set;

/**
 * 【クラスの役割】 API が返す JSON のうち、キーが {@code displayName} / {@code djName} の文字列を
 * {@link DisplayNames#truncate(String)} で省略する Jackson 設定。
 *
 * <p>表示名はランキング・リーグ・フレンド・大会など数十の API が DTO や Map でそれぞれ返しており、
 * フロントも各画面が個別に fetch しているため、出力の一か所で切り詰める。
 * DTO でも {@code Map<String, Object>} でも、書き出し中のキー名で判定するので両方に効く。
 *
 * <p>本人のプロフィール編集 ({@code /api/auth/**}) と管理画面 ({@code /api/admin/**}) では
 * 元の名前が要るので省略しない。
 */
@Configuration
public class DisplayNameJacksonConfig {

    /** 省略対象のキー名。 */
    private static final Set<String> NAME_KEYS = Set.of("displayName", "djName");

    /** 省略しない API のパス接頭辞。 */
    private static final String[] RAW_PATH_PREFIXES = { "/api/auth/", "/api/admin/" };

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer displayNameTruncatingSerializer() {
        return builder -> builder.serializerByType(String.class, new DisplayNameAwareStringSerializer());
    }

    /** キー名が表示名のときだけ省略し、それ以外の文字列はそのまま書き出すシリアライザ。 */
    static class DisplayNameAwareStringSerializer extends JsonSerializer<String> {
        @Override
        public void serialize(String value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            String key = gen.getOutputContext().getCurrentName();
            if (key != null && NAME_KEYS.contains(key) && !DisplayNames.fits(value) && !isRawPath()) {
                gen.writeString(DisplayNames.truncate(value));
            } else {
                gen.writeString(value);
            }
        }

        private static boolean isRawPath() {
            if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
                return false;
            }
            String uri = attrs.getRequest().getRequestURI();
            for (String prefix : RAW_PATH_PREFIXES) {
                if (uri.startsWith(prefix)) return true;
            }
            return false;
        }
    }
}
