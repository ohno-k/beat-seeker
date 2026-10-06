package com.beatseeker.backend;

import com.beatseeker.backend.config.DisplayNameJacksonConfig;
import com.beatseeker.backend.util.DisplayNames;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 表示名の幅計算・省略と、JSON 出力時の省略（キー名判定）の確認。 */
class DisplayNameTruncationTest {

    record Row(String displayName, String title) {
    }

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new DisplayNameJacksonConfig().displayNameTruncatingSerializer().customize(builder);
        return builder.build();
    }

    @Test
    void widthAndTruncate() {
        assertEquals(17, DisplayNames.width("TOGENASHI TOGEARI"));
        assertEquals("TOGENASHI TOGEARI", DisplayNames.truncate("TOGENASHI TOGEARI"));
        assertEquals(26, DisplayNames.width("ディスクアッパー美樹さやか"));
        String t = DisplayNames.truncate("ディスクアッパー美樹さやか");
        assertEquals("ディスクアッパー美樹さ…", t);
        assertTrue(DisplayNames.fits(t));
        assertEquals("123456789012345678901234", DisplayNames.truncate("123456789012345678901234"));
        assertEquals("1234567890123456789012…", DisplayNames.truncate("1234567890123456789012345"));
    }

    @Test
    void jsonTruncatesOnlyNameKeys() throws Exception {
        String longName = "ビビジランテ・ソンテネグロ・ホメストーニ・カルマンドーレ・ポポス";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", longName);
        m.put("djName", longName);
        m.put("comment", longName);
        m.put("rows", List.of(new Row(longName, longName)));
        String json = mapper().writeValueAsString(m);
        String cut = DisplayNames.truncate(longName);
        assertEquals("{\"displayName\":\"" + cut + "\",\"djName\":\"" + cut + "\",\"comment\":\"" + longName
                + "\",\"rows\":[{\"displayName\":\"" + cut + "\",\"title\":\"" + longName + "\"}]}", json);
    }
}
