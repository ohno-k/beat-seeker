package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.VirtualArenaRanker;
import com.beatseeker.backend.entity.VirtualArenaRankerScore;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.VirtualArenaRankerRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 【Service の役割】 DB の {@code virtual_arena_rankers} / {@code virtual_arena_ranker_scores} を読み込み、
 * アリーナ仮想プレイヤーごとの BEAT-PT / RATE-PT を集計してメモリキャッシュするサービス。
 *
 * 位置づけ: 既存の {@link TopRankersBeatPtService}（県別TOP仮想ユーザ）と同型。
 * 相違点は「データソースが classpath の gzip CSV ではなく DB の専用テーブル」である点のみ。
 * BEAT-PT / RATE-PT の計算式は {@link BeatPtCalculator} を共有し、
 * {@code TopRankersBeatPtService.computePtsForCsv} と同一条件（top100合計・
 * HYPER Lv11+ 除外・ANOTHER/LEGGENDARIA のみ RATE・100%超過ボーナス）にそろえている。
 *
 * 隔離ポリシー: キャッシュ生成時に {@code users} に存在する IIDX ID は除外する。
 * これにより、仮想プレイヤーが後から本登録した場合、再計算後に自動でランキングから消える。
 *
 * キャッシュは volatile の不変コレクションで、書き換え時は参照全体を差し替える。
 */
@Service
public class VirtualArenaRankerService {

    private static final Logger log = LoggerFactory.getLogger(VirtualArenaRankerService.class);

    /** CSV/スコア解釈時の難易度名の順序（{@link TopRankersBeatPtService} と同じ）。 */
    private static final String[] DIFF_NAMES = {"BEGINNER", "NORMAL", "HYPER", "ANOTHER", "LEGGENDARIA"};
    /** {@link #DIFF_NAMES} と同じ順で並ぶ難易度コード。 */
    private static final String[] DIFF_CODES = {"1", "2", "3", "4", "10"};

    /** 難易度名 → 難易度コードの逆引き（"ANOTHER" → "4" 等）。 */
    private static final Map<String, String> DIFF_NAME_TO_CODE = new HashMap<>();
    static {
        for (int i = 0; i < DIFF_NAMES.length; i++) DIFF_NAME_TO_CODE.put(DIFF_NAMES[i], DIFF_CODES[i]);
    }

    private final VirtualArenaRankerRepository virtualArenaRankerRepository;
    private final SongDefinitionRepository songDefinitionRepository;
    private final DifficultyRankRepository difficultyRankRepository;
    private final UserRepository userRepository;
    private final BeatPtCalculator beatPtCalculator;

    /** BEAT-PT ランキングキャッシュ（BEAT-PT 降順）。要素は下記キーを持つ Map。 */
    private volatile List<Map<String, Object>> cachedBeat = Collections.emptyList();
    /** RATE-PT ランキングキャッシュ（RATE-PT 降順）。 */
    private volatile List<Map<String, Object>> cachedRate = Collections.emptyList();
    /** iidxId → プロフィール（djName / arenaClass / scores）。 */
    private volatile Map<String, Map<String, Object>> cachedProfiles = Collections.emptyMap();

    /** 最終再計算に要した時間（ms）。 */
    private volatile long lastRecomputeDurationMs = -1L;
    /** 最終再計算が完了したエポックミリ秒。 */
    private volatile long lastRecomputeFinishedAt = -1L;

    public VirtualArenaRankerService(VirtualArenaRankerRepository virtualArenaRankerRepository,
                                     SongDefinitionRepository songDefinitionRepository,
                                     DifficultyRankRepository difficultyRankRepository,
                                     UserRepository userRepository,
                                     BeatPtCalculator beatPtCalculator) {
        this.virtualArenaRankerRepository = virtualArenaRankerRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.userRepository = userRepository;
        this.beatPtCalculator = beatPtCalculator;
    }

    /**
     * 【メソッドの役割】 Bean 初期化時に、デーモンスレッドで初回集計を開始する。
     * DB 読み込みで起動を遅らせないよう別スレッドに退避する（{@link TopRankersBeatPtService} と同方針）。
     */
    @PostConstruct
    public void init() {
        Thread t = new Thread(() -> {
            try {
                recompute();
            } catch (Exception e) {
                log.error("VirtualArenaRankerService initial recompute failed", e);
            }
        }, "virtual-arena-rankers-init");
        t.setDaemon(true);
        t.start();
    }

    /** キャッシュ済みの BEAT-PT ランキングを返す。 */
    public List<Map<String, Object>> getBeatRanking() {
        return cachedBeat;
    }

    /** キャッシュ済みの RATE-PT ランキングを返す。 */
    public List<Map<String, Object>> getRateRanking() {
        return cachedRate;
    }

    /** 指定 IIDX ID のプロフィール（scores 含む）を返す。未知なら null。 */
    public Map<String, Object> getProfile(String iidxId) {
        if (iidxId == null) return null;
        return cachedProfiles.get(iidxId);
    }

    /** 初期化状態（管理者可視化用）。 */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("cachedBeatRows", cachedBeat.size());
        status.put("cachedRateRows", cachedRate.size());
        status.put("cachedProfiles", cachedProfiles.size());
        status.put("lastRecomputeDurationMs", lastRecomputeDurationMs);
        status.put("lastRecomputeFinishedAt", lastRecomputeFinishedAt);
        return status;
    }

    /**
     * 【メソッドの役割】 キャッシュを再構築する。スクレイプ取り込み後や曲定義/難易度表更新後に呼ぶ。
     *
     * 処理の流れ:
     *  - 手順1: active 曲定義から (title,diffCode) → maxScore / level マップを構築
     *  - 手順2: active 難易度表から (title,diffName) → informalRank マップを構築
     *  - 手順3: 登録済み IIDX ID 集合を取得（除外用）
     *  - 手順4: 全仮想プレイヤーについて BEAT-PT / RATE-PT とプロフィールを集計
     *  - 手順5: BEAT / RATE それぞれ降順ソートし、不変コレクションで差し替え
     *
     * synchronized で同時再計算をシリアライズする。
     */
    public synchronized void recompute() {
        long t0 = System.currentTimeMillis();

        // 手順1: active 曲定義 → (title,diffCode) → maxScore / level
        List<SongDefinition> activeSongs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> maxScoreMap = new HashMap<>();
        Map<String, Integer> levelMap = new HashMap<>();
        for (SongDefinition s : activeSongs) {
            if (s.getNotes() == null || s.getNotes() <= 0) continue;
            String key = s.getTitle() + "\0" + s.getDifficulty();
            maxScoreMap.put(key, s.getNotes() * 2);
            if (s.getLevel() != null) levelMap.put(key, s.getLevel());
        }

        // 手順2: active 難易度表 → (title,diffName) → informalRank
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

        // 手順3: 登録済み IIDX ID（除外用）
        Set<String> registeredIidxIds = new HashSet<>();
        userRepository.findAll().forEach(u -> {
            if (u.getIidxId() != null) registeredIidxIds.add(u.getIidxId());
        });

        // 手順4: 全仮想プレイヤーを集計
        List<VirtualArenaRanker> rankers = virtualArenaRankerRepository.findAllBy();
        List<Map<String, Object>> beatResults = new ArrayList<>();
        List<Map<String, Object>> rateResults = new ArrayList<>();
        Map<String, Map<String, Object>> profilesBuilder = new HashMap<>();
        int excluded = 0;

        for (VirtualArenaRanker ranker : rankers) {
            String iidxId = ranker.getIidxId();
            if (iidxId == null) continue;
            // 本登録済みのプレイヤーは表示対象外（登録後に自動的に消える安全網）
            if (registeredIidxIds.contains(iidxId)) { excluded++; continue; }

            List<Map<String, Object>> profileScores = new ArrayList<>();
            double[] pts = computePts(ranker.getScores(), maxScoreMap, levelMap, informalRankMap, profileScores);

            double beatPt = Math.round(pts[0] * 10.0) / 10.0;
            double ratePt = Math.round(pts[1] * 10.0) / 10.0;

            Map<String, Object> beatRow = new LinkedHashMap<>();
            beatRow.put("iidxId", iidxId);
            beatRow.put("djName", ranker.getDjName());
            beatRow.put("arenaClass", ranker.getArenaClass());
            beatRow.put("rankPos", ranker.getRankPos());
            beatRow.put("beatPt", beatPt);
            beatResults.add(beatRow);

            Map<String, Object> rateRow = new LinkedHashMap<>();
            rateRow.put("iidxId", iidxId);
            rateRow.put("djName", ranker.getDjName());
            rateRow.put("arenaClass", ranker.getArenaClass());
            rateRow.put("rankPos", ranker.getRankPos());
            rateRow.put("ratePt", ratePt);
            rateResults.add(rateRow);

            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("iidxId", iidxId);
            profile.put("djName", ranker.getDjName());
            profile.put("arenaClass", ranker.getArenaClass());
            profile.put("rankPos", ranker.getRankPos());
            profile.put("beatPt", beatPt);
            profile.put("ratePt", ratePt);
            profile.put("scores", Collections.unmodifiableList(profileScores));
            profilesBuilder.put(iidxId, Collections.unmodifiableMap(profile));
        }

        beatResults.sort((a, b) -> Double.compare(
                ((Number) b.get("beatPt")).doubleValue(),
                ((Number) a.get("beatPt")).doubleValue()));
        rateResults.sort((a, b) -> Double.compare(
                ((Number) b.get("ratePt")).doubleValue(),
                ((Number) a.get("ratePt")).doubleValue()));

        cachedBeat = Collections.unmodifiableList(beatResults);
        cachedRate = Collections.unmodifiableList(rateResults);
        cachedProfiles = Collections.unmodifiableMap(profilesBuilder);

        long t1 = System.currentTimeMillis();
        lastRecomputeDurationMs = t1 - t0;
        lastRecomputeFinishedAt = t1;
        log.info("VirtualArenaRankerService: computed {} players ({} excluded as registered) in {} ms",
                beatResults.size(), excluded, lastRecomputeDurationMs);
    }

    /**
     * 【メソッドの役割】 1 プレイヤーの譜面別ベストから BEAT-PT / RATE-PT を集計し、
     * 併せてプロフィール表示用の行リストを組み立てる。
     * 集計条件は {@code TopRankersBeatPtService.computePtsForCsv} と同一。
     *
     * @param scores          プレイヤーの譜面別ベスト
     * @param maxScoreMap     (title,diffCode) → maxScore
     * @param levelMap        (title,diffCode) → level
     * @param informalRankMap (title,diffName) → informalRank
     * @param profileScoresOut プロフィール表示用の行を追記する出力リスト
     * @return {@code [beatPt, ratePt]}
     */
    private double[] computePts(List<VirtualArenaRankerScore> scores,
                                Map<String, Integer> maxScoreMap,
                                Map<String, Integer> levelMap,
                                Map<String, String> informalRankMap,
                                List<Map<String, Object>> profileScoresOut) {
        List<Double> beatPts = new ArrayList<>();
        List<Double> ratePts = new ArrayList<>();
        int perfectRateCount = 0;

        for (VirtualArenaRankerScore s : scores) {
            String title = s.getTitle();
            String diffName = s.getDifficultyName();
            Integer score = s.getScore();
            if (title == null || diffName == null || score == null || score <= 0) continue;
            String diffCode = DIFF_NAME_TO_CODE.get(diffName);
            if (diffCode == null) continue;

            String keyCode = title + "\0" + diffCode;
            Integer maxScore = maxScoreMap.get(keyCode);
            if (maxScore == null || maxScore == 0) continue;
            double scoreRate = score * 100.0 / maxScore;

            Integer level = levelMap.get(keyCode);
            String djLevel = (s.getDjLevel() != null && !s.getDjLevel().isEmpty())
                    ? s.getDjLevel() : calcDjLevel(scoreRate);

            // プロフィール行（ダッシュボード表示用）。実ユーザースコアと同じ形状に寄せる。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", title);
            row.put("difficultyName", diffName);
            row.put("difficultyLevel", level != null ? level : (s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0));
            row.put("score", score);
            row.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
            row.put("great", s.getGreat() != null ? s.getGreat() : 0);
            row.put("missCount", s.getMissCount());
            row.put("djName", ""); // 個人プロフィールなので曲側 djName は空
            row.put("scoreRate", scoreRate);
            row.put("djLevel", djLevel);
            row.put("clearType", s.getClearType() != null ? s.getClearType() : "NO PLAY");
            row.put("source", "arcade");
            profileScoresOut.add(row);

            // BEAT-PT: HYPER Lv11 以上は対象外
            boolean beatEligible = !("HYPER".equals(diffName) && level != null && level >= 11);
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
     * スコア率から DJ LEVEL (AAA/AA/…/F) を計算する（{@link TopRankersBeatPtService} と同じ境界）。
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
}
