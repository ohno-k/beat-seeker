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
 *
 * メモリ方針（重要）:
 *  同梱 CSV は 750 ファイル・展開 102MB・スコアセル約 314 万件ある。
 *  素直にオブジェクト化すると 500MB 超を常駐させることになり、2GB のインスタンスでは
 *  再計算時の新旧二重保持で OOM する。そのため以下の 3 点で常駐量を抑えている:
 *   1. 地域プロファイル（{@link AreaProfile}）は常駐させず、{@link #getAreaProfile} が
 *      要求された 1 地域の CSV だけを読み直す（{@link #areaProfileLru} で直近分を再利用）
 *   2. 曲別ランカー列は {@link SongScoreColumn} のプリミティブ配列で保持し、
 *      参照時に {@link SongScoreEntry} へ復元する
 *   3. 曲名 / DJ 名はパース時に {@link #intern} で共有する
 *  いずれも API が返す内容は変えない（件数・並び順・フィールド値とも従来どおり）。
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

    /**
     * {@link #areaProfileLru} の保持件数。
     * 1 地域あたり数千行なので、8 件でも数 MB に収まる。
     */
    private static final int AREA_PROFILE_LRU_SIZE = 8;

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
    /**
     * key: title + "\0" + diffName → スコア降順のランカー列（列指向）。
     *
     * メモリ対策: 全 CSV 合計で約 314 万エントリになるため、{@link SongScoreEntry}
     * （オブジェクト 40B + List スロット 4B）を保持すると 140MB 超になる。
     * プリミティブ配列に寝かせて 1 エントリ 12B に圧縮し、参照時に
     * {@link #getSongTopRankers} が {@link SongScoreEntry} へ復元する。
     * 返す内容は従来と完全に同一（並び順・件数とも）。
     */
    private volatile Map<String, SongScoreColumn> cachedSongScores = Collections.emptyMap();
    /**
     * key: versionNum + "\0" + prefectureFileNum → 地域メタ（CSV パス込み）。
     *
     * メモリ対策: 以前はここに全 750 地域分の {@link AreaScoreRow}（約 314 万件）を
     * 常駐させていた。{@link #getAreaProfile} は 1 リクエストで 1 地域しか参照しないため、
     * メタ情報だけを持ち、実データは要求時に該当 CSV を読み直して組み立てる
     * （{@link #areaProfileLru} で直近分のみ再利用）。
     */
    private volatile Map<String, AreaMeta> areaMetaByKey = Collections.emptyMap();

    /**
     * {@link #getAreaProfile} 用の小容量 LRU キャッシュ。
     * 同一エリアの連続参照（スコアアップロード時の仮想ライバル判定など）で
     * CSV の再パースが繰り返されるのを防ぐ。上限は {@link #AREA_PROFILE_LRU_SIZE} 件。
     */
    private final Map<String, AreaProfile> areaProfileLru = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, AreaProfile> eldest) {
                    return size() > AREA_PROFILE_LRU_SIZE;
                }
            });

    /** {@link #getAreaProfile} の結果を再利用する maxScore / level マップ（再計算時に差し替え）。 */
    private volatile Map<String, Integer> cachedMaxScoreMap = Collections.emptyMap();
    /** {@link #getAreaProfile} の結果を再利用する level マップ（再計算時に差し替え）。 */
    private volatile Map<String, Integer> cachedLevelMap = Collections.emptyMap();
    /** {@link #getAreaProfile} 用の informalRank マップ（再計算時に差し替え）。 */
    private volatile Map<String, String> cachedInformalRankMap = Collections.emptyMap();


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
    /**
     * 直近の再計算で CSV の読み込みに成功した地域数。
     * {@link #areaMetaByKey} は manifest 全行を持つ（失敗地域の名前解決にも使うため）ので、
     * 管理画面が部分ロードを検知できるようこちらを別に持つ。
     */
    private volatile int loadedAreaCount = 0;

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

    /** manifest 1 行分のメタ情報（実データは持たず、必要時に resourcePath から読み直す）。 */
    private record AreaMeta(int versionNum, String versionName,
                            int prefectureFileNum, String prefectureName,
                            String resourcePath) {}

    /**
     * 1 曲 1 難易度分のランカー列を、スコア降順で列指向に保持する不変オブジェクト。
     *
     * versionNum（0〜32）と prefectureFileNum（0〜47）はいずれも短い整数なので
     * {@code short} に格納する（{@link SongScoreColumnBuilder#add} で範囲を検証済み）。
     */
    private record SongScoreColumn(int[] scores, short[] versionNums,
                                   short[] prefFileNums, String[] djNames) {

        /** 保持しているエントリ数。 */
        int size() {
            return scores.length;
        }

        /**
         * 列を {@link SongScoreEntry} のリストへ復元する。
         *
         * バージョン名・都道府県名は列側には持たず、{@code areaMetaByKey} から引き直す。
         * 引くキーは必ず (versionNum, prefectureFileNum) の組にすること。
         * prefectureFileNum は名前と 1:1 ではなく、海外エリアで番号を再利用している
         * （51 = タイ/米国、53 = シンガポール/海外、57 = オーストラリア/海外）ため、
         * 番号だけで引くと別バージョンの名前を拾ってしまう。
         */
        List<SongScoreEntry> toEntries(Map<String, AreaMeta> areaMetaByKey) {
            List<SongScoreEntry> out = new ArrayList<>(scores.length);
            for (int i = 0; i < scores.length; i++) {
                int v = versionNums[i];
                int p = prefFileNums[i];
                AreaMeta meta = areaMetaByKey.get(v + "\0" + p);
                out.add(new SongScoreEntry(v, meta == null ? "" : meta.versionName(),
                        p, meta == null ? "" : meta.prefectureName(),
                        djNames[i], scores[i]));
            }
            return out;
        }
    }

    /**
     * {@link SongScoreColumn} を段階的に組み立てるビルダー。
     *
     * 中間表現にも {@link SongScoreEntry} を作らないことで、再計算中のピークメモリを抑える。
     * {@link #build()} 時にスコア降順へ並べ替える。同スコアは追加順を保つため、
     * 従来の {@code List.sort}（安定ソート）と同じ並びになる。
     */
    private static final class SongScoreColumnBuilder {
        private int[] scores = new int[4];
        private short[] versionNums = new short[4];
        private short[] prefFileNums = new short[4];
        private String[] djNames = new String[4];
        private int size = 0;

        void add(int score, int versionNum, int prefFileNum, String djName) {
            if (versionNum < Short.MIN_VALUE || versionNum > Short.MAX_VALUE
                    || prefFileNum < Short.MIN_VALUE || prefFileNum > Short.MAX_VALUE) {
                // manifest が想定外の値になった場合は取り込まない（列の型を壊さないための防御）。
                log.warn("SongScoreColumnBuilder: out-of-range area id (version={}, pref={}); entry skipped",
                        versionNum, prefFileNum);
                return;
            }
            if (size == scores.length) {
                int cap = size * 2;
                scores = Arrays.copyOf(scores, cap);
                versionNums = Arrays.copyOf(versionNums, cap);
                prefFileNums = Arrays.copyOf(prefFileNums, cap);
                djNames = Arrays.copyOf(djNames, cap);
            }
            scores[size] = score;
            versionNums[size] = (short) versionNum;
            prefFileNums[size] = (short) prefFileNum;
            djNames[size] = djName;
            size++;
        }

        SongScoreColumn build() {
            // (スコアの降順キー, 追加順index) を 1 本の long に詰めてプリミティブソートする。
            // 上位 32bit = Integer.MAX_VALUE - score（昇順に並べると score の降順になる）、
            // 下位 32bit = 追加順 index（同スコア時に追加順を保つ）。
            long[] order = new long[size];
            for (int i = 0; i < size; i++) {
                order[i] = ((long) (Integer.MAX_VALUE - scores[i]) << 32) | i;
            }
            Arrays.sort(order);

            int[] outScores = new int[size];
            short[] outVersions = new short[size];
            short[] outPrefs = new short[size];
            String[] outDjNames = new String[size];
            for (int i = 0; i < size; i++) {
                int src = (int) order[i];
                outScores[i] = scores[src];
                outVersions[i] = versionNums[src];
                outPrefs[i] = prefFileNums[src];
                outDjNames[i] = djNames[src];
            }
            return new SongScoreColumn(outScores, outVersions, outPrefs, outDjNames);
        }
    }

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
        status.put("cachedAreas", loadedAreaCount);
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
        SongScoreColumn col = cachedSongScores.get(title + "\0" + diffName);
        if (col == null) return Collections.emptyList();
        return col.toEntries(areaMetaByKey);
    }

    /**
     * 指定バージョン×都道府県の仮想プロファイルを返す。未知の場合は null。
     *
     * メモリ対策のため全地域を常駐させず、要求された 1 地域の CSV だけをその場で読み直す。
     * 直近 {@link #AREA_PROFILE_LRU_SIZE} 件は {@link #areaProfileLru} で再利用する。
     * 返す内容は従来（全件常駐時）と同一。
     */
    public AreaProfile getAreaProfile(int versionNum, int prefectureFileNum) {
        String key = versionNum + "\0" + prefectureFileNum;
        AreaProfile hit = areaProfileLru.get(key);
        if (hit != null) return hit;

        AreaMeta meta = areaMetaByKey.get(key);
        if (meta == null) return null;

        List<AreaScoreRow> areaRows = new ArrayList<>();
        try {
            computePtsForCsv(meta.resourcePath(), cachedMaxScoreMap, cachedLevelMap, cachedInformalRankMap,
                    meta.versionNum(), meta.versionName(), meta.prefectureFileNum(), meta.prefectureName(),
                    null, areaRows, null);
        } catch (Exception e) {
            log.error("TopRankersBeatPtService: failed to load area profile {} ({}: {})",
                    meta.resourcePath(), e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }

        AreaProfile profile = new AreaProfile(meta.versionNum(), meta.versionName(),
                meta.prefectureFileNum(), meta.prefectureName(),
                Collections.unmodifiableList(areaRows));
        areaProfileLru.put(key, profile);
        return profile;
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
        Map<String, SongScoreColumnBuilder> songScoresBuilder = new HashMap<>();
        // manifest 全行分を先に登録する。曲別ランカーのバージョン名/都道府県名はここから引くため、
        // CSV 読み込みに失敗した地域の行が songScoresBuilder に部分的に入っていても名前が解決できる。
        Map<String, AreaMeta> areaMetaBuilder = new HashMap<>(manifest.size() * 2);
        for (Map<String, Object> entry : manifest) {
            Number v = (Number) entry.get("versionNum");
            Number p = (Number) entry.get("prefectureFileNum");
            if (v == null || p == null) continue;
            areaMetaBuilder.put(v.intValue() + "\0" + p.intValue(),
                    new AreaMeta(v.intValue(), (String) entry.get("versionName"),
                            p.intValue(), (String) entry.get("prefectureName"),
                            (String) entry.get("resourcePath")));
        }
        // 全 CSV を通した文字列プール。title / djName は 750 ファイルにまたがって
        // 大量に重複するため、共有しないと同じ内容の String が数百万個できてしまう。
        Map<String, String> stringPool = new HashMap<>();
        int csvFailureCount = 0;
        String firstCsvFailurePath = null;
        Exception firstCsvFailureCause = null;
        for (Map<String, Object> entry : manifest) {
            Number versionNum = (Number) entry.get("versionNum");
            String versionName = (String) entry.get("versionName");
            Number prefFileNum = (Number) entry.get("prefectureFileNum");
            String prefectureName = (String) entry.get("prefectureName");
            String resourcePath = (String) entry.get("resourcePath");

            double[] pts;
            try {
                // areaRows は渡さない（地域プロファイルは getAreaProfile で都度読み直す）。
                pts = computePtsForCsv(resourcePath, maxScoreMap, levelMap, informalRankMap,
                        versionNum.intValue(), versionName, prefFileNum.intValue(), prefectureName,
                        songScoresBuilder, null, stringPool);
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

        // 手順5: 曲・難易度単位のスコア列をスコア降順に確定（ビルダーは順次捨てて GC 対象にする）
        Map<String, SongScoreColumn> finalized = new HashMap<>(songScoresBuilder.size() * 2);
        long totalEntries = 0;
        for (Iterator<Map.Entry<String, SongScoreColumnBuilder>> it = songScoresBuilder.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<String, SongScoreColumnBuilder> e = it.next();
            SongScoreColumn col = e.getValue().build();
            totalEntries += col.size();
            finalized.put(e.getKey(), col);
            it.remove(); // 変換済みのビルダーは即座に解放（新旧の二重保持を避ける）
        }

        cached = Collections.unmodifiableList(beatResults);
        cachedRate = Collections.unmodifiableList(rateResults);
        cachedSongScores = Collections.unmodifiableMap(finalized);
        areaMetaByKey = Collections.unmodifiableMap(areaMetaBuilder);
        cachedMaxScoreMap = Collections.unmodifiableMap(maxScoreMap);
        cachedLevelMap = Collections.unmodifiableMap(levelMap);
        cachedInformalRankMap = Collections.unmodifiableMap(informalRankMap);
        loadedAreaCount = beatResults.size();
        // 曲定義や難易度表が変わると scoreRate / level が変わるため、遅延生成済みの
        // 地域プロファイルは作り直す必要がある。
        areaProfileLru.clear();
        long t1 = System.currentTimeMillis();
        lastRecomputeDurationMs = t1 - t0;
        lastRecomputeFinishedAt = t1;
        log.info("TopRankersBeatPtService: computed {} rows, {} song-diff keys ({} entries), {} areas in {} ms (csvFailures={}, firstFailurePath={})",
                beatResults.size(), finalized.size(), totalEntries, loadedAreaCount,
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
                                      Map<String, SongScoreColumnBuilder> songScoresBuilder,
                                      List<AreaScoreRow> areaRows,
                                      Map<String, String> stringPool) throws Exception {
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
                String title = intern(cols[1], stringPool);
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
                    djName = djName == null ? "" : intern(djName, stringPool);
                    if (songScoresBuilder != null) {
                        songScoresBuilder
                                .computeIfAbsent(title + "\0" + diffName, k -> new SongScoreColumnBuilder())
                                .add(score, versionNum, prefFileNum, djName);
                    }

                    // 地域プロファイル用の行を追加（TOP ランカー仮想プロファイル表示）
                    if (areaRows != null) {
                        Integer level = levelMap.get(keyCode);
                        String djLevel = calcDjLevel(scoreRate);
                        areaRows.add(new AreaScoreRow(title, diffName, level == null ? 0 : level,
                                score, djName, scoreRate, djLevel, "NO PLAY"));
                    }

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
     * 【メソッドの役割】 同内容の String インスタンスを 1 つに寄せる（プール未指定なら素通し）。
     *
     * 曲名は 750 ファイルすべてに、DJ 名も多数のバージョン/都道府県に重複して現れる。
     * CSV パースは行ごとに新しい String を作るため、共有しないと同一内容の
     * インスタンスが数百万個できてヒープを圧迫する。
     * {@code String.intern()} ではなくローカル Map を使うのは、
     * 再計算のたびに JVM の文字列テーブルを汚さないため。
     */
    private static String intern(String s, Map<String, String> pool) {
        if (s == null || pool == null) return s;
        String hit = pool.putIfAbsent(s, s);
        return hit != null ? hit : s;
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
