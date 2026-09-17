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
 * 【Service の役割】 BEMANIWiki の「beatmania IIDX 34 ZINRAI/新曲リスト」と「旧曲リスト」を定期的に取得し、
 * 新しく載った曲・譜面や訂正（SP の B/N/H/A/L のレベルとノーツ数、GENRE/TITLE/ARTIST/BPM）を
 * beat-seeker の公開中（active）楽曲マスタへ自動で反映する。
 *
 * 取得元（source）は 2 つあり、別々に実行・記録する:
 *  - {@link #SOURCE_NEW} 新曲リスト。レベル表と総ノーツ数表が 1 ページに載っている。1 日 4 回
 *  - {@link #SOURCE_OLD} 旧曲リスト（レベル表）+ 旧曲総ノーツ数リスト（総ノーツ数表）の 2 ページ。
 *    合わせて 4MB 強あり変更も少ないので 1 日 1 回。レベル変更・ノーツ数の訂正・旧曲への譜面追加
 *    （LEGGENDARIA など）・復活曲を拾う
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
 *  - 既存曲との突き合わせは {@link WikiTitleResolver}（完全一致 → 正規化一致 → ARTIST/GENRE/ノーツ数の指紋一致）。
 *    完全一致以外は既存の曲名を採用して二重登録を避ける。新曲リストでは警告、旧曲リストでは件数が多い
 *    （特殊文字の曲が常時 40 曲ほど）ので「曲名の読み替え」として別枠に記録する。
 *  - 登録済みの譜面は、wiki 側のノーツ数だけが未記載でもレベルは比較する（保留にしない）。
 *  - BPM が「※」（譜面により異なる）のときは、登録済みの具体的な値を上書きしない。
 *  - Lv11/12 の ANOTHER/LEGGENDARIA を新規追加したときは難易度表の Uncategorized に入れる（管理画面の追加と同じ）。
 *    登録済みの ANOTHER/LEGGENDARIA のレベルが 11/12 に絡んで変わったときは、難易度表の配置確認を警告に出す。
 *  - 旧曲リストは 1 回の定期実行で変更が {@code max-auto-songs} 曲を超えたら反映を見送る（NEEDS_REVIEW）。
 *    表構成の変化などで全曲を書き換えてしまう事故の歯止め。管理画面からの手動同期はこの制限を受けない。
 *  - 同時実行は {@link AtomicBoolean} で 1 本に制限する（新曲・旧曲で共通）。
 *
 * 設定（application.yml の app.wiki-song-sync.*）:
 *   enabled     新曲リストの定期実行の有効/無効（既定 true。app.scheduling.enabled=false でも止まる）
 *   cron        新曲リストの実行時刻（JST。既定 0 20 0,6,12,18 * * * = 1 日 4 回）
 *   url         新曲リストのページ
 *   auto-apply  定期実行で DB に反映するか（false なら記録のみ。新曲・旧曲で共通）
 *   old-songs.enabled / cron / list-url / notes-url / max-auto-songs   旧曲リスト側の設定
 */
@Service
public class WikiSongSyncService {

    private static final Logger log = LoggerFactory.getLogger(WikiSongSyncService.class);

    /** 実行のきっかけ（{@link WikiSongSyncRun#getTriggerKind()}）。 */
    public static final String TRIGGER_SCHEDULED = "scheduled";
    public static final String TRIGGER_MANUAL = "manual";

    /** 取得元（{@link WikiSongSyncRun#getSource()}）。 */
    public static final String SOURCE_NEW = "new";
    public static final String SOURCE_OLD = "old";

    /** 定期実行で変更が多すぎて反映を見送ったときのステータス。 */
    public static final String STATUS_NEEDS_REVIEW = "NEEDS_REVIEW";

    /** BPM 列の「譜面により BPM が異なる（備考参照）」の印。具体的な値ではないので既存値の上書きには使わない。 */
    private static final String BPM_VARIES = "※";

    /** 旧曲の 2 ページを続けて取りに行くときの間隔（wiki への配慮）。 */
    private static final long FETCH_INTERVAL_MILLIS = 2000;

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
    private final boolean oldSongsEnabled;
    private final String oldSongsListUrl;
    private final String oldSongsNotesUrl;
    private final int oldSongsMaxAutoSongs;

    /** 同時実行防止（定期実行と手動実行が重なったときに片方を弾く）。 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 同期 1 回分の結果（API レスポンスにそのまま載せる）。
     *
     * @param runId            実行記録の ID
     * @param source           取得元（new / old）
     * @param status           SUCCESS / NO_CHANGE / NEEDS_REVIEW / FAILED
     * @param dryRun           差分確認のみだったか
     * @param pageChanged      前回の実行からページ内容が変わっていたか
     * @param songsOnPage      ページ上の曲数（未配信を含む）
     * @param added            追加した譜面（表示用文字列）
     * @param updated          更新した譜面（表示用文字列。変更内容を含む）
     * @param held             保留した譜面と理由
     * @param skippedSongs     配信前として見送った曲
     * @param warnings         解析・突き合わせの警告
     * @param titleMatches     旧曲リストで、wiki と表記が違う既存曲へ読み替えた曲名（wiki → 登録済み）
     * @param uncategorizedAdded 難易度表 Uncategorized に追加した曲名
     * @param message          1 行の要約
     */
    public record SyncResult(Long runId, String source, String status, boolean dryRun, boolean pageChanged, int songsOnPage,
                             List<String> added, List<String> updated, List<String> held, List<String> skippedSongs,
                             List<String> warnings, List<String> titleMatches, List<String> uncategorizedAdded,
                             String message) {}

    /** 突き合わせの結果（反映前の計画）。 */
    record Plan(List<WikiChartChange> changes, List<String> added, List<String> updated, List<String> held,
                        List<String> skippedSongs, List<String> warnings, List<String> titleMatches, int unchanged) {
        /** 変更がかかる曲数（1 曲の GENRE 訂正は譜面の数だけ変更行になるので、歯止めは曲数で数える）。 */
        int changedSongCount() {
            return (int) changes.stream().map(WikiChartChange::title).distinct().count();
        }
    }

    public WikiSongSyncService(SongDefinitionRepository songDefRepo,
                               GameDataService gameDataService,
                               WikiSongSyncRunRepository runRepo,
                               UserRepository userRepository,
                               AdminAuthService adminAuthService,
                               EmailService emailService,
                               ObjectMapper objectMapper,
                               @Value("${app.wiki-song-sync.enabled:true}") boolean enabled,
                               @Value("${app.wiki-song-sync.url:https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%96%B0%E6%9B%B2%E3%83%AA%E3%82%B9%E3%83%88}") String pageUrl,
                               @Value("${app.wiki-song-sync.auto-apply:true}") boolean autoApply,
                               @Value("${app.wiki-song-sync.old-songs.enabled:true}") boolean oldSongsEnabled,
                               @Value("${app.wiki-song-sync.old-songs.list-url:https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%97%A7%E6%9B%B2%E3%83%AA%E3%82%B9%E3%83%88}") String oldSongsListUrl,
                               @Value("${app.wiki-song-sync.old-songs.notes-url:https://bemaniwiki.com/?beatmania+IIDX+34+ZINRAI/%E6%97%A7%E6%9B%B2%E7%B7%8F%E3%83%8E%E3%83%BC%E3%83%84%E6%95%B0%E3%83%AA%E3%82%B9%E3%83%88}") String oldSongsNotesUrl,
                               @Value("${app.wiki-song-sync.old-songs.max-auto-songs:30}") int oldSongsMaxAutoSongs) {
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
        this.oldSongsEnabled = oldSongsEnabled;
        this.oldSongsListUrl = oldSongsListUrl;
        this.oldSongsNotesUrl = oldSongsNotesUrl;
        this.oldSongsMaxAutoSongs = oldSongsMaxAutoSongs;
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
        runScheduled(SOURCE_NEW);
    }

    /**
     * 【メソッドの役割】 旧曲リスト（+ 旧曲総ノーツ数リスト）を定期的に確認して差分を取り込む。
     * 既定は JST 5:40 の 1 日 1 回（環境変数 WIKI_OLD_SONG_SYNC_CRON で変更可）。2 ページで 4MB 強あり、
     * 旧曲の変更は少ないので新曲リストより頻度を落としている。
     */
    @Scheduled(cron = "${app.wiki-song-sync.old-songs.cron:0 40 5 * * *}", zone = "Asia/Tokyo")
    public void runScheduledOldSongs() {
        if (!oldSongsEnabled) return;
        runScheduled(SOURCE_OLD);
    }

    private void runScheduled(String source) {
        try {
            SyncResult r = sync(source, TRIGGER_SCHEDULED, !autoApply);
            log.info("[wiki{}同期] {}", sourceLabel(source), r.message());
        } catch (Exception e) {
            log.error("[wiki{}同期] 定期実行に失敗: {}", sourceLabel(source), e.getMessage(), e);
        }
    }

    // ── 同期本体 ──────────────────────────────────────────

    /**
     * 【メソッドの役割】 wiki を取得 → 解析 → 公開中マスタと突き合わせ → （dry-run でなければ）反映 → 記録。
     *
     * 旧曲リストの定期実行で、変更のかかる曲数が {@code old-songs.max-auto-songs} を超えた場合は反映せず
     * {@link #STATUS_NEEDS_REVIEW} として記録する（手動実行は制限なし）。
     *
     * @param source  取得元（{@link #SOURCE_NEW} / {@link #SOURCE_OLD}）
     * @param trigger 実行のきっかけ（scheduled / manual）
     * @param dryRun  true なら差分を計算・記録するだけで DB の曲マスタは変更しない
     * @return 結果
     * @throws IllegalArgumentException source が不正
     * @throws IllegalStateException 別の同期が実行中
     * @throws Exception 取得・解析・保存に失敗（記録には FAILED として残す）
     */
    public SyncResult sync(String source, String trigger, boolean dryRun) throws Exception {
        if (!SOURCE_NEW.equals(source) && !SOURCE_OLD.equals(source)) {
            throw new IllegalArgumentException("source は new か old を指定してください: " + source);
        }
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("bemaniwiki 同期は既に実行中です");
        }
        WikiSongSyncRun run = new WikiSongSyncRun();
        run.setStartedAt(LocalDateTime.now());
        run.setSource(source);
        run.setTriggerKind(trigger);
        run.setDryRun(dryRun);
        try {
            BemaniwikiSongListParser.Result page = fetchAndParse(source);
            LocalDate today = LocalDate.now(JstTime.JST);

            boolean pageChanged = runRepo.findFirstBySourceAndPageHashIsNotNullOrderByIdDesc(source)
                    .map(prev -> !page.contentHash().equals(prev.getPageHash()))
                    .orElse(true);

            Plan plan = buildPlan(page.songs(), today, source);
            List<String> warnings = new ArrayList<>(page.warnings());
            warnings.addAll(plan.warnings());

            boolean hasChanges = !plan.changes().isEmpty();
            int maxAutoSongs = SOURCE_OLD.equals(source) ? oldSongsMaxAutoSongs : 0;
            boolean needsReview = !dryRun && hasChanges && TRIGGER_SCHEDULED.equals(trigger)
                    && maxAutoSongs > 0 && plan.changedSongCount() > maxAutoSongs;

            List<String> uncatAdded = new ArrayList<>();
            if (!dryRun && hasChanges && !needsReview) {
                Set<String> targets = gameDataService.applyWikiChartChanges(plan.changes());
                uncatAdded.addAll(targets);
            }

            String status = needsReview ? STATUS_NEEDS_REVIEW : hasChanges ? "SUCCESS" : "NO_CHANGE";
            String message = buildMessage(source, dryRun, pageChanged, page.songs().size(), plan);
            if (needsReview) {
                message += "。変更が " + plan.changedSongCount() + " 曲あり、自動反映の上限 " + maxAutoSongs
                        + " 曲を超えたため反映を見送りました（管理画面で差分を確認して手動で同期してください）";
            }

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
            summary.put("titleMatches", plan.titleMatches());
            summary.put("uncategorizedAdded", uncatAdded);
            summary.put("unchanged", plan.unchanged());
            run.setSummaryJson(objectMapper.writeValueAsString(summary));
            run.setFinishedAt(LocalDateTime.now());
            run = runRepo.save(run);

            SyncResult result = new SyncResult(run.getId(), source, status, dryRun, pageChanged, page.songs().size(),
                    plan.added(), plan.updated(), plan.held(), plan.skippedSongs(), warnings, plan.titleMatches(),
                    uncatAdded, message);

            // 見送り（NEEDS_REVIEW）はページが変わらない限り毎日同じ内容になるので、通知は変わったときだけ。
            if (!dryRun && hasChanges && (!needsReview || pageChanged)) {
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
            m.put("source", r.getSource() != null ? r.getSource() : SOURCE_NEW);
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

    /**
     * 【メソッドの役割】 取得元のページを取りに行って解析する。
     *
     * 旧曲は 2 ページ（総ノーツ数 → レベル表の順）。どちらも 2MB 前後あるので、先のページの HTML と DOM を
     * 手放してから次を取得し、同時に抱える量を 1 ページ分に抑える。
     */
    private BemaniwikiSongListParser.Result fetchAndParse(String source) throws IOException, InterruptedException {
        if (SOURCE_OLD.equals(source)) {
            BemaniwikiSongListParser.NotesPage notes = parser.parseNotesPage(fetchHtml(oldSongsNotesUrl));
            Thread.sleep(FETCH_INTERVAL_MILLIS);
            return parser.parse(fetchHtml(oldSongsListUrl), notes);
        }
        return parser.parse(fetchHtml(pageUrl));
    }

    /** 【メソッドの役割】 ページの HTML を取得する。200 以外は例外。 */
    String fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
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
     *  - 手順1: active の全譜面を (title, difficulty) で引けるようにし、曲名の対応づけ（{@link WikiTitleResolver}）を用意
     *  - 手順2: 配信前セクションの曲は見送り（skippedSongs）
     *  - 手順3: 曲名を既存マスタに寄せる（完全一致 → 正規化一致 → 指紋一致。完全一致以外は記録に残す）
     *  - 手順4: 譜面ごとに取り込み可否を判定し、未登録なら ADD、登録済みで値が違えば UPDATE
     */
    private Plan buildPlan(List<Song> songs, LocalDate today, String source) {
        return planChanges(songDefRepo.findByRevision("active"), songs, today, source);
    }

    /** {@link #buildPlan} の本体（DB に依存しないのでユニットテストから直接呼ぶ）。 */
    static Plan planChanges(List<SongDefinition> actives, List<Song> songs, LocalDate today, String source) {
        boolean oldSongs = SOURCE_OLD.equals(source);
        Map<String, SongDefinition> byKey = new HashMap<>();
        for (SongDefinition sd : actives) {
            byKey.putIfAbsent(sd.getTitle() + "\0" + sd.getDifficulty(), sd);
        }
        WikiTitleResolver resolver = new WikiTitleResolver(actives, songs);

        List<WikiChartChange> changes = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> updated = new ArrayList<>();
        List<String> held = new ArrayList<>();
        List<String> skippedSongs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> titleMatches = new ArrayList<>();
        int unchanged = 0;

        for (Song song : songs) {
            if (!song.isReleased(today)) {
                String label = song.section() != null ? song.section().label() : "";
                skippedSongs.add(song.title() + "（" + label + "）");
                continue;
            }
            WikiTitleResolver.Match match = resolver.resolve(song);
            String dbTitle = match.dbTitle();
            if (match.kind() == WikiTitleResolver.Kind.NORMALIZED || match.kind() == WikiTitleResolver.Kind.FINGERPRINT) {
                String how = match.kind() == WikiTitleResolver.Kind.FINGERPRINT ? "（ARTIST・GENRE・ノーツ数が一致）" : "";
                if (oldSongs) {
                    titleMatches.add("wiki『" + song.title() + "』→ 登録済み『" + dbTitle + "』" + how);
                } else {
                    warnings.add("曲名の表記が既存マスタと異なるため既存の表記に寄せました" + how + ": wiki『" + song.title() + "』→ 登録済み『" + dbTitle + "』");
                }
            }
            boolean newSongWarned = false;

            for (Chart chart : song.charts().values()) {
                String label = dbTitle + " [" + BemaniwikiSongListParser.difficultyName(chart.difficulty()) + "]";
                SongDefinition existing = byKey.get(dbTitle + "\0" + chart.difficulty());
                if (!chart.isImportable()) {
                    // 登録済みの譜面は、wiki 側のノーツ数だけが未記載ならレベルの比較は続ける（保留にしない）
                    boolean levelStillComparable = existing != null && !chart.hidden() && !chart.uncertain()
                            && chart.level() != null && chart.level() > 0;
                    if (!levelStillComparable) {
                        held.add(label + "：" + chart.holdReason());
                        continue;
                    }
                }
                if (existing == null) {
                    if (oldSongs && match.kind() == WikiTitleResolver.Kind.NONE && !newSongWarned) {
                        warnings.add("旧曲リストの曲が楽曲マスタに見つからないため新規に追加します（復活曲。曲名の表記違いによる二重登録でないか確認してください）: " + song.title());
                        newSongWarned = true;
                    }
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
                    boolean anotherOrLegg = BemaniwikiSongListParser.ANOTHER.equals(chart.difficulty())
                            || BemaniwikiSongListParser.LEGGENDARIA.equals(chart.difficulty());
                    if (anotherOrLegg && (isTableLevel(existing.getLevel()) || isTableLevel(chart.level()))) {
                        warnings.add("難易度表の対象レベル（★11/12）に絡むレベル変更です。難易度表の配置を確認してください: "
                                + label + " ★" + existing.getLevel() + "→" + chart.level());
                    }
                }
                if (chart.notes() != null && !Objects.equals(existing.getNotes(), chart.notes())) {
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
                String existingBpm = trimToNull(existing.getBpm());
                boolean keepSpecificBpm = BPM_VARIES.equals(song.bpm()) && existingBpm != null;
                if (song.bpm() != null && !song.bpm().equals(existingBpm) && !keepSpecificBpm) {
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
        return new Plan(changes, added, updated, held, skippedSongs, warnings, titleMatches, unchanged);
    }

    /** 難易度表（☆11 / ☆12）の対象になるレベルか。 */
    private static boolean isTableLevel(Integer level) {
        return level != null && (level == 11 || level == 12);
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 取得元の表示名（ログ・通知・要約）。 */
    private static String sourceLabel(String source) {
        return SOURCE_OLD.equals(source) ? "旧曲" : "新曲";
    }

    private String buildMessage(String source, boolean dryRun, boolean pageChanged, int songsOnPage, Plan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(dryRun ? "【確認のみ】" : "");
        sb.append(sourceLabel(source)).append("リスト: ページ上 ").append(songsOnPage).append(" 曲");
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
                boolean needsReview = STATUS_NEEDS_REVIEW.equals(r.status());
                String subject = "[beat-seeker] bemaniwiki " + sourceLabel(r.source()) + "同期: "
                        + (needsReview ? "【未反映・要確認】" : "")
                        + "追加 " + r.added().size() + " / 更新 " + r.updated().size() + " 譜面";
                StringBuilder html = new StringBuilder();
                html.append("<p>").append(esc(r.message())).append("</p>");
                appendList(html, "追加", r.added());
                appendList(html, "更新", r.updated());
                appendList(html, "難易度表 Uncategorized に追加", r.uncategorizedAdded());
                appendList(html, "保留（wiki が埋まり次第、次回以降に取り込み）", r.held());
                appendList(html, "警告", r.warnings());
                String urls = SOURCE_OLD.equals(r.source()) ? oldSongsListUrl + " / " + oldSongsNotesUrl : pageUrl;
                html.append("<p style='color:#94a3b8;font-size:12px;'>取得元: ").append(esc(urls)).append("</p>");
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
