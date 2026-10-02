package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.TextagePageSource;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.TextagePageSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【Service の役割】 譜面分析ページの譜面再生に使う譜面データ（ノーツ・CN・BPM 変化・小節線）を返す。
 *
 * 流れ:
 *  1. textage（{@code 33/showtime.html?1AC00}）が active の楽曲マスタに登録済みの SP 譜面かを確かめる
 *     （任意のページを textage へ取りに行かせないため）
 *  2. ページのスクリプトを {@code textage_page_sources} から読む。無い・古い（{@link #REFRESH_AFTER}）ときだけ textage から取得して保存する
 *  3. 難易度フラグ付きで実行（{@link TextagePageRunner}）し、ページのノーツ数が公式ノーツ数と一致するか照合する
 *     （{@link TextageChartSync#notesMatch}。一致しなければ別譜面なので返さない）
 *  4. {@link ChartPlaybackBuilder} で再生用の形にする。結果はメモリにも持つ（{@link #MEMORY_CACHE_SIZE} 譜面）
 *
 * textage への配慮: 取得は 1 本ずつ、前回の取得から {@code app.textage-sync.request-interval-ms} 空けて行う。
 */
@Service
public class ChartPlaybackService {

    private static final Logger log = LoggerFactory.getLogger(ChartPlaybackService.class);

    /** 保存したページをこの期間が過ぎたら取り直す（譜面の修正に追随するため）。 */
    static final Duration REFRESH_AFTER = Duration.ofDays(30);
    /** 組み立て済みの再生データをメモリに持つ譜面数。 */
    private static final int MEMORY_CACHE_SIZE = 200;
    private static final Charset SHIFT_JIS = Charset.forName("windows-31j");

    /** 呼び出し側に返す失敗（HTTP ステータスと利用者向けの文言）。 */
    public static final class PlaybackException extends RuntimeException {
        private final int status;

        PlaybackException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    private final SongDefinitionRepository songDefRepo;
    private final TextagePageSourceRepository sourceRepo;
    private final String baseUrl;
    private final long requestIntervalMillis;
    private final HttpClient httpClient;
    private final TextagePageRunner runner = new TextagePageRunner();

    private final Map<String, Map<String, Object>> memory = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                    return size() > MEMORY_CACHE_SIZE;
                }
            });

    private final Object fetchLock = new Object();
    private long lastFetchAt;

    public ChartPlaybackService(SongDefinitionRepository songDefRepo,
                                TextagePageSourceRepository sourceRepo,
                                @Value("${app.textage-sync.base-url:https://textage.cc/score/}") String baseUrl,
                                @Value("${app.textage-sync.request-interval-ms:1200}") long requestIntervalMillis) {
        this.songDefRepo = songDefRepo;
        this.sourceRepo = sourceRepo;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.requestIntervalMillis = requestIntervalMillis;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * 【メソッドの役割】 譜面 1 つ分の再生データを返す。
     *
     * @param textage 譜面の識別子（例: "33/showtime.html?1AC00"）
     * @return textage・曲名・難易度・レベル・公式ノーツ数・BPM 表記と {@link ChartPlaybackBuilder.Playback} の中身
     * @throws PlaybackException 楽曲マスタに無い（404）・textage から取れない（502）・textage の譜面と一致しない（404）
     */
    public Map<String, Object> getPlayback(String textage) {
        String key = textage == null ? "" : textage.trim();
        Map<String, Object> cached = memory.get(key);
        if (cached != null) return cached;

        String page = TextageChartSync.pageOf(key);
        TextagePageRunner.Difficulty urlDifficulty = TextagePageRunner.Difficulty.ofTextage(key);
        if (page == null || urlDifficulty == null) {
            throw new PlaybackException(404, "譜面データが見つかりません");
        }
        // LEGGENDARIA だけを載せた別ページ（21/_twentyl.html?1AC00）は URL の文字が A なので、
        // URL の難易度で見つからなければ、その textage を持つ唯一の譜面の難易度で実行する
        List<SongDefinition> rows = songDefRepo.findByTextageAndRevision(key, "active").stream()
                .filter(sd -> TextagePageRunner.Difficulty.ofCode(sd.getDifficulty()) != null)
                .toList();
        SongDefinition song = rows.stream()
                .filter(sd -> urlDifficulty.code.equals(sd.getDifficulty()))
                .findFirst()
                .orElseGet(() -> rows.size() == 1 ? rows.get(0) : null);
        if (song == null) throw new PlaybackException(404, "譜面データが見つかりません");
        TextagePageRunner.Difficulty difficulty = TextagePageRunner.Difficulty.ofCode(song.getDifficulty());

        String script = loadScript(page);
        TextagePageRunner.PlaybackChart run;
        try {
            run = runner.runWithObjects(script, difficulty);
        } catch (IllegalStateException e) {
            log.warn("[譜面再生] {} の JS を実行できません: {}", key, e.getMessage());
            throw new PlaybackException(502, "譜面データを読み込めませんでした");
        }
        TextagePageRunner.PageChart chart = run.chart();
        Integer official = song.getNotes();
        if (chart.sp().isEmpty() || (official != null && official > 0 && !TextageChartSync.notesMatch(chart.notes(), official))) {
            throw new PlaybackException(404, "この譜面の再生データはまだ用意できていません");
        }

        ChartPlaybackBuilder.Playback p = ChartPlaybackBuilder.build(run);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("textage", key);
        out.put("title", song.getTitle());
        out.put("difficulty", song.getDifficulty());
        out.put("level", song.getLevel());
        out.put("notes", official != null && official > 0 ? official : chart.notes());
        out.put("bpm", song.getBpm() != null && !song.getBpm().isBlank() ? song.getBpm() : chart.bpm());
        out.put("firstMeasure", p.firstMeasure());
        out.put("endTick", p.endTick());
        out.put("measures", p.measures());
        out.put("bpmChanges", p.bpmChanges());
        out.put("objects", p.notes());
        out.put("charges", p.charges());
        memory.put(key, out);
        return out;
    }

    /** 保存済みのスクリプト（新しければそのまま、古ければ取り直し。取り直せなければ古いもの）。 */
    private String loadScript(String page) {
        Optional<TextagePageSource> saved = sourceRepo.findById(page);
        if (saved.isPresent() && saved.get().getFetchedAt().plus(REFRESH_AFTER).isAfter(LocalDateTime.now())) {
            return saved.get().getScript();
        }
        try {
            String script = TextagePageRunner.extractScript(fetch(page));
            if (script.isBlank()) throw new IOException("script が空");
            TextagePageSource src = saved.orElseGet(TextagePageSource::new);
            src.setPage(page);
            src.setScript(script);
            src.setFetchedAt(LocalDateTime.now());
            sourceRepo.save(src);
            return script;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("[譜面再生] textage のページを取得できません: {}: {}", page, e.getMessage());
            if (saved.isPresent()) return saved.get().getScript();
            throw new PlaybackException(502, "textage から譜面を取得できませんでした。時間をおいて再度お試しください");
        }
    }

    /** textage のページを Shift_JIS として取得する（1 本ずつ・間隔を空けて）。200 以外は例外。 */
    private String fetch(String page) throws IOException, InterruptedException {
        synchronized (fetchLock) {
            long wait = lastFetchAt + requestIntervalMillis - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + page))
                        .timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "beat-seeker-chart-sync/1.0 (+https://beat-seeker.onrender.com)")
                        .GET()
                        .build();
                HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
                return new String(res.body(), SHIFT_JIS);
            } finally {
                lastFetchAt = System.currentTimeMillis();
            }
        }
    }
}
