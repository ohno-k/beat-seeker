package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.ScoreRepository;
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
 * 集計基準（2026-09-16 変更）:
 *  - 現行作 {@code scores} だけでなく、各ユーザーの<b>自己歴代ベスト</b>（現行 scores ＋ 過去作
 *    past_scores を曲 × 難易度で MAX）を 1 人 1 票として集計する
 *    （{@link ScoreRepository#findLifetimeSongAvgStats()}）。
 *  - ZINRAI 移行直後は現行作の scores が空に近く、旧基準では平均・MAX- 率が数十人分の
 *    偏ったサンプルになるため、ユーザー指示により歴代基準へ切り替えた。
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
    private final JdbcTemplate jdbcTemplate;

    /** 公開する集計結果。volatile で publish/subscribe を成立させる。要素は不変として扱う。 */
    private volatile List<Map<String, Object>> cache = List.of();

    /** リフレッシュ中フラグ。多重起動を抑止。 */
    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    public SongAvgScoreRatesCacheService(ScoreRepository scoreRepository,
            JdbcTemplate jdbcTemplate) {
        this.scoreRepository = scoreRepository;
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
     *  1. {@link ScoreRepository#findLifetimeSongAvgStats()} で、各ユーザーの自己歴代ベストを
     *     1 票とした曲別の平均スコア・人数・MAX- 数・AAA 数・notes を 1 本で取得する。
     *  2. 平均スコアを「notes × 2」で割って scoreRate に変換し、各種レートを整形して出力。
     *  3. 平均スコアレート昇順でソート（詰まり気味 → 緩い順）。
     *
     * 旧実装は scores 単表の 3 クエリ（平均 / MAX- / AAA）を Java 側でマージしていたが、
     * 歴代化で scores ∪ past_scores の MAX 集約が共通の前処理になるため 1 クエリに統合した。
     * 出力キー（title / difficultyName / avgScoreRate / playerCount / maxMinusRate /
     * maxMinusCount / aaaRate / aaaCount）は旧実装と同じで、フロントの変更は不要。
     */
    private List<Map<String, Object>> computeAll() {
        // 手順1: 歴代ベスト基準の曲別集計を取得（notes も同じ行に載って返る）。
        List<Map<String, Object>> songStats = scoreRepository.findLifetimeSongAvgStats();

        // 手順2: 平均スコアを scoreRate（%）に変換しつつ、各種レートを組み立てて返す。
        List<Map<String, Object>> result = new ArrayList<>(songStats.size());
        for (Map<String, Object> row : songStats) {
            String title = (String) row.get("title");
            String diffName = (String) row.get("difficultyName");
            double avgScore = ((Number) row.get("avgScore")).doubleValue();
            int playerCount = ((Number) row.get("playerCount")).intValue();
            int maxMinusCount = ((Number) row.get("maxMinusCount")).intValue();
            int aaaCount = ((Number) row.get("aaaCount")).intValue();
            Number notesNum = (Number) row.get("notes");
            int notes = notesNum == null ? 0 : notesNum.intValue();

            // SQL 側で notes > 0 を保証しているが、念のため 0 除算を避ける。
            if (notes <= 0 || playerCount <= 0) continue;

            // MAX スコアは notes × 2。% 化するために 100 倍を掛ける。
            double avgScoreRate = avgScore * 100.0 / (notes * 2.0);

            // 10000 倍してから丸めて /100 することで小数 2 桁の % を生成する。
            double maxMinusRate = Math.round(maxMinusCount * 10000.0 / playerCount) / 100.0;
            double aaaRate = Math.round(aaaCount * 10000.0 / playerCount) / 100.0;

            Map<String, Object> entry = new HashMap<>();
            entry.put("title", title);
            entry.put("difficultyName", diffName);
            entry.put("avgScoreRate", Math.round(avgScoreRate * 100.0) / 100.0);
            entry.put("playerCount", playerCount);
            entry.put("maxMinusRate", maxMinusRate);
            entry.put("maxMinusCount", maxMinusCount);
            entry.put("aaaRate", aaaRate);
            entry.put("aaaCount", aaaCount);
            result.add(entry);
        }

        // 平均スコアレート昇順（= 難しい／詰まってない曲順）でソートして返す。
        result.sort((a, b) -> Double.compare(
            ((Number) a.get("avgScoreRate")).doubleValue(),
            ((Number) b.get("avgScoreRate")).doubleValue()));

        return result;
    }
}
