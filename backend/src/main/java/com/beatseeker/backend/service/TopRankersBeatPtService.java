package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * 【Service の役割】 classpath 同梱の「トップランカー CSV スナップショット」を読み込み、
 * バージョン×都道府県の単位で BEAT-PT / RATE-PT ランキングを集計しキャッシュするサービス。
 *
 * 責務:
 *  - 起動直後に別スレッドでマニフェストと CSV を読み、集計結果をメモリ上にキャッシュ
 *  - 読み込み失敗時は指数的バックオフで最大 5 回リトライ
 *  - 曲単位のトップランカー一覧（getSongTopRankers）
 *  - 地域（バージョン+都道府県）プロファイル（getAreaProfile）の提供
 *
 * 依存:
 *  - {@link SongDefinitionRepository}: active 曲定義から maxScore / level を取得
 *  - {@link DifficultyRankRepository}: active 難易度テーブルから informalRank を取得
 *  - classpath: top-rankers-data/manifest.json と配下の gzip 圧縮 CSV
 *
 * 主要ロジックの概観:
 *  - BEAT-PT / RATE-PT の公式は {@link ScoreRecalculationService} と同一（重複定義）
 *  - 個人ユーザーのスコアではなく「地域のトップスコア群」を入力として集計する点が異なる
 *  - キャッシュは volatile の不変コレクションで、書き換え時は参照全体を差し替える
 */
@Service
public class TopRankersBeatPtService {

    private static final Logger log = LoggerFactory.getLogger(TopRankersBeatPtService.class);

    /** classpath 上のマニフェスト JSON パス */
    private static final String MANIFEST_PATH = "top-rankers-data/manifest.json";

    /** 初期化リトライの最大試行回数 */
    private static final int MAX_INIT_ATTEMPTS = 5;
    /** 初期化リトライの基本待機時間（attempt 番号に比例した待機） */
    private static final long INIT_RETRY_BASE_DELAY_MS = 15_000L;

    /*
     * CSV のカラム順:
     *   バージョン, タイトル,
     *   BEGINNER EXスコア, BEGINNER DJName, BEGINNER 都道府県,
     *   NORMAL   EXスコア, NORMAL   DJName, NORMAL   都道府県,
     *   HYPER    EXスコア, HYPER    DJName, HYPER    都道府県,
     *   ANOTHER  EXスコア, ANOTHER  DJName, ANOTHER  都道府県,
     *   LEGGENDARIA EXスコア, LEGGENDARIA DJName, LEGGENDARIA 都道府県
     */
    /** CSV カラム解釈時の難易度名の順序 */
    private static final String[] DIFF_NAMES = {"BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA"};
    /** {@link #DIFF_NAMES} と同じ順で並ぶ難易度コード */
    private static final String[] DIFF_CODES = {"1", "2", "3", "4", "10"};

    /** active 曲定義から maxScore / level を作るためのリポジトリ */
    private final SongDefinitionRepository songDefinitionRepository;
    /** active 難易度テーブルから informalRank を作るためのリポジトリ */
    private final DifficultyRankRepository difficultyRankRepository;
    /** マニフェスト JSON のパース用 */
    private final ObjectMapper objectMapper;
    /** BEAT-PT / RATE-PT の単曲計算を担う共通ユーティリティ（ScoreRecalculationService と共有）。 */
    private final BeatPtCalculator beatPtCalculator;

    /** BEAT-PT のバージョン×都道府県ランキングキャッシュ（BEAT-PT 降順）*/
    private volatile List<Map<String, Object>> cached = Collections.emptyList();
    /** RATE-PT のバージョン×都道府県ランキングキャッシュ（RATE-PT 降順）*/
    private volatile List<Map<String, Object>> cachedRate = Collections.emptyList();
    /** key: title + "\0" + diffName → スコア降順の SongScoreEntry リスト */
    private volatile Map<String, List<SongScoreEntry>> cachedSongScores = Collections.emptyMap();
    /** key: versionNum + "\0" + prefectureFileNum → その地域の全スコア行 */
    private volatile Map<String, AreaProfile> cachedAreaProfiles = Collections.emptyMap();

    /** 初期化の進行状態 */
    public enum InitState { PENDING, RUNNING, SUCCESS, FAILED }

    /** 現在の初期化状態 */
    private volatile InitState initState = InitState.PENDING;
    /** これまでの試行回数 */
    private volatile int initAttempts = 0;
    /** 直近エラーメッセージ（成功時は null） */
    private volatile String lastError = null;
    /** 最終再計算に要した時間（ms） */
    private volatile long lastRecomputeDurationMs = -1L;
    /** 最終再計算が完了したエポックミリ秒 */
    private volatile long lastRecomputeFinishedAt = -1L;

    /** 曲・難易度単位のランカーエントリ（どのバージョン・都道府県の誰が何点か） */
    public record SongScoreEntry(int versionNum, String versionName,
                                  int prefectureFileNum, String prefectureName,
                                  String djName, int score) {}

    /** 地域プロファイル内部で保持する 1 曲 1 難易度の行 */
    public record AreaScoreRow(String title, String difficultyName, int difficultyLevel,
                                int score, String djName, double scoreRate, String djLevel, String clearType) {}

    /** ある（バージョン、都道府県）の全スコアをまとめた仮想プロファイル */
    public record AreaProfile(int versionNum, String versionName,
                               int prefectureFileNum, String prefectureName,
                               List<AreaScoreRow> scores) {}

    /**
     * 【コンストラクタ】 Spring が Repository 群と ObjectMapper、BEAT-PT 計算ユーティリティを注入する。
     */
    public TopRankersBeatPtService(SongDefinitionRepository songDefinitionRepository,
                                   DifficultyRankRepository difficultyRankRepository,
                                   ObjectMapper objectMapper,
                                   BeatPtCalculator beatPtCalculator) {
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.objectMapper = objectMapper;
        this.beatPtCalculator = beatPtCalculator;
    }

    /**
     * 【メソッドの役割】 Bean 初期化時に、デーモンスレッドで初期化処理を開始する。
     *
     * CSV 読み込みは時間がかかるため同期初期化すると起動が遅くなる。
     * 別スレッドに退避させ、起動は即座に完了させる。
     */
    @PostConstruct
    public void init() {
        Thread t = new Thread(this::initWithRetry, "top-rankers-init");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 再計算をリトライ付きで実行する内部処理。
     * 失敗時は {@link #INIT_RETRY_BASE_DELAY_MS} × attempt の指数的バックオフで待機し、
     * {@link #MAX_INIT_ATTEMPTS} 回まで試行する。
     */
    private void initWithRetry() {
        for (int attempt = 1; attempt <= MAX_INIT_ATTEMPTS; attempt++) {
            initAttempts = attempt;
            initState = InitState.RUNNING;
            log.info("TopRankersBeatPtService init attempt {}/{} starting", attempt, MAX_INIT_ATTEMPTS);
            try {
                recompute();
                if (!cached.isEmpty()) {
                    initState = InitState.SUCCESS;
                    lastError = null;
                    log.info("TopRankersBeatPtService init succeeded on attempt {} (rows={})",
                            attempt, cached.size());
                    return;
                }
                lastError = "recompute produced empty result (manifest or CSVs unreadable)";
                log.warn("TopRankersBeatPtService attempt {} completed but cache is empty; {}",
                        attempt, lastError);
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("TopRankersBeatPtService attempt {} failed", attempt, e);
            }

            if (attempt < MAX_INIT_ATTEMPTS) {
                long delay = INIT_RETRY_BASE_DELAY_MS * attempt;
                log.info("TopRankersBeatPtService retrying in {} ms", delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    initState = InitState.FAILED;
                    log.warn("TopRankersBeatPtService init interrupted");
                    return;
                }
            }
        }
        initState = InitState.FAILED;
        log.error("TopRankersBeatPtService init giving up after {} attempts; lastError={}",
                MAX_INIT_ATTEMPTS, lastError);
    }

    /**
     * 初期化状態（試行回数・最終エラー・キャッシュサイズ等）を返す。管理者画面で可視化する用途。
     */
    public Map<String, Object> getInitStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", initState.name());
        status.put("attempts", initAttempts);
        status.put("lastError", lastError);
        status.put("cachedRows", cached.size());
        status.put("cachedSongKeys", cachedSongScores.size());
        status.put("cachedAreas", cachedAreaProfiles.size());
        status.put("lastRecomputeDurationMs", lastRecomputeDurationMs);
        status.put("lastRecomputeFinishedAt", lastRecomputeFinishedAt);
        return status;
    }

    /** キャッシュ済みの BEAT-PT ランキング（バージョン×都道府県単位）を返す。 */
    public List<Map<String, Object>> getRanking() {
        return cached;
    }

    /** キャッシュ済みの RATE-PT ランキング（バージョン×都道府県単位）を返す。 */
    public List<Map<String, Object>> getRateRanking() {
        return cachedRate;
    }

    /**
     * 曲・難易度指定でランカー（バージョン/都道府県/DJ 名/スコア）を返す。
     * 事前にスコア降順でソート済み。
     */
    public List<SongScoreEntry> getSongTopRankers(String title, String diffName) {
        if (title == null || diffName == null) return Collections.emptyList();
        return cachedSongScores.getOrDefault(title + "\0" + diffName, Collections.emptyList());
    }

    /** 指定バージョン×都道府県の仮想プロファイルを返す。未知の場合は null。 */
    public AreaProfile getAreaProfile(int versionNum, int prefectureFileNum) {
        return cachedAreaProfiles.get(versionNum + "\0" + prefectureFileNum);
    }

    /**
     * 【メソッドの役割】 キャッシュを再構築する。曲定義や難易度テーブルを更新した際に呼ぶ。
     *
     * 処理の流れ:
     *  - 手順1: active 曲定義から (title_diffCode → maxScore / level) マップを作成
     *  - 手順2: active 難易度テーブルから (title_diffName → rankValue) マップを作成
     *  - 手順3: マニフェストを読み、対象 CSV それぞれについて BEAT-PT / RATE-PT を集計
     *  - 手順4: 地域単位のランキング配列を BEAT-PT / RATE-PT それぞれ降順にソート
     *  - 手順5: 曲・難易度単位のスコアリストをスコア降順ソートし freeze
     *  - 手順6: すべての volatile フィールドを不変コレクションで差し替え
     *
     * synchronized で同時再計算をシリアライズする。
     */
    public synchronized void recompute() {
        long t0 = System.currentTimeMillis();

        // 手順1: active 曲定義から (title,diffCode) → maxScore / level マップを構築
        List<SongDefinition> activeSongs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> maxScoreMap = new HashMap<>();
        Map<String, Integer> levelMap = new HashMap<>();
        for (SongDefinition s : activeSongs) {
            if (s.getNotes() == null || s.getNotes() <= 0) continue;
            String key = s.getTitle() + "\0" + s.getDifficulty();
            maxScoreMap.put(key, s.getNotes() * 2);
            if (s.getLevel() != null) levelMap.put(key, s.getLevel());
        }
        log.info("TopRankersBeatPtService: loaded {} active song definitions (maxScoreMap={}, levelMap={})",
                activeSongs.size(), maxScoreMap.size(), levelMap.size());

        // 手順2: active 難易度テーブルから (title,diffName) → informalRank マップを構築
        Map<String, String> informalRankMap = new HashMap<>();
        List<DifficultyRank> ranks = difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active");
        for (DifficultyRank r : ranks) {
            String rankText = r.getRankValue();
            for (DifficultyRankSong song : r.getSongs()) {
                String songTitle = song.getSongTitle();
                if (songTitle == null) continue;
                if (songTitle.endsWith("[L]")) {
                    String baseTitle = songTitle.substring(0, songTitle.length() - 3).trim();
                    informalRankMap.put(baseTitle + "\0LEGGENDARIA", rankText);
                } else {
                    informalRankMap.put(songTitle + "\0ANOTHER", rankText);
                }
            }
        }
        log.info("TopRankersBeatPtService: loaded {} active difficulty ranks (informalRankMap={})",
                ranks.size(), informalRankMap.size());

        List<Map<String, Object>> manifest;
        try (InputStream in = new ClassPathResource(MANIFEST_PATH).getInputStream()) {
            manifest = objectMapper.readValue(in, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.error("TopRankersBeatPtService: failed to load manifest {}", MANIFEST_PATH, e);
            throw new RuntimeException("manifest load failed: " + MANIFEST_PATH, e);
        }
        log.info("TopRankersBeatPtService: manifest loaded ({} entries)", manifest.size());

        List<Map<String, Object>> beatResults = new ArrayList<>(manifest.size());
        List<Map<String, Object>> rateResults = new ArrayList<>(manifest.size());
        Map<String, List<SongScoreEntry>> songScoresBuilder = new HashMap<>();
        Map<String, AreaProfile> areaProfilesBuilder = new HashMap<>();
        int csvFailureCount = 0;
        String firstCsvFailurePath = null;
        Exception firstCsvFailureCause = null;
        for (Map<String, Object> entry : manifest) {
            Number versionNum = (Number) entry.get("versionNum");
            String versionName = (String) entry.get("versionName");
            Number prefFileNum = (Number) entry.get("prefectureFileNum");
            String prefectureName = (String) entry.get("prefectureName");
            String resourcePath = (String) entry.get("resourcePath");

            List<AreaScoreRow> areaRows = new ArrayList<>();
            double[] pts;
            try {
                pts = computePtsForCsv(resourcePath, maxScoreMap, levelMap, informalRankMap,
                        versionNum.intValue(), versionName, prefFileNum.intValue(), prefectureName,
                        songScoresBuilder, areaRows);
            } catch (Exception e) {
                csvFailureCount++;
                if (firstCsvFailurePath == null) {
                    firstCsvFailurePath = resourcePath;
                    firstCsvFailureCause = e;
                    log.error("TopRankersBeatPtService: failed to read CSV {} ({}: {})",
                            resourcePath, e.getClass().getSimpleName(), e.getMessage(), e);
                }
                continue;
            }
            areaProfilesBuilder.put(versionNum.intValue() + "\0" + prefFileNum.intValue(),
                    new AreaProfile(versionNum.intValue(), versionName,
                            prefFileNum.intValue(), prefectureName,
                            Collections.unmodifiableList(areaRows)));

            Map<String, Object> beatRow = new LinkedHashMap<>();
            beatRow.put("versionNum", versionNum.intValue());
            beatRow.put("versionName", versionName);
            beatRow.put("prefectureFileNum", prefFileNum.intValue());
            beatRow.put("prefectureName", prefectureName);
            beatRow.put("beatPt", Math.round(pts[0] * 10.0) / 10.0);
            beatResults.add(beatRow);

            Map<String, Object> rateRow = new LinkedHashMap<>();
            rateRow.put("versionNum", versionNum.intValue());
            rateRow.put("versionName", versionName);
            rateRow.put("prefectureFileNum", prefFileNum.intValue());
            rateRow.put("prefectureName", prefectureName);
            rateRow.put("ratePt", Math.round(pts[1] * 10.0) / 10.0);
            rateResults.add(rateRow);
        }

        beatResults.sort((a, b) -> Double.compare(
                ((Number) b.get("beatPt")).doubleValue(),
                ((Number) a.get("beatPt")).doubleValue()));
        rateResults.sort((a, b) -> Double.compare(
                ((Number) b.get("ratePt")).doubleValue(),
                ((Number) a.get("ratePt")).doubleValue()));

        // 手順5: 曲・難易度単位のスコアリストをスコア降順でソートし、不変化して確定
        Map<String, List<SongScoreEntry>> finalized = new HashMap<>(songScoresBuilder.size() * 2);
        for (Map.Entry<String, List<SongScoreEntry>> e : songScoresBuilder.entrySet()) {
            List<SongScoreEntry> list = e.getValue();
            list.sort((a, b) -> Integer.compare(b.score(), a.score()));
            finalized.put(e.getKey(), Collections.unmodifiableList(list));
        }

        cached = Collections.unmodifiableList(beatResults);
        cachedRate = Collections.unmodifiableList(rateResults);
        cachedSongScores = Collections.unmodifiableMap(finalized);
        cachedAreaProfiles = Collections.unmodifiableMap(areaProfilesBuilder);
        long t1 = System.currentTimeMillis();
        lastRecomputeDurationMs = t1 - t0;
        lastRecomputeFinishedAt = t1;
        log.info("TopRankersBeatPtService: computed {} rows, {} song-diff keys, {} areas in {} ms (csvFailures={}, firstFailurePath={})",
                beatResults.size(), finalized.size(), areaProfilesBuilder.size(),
                lastRecomputeDurationMs, csvFailureCount, firstCsvFailurePath);
        if (csvFailureCount > 0) {
            log.warn("TopRankersBeatPtService: {} CSV(s) failed to read (first: {}); cache may be partial",
                    csvFailureCount, firstCsvFailurePath, firstCsvFailureCause);
        }
        if (beatResults.isEmpty() && !manifest.isEmpty()) {
            throw new RuntimeException("All " + manifest.size() + " manifest entries failed; first failure: "
                    + firstCsvFailurePath + " -> " + (firstCsvFailureCause == null ? "(none)" : firstCsvFailureCause.getMessage()));
        }
    }

    /**
     * 【メソッドの役割】 1 つの CSV ファイルを処理し、その地域の BEAT-PT / RATE-PT を計算する。
     *
     * 処理の流れ:
     *  - 手順1: gzip 圧縮 CSV を UTF-8 で開き、ヘッダー行を読み捨てる
     *  - 手順2: 各行について title と 5 難易度分のスコア/DJ 名を取り出す
     *  - 手順3: スコア > 0 で maxScore が確定している難易度のみ処理
     *  - 手順4: 曲単位インデックス songScoresBuilder と地域プロファイル areaRows にエントリ追加
     *  - 手順5: HYPER レベル 11 以上は BEAT-PT 対象外、ANOTHER/LEGGENDARIA のみ RATE-PT 対象
     *  - 手順6: 集計した BEAT-PT / RATE-PT を上位 100 件合計で返す（RATE-PT は 100% 超過を加算）
     *
     * @param resourcePath       classpath 上の gzip CSV パス
     * @param maxScoreMap        title_diffCode → maxScore
     * @param levelMap           title_diffCode → level
     * @param informalRankMap    title_diffName → informalRank
     * @param versionNum         対象バージョン番号
     * @param versionName        表示用バージョン名
     * @param prefFileNum        都道府県ファイル番号
     * @param prefectureName     都道府県名
     * @param songScoresBuilder  曲単位のインデックスへ追加書き込み
     * @param areaRows           地域プロファイル用の行リストへ追加書き込み
     * @return {@code [beatPt, ratePt]} の配列
     */
    private double[] computePtsForCsv(String resourcePath,
                                      Map<String, Integer> maxScoreMap,
                                      Map<String, Integer> levelMap,
                                      Map<String, String> informalRankMap,
                                      int versionNum, String versionName,
                                      int prefFileNum, String prefectureName,
                                      Map<String, List<SongScoreEntry>> songScoresBuilder,
                                      List<AreaScoreRow> areaRows) throws Exception {
        List<Double> beatPts = new ArrayList<>();
        List<Double> ratePts = new ArrayList<>();
        int perfectRateCount = 0;
        try (InputStream in = new ClassPathResource(resourcePath).getInputStream();
             GZIPInputStream gz = new GZIPInputStream(in);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // ヘッダー行
            if (line == null) return new double[]{0.0, 0.0};
            while ((line = reader.readLine()) != null) {
                String[] cols = splitCsv(line);
                // 期待: 少なくとも 2 + 5*3 = 17 カラム（バージョン+タイトル+5難易度×(EX/DJName/都道府県)）
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

                    String diffName = DIFF_NAMES[d];
                    String diffCode = DIFF_CODES[d];
                    String keyCode = title + "\0" + diffCode;
                    Integer maxScore = maxScoreMap.get(keyCode);
                    if (maxScore == null || maxScore == 0) continue;
                    double scoreRate = score * 100.0 / maxScore;

                    // 曲単位トップランカーインデックスへ追加（曲詳細ランキングタブで使用）
                    String djName = cols[3 + d * 3];
                    songScoresBuilder
                            .computeIfAbsent(title + "\0" + diffName, k -> new ArrayList<>())
                            .add(new SongScoreEntry(versionNum, versionName, prefFileNum, prefectureName,
                                    djName == null ? "" : djName, score));

                    // 地域プロファイル用の行を追加（TOP ランカー仮想プロファイル表示）
                    Integer level = levelMap.get(keyCode);
                    String djLevel = calcDjLevel(scoreRate);
                    areaRows.add(new AreaScoreRow(title, diffName, level == null ? 0 : level,
                            score, djName == null ? "" : djName, scoreRate, djLevel, "NO PLAY"));

                    // BEAT-PT: HYPER でレベル 11 以上の譜面は対象外（ScoreRecalculationService と同条件）
                    boolean beatEligible = !("HYPER".equals(diffName)
                            && levelMap.get(keyCode) != null && levelMap.get(keyCode) >= 11);
                    if (beatEligible) {
                        String informalRank = informalRankMap.get(title + "\0" + diffName);
                        double pt = beatPtCalculator.calculatePoints(scoreRate, informalRank);
                        if (pt > 0) beatPts.add(pt);
                    }

                    // RATE-PT: ANOTHER / LEGGENDARIA のみ対象
                    if ("ANOTHER".equals(diffName) || "LEGGENDARIA".equals(diffName)) {
                        double rPt = beatPtCalculator.calculateScoreRateTierPoints(scoreRate);
                        if (rPt > 0) ratePts.add(rPt);
                        if (scoreRate >= 100.0) perfectRateCount++;
                    }
                }
            }
        }
        beatPts.sort(Collections.reverseOrder());
        ratePts.sort(Collections.reverseOrder());
        double beatTotal = 0;
        for (int i = 0; i < Math.min(100, beatPts.size()); i++) beatTotal += beatPts.get(i);
        double rateTotal = 0;
        for (int i = 0; i < Math.min(100, ratePts.size()); i++) rateTotal += ratePts.get(i);
        if (perfectRateCount > 100) rateTotal += (perfectRateCount - 100);
        return new double[]{beatTotal, rateTotal};
    }

    /**
     * スコア率から DJ LEVEL (AAA/AA/…/F) を計算する。
     * 既存のクライアント側ロジックと同じ境界（100/9 × n）を使用している。
     */
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

    /**
     * 最小限の CSV スプリッタ。
     * ダブルクォートで囲まれたフィールドと、その内部のエスケープダブルクォート（""）に対応する。
     * 外部ライブラリを入れずに済ませるための実装。
     */
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
