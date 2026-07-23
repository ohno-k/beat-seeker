package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【Service の役割】 曲別平均スコアレートページ（{@code /api/scores/song-avg-score-rates}）の
 * 集計結果を in-memory にキャッシュするサービス。
 *
 * 背景:
 *  - 元はリクエスト毎に {@link ScoreRepository#findSongAvgScores()} /
 *    {@link ScoreRepository#findSongMaxMinusCounts()} / {@link ScoreRepository#findSongAaaCounts()}
 *    の 3 本の scores 全件集計を同期実行していた。データ増加に伴いレスポンスが重くなり、
 *    PostgreSQL の {@code statement_timeout = 30s} 超過のリスクも高まっていた。
 *  - 結果は全ユーザーのベストスコアにのみ依存し、リアルタイム性は不要のため
 *    定期リフレッシュ + in-memory 配信で十分。
 *
 * 動作:
 *  - 起動 2 分後に初回ロード、その後 {@link #REFRESH_INTERVAL_MS}（30 分）毎に再計算
 *  - リフレッシュ用トランザクション内で {@code SET LOCAL statement_timeout = '180s'} を発行し、
 *    集計クエリだけ接続レベルの 30 秒タイムアウトを緩和する（他リクエストには影響しない）
 *  - リフレッシュ中は {@link #refreshing} フラグで多重実行を防止
 *  - 失敗時は前回値を保持し続け、次回リフレッシュでリトライする
 *
 * {@link SongArenaAveragesCacheService} / {@link SongRankingAggregateCacheService} と
 * 同じ設計パターンの兄弟サービス。
 */
@Service
public class SongAvgScoreRatesCacheService {

    private static final Logger log = LoggerFactory.getLogger(SongAvgScoreRatesCacheService.class);

    /** 再計算間隔（ミリ秒）。30 分。 */
    private static final long REFRESH_INTERVAL_MS = 30L * 60L * 1000L;
    // 起動直後の DataInitializer の ALTER TABLE とロック競合しないよう初回を遅延（SongArenaAveragesCacheService と同様）。
    private static final long INITIAL_DELAY_MS = 120L * 1000L;

    private final ScoreRepository scoreRepository;
    private final SongDefinitionRepository songDefinitionRepository;
    private final JdbcTemplate jdbcTemplate;

    /** 公開する集計結果。volatile で publish/subscribe を成立させる。要素は不変として扱う。 */
    private volatile List<Map<String, Object>> cache = List.of();

    /** リフレッシュ中フラグ。多重起動を抑止。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SongAvgScoreRatesCacheService(ScoreRepository scoreRepository,
            SongDefinitionRepository songDefinitionRepository,
            JdbcTemplate jdbcTemplate) {
        this.scoreRepository = scoreRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 【メソッドの役割】 キャッシュ済みの集計結果を返す。
     *
     * 初回リフレッシュ完了前は空リストを返す。フロントは「データ 0 件」として描画される。
     */
    public List<Map<String, Object>> get() {
        return cache;
    }

    /**
     * 【メソッドの役割】 集計結果を再計算してキャッシュを差し替える。
     *
     * 30 分毎の定期実行に加え、起動 2 分後にも 1 回走る（{@code initialDelay}）。
     * 既にリフレッシュ中なら何もしない（多重起動防止）。
     */
    @Scheduled(fixedDelay = REFRESH_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    @Transactional
    public void refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            log.debug("song-avg-score-rates cache refresh already in progress, skipping");
            return;
        }
        long start = System.currentTimeMillis();
        try {
            // 無制限(0)ではなく有限上限。暴走クエリが接続を握り続けてインスタンスを不安定化させない。
            jdbcTemplate.execute("SET LOCAL statement_timeout = '180s'");
            List<Map<String, Object>> next = computeAll();
            this.cache = next;
            log.info("Refreshed song-avg-score-rates cache: {} rows in {} ms",
                    next.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Failed to refresh song-avg-score-rates cache after {} ms (keeping previous value of {} rows)",
                    System.currentTimeMillis() - start, cache.size(), e);
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * 【メソッドの役割】 曲単位で「平均スコアレート」「MAX- 率」「AAA 率」を集計する。
     *
     * 処理の流れ:
     *  1. active リビジョンの SongDefinition から notes lookup（Lv11 以上の A/L のみ）を構築。
     *  2. DB から per-song 平均スコア、MAX- 数、AAA 数をそれぞれ取得して lookup を作る。
     *  3. 平均スコアを「notes × 2」で割って scoreRate に変換し、各種レートを整形して出力。
     *  4. 平均スコアレート昇順でソート（詰まり気味 → 緩い順）。
     *
     * 分離クエリ × Java 側マージ方式を採るのは、単一重量 JOIN を避けるため。
     */
    private List<Map<String, Object>> computeAll() {
        // 手順1: 「曲名|難易度名」→ notes 数 の lookup を構築（高速・シングルテーブル）。
        List<SongDefinition> songDefs = songDefinitionRepository.findByRevision("active");
        Map<String, Integer> notesMap = new HashMap<>();
        for (SongDefinition sd : songDefs) {
            // Lv11 未満は対象外なのでスキップ。
            if (sd.getLevel() == null || sd.getLevel() < 11) continue;
            // 難易度コード 4 = ANOTHER、10 = LEGGENDARIA という IIDX の内部規約に従う。
            if ("4".equals(sd.getDifficulty())) {
                notesMap.put(sd.getTitle() + "|ANOTHER", sd.getNotes());
            } else if ("10".equals(sd.getDifficulty())) {
                notesMap.put(sd.getTitle() + "|LEGGENDARIA", sd.getNotes());
            }
        }

        // 手順2: 曲単位の平均スコアを取得（単一テーブル GROUP BY で軽量）。
        List<Map<String, Object>> songAvgs = scoreRepository.findSongAvgScores();

        // 手順3: MAX-（1 点差 AAA）の曲別カウントを集計 JOIN で取得（約 1000 行）。
        List<Map<String, Object>> maxMinusData = scoreRepository.findSongMaxMinusCounts();
        Map<String, int[]> maxMinusStats = new HashMap<>();
        for (Map<String, Object> row : maxMinusData) {
            String key = row.get("title") + "|" + row.get("difficultyName");
            int maxMinusCount = ((Number) row.get("maxMinusCount")).intValue();
            int totalCount = ((Number) row.get("totalCount")).intValue();
            // 値 2 要素を持つ int[] に詰めて lookup に保存する（Map<String, Stats> より軽量）。
            maxMinusStats.put(key, new int[]{maxMinusCount, totalCount});
        }

        // 手順3b: AAA の曲別カウントも同様に集計。
        List<Map<String, Object>> aaaData = scoreRepository.findSongAaaCounts();
        Map<String, int[]> aaaStats = new HashMap<>();
        for (Map<String, Object> row : aaaData) {
            String key = row.get("title") + "|" + row.get("difficultyName");
            int aaaCount = ((Number) row.get("aaaCount")).intValue();
            int totalCount = ((Number) row.get("totalCount")).intValue();
            aaaStats.put(key, new int[]{aaaCount, totalCount});
        }

        // 手順4: 平均スコアを scoreRate（%）に変換しつつ、各種レートを組み立てて返す。
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : songAvgs) {
            String title = (String) row.get("title");
            String diffName = (String) row.get("difficultyName");
            double avgScore = ((Number) row.get("avgScore")).doubleValue();
            int playerCount = ((Number) row.get("playerCount")).intValue();

            String key = title + "|" + diffName;
            Integer notes = notesMap.get(key);
            // notes 情報が無い曲（SongDefinition 未登録、もしくは Lv11 未満）はスキップ。
            if (notes == null || notes <= 0) continue;

            // MAX スコアは notes × 2。% 化するために 100 倍を掛ける。
            double avgScoreRate = avgScore * 100.0 / (notes * 2.0);

            int[] stats = maxMinusStats.get(key);
            // 10000 倍してから丸めて /100 することで小数 2 桁の % を生成する。
            double maxMinusRate = (stats != null && stats[1] > 0)
                ? Math.round(stats[0] * 10000.0 / stats[1]) / 100.0
                : 0.0;

            Map<String, Object> entry = new HashMap<>();
            entry.put("title", title);
            entry.put("difficultyName", diffName);
            entry.put("avgScoreRate", Math.round(avgScoreRate * 100.0) / 100.0);
            entry.put("playerCount", playerCount);
            entry.put("maxMinusRate", maxMinusRate);
            entry.put("maxMinusCount", stats != null ? stats[0] : 0);

            int[] aStats = aaaStats.get(key);
            double aaaRate = (aStats != null && aStats[1] > 0)
                ? Math.round(aStats[0] * 10000.0 / aStats[1]) / 100.0
                : 0.0;
            entry.put("aaaRate", aaaRate);
            entry.put("aaaCount", aStats != null ? aStats[0] : 0);
            result.add(entry);
        }

        // 平均スコアレート昇順（= 難しい／詰まってない曲順）でソートして返す。
        result.sort((a, b) -> Double.compare(
            ((Number) a.get("avgScoreRate")).doubleValue(),
            ((Number) b.get("avgScoreRate")).doubleValue()));

        return result;
    }
}
