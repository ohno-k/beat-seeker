package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.SystemTaskRun;
import com.beatseeker.backend.entity.UserComparisonStat;
import com.beatseeker.backend.entity.UserComparisonStat.LevelCategory;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SystemTaskRunRepository;
import com.beatseeker.backend.repository.UserComparisonStatRepository;
import com.beatseeker.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 【Service の役割】 管理画面「ユーザー間スコア比較」の勝敗集計を作る日次バッチ本体。
 *
 * 何を作るか:
 *   全ユーザー × 全ユーザーの EX-SCORE 勝敗（WIN / LOSS / DRAW / 片方のみプレイ）を
 *   公式レベル帯（Lv.10 以下 / Lv.11 / Lv.12）ごとに数え、{@link UserComparisonStat} に保存する。
 *   画面はこの表を読むだけで「1 人を選ぶ → 全ユーザーとの勝率降順」を描ける。
 *
 * なぜ事前計算するのか:
 *   ユーザー数 N・譜面数 M に対して総当たりは O(N^2 * M)。リクエスト毎に回せる重さではない。
 *   一方で結果は各ユーザーのベストスコアにしか依存せず、分単位の鮮度も要らないため、
 *   1 日 1 回作り直した表を配るだけで十分。
 *
 * いつ走るか（2 段構え）:
 *  1. 日次バッチ … 1 時間おきのポーリングで「JST の今日ぶんがまだなら走らせる」。
 *     cron 一発にしないのは Render のインスタンスがデプロイ・再起動で止まり得るため。
 *     発火時刻に寝ていても次に起きたときに追いつける。二重実行は {@link SystemTaskRun} で防ぐ。
 *  2. その場実行 … 画面が要求したユーザーの今日ぶんが無ければ、その 1 人ぶんだけ同期で集計する
 *     （{@link #ensureStatsForUser(Long)}）。全ユーザー総当たりの N 分の 1 で済むので待たせない。
 *
 * 集計対象:
 *   ANOTHER / LEGGENDARIA 譜面のうち EX-SCORE > 0（＝プレイ済み）のもののみ。
 *   これはフロントの AdminComparisonModal と同じ条件で、両者未プレイの譜面は数に入らない。
 */
@Service
public class UserComparisonStatsService {

    private static final Logger log = LoggerFactory.getLogger(UserComparisonStatsService.class);

    /** 日次バッチの実行判定を行う間隔（ミリ秒）。1 時間。 */
    private static final long POLL_INTERVAL_MS = 60L * 60L * 1000L;
    /** 起動直後は DataInitializer の DDL と競合し得るので、初回ポーリングを遅らせる。 */
    private static final long INITIAL_DELAY_MS = 5L * 60L * 1000L;

    /** 日次バッチを走らせてよい JST の時刻（時）。SongRankBatchService（03:00）と重ならないよう後ろに置く。 */
    private static final int DAILY_RUN_HOUR_JST = 4;

    /** 日付判定・タスクキーに使うタイムゾーン。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** 日次バッチの実行記録キーの接頭辞。日付を付けて「1 日 1 回」を表現する。 */
    public static final String TASK_KEY_PREFIX = "user-comparison-stats:daily:";

    /** 重量クエリだけ接続既定の 30 秒制限を緩める値。 */
    private static final String STATEMENT_TIMEOUT = "180s";

    /** レベル帯の数。{@link LevelCategory} の要素数と一致する。 */
    private static final int LEVEL_SLOTS = LevelCategory.values().length;

    /** 一括保存を分割する単位。永続化コンテキストを空にする間隔でもある。 */
    private static final int PERSIST_CHUNK_SIZE = 1000;

    private final ScoreRepository scoreRepository;
    private final UserRepository userRepository;
    private final UserComparisonStatRepository statRepository;
    private final SystemTaskRunRepository taskRunRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 集計処理の同時実行を抑えるロック。
     *
     * 全体バッチと「その場実行」が同時に走ると同じ行を取り合うため、集計は常に 1 本だけにする。
     * その場実行はレスポンスを待たせている最中なので、待たされる側も素直に順番待ちさせる
     * （諦めて空を返すより、少し待って正しい表を出すほうが親切）。
     */
    private final ReentrantLock computeLock = new ReentrantLock();

    /** 一括 INSERT の途中で永続化コンテキストを空にするために使う。 */
    @PersistenceContext
    private EntityManager entityManager;

    public UserComparisonStatsService(ScoreRepository scoreRepository,
                                      UserRepository userRepository,
                                      UserComparisonStatRepository statRepository,
                                      SystemTaskRunRepository taskRunRepository,
                                      JdbcTemplate jdbcTemplate,
                                      TransactionTemplate transactionTemplate) {
        this.scoreRepository = scoreRepository;
        this.userRepository = userRepository;
        this.statRepository = statRepository;
        this.taskRunRepository = taskRunRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    // ========================================================================
    // 日次バッチ
    // ========================================================================

    /**
     * 【メソッドの役割】 「JST の今日ぶんの日次バッチがまだなら走らせる」ポーリング。
     *
     * cron ではなくポーリングにしている理由はクラスコメント参照。
     * 実行済みかどうかは {@link SystemTaskRun}（キー = {@code user-comparison-stats:daily:yyyy-MM-dd}）で判定する。
     *
     * {@code @Async} を付けているのは、総当たりの所要時間がユーザー数の 2 乗に比例して伸びるため。
     * 既定のスケジューラはスレッド 1 本なので、ここで居座ると他の定期ジョブまで止めてしまう。
     * 二重実行は {@link #computeLock} と {@link SystemTaskRun} の RUNNING 判定で防いでいる。
     */
    @Scheduled(fixedDelay = POLL_INTERVAL_MS, initialDelay = INITIAL_DELAY_MS)
    @Async
    public void pollDailyBatch() {
        LocalDateTime nowJst = LocalDateTime.now(JST);
        // 予定時刻より前は何もしない。過ぎていれば（何時間遅れていても）その場で追いつく。
        if (nowJst.getHour() < DAILY_RUN_HOUR_JST) return;

        LocalDate today = nowJst.toLocalDate();
        if (hasSucceededOn(today)) return;

        runDailyBatch(today);
    }

    /**
     * 【メソッドの役割】 指定日ぶんの全ユーザー総当たり集計を実行し、実行記録を残す。
     *
     * 既に同じ日のレコードが SUCCESS / RUNNING で存在する場合は何もしない（二重実行防止）。
     * FAILED の場合は同じ行を使い回して再挑戦する（前回の失敗を引きずらない）。
     *
     * @param date JST の対象日
     * @return 実行したなら true、既に実行済み・実行中で見送ったなら false
     */
    public boolean runDailyBatch(LocalDate date) {
        String taskKey = TASK_KEY_PREFIX + date;

        Optional<SystemTaskRun> existing = taskRunRepository.findByTaskKey(taskKey);
        SystemTaskRun run = existing.orElseGet(SystemTaskRun::new);
        if (existing.isPresent()) {
            SystemTaskRun.Status status = run.getStatus();
            // SUCCESS = 今日ぶんは完了済み。RUNNING = 別スレッドが実行中。どちらも二重に走らせない。
            if (status == SystemTaskRun.Status.SUCCESS || status == SystemTaskRun.Status.RUNNING) return false;
        }

        run.setTaskKey(taskKey);
        run.setStatus(SystemTaskRun.Status.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(null);
        run.setDetail(null);
        taskRunRepository.save(run);

        try {
            int rows = refreshAll();
            run.setStatus(SystemTaskRun.Status.SUCCESS);
            run.setDetail(rows + " rows");
        } catch (Exception e) {
            log.error("user-comparison-stats daily batch failed for {}", date, e);
            run.setStatus(SystemTaskRun.Status.FAILED);
            run.setDetail(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            run.setFinishedAt(LocalDateTime.now());
            taskRunRepository.save(run);
        }
        return true;
    }

    /** 指定日の日次バッチが成功済みかどうか。 */
    public boolean hasSucceededOn(LocalDate date) {
        return taskRunRepository.findByTaskKey(TASK_KEY_PREFIX + date)
                .map(r -> r.getStatus() == SystemTaskRun.Status.SUCCESS)
                .orElse(false);
    }

    /**
     * 【メソッドの役割】 全ユーザー総当たりで集計し直し、キャッシュテーブルを丸ごと入れ替える。
     *
     * 差分更新をしないのは、スコアは日々どこが変わったか分からず、
     * 全消し → 全入れのほうが状態がぶれないため。
     *
     * @return 書き込んだ行数
     */
    public int refreshAll() {
        computeLock.lock();
        try {
            long start = System.currentTimeMillis();
            ScoreIndex index = loadScoreIndex();

            List<UserComparisonStat> rows = new ArrayList<>();
            List<Long> userIds = index.userIds;
            // 対 (i, j) を 1 回だけ突き合わせ、A→B と B→A の 2 行を同時に作る。
            // 総当たりを 2 回やると計算量が倍になるので、勝敗を反転させて使い回す。
            for (int i = 0; i < userIds.size(); i++) {
                for (int j = i + 1; j < userIds.size(); j++) {
                    PairResult pair = compare(index, i, j);
                    rows.addAll(pair.toBothRows(userIds.get(i), userIds.get(j)));
                }
            }

            int written = persist(rows, statRepository::deleteAllRows);
            log.info("Rebuilt user-comparison-stats: {} users, {} rows in {} ms",
                    userIds.size(), written, System.currentTimeMillis() - start);
            return written;
        } finally {
            computeLock.unlock();
        }
    }

    // ========================================================================
    // 画面からの読み出し（必要ならその場で集計）
    // ========================================================================

    /**
     * 【メソッドの役割】 指定ユーザーを主体とする集計行を返す。今日ぶんが無ければその場で作る。
     *
     * 「無ければ」の判定:
     *  1. 今日の日次バッチが成功していれば、そのユーザーの行が 0 件でも正しい結果（＝比較材料なし）なので信用する。
     *  2. そうでなければ、そのユーザー自身の最終集計時刻が JST の今日以降かを見る。
     *  3. どちらも満たさなければ、そのユーザー 1 人ぶんだけ同期で集計する。
     *
     * @param userId 主体となるユーザーの ID
     * @return そのユーザーの全相手 × 全レベル帯の集計行
     */
    public List<UserComparisonStat> ensureStatsForUser(Long userId) {
        if (hasSucceededOn(LocalDate.now(JST))) {
            return statRepository.findByUserId(userId);
        }

        LocalDateTime todayStartJst = LocalDate.now(JST).atStartOfDay();
        LocalDateTime latest = statRepository.findLatestComputedAtByUserId(userId);
        if (latest != null && !latest.isBefore(todayStartJst)) {
            return statRepository.findByUserId(userId);
        }

        refreshForUser(userId);
        return statRepository.findByUserId(userId);
    }

    /**
     * 【メソッドの役割】 1 ユーザーぶんだけ集計し直す（画面を待たせている時の即時実行用）。
     *
     * 全体バッチと違い、書き換えるのは「そのユーザーを主体とする行」だけ。
     * 相手側から見た逆向きの行は古いままになるが、それらは日次バッチが揃え直す。
     *
     * @param userId 主体となるユーザーの ID
     * @return 書き込んだ行数
     */
    public int refreshForUser(Long userId) {
        computeLock.lock();
        try {
            long start = System.currentTimeMillis();
            ScoreIndex index = loadScoreIndex();

            int self = index.userIds.indexOf(userId);
            List<UserComparisonStat> rows = new ArrayList<>();
            if (self >= 0) {
                for (int j = 0; j < index.userIds.size(); j++) {
                    if (j == self) continue;
                    PairResult pair = compare(index, self, j);
                    rows.addAll(pair.toSelfRows(userId, index.userIds.get(j)));
                }
            }

            int written = persist(rows, () -> statRepository.deleteByUserId(userId));
            log.info("Rebuilt user-comparison-stats for user {}: {} rows in {} ms",
                    userId, written, System.currentTimeMillis() - start);
            return written;
        } finally {
            computeLock.unlock();
        }
    }

    /**
     * 削除 → 一括保存を 1 トランザクションで行う。
     *
     * 途中で失敗しても「消えただけの状態」が残らないよう、削除と INSERT は必ず同じ境界に入れる。
     *
     * 全ユーザー総当たりでは行数がユーザー数の 2 乗に比例して増えるため、
     * {@link #PERSIST_CHUNK_SIZE} 件ごとに flush + clear して永続化コンテキストを空にする。
     * これをしないと INSERT 済みのエンティティをセッションが抱え続け、
     * ユーザー数が増えたときにヒープを圧迫する。
     */
    private int persist(List<UserComparisonStat> rows, Runnable delete) {
        Integer written = transactionTemplate.execute(status -> {
            delete.run();
            // JPQL の DELETE と後続 INSERT の順序を DB 側でも保つため、ここで一度吐き出す。
            statRepository.flush();
            for (int from = 0; from < rows.size(); from += PERSIST_CHUNK_SIZE) {
                int to = Math.min(from + PERSIST_CHUNK_SIZE, rows.size());
                statRepository.saveAll(rows.subList(from, to));
                statRepository.flush();
                entityManager.clear();
            }
            return rows.size();
        });
        return written == null ? 0 : written;
    }

    // ========================================================================
    // 突き合わせの中身
    // ========================================================================

    /**
     * 【メソッドの役割】 全ユーザーのベストスコアを、突き合わせしやすい形に畳んでメモリに載せる。
     *
     * 譜面（曲名 × 難易度名）に連番を振り、ユーザーごとに「連番の昇順に並んだ譜面配列 + スコア配列」を持つ。
     * 並べておくとペア比較がマージ結合（両方を頭から舐めるだけ）になり、
     * ハッシュ表を引き直すより速く、メモリも int 配列 2 本ぶんで済む。
     */
    private ScoreIndex loadScoreIndex() {
        List<Map<String, Object>> raw = transactionTemplate.execute(status -> {
            // 全ユーザー × 全譜面の GROUP BY は接続既定の 30 秒に収まらない可能性があるため一時的に緩める。
            jdbcTemplate.execute("SET LOCAL statement_timeout = '" + STATEMENT_TIMEOUT + "'");
            return scoreRepository.findAllUserBestScoresForComparison();
        });
        if (raw == null) raw = List.of();

        Map<String, Integer> chartIds = new HashMap<>();
        List<LevelCategory> chartLevels = new ArrayList<>();
        Map<Long, ChartScoreBuilder> perUser = new HashMap<>();

        for (Map<String, Object> row : raw) {
            LevelCategory category = categoryOf(toInt(row.get("difficultyLevel")));
            // Lv.13 以上のような想定外の値は、どのレベル帯にも属さないので捨てる。
            if (category == null) continue;

            Long userId = toLong(row.get("userId"));
            Integer score = toInt(row.get("score"));
            if (userId == null || score == null) continue;

            // フロントの比較キー（title + difficultyName）と同じ粒度。区切りは AdminController#optionsKey と同じ "||"。
            String key = row.get("title") + "||" + row.get("difficultyName");
            Integer chartId = chartIds.get(key);
            if (chartId == null) {
                chartId = chartLevels.size();
                chartIds.put(key, chartId);
                chartLevels.add(category);
            }
            perUser.computeIfAbsent(userId, k -> new ChartScoreBuilder()).add(chartId, score);
        }

        // 主体になり得るのは「DB に存在する全ユーザー」。スコア 0 件のユーザーも、
        // 相手から見れば「相手だけプレイ済み」を積み上げる比較対象なので並びには残す。
        List<Long> userIds = new ArrayList<>(userRepository.findAllUserIds());

        LevelCategory[] levels = chartLevels.toArray(new LevelCategory[0]);
        List<UserCharts> charts = new ArrayList<>(userIds.size());
        for (Long userId : userIds) {
            ChartScoreBuilder builder = perUser.get(userId);
            charts.add(builder == null ? UserCharts.EMPTY : builder.build(levels));
        }
        return new ScoreIndex(userIds, levels, charts);
    }

    /**
     * 【メソッドの役割】 2 ユーザーの譜面配列をマージ結合し、レベル帯ごとの勝敗を数える。
     *
     * どちらも譜面 ID の昇順に並んでいるので、2 本のカーソルを進めるだけで
     * 「両者がプレイ済みの譜面」を線形時間で拾える。
     *
     * @param index 事前に畳んだスコア表
     * @param a     主体側のユーザー添字
     * @param b     相手側のユーザー添字
     * @return レベル帯ごとの勝敗（a 視点）
     */
    private PairResult compare(ScoreIndex index, int a, int b) {
        return comparePair(index.charts.get(a), index.charts.get(b), index.chartLevels);
    }

    /**
     * 【メソッドの役割】 {@link #compare} の中身。譜面列 2 本を直接受け取る、テストしやすい形。
     *
     * @param ua     主体側の譜面列
     * @param ub     相手側の譜面列
     * @param levels 譜面 ID → レベル帯の対応表
     * @return レベル帯ごとの勝敗（ua 視点）
     */
    static PairResult comparePair(UserCharts ua, UserCharts ub, LevelCategory[] levels) {
        PairResult result = new PairResult();

        int i = 0;
        int j = 0;
        while (i < ua.chartIds.length && j < ub.chartIds.length) {
            int ca = ua.chartIds[i];
            int cb = ub.chartIds[j];
            if (ca < cb) {
                i++;
            } else if (ca > cb) {
                j++;
            } else {
                int slot = levels[ca].ordinal();
                int diff = ua.scores[i] - ub.scores[j];
                if (diff > 0) result.win[slot]++;
                else if (diff < 0) result.loss[slot]++;
                else result.draw[slot]++;
                result.matched[slot]++;
                i++;
                j++;
            }
        }

        // 「片方だけプレイ済み」は、そのユーザーのプレイ済み数から共通数を引けば求まる。
        for (int slot = 0; slot < LEVEL_SLOTS; slot++) {
            result.onlySelf[slot] = ua.playedCounts[slot] - result.matched[slot];
            result.onlyOpponent[slot] = ub.playedCounts[slot] - result.matched[slot];
        }
        return result;
    }

    /** 公式レベルをレベル帯に振り分ける。対象外なら null。 */
    private static LevelCategory categoryOf(Integer level) {
        if (level == null) return null;
        if (level <= 10) return LevelCategory.LV10MINUS;
        if (level == 11) return LevelCategory.LV11;
        if (level == 12) return LevelCategory.LV12;
        return null;
    }

    private static Integer toInt(Object v) {
        return v instanceof Number n ? n.intValue() : null;
    }

    private static Long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    // ========================================================================
    // 内部データ構造
    // ========================================================================

    /** ペア比較の結果（主体側から見た勝敗）をレベル帯ごとの配列で持つ入れ物。 */
    static final class PairResult {
        final int[] win = new int[LEVEL_SLOTS];
        final int[] loss = new int[LEVEL_SLOTS];
        final int[] draw = new int[LEVEL_SLOTS];
        final int[] matched = new int[LEVEL_SLOTS];
        final int[] onlySelf = new int[LEVEL_SLOTS];
        final int[] onlyOpponent = new int[LEVEL_SLOTS];

        /** a→b と b→a の両方向の行を作る（b 視点は勝敗と「片方のみ」を入れ替えるだけ）。 */
        List<UserComparisonStat> toBothRows(Long userA, Long userB) {
            List<UserComparisonStat> rows = new ArrayList<>(LEVEL_SLOTS * 2);
            rows.addAll(toSelfRows(userA, userB));
            for (LevelCategory category : LevelCategory.values()) {
                int slot = category.ordinal();
                if (isEmpty(slot)) continue;
                rows.add(row(userB, userA, category,
                        loss[slot], win[slot], draw[slot], onlyOpponent[slot], onlySelf[slot]));
            }
            return rows;
        }

        /** 主体側（a）から見た行だけを作る。 */
        List<UserComparisonStat> toSelfRows(Long userA, Long userB) {
            List<UserComparisonStat> rows = new ArrayList<>(LEVEL_SLOTS);
            for (LevelCategory category : LevelCategory.values()) {
                int slot = category.ordinal();
                if (isEmpty(slot)) continue;
                rows.add(row(userA, userB, category,
                        win[slot], loss[slot], draw[slot], onlySelf[slot], onlyOpponent[slot]));
            }
            return rows;
        }

        /** そのレベル帯に数えるものが何も無いか。全 0 の行は情報量が無いので保存しない。 */
        private boolean isEmpty(int slot) {
            return matched[slot] == 0 && onlySelf[slot] == 0 && onlyOpponent[slot] == 0;
        }

        private static UserComparisonStat row(Long userId, Long opponentId, LevelCategory category,
                                              int win, int loss, int draw, int onlySelf, int onlyOpponent) {
            UserComparisonStat stat = new UserComparisonStat();
            stat.setUserId(userId);
            stat.setOpponentId(opponentId);
            stat.setLevelCategory(category);
            stat.setWin(win);
            stat.setLoss(loss);
            stat.setDraw(draw);
            stat.setOnlySelf(onlySelf);
            stat.setOnlyOpponent(onlyOpponent);
            stat.setComputedAt(LocalDateTime.now());
            return stat;
        }
    }

    /** 1 ユーザーぶんの「譜面 ID 昇順のスコア列」。ペア比較のマージ結合はこれを舐める。 */
    static final class UserCharts {
        /** スコアを 1 件も持たないユーザー用の共有インスタンス。中身は不変なので使い回して問題ない。 */
        static final UserCharts EMPTY = new UserCharts(new int[0], new int[0], new int[LEVEL_SLOTS]);

        final int[] chartIds;
        final int[] scores;
        /** レベル帯ごとのプレイ済み譜面数。「片方だけプレイ済み」の算出に使う。 */
        final int[] playedCounts;

        UserCharts(int[] chartIds, int[] scores, int[] playedCounts) {
            this.chartIds = chartIds;
            this.scores = scores;
            this.playedCounts = playedCounts;
        }
    }

    /** {@link UserCharts} を組み立てる可変バッファ。読み込み中だけ使う。 */
    static final class ChartScoreBuilder {
        private int[] chartIds = new int[64];
        private int[] scores = new int[64];
        private int size = 0;

        void add(int chartId, int score) {
            if (size == chartIds.length) {
                chartIds = Arrays.copyOf(chartIds, size * 2);
                scores = Arrays.copyOf(scores, size * 2);
            }
            chartIds[size] = chartId;
            scores[size] = score;
            size++;
        }

        /** 譜面 ID 昇順に並べ替えつつ、レベル帯ごとのプレイ済み数を数える。 */
        UserCharts build(LevelCategory[] levels) {
            // (chartId, score) を 1 本の long に詰めて並べ替える。
            // 上位 32bit が chartId（常に 0 以上）なので、long の昇順 = chartId の昇順になる。
            long[] packed = new long[size];
            for (int i = 0; i < size; i++) {
                packed[i] = ((long) chartIds[i] << 32) | (scores[i] & 0xFFFFFFFFL);
            }
            Arrays.sort(packed);

            int[] ids = new int[size];
            int[] values = new int[size];
            int[] playedCounts = new int[LEVEL_SLOTS];
            for (int i = 0; i < size; i++) {
                int chartId = (int) (packed[i] >>> 32);
                ids[i] = chartId;
                values[i] = (int) packed[i];
                playedCounts[levels[chartId].ordinal()]++;
            }
            return new UserCharts(ids, values, playedCounts);
        }
    }

    /** 突き合わせ 1 回ぶんの入力一式（ユーザー並び・譜面のレベル帯・ユーザー別スコア列）。 */
    private static final class ScoreIndex {
        final List<Long> userIds;
        final LevelCategory[] chartLevels;
        final List<UserCharts> charts;

        ScoreIndex(List<Long> userIds, LevelCategory[] chartLevels, List<UserCharts> charts) {
            this.userIds = userIds;
            this.chartLevels = chartLevels;
            this.charts = charts;
        }
    }
}
