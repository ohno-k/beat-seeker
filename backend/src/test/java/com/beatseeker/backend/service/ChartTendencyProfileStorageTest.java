package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ChartTendencyProfile;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 譜面傾向プロファイルの保存方法（2026-09 の見直し）を H2 で検証する。
 *  - 管理画面の JSON 取り込みは全置換ではなく追加・上書きで、同期が足した行を消さない
 *  - 旧方式のファイルで、新方式で解析済みの行を巻き戻さない
 *  - 同期の保存は同じ (曲名, 難易度) の別キーの行を消す（予測計算の曲名引きで重複しない）
 *  - 楽曲マスタの textage は active・draft の両方に書く
 */
@DataJpaTest
@Import({ChartTendencyService.class, JacksonAutoConfiguration.class})
@TestPropertySource(properties = {
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
class ChartTendencyProfileStorageTest {

    @Autowired ChartTendencyService service;
    @Autowired ChartTendencyProfileRepository profileRepo;
    @Autowired SongDefinitionRepository songDefRepo;
    @Autowired ObjectMapper mapper;

    private Map<String, Object> profile(String textage, String title, String diff, int notes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("textage", textage);
        m.put("title", title);
        m.put("difficulty", diff);
        m.put("level", 10);
        m.put("notes", notes);
        m.put("events", 100);
        m.put("tags", List.of("scratch_low"));
        return m;
    }

    @Test
    void importIsUpsert_andDoesNotRollBackNewerAnalysis() {
        service.saveAnalyzedProfiles(List.of(profile("34/new.html?1AC00", "New Song", "4", 1500)), "textage-js-1");
        profileRepo.save(legacy("20/old.html?1AC00", "Old Song", "4", 1000));

        JsonNode file = mapper.valueToTree(List.of(
                profile("20/old.html?1AC00", "Old Song", "4", 1001),     // 旧方式どうし → 上書き
                profile("34/new.html?1AC00", "New Song", "4", 999),      // 新方式の行 → 巻き戻さない
                profile("21/other.html?1HC00", "Other", "3", 700)));     // 追加
        Map<String, Object> r = service.importFromJsonArray(file);

        assertThat(r).containsEntry("inserted", 1).containsEntry("updated", 1).containsEntry("keptNewer", 1);
        assertThat(profileRepo.count()).isEqualTo(3);
        assertThat(profileRepo.findById("20/old.html?1AC00").orElseThrow().getNotes()).isEqualTo(1001);
        ChartTendencyProfile kept = profileRepo.findById("34/new.html?1AC00").orElseThrow();
        assertThat(kept.getNotes()).isEqualTo(1500);
        assertThat(kept.getAnalyzerVersion()).isEqualTo("textage-js-1");
        assertThat(kept.getAnalyzedAt()).isNotNull();
    }

    @Test
    void saveAnalyzedReplacesRowOfSameChartUnderAnotherKey() {
        profileRepo.save(legacy("20/_decohel.html?1AC00", "龍と少女とデコヒーレンス", "4", 1515));

        Set<String> inserted = service.saveAnalyzedProfiles(
                List.of(profile("20/_decoher.html?1AC00", "龍と少女とデコヒーレンス", "4", 1515)), "textage-js-1");

        assertThat(inserted).containsExactly("20/_decoher.html?1AC00");
        assertThat(profileRepo.findAllByTitleAndDifficulty("龍と少女とデコヒーレンス", "4"))
                .extracting(ChartTendencyProfile::getTextage).containsExactly("20/_decoher.html?1AC00");
        assertThat(profileRepo.findAllKeys()).hasSize(1);
    }

    @Test
    void updateTextageWritesActiveAndDraft() {
        songDefRepo.save(song("active", null));
        songDefRepo.save(song("draft", "20/_decohel.html?1AC00"));
        songDefRepo.save(song("active", null)).setDifficulty("3");

        int n = songDefRepo.updateTextage("龍と少女とデコヒーレンス", "4", "20/_decoher.html?1AC00");

        assertThat(n).isEqualTo(2);
        assertThat(songDefRepo.findAllByTitleAndDifficultyAndRevision("龍と少女とデコヒーレンス", "4", "draft"))
                .extracting(SongDefinition::getTextage).containsExactly("20/_decoher.html?1AC00");
        // 2 回目は変更なし
        assertThat(songDefRepo.updateTextage("龍と少女とデコヒーレンス", "4", "20/_decoher.html?1AC00")).isZero();
    }

    private static ChartTendencyProfile legacy(String textage, String title, String diff, int notes) {
        ChartTendencyProfile p = new ChartTendencyProfile();
        p.setTextage(textage);
        p.setTitle(title);
        p.setDifficulty(diff);
        p.setNotes(notes);
        return p;
    }

    private static SongDefinition song(String revision, String textage) {
        SongDefinition s = new SongDefinition();
        s.setTitle("龍と少女とデコヒーレンス");
        s.setDifficulty("4");
        s.setRevision(revision);
        s.setTextage(textage);
        return s;
    }
}
