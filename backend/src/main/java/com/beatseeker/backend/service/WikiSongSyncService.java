package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.WikiSongSyncRun;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.WikiSongSyncRunRepository;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Chart;
import com.beatseeker.backend.service.BemaniwikiSongListParser.Song;
import com.beatseeker.backend.service.GameDataService.WikiChartChange;
import com.beatseeker.backend.util.JstTime;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 BEMANIWiki の「beatmania IIDX 34 ZINRAI/新曲リスト」を定期的に取得し、
 * 新しく載った曲・譜面（SP の B/N/H/A/L のレベルとノーツ数、GENRE/TITLE/ARTIST/BPM）を
 * beat-seeker の公開中（active）楽曲マスタへ自動で反映する。
 *
 * 責務:
 *  - ページの取得（HTTP）と解析（{@link BemaniwikiSongListParser}）
 *  - 公開中の曲マスタとの突き合わせ（追加 / 更新 / 保留 の仕分け）
 *  - 反映（{@link GameDataService#applyWikiChartChanges}）と実行記録（{@link WikiSongSyncRun}）
 *  - 反映があったときの管理者へのメール通知
 *
 * 依存:
 *  - {@link SongDefinitionRepository}: 公開中の曲マスタ（比較元）
 *  - {@link GameDataService}: 曲マスタへの書き込みと難易度表 Uncategorized への追加
 *  - {@link WikiSongSyncRunRepository}: 実行記録
 *  - {@link EmailService} / {@link UserRepository} / {@link AdminAuthService}: 管理者宛の通知
 *
 * 主要ロジックの概観:
 *  - 取り込みは draft を経由せず active に直接書く（管理者が編集中の draft を巻き込まないため）。
 *    {@code app.wiki-song-sync.auto-apply=false} にすると定期実行は差分の確認（dry-run）だけになる。
 *  - 取り込む譜面は「配信済みセクション・レベルとノーツ数が確定・未解禁でない」もののみ。
 *    それ以外は保留として記録し、wiki が埋まった次回以降の実行で拾う。
 *  - 既存曲との突き合わせは曲名の完全一致 → 正規化一致（全角半角・空白・波ダッシュ）の順。正規化でしか
 *    一致しない場合は既存の曲名を採用して二重登録を避け、警告として記録する。
 *  - Lv11/12 の ANOTHER/LEGGENDARIA を新規追加したときは難易度表の Uncategorized に入れる（管理画面の追加と同じ）。
 *  - 同時実行は {@link AtomicBoolean} で 1 本に制限する。
 *
 * 設定（application.yml の app.wiki-song-sync.*）:
 *   enabled     定期実行の有効/無効（既定 true。app.scheduling.enabled=false でも止まる）
 *   cron        実行時刻（JST。既定 0 20 0,6,12,18 * * * = 1 日 4 回）
 *   url         取得するページ
 *   auto-apply  定期実行で DB に反映するか（false なら記録のみ）
 */
@Service
public class WikiSongSyncService {

    private static final Logger log = LoggerFactory.getLogger(WikiSongSyncService.class);

    /** 実行のきっかけ（{@link WikiSongSyncRun#getTriggerKind()}）。 */
    public static final String TRIGGER_SCHEDULED = "scheduled";
    public static final String TRIGGER_MANUAL = "manual";

    private final SongDefinitionRepository songDefRepo;
    private final GameDataService gameDataService;
    private final WikiSongSyncRunRepository runRepo;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;
    private final EmailService emailService;
    private final ObjectMapper objectMapper;

    private final BemaniwikiSongListParser parser = new BemaniwikiSongListParser();
    private final HttpClient httpClient;

    private final boolean enabled;
    private final String pageUrl;
    private final boolean autoApply;

    /** 同時実行防止（定期実行と手動実行が重なったときに片方を弾く）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 同期 1 回分の結果（API レスポンスにそのまま載せる）。
     *
     * @param runId            実行記録の ID
     * @param status           SUCCESS / NO_CHANGE / FAILED
     * @param dryRun           差分確認のみだったか
     * @param pageChanged      前回の実行からページ内容が変わっていたか
     * @param songsOnPage      ページ上の曲数（未配信を含む）
     * @param added            追加した譜面（表示用文字列）
     * @param updated          更新した譜面（表示用文字列。変更内容を含む）
     * @param held             保留した譜面と理由
     * @param skippedSongs     配信前として見送った曲
     * @param warnings         解析・突き合わせの警告
     * @param uncategorizedAdded 難易度表 Uncategorized に追加した曲名
     * @param message          1 行の要約
     */
    public record SyncResult(Long runId, String status, boolean dryRun, boolean pageChanged, int songsOnPage,
                             List<String> added, List<String> updated, List<String> held, List<String> skippedSongs,
                             List<String> warnings, List<String> uncategorizedAdded, String message) {}

    /** 突き合わせの結果（反映前の計画）。 */
    private record Plan(List<WikiChartChange> changes, List<String> added, List<String> updated, List<String> held,
                        List<String> skippedSongs, List<String> warnings, int unchanged) {}

    public WikiSongSyncService(SongDefinitionRepository songDefRepo,
                               GameDataService gameDataService,
                               WikiSongSyncRunRepository runRepo,
                               UserRepository userRepository,
                               AdminAuthService adminAuthService,
                               EmailService emailService,
                               ObjectMapper objectMapper,
                               @Value("${app.wiki-song-sync.enabled:true}") boolean enabled,
                               @Value("${app.wiki-song-sync.url:https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%96%B0%E6%9B%B2%E3%83%AA%E3%82%B9%E3%83%88}") String pageUrl,
                               @Value("${app.wiki-song-sync.auto-apply:true}") boolean autoApply) {
        this.songDefRepo = songDefRepo;
        this.gameDataService = gameDataService;
        this.runRepo = runRepo;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
        this.emailService = emailService;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.pageUrl = pageUrl;
        this.autoApply = autoApply;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── 定期実行 ──────────────────────────────────────────

    /**
     * 【メソッドの役割】 定期的に wiki を確認して差分を取り込む。
     * 既定は JST 0:20 / 6:20 / 12:20 / 18:20（環境変数 WIKI_SONG_SYNC_CRON で変更可）。
     * {@code app.scheduling.enabled=false}（prod-db プロファイル）では動かない。
     */
    @Scheduled(cron = "${app.wiki-song-sync.cron:0 20 0,6,12,18 * * *}", zone = "Asia/Tokyo")
    public void runScheduled() {
        if (!enabled) return;
        try {
            SyncResult r = sync(TRIGGER_SCHEDULED, !autoApply);
            log.info("[wiki新曲同期] {}", r.message());
        } catch (Exception e) {
            log.error("[wiki新曲同期] 定期実行に失敗: {}", e.getMessage(), e);
        }
    }

    // ── 同期本体 ──────────────────────────────────────────

    /**
     * 【メソッドの役割】 wiki を取得 → 解析 → 公開中マスタと突き合わせ → （dry-run でなければ）反映 → 記録。
     *
     * @param trigger 実行のきっかけ（scheduled / manual）
     * @param dryRun  true なら差分を計算・記録するだけで DB の曲マスタは変更しない
     * @return 結果
     * @throws IllegalStateException 別の同期が実行中
     * @throws Exception 取得・解析・保存に失敗（記録には FAILED として残す）
     */
    public SyncResult sync(String trigger, boolean dryRun) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("bemaniwiki 同期は既に実行中です");
        }
        WikiSongSyncRun run = new WikiSongSyncRun();
        run.setStartedAt(LocalDateTime.now());
        run.setTriggerKind(trigger);
        run.setDryRun(dryRun);
        try {
            String html = fetchHtml();
            BemaniwikiSongListParser.Result page = parser.parse(html);
            LocalDate today = LocalDate.now(JstTime.JST);

            boolean pageChanged = runRepo.findFirstByPageHashIsNotNullOrderByIdDesc()
                    .map(prev -> !page.contentHash().equals(prev.getPageHash()))
                    .orElse(true);

            Plan plan = buildPlan(page.songs(), today);
            List<String> warnings = new ArrayList<>(page.warnings());
            warnings.addAll(plan.warnings());

            List<String> uncatAdded = new ArrayList<>();
            if (!dryRun && !plan.changes().isEmpty()) {
                Set<String> targets = gameDataService.applyWikiChartChanges(plan.changes());
                uncatAdded.addAll(targets);
            }

            boolean hasChanges = !plan.changes().isEmpty();
            String status = hasChanges ? "SUCCESS" : "NO_CHANGE";
            String message = buildMessage(dryRun, pageChanged, page.songs().size(), plan);

            run.setStatus(status);
            run.setPageHash(page.contentHash());
            run.setPageChanged(pageChanged);
            run.setSongsOnPage(page.songs().size());
            run.setAddedCount(plan.added().size());
            run.setUpdatedCount(plan.updated().size());
            run.setHeldCount(plan.held().size());
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("added", plan.added());
            summary.put("updated", plan.updated());
            summary.put("held", plan.held());
            summary.put("skippedSongs", plan.skippedSongs());
            summary.put("warnings", warnings);
            summary.put("uncategorizedAdded", uncatAdded);
            summary.put("unchanged", plan.unchanged());
            run.setSummaryJson(objectMapper.writeValueAsString(summary));
            run.setFinishedAt(LocalDateTime.now());
            run = runRepo.save(run);

            SyncResult result = new SyncResult(run.getId(), status, dryRun, pageChanged, page.songs().size(),
                    plan.added(), plan.updated(), plan.held(), plan.skippedSongs(), warnings, uncatAdded, message);

            if (!dryRun && hasChanges) {
                notifyAdmin(result);
            }
            return result;
        } catch (Exception e) {
            run.setStatus("FAILED");
            run.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
            run.setFinishedAt(LocalDateTime.now());
            try {
                runRepo.save(run);
            } catch (Exception saveError) {
                log.warn("[wiki新曲同期] 失敗記録の保存にも失敗: {}", saveError.getMessage());
            }
            throw e;
        } finally {
            running.set(false);
        }
    }

    /** 【メソッドの役割】 直近の実行記録を新しい順に返す（管理画面用）。日時は JST オフセット付き文字列にする。 */
    public List<Map<String, Object>> recentRuns() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WikiSongSyncRun r : runRepo.findTop20ByOrderByIdDesc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("startedAt", JstTime.toIsoString(r.getStartedAt()));
            m.put("finishedAt", JstTime.toIsoString(r.getFinishedAt()));
            m.put("trigger", r.getTriggerKind());
            m.put("dryRun", r.isDryRun());
            m.put("status", r.getStatus());
            m.put("pageChanged", r.getPageChanged());
            m.put("songsOnPage", r.getSongsOnPage());
            m.put("addedCount", r.getAddedCount());
            m.put("updatedCount", r.getUpdatedCount());
            m.put("heldCount", r.getHeldCount());
            m.put("errorMessage", r.getErrorMessage());
            out.add(m);
        }
        return out;
    }

    // ── 取得 ──────────────────────────────────────────────

    /** 【メソッドの役割】 ページの HTML を取得する。200 以外は例外。 */
    String fetchHtml() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(pageUrl))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "beat-seeker-song-sync/1.0 (+https://beat-seeker.onrender.com)")
                .header("Accept", "text/html")
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IOException("bemaniwiki の取得に失敗: HTTP " + res.statusCode());
        }
        return res.body();
    }

    // ── 突き合わせ ────────────────────────────────────────

    /**
     * 【メソッドの役割】 解析した曲を公開中の曲マスタと突き合わせ、追加・更新・保留に仕分ける。
     *
     * 処理の流れ:
     *  - 手順1: active の全譜面を (title, difficulty) と 正規化した title で引けるようにする
     *  - 手順2: 配信前セクションの曲は見送り（skippedSongs）
     *  - 手順3: 曲名を既存マスタに寄せる（完全一致 → 正規化一致。後者は警告）
     *  - 手順4: 譜面ごとに取り込み可否を判定し、未登録なら ADD、登録済みで値が違えば UPDATE
     */
    private Plan buildPlan(List<Song> songs, LocalDate today) {
        List<SongDefinition> actives = songDefRepo.findByRevision("active");
        Map<String, SongDefinition> byKey = new HashMap<>();
        Map<String, String> titleByNorm = new HashMap<>();
        for (SongDefinition sd : actives) {
            byKey.putIfAbsent(sd.getTitle() + "\0" + sd.getDifficulty(), sd);
            titleByNorm.putIfAbsent(BemaniwikiSongListParser.normalizeTitle(sd.getTitle()), sd.getTitle());
        }

        List<WikiChartChange> changes = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> held = new ArrayList<>();
        List<String> skippedSongs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int unchanged = 0;

        for (Song song : songs) {
            if (!song.isReleased(today)) {
                String label = song.section() != null ? song.section().label() : "";
                skippedSongs.add(song.title() + "（" + label + "）");
                continue;
            }
            String dbTitle = song.title();
            String norm = BemaniwikiSongListParser.normalizeTitle(song.title());
            boolean exactExists = actives.stream().anyMatch(sd -> sd.getTitle().equals(song.title()));
            if (!exactExists && titleByNorm.containsKey(norm)) {
                dbTitle = titleByNorm.get(norm);
                warnings.add("曲名の表記が既存マスタと異なるため既存の表記に寄せました: wiki『" + song.title() + "』→ 登録済み『" + dbTitle + "』");
            }

            for (Chart chart : song.charts().values()) {
                String label = dbTitle + " [" + BemaniwikiSongListParser.difficultyName(chart.difficulty()) + "]";
                if (!chart.isImportable()) {
                    held.add(label + "：" + chart.holdReason());
                    continue;
                }
                SongDefinition existing = byKey.get(dbTitle + "\0" + chart.difficulty());
                if (existing == null) {
                    changes.add(new WikiChartChange(dbTitle, chart.difficulty(), WikiChartChange.ADD,
                            chart.level(), chart.notes(), song.genre(), song.artist(), song.bpm()));
                    added.add(label + " ★" + chart.level() + " / " + chart.notes() + " notes");
                    continue;
                }
                List<String> diffs = new ArrayList<>();
                Integer level = null;
                Integer notes = null;
                String genre = null;
                String artist = null;
                String bpm = null;
                if (!Objects.equals(existing.getLevel(), chart.level())) {
                    level = chart.level();
                    diffs.add("★" + existing.getLevel() + "→" + chart.level());
                }
                if (!Objects.equals(existing.getNotes(), chart.notes())) {
                    notes = chart.notes();
                    diffs.add("notes " + existing.getNotes() + "→" + chart.notes());
                }
                if (song.genre() != null && !song.genre().equals(trimToNull(existing.getGenre()))) {
                    genre = song.genre();
                    diffs.add("genre『" + existing.getGenre() + "』→『" + song.genre() + "』");
                }
                if (song.artist() != null && !song.artist().equals(trimToNull(existing.getArtist()))) {
                    artist = song.artist();
                    diffs.add("artist『" + existing.getArtist() + "』→『" + song.artist() + "』");
                }
                if (song.bpm() != null && !song.bpm().equals(trimToNull(existing.getBpm()))) {
                    bpm = song.bpm();
                    diffs.add("bpm " + existing.getBpm() + "→" + song.bpm());
                }
                if (diffs.isEmpty()) {
                    unchanged++;
                    continue;
                }
                changes.add(new WikiChartChange(dbTitle, chart.difficulty(), WikiChartChange.UPDATE,
                        level, notes, genre, artist, bpm));
                updated.add(label + "：" + String.join(", ", diffs));
            }
        }
        return new Plan(changes, added, updated, held, skippedSongs, warnings, unchanged);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String buildMessage(boolean dryRun, boolean pageChanged, int songsOnPage, Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "【確認のみ】" : "");
        sb.append("ページ上 ").append(songsOnPage).append(" 曲");
        sb.append(pageChanged ? "（前回から変更あり）" : "（前回から変更なし）");
        sb.append("。追加 ").append(plan.added().size())
          .append(" / 更新 ").append(plan.updated().size())
          .append(" / 既存一致 ").append(plan.unchanged())
          .append(" / 保留 ").append(plan.held().size())
          .append(" 譜面、配信前 ").append(plan.skippedSongs().size()).append(" 曲");
        if (dryRun && !plan.changes().isEmpty()) sb.append("（DB は変更していません）");
        return sb.toString();
    }

    // ── 通知 ──────────────────────────────────────────────

    /** 【メソッドの役割】 反映があったとき、管理者（メールアドレス登録済みの場合）へ内訳をメールする。失敗はログのみ。 */
    private void notifyAdmin(SyncResult r) {
        try {
            userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                if (admin.getEmail() == null || admin.getEmail().isBlank()) return;
                String subject = "[beat-seeker] bemaniwiki 新曲同期: 追加 " + r.added().size() + " / 更新 " + r.updated().size() + " 譜面";
                StringBuilder html = new StringBuilder();
                html.append("<p>").append(esc(r.message())).append("</p>");
                appendList(html, "追加", r.added());
                appendList(html, "更新", r.updated());
                appendList(html, "難易度表 Uncategorized に追加", r.uncategorizedAdded());
                appendList(html, "保留（wiki が埋まり次第、次回以降に取り込み）", r.held());
                appendList(html, "警告", r.warnings());
                html.append("<p style='color:#94a3b8;font-size:12px;'>取得元: ").append(esc(pageUrl)).append("</p>");
                emailService.sendAdminNotification(admin.getEmail(), subject, html.toString());
            });
        } catch (Exception e) {
            log.warn("[wiki新曲同期] 管理者通知に失敗: {}", e.getMessage());
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
}
