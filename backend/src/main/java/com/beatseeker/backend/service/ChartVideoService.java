package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.ChartVideoOffset;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.SongVideo;
import com.beatseeker.backend.repository.ChartVideoOffsetRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.SongVideoRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 【Service の役割】 譜面再生で一緒に流す原曲の YouTube 動画を用意し、譜面とのずれを保存する。
 *
 * 動画（曲ごと、{@link SongVideo}）:
 *  - 譜面再生で「動画を表示」を押したとき、その曲の動画がまだ無ければ YouTube Data API v3 で検索する
 *    （「曲名 アーティスト beatmania IIDX」。埋め込み可・公開の動画だけ）
 *  - 候補は長さ（譜面の長さに近いもの。短すぎる・長すぎる動画は除く）と題名（曲名・IIDX を含む、
 *    remix・歌ってみた等を含まない）で並べ、上位 {@link #KEEP_CANDIDATES} 本を保存する。「別の動画」は次の候補へ
 *  - 検索は 1 回 101 単位（search 100 + videos 1）。無料枠 10,000 単位/日なので、1 日の回数に上限を設ける
 *
 * ずれ（譜面ごと、{@link ChartVideoOffset}）: 動画の位置 = 譜面の時刻 + offsetSec。利用者全員で共有し、ログインした人が保存できる。
 * その譜面のずれが無ければ、同じ曲・同じ動画の別譜面のずれを借りる（音源が同じなので大抵そのまま合う）。
 */
@Service
public class ChartVideoService {

    private static final Logger log = LoggerFactory.getLogger(ChartVideoService.class);

    static final int KEEP_CANDIDATES = 6;
    /** 見つからなかった曲を検索し直すまでの間隔。 */
    static final Duration RESEARCH_AFTER = Duration.ofDays(14);
    /** YouTube の割り当てが戻る時刻の区切り（太平洋時間の 0 時）。 */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern URL_ID = Pattern.compile(
            "(?:youtu\\.be/|[?&]v=|/embed/|/shorts/|/live/)([A-Za-z0-9_-]{11})");

    /** 失敗（HTTP ステータスと利用者向けの文言）。 */
    public static final class VideoException extends RuntimeException {
        private final int status;

        VideoException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }

    /** 動画の候補。 */
    public record Candidate(String id, String title, String channel, Integer durationSec) {}

    private final SongDefinitionRepository songDefRepo;
    private final SongVideoRepository videoRepo;
    private final ChartVideoOffsetRepository offsetRepo;
    private final ChartPlaybackService playbackService;
    private final String apiKey;
    private final int dailySearchLimit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final Object searchLock = new Object();
    private LocalDate quotaDay;
    private int searchesToday;
    /** YouTube から割り当て超過（403 quotaExceeded）が返った日。その日はもう検索しない。 */
    private LocalDate exhaustedDay;

    public ChartVideoService(SongDefinitionRepository songDefRepo,
                             SongVideoRepository videoRepo,
                             ChartVideoOffsetRepository offsetRepo,
                             ChartPlaybackService playbackService,
                             @Value("${app.youtube.api-key:}") String apiKey,
                             @Value("${app.youtube.daily-search-limit:90}") int dailySearchLimit) {
        this.songDefRepo = songDefRepo;
        this.videoRepo = videoRepo;
        this.offsetRepo = offsetRepo;
        this.playbackService = playbackService;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.dailySearchLimit = dailySearchLimit;
    }

    // ── 取得 ─────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 譜面の動画とずれを返す。動画がまだ無ければ（{@code search} のとき）検索して保存する。
     *
     * @return status（ok / notFound / disabled / none）・動画の情報・候補の位置と数・ずれ（offsetSec、無ければ null）・
     *         ずれの出どころ（offsetSource: chart = この譜面 / song = 同じ曲の別譜面）
     */
    public Map<String, Object> get(String textage, boolean search) {
        SongDefinition chart = chartOf(textage);
        String title = chart.getTitle();
        Optional<SongVideo> saved = videoRepo.findById(title);
        SongVideo video = saved.orElse(null);
        boolean stale = video != null && video.getVideoId() == null && video.getSearchedAt() != null
                && video.getSearchedAt().plus(RESEARCH_AFTER).isBefore(LocalDateTime.now());
        if ((video == null || stale) && search) {
            if (apiKey.isEmpty()) return Map.of("status", "disabled");
            video = searchAndSave(chart, video);
        }
        if (video == null) return Map.of("status", "none");
        return toMap(textage.trim(), video);
    }

    /** 【メソッドの役割】 次の候補の動画に替える（ログインした人）。ずれはその動画で合わせ直す。 */
    public Map<String, Object> next(String textage, Long userId) {
        SongDefinition chart = chartOf(textage);
        SongVideo video = videoRepo.findById(chart.getTitle())
                .orElseThrow(() -> new VideoException(404, "この曲の動画はまだ用意されていません"));
        List<Candidate> candidates = candidatesOf(video);
        int cur = video.getCandidateIndex() == null ? -1 : video.getCandidateIndex();
        if (candidates.isEmpty() || (candidates.size() == 1 && cur == 0)) {
            throw new VideoException(409, "ほかの候補がありません");
        }
        int nextIdx = (cur + 1) % candidates.size();
        apply(video, candidates.get(nextIdx), nextIdx, userId);
        videoRepo.save(video);
        return toMap(textage.trim(), video);
    }

    /** 【メソッドの役割】 動画を URL・ID で指定する（管理者）。長さ・題名は API キーがあれば取り直す。 */
    public Map<String, Object> setManual(String textage, String urlOrId, Long userId) {
        SongDefinition chart = chartOf(textage);
        String id = parseVideoId(urlOrId);
        if (id == null) throw new VideoException(400, "YouTube の URL か動画 ID を指定してください");
        Candidate c = new Candidate(id, null, null, null);
        if (!apiKey.isEmpty()) {
            try {
                List<Candidate> details = fetchDetails(List.of(id));
                if (!details.isEmpty()) c = details.get(0);
            } catch (Exception e) {
                log.warn("[原曲動画] 動画の情報を取得できません: {}: {}", id, e.getMessage());
            }
        }
        SongVideo video = videoRepo.findById(chart.getTitle()).orElseGet(() -> {
            SongVideo v = new SongVideo();
            v.setTitle(chart.getTitle());
            return v;
        });
        apply(video, c, -1, userId);
        videoRepo.save(video);
        return toMap(textage.trim(), video);
    }

    /** 【メソッドの役割】 譜面と動画のずれを保存する（ログインした人。利用者全員で共有）。 */
    public Map<String, Object> saveOffset(String textage, String videoId, double offsetSec, Long userId) {
        SongDefinition chart = chartOf(textage);
        SongVideo video = videoRepo.findById(chart.getTitle())
                .orElseThrow(() -> new VideoException(404, "この曲の動画はまだ用意されていません"));
        if (video.getVideoId() == null || !video.getVideoId().equals(videoId)) {
            throw new VideoException(409, "動画が替わっています。ページを開き直してください");
        }
        if (!Double.isFinite(offsetSec) || Math.abs(offsetSec) > 600) {
            throw new VideoException(400, "ずれの値が不正です");
        }
        String key = textage.trim();
        ChartVideoOffset o = offsetRepo.findById(key).orElseGet(ChartVideoOffset::new);
        o.setTextage(key);
        o.setTitle(chart.getTitle());
        o.setVideoId(videoId);
        o.setOffsetSec(Math.round(offsetSec * 1000) / 1000.0);
        o.setUpdatedBy(userId);
        o.setUpdatedAt(LocalDateTime.now());
        offsetRepo.save(o);
        return toMap(key, video);
    }

    // ── 中身 ─────────────────────────────────────────────────

    private SongDefinition chartOf(String textage) {
        String key = textage == null ? "" : textage.trim();
        return songDefRepo.findByTextageAndRevision(key, "active").stream().findFirst()
                .orElseThrow(() -> new VideoException(404, "譜面が見つかりません"));
    }

    private Map<String, Object> toMap(String textage, SongVideo video) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (video.getVideoId() == null) {
            m.put("status", "notFound");
            return m;
        }
        m.put("status", "ok");
        m.put("videoId", video.getVideoId());
        m.put("videoTitle", video.getVideoTitle());
        m.put("channelTitle", video.getChannelTitle());
        m.put("durationSec", video.getDurationSec());
        m.put("candidateIndex", video.getCandidateIndex());
        m.put("candidateCount", candidatesOf(video).size());
        Double offset = null;
        String source = null;
        ChartVideoOffset own = offsetRepo.findById(textage).orElse(null);
        if (own != null && video.getVideoId().equals(own.getVideoId())) {
            offset = own.getOffsetSec();
            source = "chart";
        } else {
            Optional<ChartVideoOffset> sibling = offsetRepo.findByTitleAndVideoId(video.getTitle(), video.getVideoId()).stream()
                    .max(Comparator.comparing(ChartVideoOffset::getUpdatedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
            if (sibling.isPresent()) {
                offset = sibling.get().getOffsetSec();
                source = "song";
            }
        }
        m.put("offsetSec", offset);
        m.put("offsetSource", source);
        return m;
    }

    private static void apply(SongVideo video, Candidate c, int index, Long userId) {
        video.setVideoId(c.id());
        video.setVideoTitle(truncate(c.title(), 300));
        video.setChannelTitle(truncate(c.channel(), 200));
        video.setDurationSec(c.durationSec());
        video.setCandidateIndex(index);
        video.setUpdatedAt(LocalDateTime.now());
        video.setUpdatedBy(userId);
    }

    private List<Candidate> candidatesOf(SongVideo video) {
        if (video.getCandidatesJson() == null || video.getCandidatesJson().isBlank()) return List.of();
        try {
            return mapper.readValue(video.getCandidatesJson(), new TypeReference<List<Candidate>>() {});
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 検索して上位の候補を保存する（1 曲 1 回。同時に同じ曲を検索しないよう直列）。 */
    private SongVideo searchAndSave(SongDefinition chart, SongVideo existing) {
        synchronized (searchLock) {
            // 待っている間にほかの要求が検索し終えていればそれを使う
            Optional<SongVideo> again = videoRepo.findById(chart.getTitle());
            if (again.isPresent() && (existing == null || again.get().getSearchedAt() != null
                    && !again.get().getSearchedAt().equals(existing.getSearchedAt()))) {
                return again.get();
            }
            LocalDate today = LocalDate.now(QUOTA_ZONE);
            if (!today.equals(quotaDay)) {
                quotaDay = today;
                searchesToday = 0;
            }
            if (today.equals(exhaustedDay) || searchesToday >= dailySearchLimit) {
                throw new VideoException(429, "今日の動画検索の上限に達しました。明日の夕方以降にもう一度お試しください");
            }
            searchesToday++;

            Double chartSec = chartSeconds(chart.getTextage());
            List<Candidate> ranked;
            try {
                ranked = rank(fetchDetails(searchIds(chart)), chart.getTitle(), chartSec);
            } catch (QuotaExceeded e) {
                exhaustedDay = today;
                throw new VideoException(429, "今日の動画検索の上限に達しました。明日の夕方以降にもう一度お試しください");
            } catch (Exception e) {
                log.warn("[原曲動画] 検索に失敗しました: {}: {}", chart.getTitle(), e.getMessage());
                throw new VideoException(502, "動画を検索できませんでした。時間をおいて再度お試しください");
            }
            List<Candidate> kept = ranked.subList(0, Math.min(KEEP_CANDIDATES, ranked.size()));

            SongVideo video = existing != null ? existing : new SongVideo();
            video.setTitle(chart.getTitle());
            try {
                video.setCandidatesJson(mapper.writeValueAsString(kept));
            } catch (IOException e) {
                video.setCandidatesJson("[]");
            }
            video.setSearchedAt(LocalDateTime.now());
            if (kept.isEmpty()) {
                video.setVideoId(null);
                video.setCandidateIndex(null);
            } else {
                apply(video, kept.get(0), 0, null);
            }
            log.info("[原曲動画] {} を検索: 候補 {} 本、採用 {}（今日 {} 回目）", chart.getTitle(), kept.size(),
                    kept.isEmpty() ? "なし" : kept.get(0).id(), searchesToday);
            return videoRepo.save(video);
        }
    }

    /** 譜面の長さ（秒）。取れなければ null（長さで絞らない）。 */
    @SuppressWarnings("unchecked")
    private Double chartSeconds(String textage) {
        try {
            Map<String, Object> p = playbackService.getPlayback(textage);
            return chartSeconds((List<double[]>) p.get("bpmChanges"), ((Number) p.get("endTick")).intValue());
        } catch (Exception e) {
            return null;
        }
    }

    /** BPM 変化 [tick, BPM] と終わりの tick から秒を求める（1 tick = 5 / (8 × BPM) 秒）。 */
    static Double chartSeconds(List<double[]> bpmChanges, int endTick) {
        if (bpmChanges == null || bpmChanges.isEmpty() || endTick <= 0) return null;
        double sec = 0;
        for (int i = 0; i < bpmChanges.size(); i++) {
            double from = bpmChanges.get(i)[0];
            double to = i + 1 < bpmChanges.size() ? Math.min(bpmChanges.get(i + 1)[0], endTick) : endTick;
            double bpm = bpmChanges.get(i)[1];
            if (to > from && bpm > 0) sec += (to - from) * 5 / (8 * bpm);
        }
        return sec > 0 ? sec : null;
    }

    // ── 候補の並べ方 ───────────────────────────────────────────

    /** 題名にあると原曲ではない可能性が高い語。 */
    private static final List<String> NOT_ORIGINAL = List.of(
            "remix", "cover", "歌ってみた", "弾いてみた", "叩いてみた", "piano", "ピアノ", "アレンジ", "arrange",
            "medley", "メドレー", "nightcore", "slowed", "耐久", "1時間", "1 hour", "mashup", "reaction");

    /**
     * 【メソッドの役割】 候補を良い順に並べる（長さが合わないものは除く）。
     *
     * 長さ: 譜面より 3 秒以上短い・譜面より 4 分以上長い動画は除く。プレー動画は前後に選曲・リザルトが入るので、
     * 20 秒までの超過は減点しない。題名: 曲名を含む +3、beatmania / IIDX を含む +1、原曲でない語 -4。
     * 同点は検索順（YouTube の関連度）。
     */
    static List<Candidate> rank(List<Candidate> details, String songTitle, Double chartSec) {
        String song = normalize(songTitle);
        List<Candidate> pool = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (int i = 0; i < details.size(); i++) {
            Candidate c = details.get(i);
            Integer dur = c.durationSec();
            if (dur == null || dur <= 0) continue;
            double score = -0.01 * i;
            if (chartSec != null) {
                if (dur < chartSec - 3 || dur > chartSec + 240) continue;
                score -= Math.max(0, dur - chartSec - 20) / 40.0;
            }
            String t = normalize(c.title());
            if (!song.isEmpty() && t.contains(song)) score += 3;
            if (t.contains("iidx") || t.contains("beatmania")) score += 1;
            for (String w : NOT_ORIGINAL) {
                if (t.contains(normalize(w))) {
                    score -= 4;
                    break;
                }
            }
            pool.add(c);
            scores.add(score);
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) order.add(i);
        order.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));
        List<Candidate> out = new ArrayList<>();
        for (int i : order) out.add(pool.get(i));
        return out;
    }

    /** 比べるための正規化（全角半角・大文字小文字・空白と記号の差を無くす）。 */
    static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return n.replaceAll("[\\s\\p{Punct}　・〜～「」『』【】（）]", "");
    }

    /** URL（watch?v= / youtu.be / embed / shorts）か 11 文字の ID から動画 ID を取り出す。 */
    static String parseVideoId(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (VIDEO_ID.matcher(t).matches()) return t;
        Matcher m = URL_ID.matcher(t);
        return m.find() ? m.group(1) : null;
    }

    /** ISO 8601 の長さ（PT3M2S）を秒に。読めなければ null。 */
    static Integer parseDuration(String iso) {
        if (iso == null) return null;
        try {
            return (int) Duration.parse(iso).getSeconds();
        } catch (Exception e) {
            return null;
        }
    }

    // ── YouTube Data API ──────────────────────────────────────

    private static final class QuotaExceeded extends Exception {}

    /** search.list（100 単位）: 埋め込み可の動画を最大 15 本。 */
    private List<String> searchIds(SongDefinition chart) throws Exception {
        String q = chart.getTitle() + " " + (chart.getArtist() == null ? "" : chart.getArtist()) + " beatmania IIDX";
        JsonNode root = call("search?part=snippet&type=video&maxResults=15&videoEmbeddable=true&regionCode=JP"
                + "&relevanceLanguage=ja&q=" + URLEncoder.encode(q.trim(), StandardCharsets.UTF_8));
        List<String> ids = new ArrayList<>();
        for (JsonNode item : root.path("items")) {
            String id = item.path("id").path("videoId").asText("");
            if (!id.isEmpty()) ids.add(id);
        }
        return ids;
    }

    /** videos.list（1 単位）: 長さ・題名・チャンネル。公開かつ埋め込み可のものだけ、渡した順のまま返す。 */
    private List<Candidate> fetchDetails(List<String> ids) throws Exception {
        if (ids.isEmpty()) return List.of();
        JsonNode root = call("videos?part=snippet,contentDetails,status&id=" + String.join(",", ids));
        Map<String, Candidate> byId = new LinkedHashMap<>();
        for (JsonNode item : root.path("items")) {
            JsonNode status = item.path("status");
            if (!status.path("embeddable").asBoolean(false)) continue;
            if (!"public".equals(status.path("privacyStatus").asText())) continue;
            byId.put(item.path("id").asText(), new Candidate(item.path("id").asText(),
                    item.path("snippet").path("title").asText(null),
                    item.path("snippet").path("channelTitle").asText(null),
                    parseDuration(item.path("contentDetails").path("duration").asText(null))));
        }
        List<Candidate> out = new ArrayList<>();
        for (String id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
        return out;
    }

    private JsonNode call(String pathAndQuery) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.googleapis.com/youtube/v3/" + pathAndQuery
                        + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() == 403 && res.body().contains("quotaExceeded")) throw new QuotaExceeded();
        if (res.statusCode() != 200) {
            throw new IOException("HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(300, res.body().length())));
        }
        return mapper.readTree(res.body());
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
