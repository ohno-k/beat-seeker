package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Chart;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Song;
import com.beatseeker.backend.service.GameDataService.WikiChartChange;
import com.beatseeker.backend.service.WikiSongSyncService.Plan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 旧曲リストと公開中の楽曲マスタの突き合わせ（{@link WikiSongSyncService#planChanges}）が、
 * 二重登録を起こさずに差分だけを拾うことを検証する。
 *
 * 楽曲マスタは「wiki と同じ内容」を出発点に、本番で実際に起きている食い違い
 * （公式 CSV 由来の ASCII 曲名、レベル変更、LEGGENDARIA の追加、ノーツ数の誤り）を 1 つずつ仕込む。
 */
class WikiSongSyncPlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 17);

    private static List<Song> wikiSongs;

    @BeforeAll
    static void parse() throws IOException {
        wikiSongs = BemaniwikiOldSongListParserTest.parseFixtures().songs();
    }

    /** wiki と完全に同じ内容の楽曲マスタ（ノーツ数が未記載の譜面は 0 ノーツで登録済みとする）。 */
    private static List<SongDefinition> mastersSameAsWiki() {
        List<SongDefinition> out = new ArrayList<>();
        for (Song s : wikiSongs) {
            for (Chart c : s.charts().values()) {
                SongDefinition sd = new SongDefinition();
                sd.setTitle(s.title());
                sd.setDifficulty(c.difficulty());
                sd.setLevel(c.level());
                sd.setNotes(c.notes() != null ? c.notes() : 0);
                sd.setGenre(s.genre());
                sd.setArtist(s.artist());
                sd.setBpm(s.bpm());
                out.add(sd);
            }
        }
        return out;
    }

    private static void rename(List<SongDefinition> masters, String from, String to) {
        masters.stream().filter(sd -> sd.getTitle().equals(from)).forEach(sd -> sd.setTitle(to));
    }

    private static SongDefinition chart(List<SongDefinition> masters, String title, String difficulty) {
        return masters.stream().filter(sd -> sd.getTitle().equals(title) && sd.getDifficulty().equals(difficulty))
                .findFirst().orElseThrow(() -> new AssertionError("譜面が無い: " + title + " " + difficulty));
    }

    private static Plan planOld(List<SongDefinition> masters) {
        return WikiSongSyncService.planChanges(masters, wikiSongs, TODAY, WikiSongSyncService.SOURCE_OLD);
    }

    @Test
    void 楽曲マスタがwikiと同じなら変更なし() {
        Plan plan = planOld(mastersSameAsWiki());
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.titleMatches()).isEmpty();
        assertThat(plan.held()).isEmpty();
        assertThat(plan.warnings()).isEmpty();
        assertThat(plan.unchanged()).isEqualTo(mastersSameAsWiki().size());
    }

    @Test
    void 公式CSV由来のASCII曲名は指紋一致で既存曲に寄せ二重登録しない() {
        List<SongDefinition> masters = mastersSameAsWiki();
        rename(masters, "ÆTHER", "ATHER");
        rename(masters, "焱影", "火影");
        rename(masters, "LOVE♡SHINE", "LOVE SHINE");
        rename(masters, "POLꓘAMAИIA", "POLKAMANIA");

        Plan plan = planOld(masters);
        assertThat(plan.changes()).as("既存曲として扱い、追加も更新もしない").isEmpty();
        assertThat(plan.titleMatches()).hasSize(4);
        assertThat(plan.titleMatches()).anyMatch(m -> m.contains("wiki『ÆTHER』→ 登録済み『ATHER』"));
        assertThat(plan.titleMatches()).anyMatch(m -> m.contains("wiki『焱影』→ 登録済み『火影』"));
        assertThat(plan.warnings()).as("旧曲リストの読み替えは警告ではなく別枠").isEmpty();
    }

    @Test
    void 指紋一致した曲の値の差分は既存の曲名で更新する() {
        List<SongDefinition> masters = mastersSameAsWiki();
        rename(masters, "Mächö Mönky", "Macho Monky");
        chart(masters, "Macho Monky", "4").setNotes(1500); // N と H の 2 譜面は一致

        Plan plan = planOld(masters);
        assertThat(plan.changes()).hasSize(1);
        WikiChartChange c = plan.changes().get(0);
        assertThat(c.kind()).isEqualTo(WikiChartChange.UPDATE);
        assertThat(c.title()).isEqualTo("Macho Monky");
        assertThat(c.difficulty()).isEqualTo("4");
        assertThat(c.notes()).isEqualTo(1588);
        assertThat(c.level()).isNull();
    }

    @Test
    void ノーツ数が一致してもARTISTが違う曲には寄せない() {
        List<SongDefinition> masters = mastersSameAsWiki();
        rename(masters, "ÆTHER", "ATHER");
        masters.stream().filter(sd -> sd.getTitle().equals("ATHER")).forEach(sd -> sd.setArtist("someone else"));

        Plan plan = planOld(masters);
        assertThat(plan.titleMatches()).isEmpty();
        assertThat(plan.added()).as("別の曲として追加し、確認を促す").hasSize(4);
        assertThat(plan.changes()).allMatch(c -> c.title().equals("ÆTHER") && c.kind().equals(WikiChartChange.ADD));
        assertThat(plan.warnings()).anyMatch(w -> w.contains("楽曲マスタに見つからない") && w.contains("ÆTHER"));
    }

    @Test
    void 指紋一致の候補が複数あるときは断定しない() {
        List<SongDefinition> masters = mastersSameAsWiki();
        rename(masters, "焱影", "火影");
        for (SongDefinition sd : List.copyOf(masters)) {
            if (!sd.getTitle().equals("火影")) continue;
            SongDefinition twin = new SongDefinition();
            twin.setTitle("火影 (copy)");
            twin.setDifficulty(sd.getDifficulty());
            twin.setLevel(sd.getLevel());
            twin.setNotes(sd.getNotes());
            twin.setGenre(sd.getGenre());
            twin.setArtist(sd.getArtist());
            masters.add(twin);
        }
        Plan plan = planOld(masters);
        assertThat(plan.titleMatches()).isEmpty();
        assertThat(plan.warnings()).anyMatch(w -> w.contains("楽曲マスタに見つからない") && w.contains("焱影"));
    }

    @Test
    void 譜面を共有する別の曲には寄せない() {
        // V の B/N/H は Evans・crew と同じ譜面（ノーツ数 173/646/1272）。V がマスタに無くても Evans や crew に寄せてはいけない
        List<SongDefinition> masters = mastersSameAsWiki();
        masters.removeIf(sd -> sd.getTitle().equals("V"));

        Plan plan = planOld(masters);
        assertThat(plan.titleMatches()).isEmpty();
        assertThat(plan.changes()).hasSize(4);
        assertThat(plan.changes()).allMatch(c -> c.title().equals("V") && c.kind().equals(WikiChartChange.ADD));
    }

    @Test
    void レベル変更と譜面追加とノーツ数訂正を拾う() {
        List<SongDefinition> masters = mastersSameAsWiki();
        // 本番と同じ食い違い: 曲名の大文字小文字、LEGGENDARIA ★11→12、LEGGENDARIA 追加、ノーツ数の誤り
        rename(masters, "Time to Empress", "Time To Empress");
        chart(masters, "Time To Empress", "10").setLevel(11);
        masters.removeIf(sd -> sd.getTitle().equals("Medicine of love") && sd.getDifficulty().equals("10"));
        chart(masters, "Friction[!]Function", "2").setNotes(311);

        Plan plan = planOld(masters);
        assertThat(plan.changes()).hasSize(3);
        assertThat(plan.updated()).anyMatch(u -> u.startsWith("Time To Empress [LEGGENDARIA]") && u.contains("★11→12"));
        assertThat(plan.updated()).anyMatch(u -> u.startsWith("Friction[!]Function [NORMAL]") && u.contains("notes 311→560"));
        assertThat(plan.added()).containsExactly("Medicine of love [LEGGENDARIA] ★11 / 1273 notes");
        assertThat(plan.titleMatches()).containsExactly("wiki『Time to Empress』→ 登録済み『Time To Empress』");
        assertThat(plan.warnings()).hasSize(1);
        assertThat(plan.warnings().get(0)).contains("難易度表の配置を確認").contains("Time To Empress [LEGGENDARIA]");
        assertThat(plan.changedSongCount()).isEqualTo(3);
    }

    @Test
    void 登録済みでノーツ表に無い譜面もレベルの変更は拾う() throws IOException {
        List<SongDefinition> masters = mastersSameAsWiki();
        chart(masters, "Dr.LOVE", "3").setLevel(9);
        List<Song> songsWithoutNotes = BemaniwikiOldSongListParserTest.parseFixturesWithoutNotesFor("Dr.LOVE").songs();

        Plan plan = WikiSongSyncService.planChanges(masters, songsWithoutNotes, TODAY, WikiSongSyncService.SOURCE_OLD);
        assertThat(plan.changes()).hasSize(1);
        WikiChartChange c = plan.changes().get(0);
        assertThat(c.title()).isEqualTo("Dr.LOVE");
        assertThat(c.difficulty()).isEqualTo("3");
        assertThat(c.level()).isEqualTo(3);
        assertThat(c.notes()).as("wiki に無いノーツ数で登録済みの値を消さない").isNull();
        assertThat(plan.held()).as("登録済みの譜面はノーツ数が未記載でも保留にしない").isEmpty();
    }

    @Test
    void 未登録でノーツ数が未記載の譜面は保留にする() throws IOException {
        List<SongDefinition> masters = mastersSameAsWiki();
        masters.removeIf(sd -> sd.getTitle().equals("Dr.LOVE"));
        List<Song> songsWithoutNotes = BemaniwikiOldSongListParserTest.parseFixturesWithoutNotesFor("Dr.LOVE").songs();

        Plan plan = WikiSongSyncService.planChanges(masters, songsWithoutNotes, TODAY, WikiSongSyncService.SOURCE_OLD);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.held()).hasSize(3).allMatch(h -> h.startsWith("Dr.LOVE [") && h.contains("ノーツ数未記載"));
        assertThat(plan.warnings()).as("追加しないので確認の警告も出さない").isEmpty();
    }

    @Test
    void BPMの印で登録済みの具体的な値を上書きしない() {
        List<SongDefinition> masters = mastersSameAsWiki();
        masters.stream().filter(sd -> sd.getTitle().equals("GRADIUSIC CYBER")).forEach(sd -> sd.setBpm("159-167"));
        masters.stream().filter(sd -> sd.getTitle().equals("Dr.LOVE")).forEach(sd -> sd.setBpm("120"));

        Plan plan = planOld(masters);
        assertThat(plan.changes()).hasSize(3).allMatch(c -> c.title().equals("Dr.LOVE") && "123".equals(c.bpm()));
    }

    @Test
    void 新曲リストでは曲名の読み替えを警告として残す() {
        List<SongDefinition> masters = mastersSameAsWiki();
        rename(masters, "ÆTHER", "ATHER");

        Plan plan = WikiSongSyncService.planChanges(masters, wikiSongs, TODAY, WikiSongSyncService.SOURCE_NEW);
        assertThat(plan.changes()).isEmpty();
        assertThat(plan.titleMatches()).isEmpty();
        assertThat(plan.warnings()).hasSize(1);
        assertThat(plan.warnings().get(0)).contains("既存の表記に寄せました").contains("wiki『ÆTHER』→ 登録済み『ATHER』");
    }
}
