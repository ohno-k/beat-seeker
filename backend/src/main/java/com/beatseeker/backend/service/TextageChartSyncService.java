package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.TextagePageAttempt;
import com.beatseeker.backend.entity.WikiSongSyncRun;
import com.beatseeker.backend.repository.ChartTendencyProfileRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.TextagePageAttemptRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.WikiSongSyncRunRepository;
import com.beatseeker.backend.service.TextageChartSync.Outcome;
import com.beatseeker.backend.service.TextageChartSync.Plan;
import com.beatseeker.backend.service.TextageChartSync.ProfileKey;
import com.beatseeker.backend.service.WikiSongSyncService.SyncResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 textage.cc から譜面データを取得して譜面傾向プロファイル（{@code chart_tendency_profiles}）を
 * 自動で拡充する。bemaniwiki 同期（{@link WikiSongSyncService}）が楽曲マスタに足した譜面の「譜面の中身」側を埋める役割。
 *
 * 毎回やること:
 *  - 公開中の SP 譜面のうち、プロファイルが無いもの（新曲・譜面追加）と旧方式で解析されたものを洗い出す
 *  - textage のページを特定（登録済みの textage → 同じ曲の他の譜面のページ → titletbl.js の候補）
 *  - ページの JS を難易度フラグ付きで実行（{@link TextagePageRunner}）し、ページのノーツ数が公式ノーツ数と一致した譜面だけ解析
 *    （{@link ChartTendencyAnalyzer}）してプロファイルを保存。楽曲マスタの textage も記録する（active・draft 両方）
 *  - 実行記録は bemaniwiki 同期と同じ {@code wiki_song_sync_runs} に {@code source = "textage"} で残す
 *
 * 旧方式の再解析: 2026-04 に tools/batch_analyze.py で一括投入したプロファイルは、正規表現でページの JS を追う方式だったため
 * 譜面の取り違え・ノーツの欠け（公式ノーツ数と一致したのは 558 / 6,033 譜面）・解析値の無いプレースホルダ（1,584 行）がある。
 * {@code reanalyze-legacy=true}（既定）なら、新しい譜面を優先しつつ 1 回あたりの取得上限の残りで順次解析し直す。
 *
 * textage への配慮: 1 回に取得するページ数に上限（定期実行 {@code max-pages}、手動 {@code manual-max-pages}）を設け、
 * リクエストの間隔を {@code request-interval-ms} 空ける。
 *
 * 設定（application.yml の app.textage-sync.*）: enabled / cron / base-url / auto-apply / max-pages /
 * manual-max-pages / request-interval-ms / reanalyze-legacy
 */
@Service
public class TextageChartSyncService {

    private static final Logger log = LoggerFactory.getLogger(TextageChartSyncService.class);

    /** 実行記録（{@link WikiSongSyncRun#getSource()}）の取得元。 */
    public static final String SOURCE = "textage";

    /** 解析方式の版（プロファイルの {@code analyzerVersion}）。解析ロジックを変えたら上げると、同期が順次解析し直す。 */
    public static final String ANALYZER_VERSION = "textage-js-1";

    /** 未解析の譜面（新曲など）を含む曲の再試行間隔（1 日 2 回の定期実行のうち 1 回おき）。 */
    private static final Duration NEW_RETRY = Duration.ofHours(20);
    /** 旧方式の再解析で照合できなかった譜面の再試行間隔。 */
    private static final Duration LEGACY_RETRY = Duration.ofDays(14);

    /** textage のページ・titletbl.js の文字コード。 */
    private static final Charset SHIFT_JIS = Charset.forName("windows-31j");

    private final SongDefinitionRepository songDefRepo;
    private final ChartTendencyProfileRepository profileRepo;
    private final ChartTendencyService chartTendencyService;
    private final TextagePageAttemptRepository attemptRepo;
    private final WikiSongSyncRunRepository runRepo;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    private final boolean enabled;
    private final String baseUrl;
    private final boolean autoApply;
    private final int maxPages;
    private final int manualMaxPages;
    private final long requestIntervalMillis;
    private final boolean reanalyzeLegacy;

    private final AtomicBoolean running = new AtomicBoolean(false);

    public TextageChartSyncService(SongDefinitionRepository songDefRepo,
                                   ChartTendencyProfileRepository profileRepo,
                                   ChartTendencyService chartTendencyService,
                                   TextagePageAttemptRepository attemptRepo,
                                   WikiSongSyncRunRepository runRepo,
                                   UserRepository userRepository,
                                   AdminAuthService adminAuthService,
                                   EmailService emailService,
                                   ObjectMapper objectMapper,
                                   @Value("${app.textage-sync.enabled:true}") boolean enabled,
                                   @Value("${app.textage-sync.base-url:https://textage.cc/score/}") String baseUrl,
                                   @Value("${app.textage-sync.auto-apply:true}") boolean autoApply,
                                   @Value("${app.textage-sync.max-pages:120}") int maxPages,
                                   @Value("${app.textage-sync.manual-max-pages:20}") int manualMaxPages,
                                   @Value("${app.textage-sync.request-interval-ms:1200}") long requestIntervalMillis,
                                   @Value("${app.textage-sync.reanalyze-legacy:true}") boolean reanalyzeLegacy) {
        this.songDefRepo = songDefRepo;
        this.profileRepo = profileRepo;
        this.chartTendencyService = chartTendencyService;
        this.attemptRepo = attemptRepo;
        this.runRepo = runRepo;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.autoApply = autoApply;
        this.maxPages = maxPages;
        this.manualMaxPages = manualMaxPages;
        this.requestIntervalMillis = requestIntervalMillis;
        this.reanalyzeLegacy = reanalyzeLegacy;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── 定期実行 ──────────────────────────────────────────

    /**
     * 【メソッドの役割】 定期的に textage を確認してプロファイルを拡充する。
     * 既定は JST 4:50 / 16:50 の 1 日 2 回（bemaniwiki 同期の後。環境変数 TEXTAGE_SYNC_CRON で変更可）。
     * {@code app.scheduling.enabled=false}（prod-db プロファイル）では動かない。
     */
    @Scheduled(cron = "${app.textage-sync.cron:0 50 4,16 * * *}", zone = "Asia/Tokyo")
    public void runScheduled() {
        if (!enabled) return;
        try {
            SyncResult r = sync(WikiSongSyncService.TRIGGER_SCHEDULED, !autoApply);
            log.info("[textage譜面同期] {}", r.message());
        } catch (Exception e) {
            log.error("[textage譜面同期] 定期実行に失敗: {}", e.getMessage(), e);
        }
    }

    // ── 同期本体 ──────────────────────────────────────────

    /**
     * 【メソッドの役割】 洗い出し → textage の取得・照合・解析 →（dry-run でなければ）保存 → 記録。
     *
     * 結果は bemaniwiki 同期と同じ形（{@link SyncResult}）で返す。各欄の意味:
     * added = 新規に解析した譜面、updated = 旧方式から解析し直した譜面、held = 照合できなかった譜面、
     * skippedSongs = 取得上限で次回に回した曲、titleMatches = textage のページを特定・訂正した記録。
     *
     * @param trigger scheduled / manual（手動は取得上限が {@code manual-max-pages}）
     * @param dryRun  true なら取得・解析まで行い、DB（プロファイル・楽曲マスタ・試行記録）は変更しない
     * @throws IllegalStateException 別の textage 同期が実行中
     * @throws Exception 失敗（記録には FAILED として残す）
     */
    public SyncResult sync(String trigger, boolean dryRun) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("textage 譜面同期は既に実行中です");
        }
        WikiSongSyncRun run = new WikiSongSyncRun();
        run.setStartedAt(LocalDateTime.now());
        run.setSource(SOURCE);
        run.setTriggerKind(trigger);
        run.setDryRun(dryRun);
        try {
            List<ProfileKey> keys = new ArrayList<>();
            for (Object[] row : profileRepo.findAllKeys()) {
                keys.add(new ProfileKey((String) row[0], (String) row[1], (String) row[2], (String) row[3]));
            }
            Plan plan = TextageChartSync.plan(songDefRepo.findByRevision("active"), keys, ANALYZER_VERSION, reanalyzeLegacy);

            Map<String, LocalDateTime> lastAttempts = new HashMap<>();
            for (TextagePageAttempt a : attemptRepo.findAll()) lastAttempts.put(a.getPage(), a.getLastAttemptAt());

            int budget = WikiSongSyncService.TRIGGER_MANUAL.equals(trigger) ? manualMaxPages : maxPages;
            RateLimitedFetcher fetcher = new RateLimitedFetcher();
            // 手動実行は「bemaniwiki 同期の直後に textage も見たい」用途なので、新しい譜面は間隔を空けずに試す
            TextageChartSync.Retry retry = WikiSongSyncService.TRIGGER_MANUAL.equals(trigger)
                    ? new TextageChartSync.Retry(Duration.ZERO, LEGACY_RETRY)
                    : new TextageChartSync.Retry(NEW_RETRY, LEGACY_RETRY);
            Outcome out = TextageChartSync.execute(plan, fetcher, fetcher, budget, lastAttempts, retry,
                    LocalDateTime.now(), ANALYZER_VERSION);

            if (!dryRun) {
                if (!out.profiles().isEmpty()) {
                    chartTendencyService.saveAnalyzedProfiles(out.profiles(), ANALYZER_VERSION);
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
                    a.setLastResult(truncate(e.getValue(), 500));
                    attempts.add(a);
                }
                attemptRepo.saveAll(attempts);
            }

            boolean hasChanges = !out.profiles().isEmpty();
            String status = hasChanges ? "SUCCESS" : "NO_CHANGE";
            String message = buildMessage(dryRun, plan, out);

            run.setStatus(status);
            run.setSongsOnPage(plan.newCount() + plan.legacyCount());
            run.setAddedCount(out.added().size());
            run.setUpdatedCount(out.reanalyzed().size());
            run.setHeldCount(out.held().size());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("added", out.added());
            summary.put("updated", out.reanalyzed());
            summary.put("held", out.held());
            summary.put("deferred", out.deferred());
            summary.put("warnings", out.warnings());
            summary.put("resolutions", out.resolutions());
            summary.put("pagesFetched", out.pagesFetched());
            summary.put("waiting", out.waiting());
            summary.put("pendingNew", plan.newCount());
            summary.put("pendingLegacy", plan.legacyCount());
            summary.put("upToDate", plan.upToDate());
            run.setSummaryJson(objectMapper.writeValueAsString(summary));
            run.setFinishedAt(LocalDateTime.now());
            run = runRepo.save(run);

            SyncResult result = new SyncResult(run.getId(), SOURCE, status, dryRun, false, plan.newCount() + plan.legacyCount(),
                    out.added(), out.reanalyzed(), out.held(), out.deferred(), out.warnings(), out.resolutions(),
                    List.of(), message);
            // 旧方式の再解析だけの回は毎回になるので通知しない。新しい譜面を足したときだけ知らせる
            if (!dryRun && !out.added().isEmpty()) notifyAdmin(result);
            return result;
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
            try {
                runRepo.save(run);
            } catch (Exception saveError) {
                log.warn("[textage譜面同期] 失敗記録の保存にも失敗: {}", saveError.getMessage());
            }
            throw e;
        } finally {
            running.set(false);
        }
    }

    // ── 取得 ──────────────────────────────────────────────

    /** textage への取得。2 回目以降はリクエストの間隔を空ける。 */
    private final class RateLimitedFetcher implements TextageChartSync.Fetcher {
        private boolean first = true;

        @Override
        public String fetch(String path) throws Exception {
            if (!first && requestIntervalMillis > 0) Thread.sleep(requestIntervalMillis);
            first = false;
            return fetchText(baseUrl + path);
        }
    }

    /** 【メソッドの役割】 textage のファイルを Shift_JIS として取得する。200 以外は例外。 */
    private String fetchText(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "beat-seeker-chart-sync/1.0 (+https://beat-seeker.onrender.com)")
                .GET()
                .build();
        HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode());
        }
        return new String(res.body(), SHIFT_JIS);
    }

    // ── 要約・通知 ────────────────────────────────────────

    private static String buildMessage(boolean dryRun, Plan plan, Outcome out) {
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "【確認のみ】" : "");
        sb.append("textage 譜面: 未解析 ").append(plan.newCount())
          .append(" / 旧方式 ").append(plan.legacyCount())
          .append(" / 解析済み ").append(plan.upToDate()).append(" 譜面");
        sb.append("。今回 ").append(out.pagesFetched()).append(" ページ取得し、新規 ").append(out.added().size())
          .append(" / 再解析 ").append(out.reanalyzed().size())
          .append(" / 保留 ").append(out.held().size()).append(" 譜面");
        if (!out.deferred().isEmpty()) sb.append("、上限で次回に回した曲 ").append(out.deferred().size());
        if (out.waiting() > 0) sb.append("、再試行待ちの曲 ").append(out.waiting());
        if (dryRun && !out.profiles().isEmpty()) sb.append("（DB は変更していません）");
        return sb.toString();
    }

    /** 【メソッドの役割】 新しい譜面を解析したとき、管理者（メールアドレス登録済みの場合）へ内訳をメールする。失敗はログのみ。 */
    private void notifyAdmin(SyncResult r) {
        try {
            userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                if (admin.getEmail() == null || admin.getEmail().isBlank()) return;
                String subject = "[beat-seeker] textage 譜面同期: 新規 " + r.added().size() + " / 再解析 " + r.updated().size() + " 譜面";
                StringBuilder html = new StringBuilder();
                html.append("<p>").append(esc(r.message())).append("</p>");
                appendList(html, "新規に解析", r.added());
                appendList(html, "textage のページを特定・訂正", r.titleMatches());
                appendList(html, "保留（textage の譜面とノーツ数が一致しない・ページが無い）", r.held());
                appendList(html, "警告", r.warnings());
                emailService.sendAdminNotification(admin.getEmail(), subject, html.toString());
            });
        } catch (Exception e) {
            log.warn("[textage譜面同期] 管理者通知に失敗: {}", e.getMessage());
        }
    }

    private static void appendList(StringBuilder html, String heading, List<String> items) {
        if (items == null || items.isEmpty()) return;
        html.append("<h3 style='font-size:14px;margin:16px 0 4px;'>").append(esc(heading)).append("（").append(items.size()).append("）</h3><ul>");
        for (String s : items) html.append("<li style='font-size:13px;'>").append(esc(s)).append("</li>");
        html.append("</ul>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
