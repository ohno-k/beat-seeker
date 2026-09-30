package com.beatseeker.backend.service;

import com.beatseeker.backend.DataInitializer;
import com.beatseeker.backend.entity.TextagePageAttempt;
import com.beatseeker.backend.entity.TextagePageSource;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.TextagePageAttemptRepository;
import com.beatseeker.backend.repository.TextagePageSourceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** 一時: A/L を含む曲の textage を上限なしで取得する（本番 DB）。実行後に削除する。 */
@SpringBootTest
@ActiveProfiles("prod-db")
class TmpTextageFullSyncRun {

    @MockBean DataInitializer dataInitializer;
    @MockBean TimelineBackfillService timelineBackfillService;
    @MockBean PairRegressionService pairRegressionService;

    @Autowired SongDefinitionRepository songDefRepo;
    @Autowired ChartTendencyProfileRepository profileRepo;
    @Autowired ChartTendencyService chartTendencyService;
    @Autowired TextagePageAttemptRepository attemptRepo;
    @Autowired TextagePageSourceRepository sourceRepo;

    static final Charset SJIS = Charset.forName("windows-31j");
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    long lastFetch;
    String titleTbl;

    String get(String path) throws Exception {
        long wait = lastFetch + 1200 - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://textage.cc/score/" + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "beat-seeker-chart-sync/1.0 (+https://beat-seeker.onrender.com)")
                    .GET().build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new java.io.IOException("HTTP " + res.statusCode());
            return new String(res.body(), SJIS);
        } finally {
            lastFetch = System.currentTimeMillis();
        }
    }

    @Test
    void run() throws Exception {
        Path outDir = Path.of(System.getenv().getOrDefault("TMP_OUT",
                System.getProperty("java.io.tmpdir")));
        boolean dry = "1".equals(System.getenv("TMP_DRY"));
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(outDir.resolve("textage-full-sync.log"), StandardCharsets.UTF_8), true)) {
            List<TextageChartSync.ProfileKey> keys = new ArrayList<>();
            for (Object[] row : profileRepo.findAllKeys()) {
                keys.add(new TextageChartSync.ProfileKey((String) row[0], (String) row[1], (String) row[2], (String) row[3]));
            }
            TextageChartSync.Plan plan = TextageChartSync.plan(songDefRepo.findByRevision("active"), keys,
                    TextageChartSyncService.ANALYZER_VERSION, true);
            List<TextageChartSync.SongWork> targets = plan.songs().stream()
                    .filter(s -> s.charts().stream().anyMatch(c -> "4".equals(c.difficulty()) || "10".equals(c.difficulty())))
                    .toList();
            long alCharts = targets.stream().flatMap(s -> s.charts().stream())
                    .filter(c -> "4".equals(c.difficulty()) || "10".equals(c.difficulty())).count();
            log.printf("plan: songs=%d new=%d legacy=%d upToDate=%d linkRestores=%d / A/L songs=%d A/L charts=%d%n",
                    plan.songs().size(), plan.newCount(), plan.legacyCount(), plan.upToDate(), plan.linkRestores().size(),
                    targets.size(), alCharts);
            if (dry) return;

            for (Map.Entry<String, String> e : plan.linkRestores().entrySet()) {
                String[] k = e.getKey().split("\0", 2);
                songDefRepo.updateTextage(k[0], k[1], e.getValue());
            }

            TextageChartSync.Fetcher pageFetcher = path -> {
                String html = get(path);
                try {
                    String script = TextagePageRunner.extractScript(html);
                    if (!script.isBlank()) {
                        TextagePageSource src = sourceRepo.findById(path).orElseGet(TextagePageSource::new);
                        src.setPage(path);
                        src.setScript(script);
                        src.setFetchedAt(LocalDateTime.now());
                        sourceRepo.save(src);
                    }
                } catch (Exception ex) {
                    log.println("  source save failed " + path + ": " + ex.getMessage());
                }
                return html;
            };
            TextageChartSync.Fetcher tableFetcher = path -> {
                if (titleTbl == null) titleTbl = get(path);
                return titleTbl;
            };

            int chunk = 25;
            int added = 0, re = 0, held = 0, pages = 0;
            List<String> heldAll = new ArrayList<>();
            List<String> warnAll = new ArrayList<>();
            for (int i = 0; i < targets.size(); i += chunk) {
                List<TextageChartSync.SongWork> part = targets.subList(i, Math.min(i + chunk, targets.size()));
                TextageChartSync.Plan sub = new TextageChartSync.Plan(new ArrayList<>(part), 0, 0, Map.of());
                TextageChartSync.Outcome out = TextageChartSync.execute(sub, pageFetcher, tableFetcher, 100000,
                        Map.of(), TextageChartSync.Retry.NONE, LocalDateTime.now(), TextageChartSyncService.ANALYZER_VERSION);
                if (!out.profiles().isEmpty()) {
                    chartTendencyService.saveAnalyzedProfiles(out.profiles(), TextageChartSyncService.ANALYZER_VERSION);
                }
                for (Map.Entry<String, String> e : out.textageUpdates().entrySet()) {
                    String[] k = e.getKey().split("\0", 2);
                    songDefRepo.updateTextage(k[0], k[1], e.getValue());
                }
                LocalDateTime now = LocalDateTime.now();
                List<TextagePageAttempt> attempts = new ArrayList<>();
                for (Map.Entry<String, String> e : out.attempts().entrySet()) {
                    TextagePageAttempt a = new TextagePageAttempt();
                    a.setPage(e.getKey());
                    a.setLastAttemptAt(now);
                    String v = e.getValue();
                    a.setLastResult(v != null && v.length() > 500 ? v.substring(0, 500) : v);
                    attempts.add(a);
                }
                attemptRepo.saveAll(attempts);
                added += out.added().size();
                re += out.reanalyzed().size();
                held += out.held().size();
                pages += out.pagesFetched();
                heldAll.addAll(out.held());
                warnAll.addAll(out.warnings());
                log.printf("[%s] %d/%d songs, pages=%d added=%d reanalyzed=%d held=%d%n",
                        now, Math.min(i + chunk, targets.size()), targets.size(), pages, added, re, held);
            }
            log.println("== held ==");
            heldAll.forEach(log::println);
            log.println("== warnings ==");
            warnAll.forEach(log::println);
            log.println("DONE");
        }
    }
}
