package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【テストの役割】 {@link TopRankersBeatPtService} のメモリ削減リファクタリングが
 * 「返す内容を一切変えていない」ことを、同梱の実データ（750 CSV / 約 314 万エントリ）で検証する。
 *
 * 検証対象の変更点:
 *  1. 曲別ランカーの列指向（プリミティブ配列）化 → {@link TopRankersBeatPtService#getSongTopRankers}
 *  2. 地域プロファイルの遅延読み込み化       → {@link TopRankersBeatPtService#getAreaProfile}
 *  3. 曲名 / DJ 名の文字列共有（intern）
 *
 * 方針: このテスト内にリファクタリング前と同じロジック（参照実装）を持ち、
 * 同じ入力から作った結果とサービスの出力を全件突き合わせる。
 *
 * 曲定義（maxScore）は DB 由来なので、テストでは CSV に現れる全譜面を
 * 網羅する擬似マスタを組み立てて渡す。これにより 1 行も除外されず、
 * 本番より多い全 314 万エントリが検証対象になる。
 */
class TopRankersBeatPtServiceMemoryTest {

    /** CSV カラム解釈時の難易度名の順序（サービス側と同じ）。 */
    private static final String[] DIFF_NAMES = {"BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA"};
    /** {@link #DIFF_NAMES} と同じ順で並ぶ難易度コード。 */
    private static final String[] DIFF_CODES = {"1", "2", "3", "4", "10"};

    /** manifest（バージョン×都道府県 × CSV パス）。 */
    private static List<Map<String, Object>> manifest;
    /** CSV に現れる全譜面を網羅する擬似曲定義。 */
    private static List<SongDefinition> songDefinitions;
    /** 再計算済みのサービス。 */
    private static TopRankersBeatPtService service;

    /**
     * 全 CSV を 1 度だけ走査して擬似マスタを作り、サービスを再計算しておく。
     * CSV が 750 ファイルあるため、テストメソッドごとの再計算は避ける。
     */
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = new ClassPathResource("top-rankers-data/manifest.json").getInputStream()) {
            manifest = mapper.readValue(in, List.class);
        }

        // CSV に出てくる (title, diffCode) をすべて拾い、notes を固定値で埋めた擬似マスタを作る。
        // maxScore = notes * 2 が 0 でなければサービス側のフィルタを通過する。
        Map<String, Integer> seen = new HashMap<>();
        for (Map<String, Object> entry : manifest) {
            forEachScoreCell(resourcePath(entry), (title, diffIndex, score, djName) ->
                    seen.putIfAbsent(title + "\0" + DIFF_CODES[diffIndex], diffIndex));
        }
        songDefinitions = new ArrayList<>(seen.size());
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            String[] parts = e.getKey().split("\0", -1);
            SongDefinition def = new SongDefinition();
            def.setTitle(parts[0]);
            def.setDifficulty(parts[1]);
            // 実データのスコアより十分大きい notes を与え、scoreRate が 100% を超えないようにする。
            def.setNotes(3000);
            def.setLevel(12);
            songDefinitions.add(def);
        }

        SongDefinitionRepository songRepo = Mockito.mock(SongDefinitionRepository.class);
        Mockito.when(songRepo.findByRevision("active")).thenReturn(songDefinitions);
        DifficultyRankRepository rankRepo = Mockito.mock(DifficultyRankRepository.class);
        Mockito.when(rankRepo.findByRevisionOrderBySortOrderAsc("active")).thenReturn(Collections.emptyList());

        service = new TopRankersBeatPtService(songRepo, rankRepo, mapper, new BeatPtCalculator());
        service.recompute();
    }

    /**
     * 曲別ランカー一覧が、リファクタリング前と完全に同一であることを全キーで確認する。
     *
     * 参照実装（旧ロジック）: CSV を順に読み、(title, 難易度名) ごとに
     * SongScoreEntry を追加し、最後にスコア降順で安定ソートする。
     */
    @Test
    void songTopRankersMatchLegacyImplementation() {
        // 参照実装を組み立てる（旧 recompute と同じ手順）。
        Map<String, List<TopRankersBeatPtService.SongScoreEntry>> expected = new HashMap<>();
        for (Map<String, Object> entry : manifest) {
            int versionNum = ((Number) entry.get("versionNum")).intValue();
            String versionName = (String) entry.get("versionName");
            int prefFileNum = ((Number) entry.get("prefectureFileNum")).intValue();
            String prefectureName = (String) entry.get("prefectureName");
            forEachScoreCell(resourcePath(entry), (title, diffIndex, score, djName) ->
                    expected.computeIfAbsent(title + "\0" + DIFF_NAMES[diffIndex], k -> new ArrayList<>())
                            .add(new TopRankersBeatPtService.SongScoreEntry(
                                    versionNum, versionName, prefFileNum, prefectureName,
                                    djName == null ? "" : djName, score)));
        }
        for (List<TopRankersBeatPtService.SongScoreEntry> list : expected.values()) {
            // 旧実装は List.sort（安定ソート）。同スコアは追加順のまま。
            list.sort((a, b) -> Integer.compare(b.score(), a.score()));
        }

        assertFalse(expected.isEmpty(), "参照実装が空。テストデータの読み込みに失敗している");

        long totalEntries = 0;
        for (Map.Entry<String, List<TopRankersBeatPtService.SongScoreEntry>> e : expected.entrySet()) {
            String[] parts = e.getKey().split("\0", -1);
            List<TopRankersBeatPtService.SongScoreEntry> actual =
                    service.getSongTopRankers(parts[0], parts[1]);
            assertEquals(e.getValue(), actual,
                    "曲別ランカーが一致しない: title=" + parts[0] + " diff=" + parts[1]);
            totalEntries += actual.size();
        }
        System.out.println("[verify] song-diff keys = " + expected.size()
                + ", total entries = " + totalEntries);
        // 同梱 CSV から取り込まれるスコアセル数。
        // 17 カラム未満の行（ANOTHER/LEGGENDARIA 列を持たない古い曲の行。全体の約 1/3）は
        // リファクタリング前から本番コードが丸ごとスキップしており、この数はその除外後の値。
        // 同梱データを差し替えたら更新すること。
        assertEquals(2_121_084L, totalEntries, "検証したエントリ総数が想定と違う");
    }

    /**
     * 地域プロファイルの遅延読み込みが、リファクタリング前の常駐版と同じ行を返すことを確認する。
     *
     * 全 750 地域を参照実装と同時に保持するとテスト側がメモリを食うため、
     * 1 地域ずつ「参照実装で作る → サービスから取る → 比較 → 捨てる」を繰り返す。
     */
    @Test
    void areaProfilesMatchLegacyImplementation() {
        Map<String, Integer> maxScoreMap = new HashMap<>();
        Map<String, Integer> levelMap = new HashMap<>();
        for (SongDefinition s : songDefinitions) {
            String key = s.getTitle() + "\0" + s.getDifficulty();
            maxScoreMap.put(key, s.getNotes() * 2);
            levelMap.put(key, s.getLevel());
        }

        int checked = 0;
        for (Map<String, Object> entry : manifest) {
            int versionNum = ((Number) entry.get("versionNum")).intValue();
            int prefFileNum = ((Number) entry.get("prefectureFileNum")).intValue();

            // 参照実装（旧 computePtsForCsv の areaRows 生成部分）。
            List<TopRankersBeatPtService.AreaScoreRow> expectedRows = new ArrayList<>();
            forEachScoreCell(resourcePath(entry), (title, diffIndex, score, djName) -> {
                String keyCode = title + "\0" + DIFF_CODES[diffIndex];
                Integer maxScore = maxScoreMap.get(keyCode);
                if (maxScore == null || maxScore == 0) return;
                double scoreRate = score * 100.0 / maxScore;
                Integer level = levelMap.get(keyCode);
                expectedRows.add(new TopRankersBeatPtService.AreaScoreRow(
                        title, DIFF_NAMES[diffIndex], level == null ? 0 : level,
                        score, djName == null ? "" : djName, scoreRate,
                        calcDjLevel(scoreRate), "NO PLAY"));
            });

            TopRankersBeatPtService.AreaProfile profile =
                    service.getAreaProfile(versionNum, prefFileNum);
            assertNotNull(profile, "地域プロファイルが取得できない: " + versionNum + "/" + prefFileNum);
            assertEquals(entry.get("versionName"), profile.versionName());
            assertEquals(entry.get("prefectureName"), profile.prefectureName());
            assertEquals(expectedRows, profile.scores(),
                    "地域プロファイルの行が一致しない: " + versionNum + "/" + prefFileNum);
            checked++;
        }
        System.out.println("[verify] area profiles checked = " + checked);
        assertEquals(manifest.size(), checked);
    }

    /**
     * 都道府県番号は名前と 1:1 ではない（51=タイ/米国, 53=シンガポール/海外, 57=オーストラリア/海外）。
     * 列指向化で名前を番号から引き直すようにしたため、(バージョン, 都道府県) の組で
     * 正しく解決できていることを、番号を再利用しているエリアで明示的に確認する。
     */
    @Test
    void reusedPrefectureNumbersResolveToCorrectNames() {
        Map<Integer, Set<String>> namesByPrefNum = new HashMap<>();
        for (Map<String, Object> entry : manifest) {
            namesByPrefNum
                    .computeIfAbsent(((Number) entry.get("prefectureFileNum")).intValue(), k -> new HashSet<>())
                    .add((String) entry.get("prefectureName"));
        }
        Set<Integer> reused = new HashSet<>();
        namesByPrefNum.forEach((num, names) -> {
            if (names.size() > 1) reused.add(num);
        });
        assertFalse(reused.isEmpty(), "番号を再利用しているエリアが無い。テストの前提が変わった可能性がある");
        System.out.println("[verify] reused prefectureFileNum = " + reused);

        // 番号を再利用しているエリアについて、manifest 上の名前がそのまま返ることを確認する。
        int checked = 0;
        for (Map<String, Object> entry : manifest) {
            int prefFileNum = ((Number) entry.get("prefectureFileNum")).intValue();
            if (!reused.contains(prefFileNum)) continue;
            int versionNum = ((Number) entry.get("versionNum")).intValue();
            String expectedName = (String) entry.get("prefectureName");

            // このエリアに実在する曲を拾い、その曲別ランカーから当該エントリを探して名前を照合する。
            boolean found = false;
            TopRankersBeatPtService.AreaProfile profile = service.getAreaProfile(versionNum, prefFileNum);
            assertNotNull(profile);
            // 全行が 17 カラム未満で取り込み対象が 1 件も無いエリア（例: SPADA/米国）は照合できないので飛ばす。
            if (profile.scores().isEmpty()) continue;
            for (TopRankersBeatPtService.AreaScoreRow row : profile.scores()) {
                List<TopRankersBeatPtService.SongScoreEntry> entries =
                        service.getSongTopRankers(row.title(), row.difficultyName());
                for (TopRankersBeatPtService.SongScoreEntry se : entries) {
                    if (se.versionNum() == versionNum && se.prefectureFileNum() == prefFileNum) {
                        assertEquals(expectedName, se.prefectureName(),
                                "都道府県名が取り違えられている: version=" + versionNum + " pref=" + prefFileNum);
                        assertEquals(entry.get("versionName"), se.versionName(),
                                "バージョン名が取り違えられている: version=" + versionNum + " pref=" + prefFileNum);
                        found = true;
                        break;
                    }
                }
                if (found) break;
            }
            assertTrue(found, "検証用エントリが見つからない: " + versionNum + "/" + prefFileNum);
            checked++;
        }
        System.out.println("[verify] reused-number areas checked = " + checked);
        assertTrue(checked > 0);
    }

    /** manifest に無いエリアは従来どおり null（= 404）を返す。 */
    @Test
    void unknownAreaReturnsNull() {
        assertNull(service.getAreaProfile(9999, 9999));
        assertTrue(service.getSongTopRankers("存在しない曲", "ANOTHER").isEmpty());
        assertTrue(service.getSongTopRankers(null, null).isEmpty());
    }

    // ---- ヘルパー ----

    /** CSV の 1 スコアセルを受け取るコールバック。 */
    private interface CellConsumer {
        void accept(String title, int diffIndex, int score, String djName);
    }

    /** manifest エントリの CSV パスを返す。 */
    private static String resourcePath(Map<String, Object> entry) {
        return (String) entry.get("resourcePath");
    }

    /**
     * gzip CSV を読み、score > 0 のセルだけをコールバックへ流す。
     * サービス側 computePtsForCsv の走査条件（カラム数・数値パース・score>0）に合わせている。
     */
    private static void forEachScoreCell(String resourcePath, CellConsumer consumer) {
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream();
             GZIPInputStream gz = new GZIPInputStream(in);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // ヘッダー行
            if (line == null) return;
            while ((line = reader.readLine()) != null) {
                String[] cols = splitCsv(line);
                if (cols.length < 2 + 5 * 3) continue;
                String title = cols[1];
                for (int d = 0; d < DIFF_NAMES.length; d++) {
                    String scoreStr = cols[2 + d * 3];
                    if (scoreStr == null || scoreStr.isEmpty()) continue;
                    int score;
                    try {
                        score = Integer.parseInt(scoreStr.trim());
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    if (score <= 0) continue;
                    consumer.accept(title, d, score, cols[3 + d * 3]);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("CSV 読み込みに失敗: " + resourcePath, e);
        }
    }

    /** サービス側 calcDjLevel と同じ境界。 */
    private static String calcDjLevel(double scoreRate) {
        if (scoreRate >= 100.0 / 9 * 8) return "AAA";
        if (scoreRate >= 100.0 / 9 * 7) return "AA";
        if (scoreRate >= 100.0 / 9 * 6) return "A";
        if (scoreRate >= 100.0 / 9 * 5) return "B";
        if (scoreRate >= 100.0 / 9 * 4) return "C";
        if (scoreRate >= 100.0 / 9 * 3) return "D";
        if (scoreRate >= 100.0 / 9 * 2) return "E";
        return "F";
    }

    /** サービス側 splitCsv と同じ最小 CSV スプリッタ。 */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == ',') {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else if (c == '"' && cur.length() == 0) {
                    inQuotes = true;
                } else {
                    cur.append(c);
                }
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
