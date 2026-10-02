package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.service.TextageChartSync.Outcome;
import com.beatseeker.backend.service.TextageChartSync.Plan;
import com.beatseeker.backend.service.TextageChartSync.ProfileKey;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 textage 譜面同期の洗い出し（{@link TextageChartSync#plan}）と、ページの特定・照合・解析
 * （{@link TextageChartSync#execute}）を、実ページのフィクスチャで検証する。
 *
 * 楽曲マスタの値（曲名・ノーツ数・textage）は本番の song_data.json と同じ。
 * 「龍と少女とデコヒーレンス」は N/H/A の textage が LEGGENDARIA 専用ページ（_decohel）を指している実在の誤りで、
 * 同期がノーツ数の照合で気づき、titletbl.js から正しいページ（_decoher）に訂正することを確認する。
 */
class TextageChartSyncTest {

    private static final String V = TextageChartSyncService.ANALYZER_VERSION;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 29, 4, 50);

    private static SongDefinition sd(String title, String artist, String genre, String bpm, String diff, int level, int notes, String textage) {
        SongDefinition s = new SongDefinition();
        s.setTitle(title);
        s.setArtist(artist);
        s.setGenre(genre);
        s.setBpm(bpm);
        s.setDifficulty(diff);
        s.setLevel(level);
        s.setNotes(notes);
        s.setTextage(textage);
        return s;
    }

    private static List<SongDefinition> masters() {
        List<SongDefinition> m = new ArrayList<>();
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "2", 3, 265, "1/22dunk.html?1NC00"));
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "3", 4, 323, "1/22dunk.html?1HC00"));
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "4", 5, 329, "1/22dunk.html?1AC00"));
        String d = "龍と少女とデコヒーレンス";
        m.add(sd(d, "黒猫ダンジョン", "EPIC TECHNO", "165", "2", 5, 427, "20/_decohel.html?1NB00"));
        m.add(sd(d, "黒猫ダンジョン", "EPIC TECHNO", "165", "3", 8, 863, "20/_decohel.html?1HB00"));
        m.add(sd(d, "黒猫ダンジョン", "EPIC TECHNO", "165", "4", 11, 1515, "20/_decohel.html?1AC00"));
        m.add(sd(d, "黒猫ダンジョン", "EPIC TECHNO", "165", "10", 12, 1713, "20/_decohel.html?1XC00"));
        // bemaniwiki 同期で入ったばかりの曲（textage もプロファイルも無い）
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "2", 4, 401, null));
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "3", 8, 778, null));
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "4", 10, 1152, null));
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "10", 12, 1596, null));
        // textage に無い曲
        m.add(sd("Not On Textage", "nobody", "NONE", "150", "4", 10, 1000, null));
        // ノーツ数が未登録の譜面は照合できないので対象外
        m.add(sd("Not On Textage", "nobody", "NONE", "150", "3", 7, 0, null));
        // DP は対象外
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "14", 5, 400, null));
        return m;
    }

    /** 2026-04 の一括投入（旧方式）と同じ状態のプロファイル。 */
    private static List<ProfileKey> legacyProfiles() {
        List<ProfileKey> p = new ArrayList<>();
        for (SongDefinition s : masters()) {
            if (s.getTextage() != null) p.add(new ProfileKey(s.getTextage(), s.getTitle(), s.getDifficulty(), null));
        }
        return p;
    }

    private static final TextageChartSync.Fetcher PAGES = path -> TextageFixtures.page(path);
    private static final TextageChartSync.Fetcher TABLE = path -> TextageFixtures.titleTable();

    @Test
    void plan_newChartsFirst_legacyOnlyWhenRequested() {
        Plan plan = TextageChartSync.plan(masters(), legacyProfiles(), V, true);

        assertThat(plan.songs()).extracting(TextageChartSync.SongWork::title)
                .containsExactly("yellow head joe", "Not On Textage", "22DUNK", "龍と少女とデコヒーレンス");
        assertThat(plan.newCount()).isEqualTo(5);
        assertThat(plan.legacyCount()).isEqualTo(7);
        assertThat(plan.noNotes()).isEqualTo(1);

        Plan newOnly = TextageChartSync.plan(masters(), legacyProfiles(), V, false);
        assertThat(newOnly.legacyCount()).isZero();
        assertThat(newOnly.newCount()).isEqualTo(5);
    }

    @Test
    void plan_skipsChartsAlreadyAnalyzedWithCurrentVersion() {
        List<ProfileKey> profiles = new ArrayList<>(legacyProfiles());
        profiles.removeIf(p -> p.title().equals("22DUNK"));
        profiles.add(new ProfileKey("1/22dunk.html?1NC00", "22DUNK", "2", V));
        profiles.add(new ProfileKey("1/22dunk.html?1HC00", "22DUNK", "3", V));
        profiles.add(new ProfileKey("1/22dunk.html?1AC00", "22DUNK", "4", V));

        Plan plan = TextageChartSync.plan(masters(), profiles, V, true);

        assertThat(plan.songs()).extracting(TextageChartSync.SongWork::title).doesNotContain("22DUNK");
        assertThat(plan.upToDate()).isEqualTo(3);
    }

    @Test
    void execute_analyzesNewSong_reanalyzesLegacy_andCorrectsWrongLinks() {
        Plan plan = TextageChartSync.plan(masters(), legacyProfiles(), V, true);

        Outcome out = TextageChartSync.execute(plan, PAGES, TABLE, 50, Map.of(), TextageChartSync.Retry.NONE, NOW, V);

        // 新曲: titletbl.js の曲名一致で 19/yheadjoe を特定し、4 譜面とも照合できた
        assertThat(out.added()).hasSize(4).allMatch(s -> s.startsWith("yellow head joe"));
        assertThat(out.textageUpdates())
                .containsEntry("yellow head joe\u00004", "19/yheadjoe.html?1AC00")
                .containsEntry("yellow head joe\u000010", "19/yheadjoe.html?1XC00");
        assertThat(out.resolutions()).anyMatch(s -> s.startsWith("yellow head joe → 19/yheadjoe.html"));

        // 旧方式: 22DUNK はそのまま、デコヒーレンスは L 以外のリンクを本来のページに訂正
        assertThat(out.reanalyzed()).hasSize(7);
        assertThat(out.textageUpdates())
                .containsEntry("龍と少女とデコヒーレンス\u00002", "20/_decoher.html?1NC00")
                .containsEntry("龍と少女とデコヒーレンス\u00004", "20/_decoher.html?1AC00")
                .doesNotContainKey("龍と少女とデコヒーレンス\u000010")
                .doesNotContainKey("22DUNK\u00002");
        assertThat(out.resolutions()).anyMatch(s -> s.contains("20/_decohel.html?1AC00 → 20/_decoher.html?1AC00"));

        // textage に無い曲は保留
        assertThat(out.held()).containsExactly("Not On Textage [ANOTHER]：textage に該当するページが見つからない");
        assertThat(out.deferred()).isEmpty();

        // プロファイルは曲情報 + 解析値、notes は公式ノーツ数
        Map<String, Object> legg = out.profiles().stream()
                .filter(p -> "19/yheadjoe.html?1XC00".equals(p.get("textage"))).findFirst().orElseThrow();
        assertThat(legg).containsEntry("title", "yellow head joe").containsEntry("difficulty", "10")
                .containsEntry("level", 12).containsEntry("notes", 1596).containsEntry("analyzer_version", V)
                .containsKey("cn_notes");
        // 旧方式の行は登録済みの textage のまま上書きする
        assertThat(out.profiles()).anyMatch(p -> "1/22dunk.html?1NC00".equals(p.get("textage")));

        // 取得: 22dunk, _decohel, titletbl.js, _decoher, yheadjoe（Not On Textage は候補なしで取得なし）
        assertThat(out.pagesFetched()).isEqualTo(5);
        assertThat(out.attempts()).containsKeys("1/22dunk.html", "20/_decohel.html", "20/_decoher.html", "19/yheadjoe.html",
                TextageChartSync.unresolvedKey("Not On Textage"));
    }

    @Test
    void execute_defersWhenBudgetRunsOut() {
        Plan plan = TextageChartSync.plan(masters(), legacyProfiles(), V, true);

        // 1 ページ分（= titletbl.js）で尽きる → 新曲のページは次回
        Outcome out = TextageChartSync.execute(plan, PAGES, TABLE, 1, Map.of(), TextageChartSync.Retry.NONE, NOW, V);

        assertThat(out.profiles()).isEmpty();
        assertThat(out.held()).isEmpty();
        assertThat(out.deferred()).contains("yellow head joe（4 譜面）", "22DUNK（3 譜面）");
    }

    @Test
    void execute_legacyPagesAreVisitedOldestFirst() {
        Plan legacyOnly = TextageChartSync.plan(masters().stream()
                .filter(s -> s.getTextage() != null).toList(), legacyProfiles(), V, true);
        assertThat(legacyOnly.newCount()).isZero();

        // 22dunk は昨日取りに行った → 一度も取りに行っていないデコヒーレンスが先
        Outcome out = TextageChartSync.execute(legacyOnly, PAGES, TABLE, 1,
                Map.of("1/22dunk.html", LocalDateTime.of(2026, 9, 28, 4, 50)), TextageChartSync.Retry.NONE, NOW, V);

        assertThat(out.attempts()).containsKey("20/_decohel.html").doesNotContainKey("1/22dunk.html");
        assertThat(out.deferred()).anyMatch(s -> s.startsWith("22DUNK"));
    }

    @Test
    void execute_waitsBeforeRetryingPagesThatWereTriedRecently() {
        Plan legacyOnly = TextageChartSync.plan(masters().stream()
                .filter(s -> s.getTextage() != null).toList(), legacyProfiles(), V, true);
        TextageChartSync.Retry retry = new TextageChartSync.Retry(Duration.ofHours(20), Duration.ofDays(14));

        // 22dunk は 3 日前（14 日経っていない）→ 見送り。デコヒーレンスは 2 ページとも 20 日前 → 再試行
        Outcome out = TextageChartSync.execute(legacyOnly, PAGES, TABLE, 50, Map.of(
                "1/22dunk.html", NOW.minusDays(3),
                "20/_decohel.html", NOW.minusDays(20)), retry, NOW, V);

        assertThat(out.waiting()).isEqualTo(1);
        assertThat(out.attempts()).containsKey("20/_decohel.html").doesNotContainKey("1/22dunk.html");
    }

    /** 楽曲マスタの textage が失われ、旧方式プロファイルにだけページが残っている曲（2026-09-30 に本番で 173 譜面）。 */
    private static List<SongDefinition> lostLinkMasters() {
        List<SongDefinition> m = new ArrayList<>();
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "2", 3, 265, "1/22dunk.html?1NC00"));
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "3", 4, 323, "1/22dunk.html?1HC00"));
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "4", 5, 329, "1/22dunk.html?1AC00"));
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "4", 10, 1152, null));
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "10", 12, 1596, null));
        return m;
    }

    private static List<ProfileKey> lostLinkProfiles() {
        List<ProfileKey> p = new ArrayList<>(legacyProfiles());
        p.removeIf(k -> !k.title().equals("22DUNK"));
        p.add(new ProfileKey("19/yheadjoe.html?1AC00", "yellow head joe", "4", null));
        p.add(new ProfileKey("19/yheadjoe.html?1XC00", "yellow head joe", "10", null));
        return p;
    }

    @Test
    void execute_restoresLostLinksFromProfilePages_withoutTitleTable() {
        Plan plan = TextageChartSync.plan(lostLinkMasters(), lostLinkProfiles(), V, true);
        TextageChartSync.Fetcher noTable = path -> { throw new AssertionError("titletbl.js は不要"); };

        Outcome out = TextageChartSync.execute(plan, PAGES, noTable, 50, Map.of(), TextageChartSync.Retry.NONE, NOW, V);

        // プロファイルのページをノーツ数で照合してから楽曲マスタへ書き戻す
        assertThat(out.textageUpdates())
                .containsEntry("yellow head joe\u00004", "19/yheadjoe.html?1AC00")
                .containsEntry("yellow head joe\u000010", "19/yheadjoe.html?1XC00");
        assertThat(out.reanalyzed()).anyMatch(s -> s.startsWith("yellow head joe [LEGGENDARIA]"));
        assertThat(out.resolutions()).anyMatch(s -> s.startsWith("yellow head joe → 19/yheadjoe.html（既存プロファイル"));
        assertThat(out.held()).isEmpty();
        assertThat(out.warnings()).isEmpty();
    }

    @Test
    void execute_songsHiddenFromChartAnalysisGoBeforeOtherLegacySongs() {
        Plan plan = TextageChartSync.plan(lostLinkMasters(), lostLinkProfiles(), V, true);
        assertThat(plan.newCount()).isZero();
        // 楽曲マスタ上は 22DUNK が先だが、★12 [L] が譜面分析に出ていない yellow head joe を先に回す
        assertThat(plan.songs()).extracting(TextageChartSync.SongWork::title).containsExactly("yellow head joe", "22DUNK");

        Outcome out = TextageChartSync.execute(plan, PAGES, TABLE, 1, Map.of(), TextageChartSync.Retry.NONE, NOW, V);

        assertThat(out.attempts()).containsKey("19/yheadjoe.html").doesNotContainKey("1/22dunk.html");
        assertThat(out.deferred()).containsExactly("22DUNK（3 譜面）");
    }

    @Test
    void plan_hiddenChartsOrder_topLevelAnotherFirst_thenLowerLevels() {
        List<SongDefinition> m = new ArrayList<>(lostLinkMasters());
        // N だけリンクが無い曲（譜面分析の一覧は A/L だけなので優先しない）
        m.add(sd("Normal Only", "someone", "POP", "150", "2", 5, 400, null));
        // ☆10 の ANOTHER のリンクが無い曲（2026-09-30 時点で本番の ☆10 以下の A/L は 830 譜面中 41 譜面しかリンクが無い）
        m.add(sd("Low Level Song", "someone", "POP", "150", "4", 10, 900, null));
        List<ProfileKey> p = new ArrayList<>(lostLinkProfiles());
        p.add(new ProfileKey("30/normonly.html?1NC00", "Normal Only", "2", null));
        p.add(new ProfileKey("30/lowlevel.html?1AC00", "Low Level Song", "4", null));

        Plan plan = TextageChartSync.plan(m, p, V, true);

        assertThat(plan.songs()).extracting(TextageChartSync.SongWork::title)
                .containsExactly("yellow head joe", "Low Level Song", "22DUNK", "Normal Only");
        assertThat(plan.songs()).extracting(TextageChartSync.SongWork::hiddenRank).containsExactly(0, 1, 2, 2);
    }

    @Test
    void plan_restoresLinksOfChartsAlreadyAnalyzedWithCurrentVersion_withoutFetching() {
        List<SongDefinition> m = new ArrayList<>();
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "2", 3, 265, null));               // リンクが消えた
        m.add(sd("22DUNK", "SLAKE", "TECHNO", "135", "4", 5, 329, "1/22dunk.html?1AC00")); // リンクあり
        List<ProfileKey> p = List.of(
                new ProfileKey("1/22dunk.html?1NC00", "22DUNK", "2", V),
                new ProfileKey("1/22dunk.html?1AC00", "22DUNK", "4", V));

        Plan plan = TextageChartSync.plan(m, p, V, true);

        assertThat(plan.songs()).isEmpty(); // 取得はしない
        assertThat(plan.linkRestores()).containsExactly(Map.entry("22DUNK\u00002", "1/22dunk.html?1NC00"));
    }

    /**
     * LEGGENDARIA に ANOTHER の文字（?1AC00）の textage が登録されている場合。
     * ANOTHER と同じページなら ?1XC00 に訂正し（GENE・THE BLACK KNIGHT など、2026-10-03 に本番で 4 譜面）、
     * LEGGENDARIA だけの別ページ（廿 21/_twentyl.html?1AC00 など 42 譜面）ならそのまま残す。
     */
    @Test
    void execute_correctsLeggendariaLinkWithAnotherChar_onlyOnSharedPage() {
        List<SongDefinition> m = new ArrayList<>();
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "4", 10, 1152, "19/yheadjoe.html?1AC00"));
        m.add(sd("yellow head joe", "S-C-U", "TEK-TRANCE", "187", "10", 12, 1596, "19/yheadjoe.html?1AC00"));
        m.add(sd("龍と少女とデコヒーレンス", "黒猫ダンジョン", "EPIC TECHNO", "165", "10", 12, 1713, "20/_decohel.html?1AC00"));

        Plan plan = TextageChartSync.plan(m, List.of(), V, true);
        Outcome out = TextageChartSync.execute(plan, PAGES, TABLE, 50, Map.of(), TextageChartSync.Retry.NONE, NOW, V);

        assertThat(out.added()).hasSize(3);
        assertThat(out.textageUpdates())
                .containsExactly(Map.entry("yellow head joe\u000010", "19/yheadjoe.html?1XC00"));
        assertThat(out.profiles()).extracting(p -> p.get("textage"))
                .containsExactlyInAnyOrder("19/yheadjoe.html?1AC00", "19/yheadjoe.html?1XC00", "20/_decohel.html?1AC00");
    }

    @Test
    void notesMatchTolerance() {
        assertThat(TextageChartSync.notesMatch(1001, 1002)).isTrue();  // 2 ノーツ差までは常に許容
        assertThat(TextageChartSync.notesMatch(745, 741)).isTrue();    // 1% 以内（SOLITON BEAM [N] の textage と公式の差）
        assertThat(TextageChartSync.notesMatch(1713, 1515)).isFalse(); // 別譜面（LEGGENDARIA のページで ANOTHER を引いた）
        assertThat(TextageChartSync.notesMatch(0, 427)).isFalse();     // ページがノーツ数を宣言していない
    }

    @Test
    void pageOfTextage() {
        assertThat(TextageChartSync.pageOf("20/_decohel.html?1XC00")).isEqualTo("20/_decohel.html");
        assertThat(TextageChartSync.pageOf("s/genom.html")).isEqualTo("s/genom.html");
        assertThat(TextageChartSync.pageOf("https://example.com/x")).isNull();
        assertThat(TextageChartSync.pageOf(" ")).isNull();
    }
}
