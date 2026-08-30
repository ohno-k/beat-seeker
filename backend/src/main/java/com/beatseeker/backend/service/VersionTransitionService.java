package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【Service の役割】 新作稼働にともなう「作品の世代切り替え」の各手順を実装する。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされる。beat-seeker でも初日に
 * 前作のスコアを過去作へ移し、BEAT-PT / RATE-PT を積み直す。その一連の作業のうち、
 * <b>機械的に実行してよい部分</b>をここに実装する。起動そのものは
 * {@link VersionTransitionScheduler} が担当する。
 *
 * 手順:
 *  1. {@link #captureSnapshot} … 前作の最終 PT を {@code version_pt_snapshots} へ焼き付ける（追記のみ）
 *  2. {@link #copyScoresToPastScores} … {@code scores} を {@code past_scores} へ複製する（追記のみ）
 *  3. {@link #applyDifficultyDraft} … 難易度表 draft を active へ適用する
 *  4. {@link #resetCurrentScores} … {@code scores} と派生データを初期化する（<b>唯一の破壊的手順</b>）
 *
 * 実行順序の不変条件: <b>1 → 2 → 4</b> の順を必ず守る。1 を撮る前に 4 を行うと前作の順位が
 *   永久に失われる。{@link #resetCurrentScores} 自身も 1 と 2 の結果が DB に無ければ例外で止まり、
 *   {@link VersionTransitionScheduler} 側でも前段の成否を見て順序を守る。
 *
 * 冪等性: 1 と 2 は既存行があれば何もしない（{@code ON CONFLICT DO NOTHING}）ため、二重に走っても壊れない。
 *   <b>4 だけは冪等ではない</b>（呼ぶたびに履歴のリセット行が増える）。二重実行の防止は
 *   {@link VersionTransitionScheduler} の {@code system_task_runs} が担う。
 *
 * 手順 4 の既定: スケジューラ側では {@code app.version-transition.reset-scores} を明示的に
 *   true にしない限り実行されない。取り返しがつかない操作を、稼働日を設定しただけで走らせないため。
 */
@Service
public class VersionTransitionService {

    private static final Logger log = LoggerFactory.getLogger(VersionTransitionService.class);

    /**
     * スコア初期化のあとに履歴へ差し込む「世代リセット行」のタグ。
     *
     * 通常のアップロード（タグ null）とも INFINITAS 取り込み（タグ {@code "INFINITAS"}）とも
     * 別の値にしてある。同日 1 レコードへ集約する upsert はタグ一致で既存行を引き当てるため、
     * 別タグにしておけば初日のアップロードがこの行を上書きせず、両方が残る。
     */
    static final String RESET_LOG_TAG = "VERSION-RESET";

    private final JdbcTemplate jdbcTemplate;
    private final VersionPtSnapshotRepository snapshotRepository;
    private final GameDataService gameDataService;
    private final SongRankingAggregateCacheService songRankingAggregateCache;
    private final SongArenaAveragesCacheService songArenaAveragesCache;
    private final SongAvgScoreRatesCacheService songAvgScoreRatesCache;

    public VersionTransitionService(JdbcTemplate jdbcTemplate,
                                    VersionPtSnapshotRepository snapshotRepository,
                                    GameDataService gameDataService,
                                    SongRankingAggregateCacheService songRankingAggregateCache,
                                    SongArenaAveragesCacheService songArenaAveragesCache,
                                    SongAvgScoreRatesCacheService songAvgScoreRatesCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
        this.gameDataService = gameDataService;
        this.songRankingAggregateCache = songRankingAggregateCache;
        this.songArenaAveragesCache = songArenaAveragesCache;
        this.songAvgScoreRatesCache = songAvgScoreRatesCache;
    }

    /**
     * 【メソッドの役割】 前作の最終 PT を全ユーザーぶん {@code version_pt_snapshots} へ焼き付ける。
     *
     * 集計元は {@code score_history_logs} の<b>ユーザーごとの最新行</b>。現行のランキング
     * （{@code ScoreHistoryLogRepository#getGlobalRanking}）と同じ取り方にしてあるので、
     * 撮影直前に画面で見えている順位がそのまま保存される。
     *
     * RATE-PT の順位だけ {@code total_rate_pt > 0} に絞るのも現行ランキングに合わせたもの
     * （RATE-PT を持たないユーザーが 1 位タイに並ぶのを防ぐため）。
     *
     * @param version 焼き付ける作品バージョン（例: 33）
     * @param dryRun  true なら DB を変更せず、対象になるユーザー数だけを返す
     * @return 書き込んだ（dry-run 時は書き込む予定の）行数
     */
    @Transactional
    public int captureSnapshot(int version, boolean dryRun) {
        Integer candidates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM score_history_logs) t", Integer.class);
        int expected = candidates == null ? 0 : candidates;

        if (dryRun) {
            log.info("[世代切替] スナップショット dry-run: version={} 対象 {} 人", version, expected);
            return expected;
        }

        int inserted = jdbcTemplate.update(
                "INSERT INTO version_pt_snapshots " +
                "  (version, user_id, iidx_id, display_name, total_beat_pt, total_rate_pt, " +
                "   total_kenban_pt, total_sara_pt, privacy_level, last_uploaded_at, captured_at) " +
                "SELECT ?, l.user_id, u.iidx_id, u.display_name, l.total_beat_pt, l.total_rate_pt, " +
                "       l.total_kenban_pt, l.total_sara_pt, u.privacy_level, l.uploaded_at, now() " +
                "FROM ( " +
                "  SELECT DISTINCT ON (user_id) user_id, total_beat_pt, total_rate_pt, " +
                "         total_kenban_pt, total_sara_pt, uploaded_at " +
                "  FROM score_history_logs " +
                "  ORDER BY user_id, uploaded_at DESC " +
                ") l " +
                "JOIN users u ON u.id = l.user_id " +
                "ON CONFLICT DO NOTHING",
                version);

        // 順位を付与する。RANK() なので同値は同順位、次順位は飛ぶ（現行ランキングと同じ挙動）。
        jdbcTemplate.update(
                "UPDATE version_pt_snapshots s SET beat_rank = r.rk " +
                "FROM (SELECT id, RANK() OVER (ORDER BY total_beat_pt DESC) AS rk " +
                "      FROM version_pt_snapshots WHERE version = ?) r " +
                "WHERE s.id = r.id AND s.version = ?",
                version, version);
        jdbcTemplate.update(
                "UPDATE version_pt_snapshots s SET rate_rank = r.rk " +
                "FROM (SELECT id, RANK() OVER (ORDER BY total_rate_pt DESC) AS rk " +
                "      FROM version_pt_snapshots WHERE version = ? AND total_rate_pt > 0) r " +
                "WHERE s.id = r.id AND s.version = ?",
                version, version);

        long total = snapshotRepository.countByVersion(version);
        log.info("[世代切替] スナップショット完了: version={} 新規 {} 件 / 累計 {} 件（対象 {} 人）",
                version, inserted, total, expected);
        return inserted;
    }

    /**
     * 【メソッドの役割】 現行作のスコアを {@code past_scores} へ複製する（元データは消さない）。
     *
     * 利用者が前作の CSV を取り込み直さずに「歴代」タブで見られるようにするための手順。
     *
     * 実装上の判断:
     *  - <b>譜面ごとに最も高いスコアだけを移す。</b> {@code past_scores} の一意キーは
     *    (user_id, version, title, difficulty_name) で {@code source} を含まないため、
     *    アーケードと INFINITAS の両方に記録がある譜面はそのままでは衝突する。
     *    歴代ベストの用途に照らして最高スコアを採る。
     *  - <b>未プレー（score = 0）は移さない。</b> 公式 CSV は全収録曲を含むため、
     *    そのまま入れると未プレー行でテーブルが膨らむ。
     *  - {@code past_scores.last_played_at} は文字列カラム（"YYYY-MM-DD HH24:MI" 書式）なので
     *    {@code to_char} で変換する。{@code scores} 側は timestamp 型。
     *
     * @param version 移送先の作品バージョン（例: 33）
     * @param dryRun  true なら DB を変更せず、対象行数だけを返す
     * @return 書き込んだ（dry-run 時は書き込む予定の）行数
     */
    @Transactional
    public int copyScoresToPastScores(int version, boolean dryRun) {
        String selectSql =
                "SELECT DISTINCT ON (s.user_id, s.title, s.difficulty_name) " +
                "       s.user_id, s.title, s.artist, s.genre, s.difficulty_name, s.difficulty_level, " +
                "       s.score, s.clear_type, s.dj_level, s.pgreat, s.great, s.miss_count, s.play_count, " +
                "       to_char(s.last_played_at, 'YYYY-MM-DD HH24:MI') AS last_played_at " +
                "FROM scores s " +
                "WHERE s.score > 0 " +
                "ORDER BY s.user_id, s.title, s.difficulty_name, s.score DESC";

        if (dryRun) {
            Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM (" + selectSql + ") t", Integer.class);
            int expected = n == null ? 0 : n;
            log.info("[世代切替] スコア複製 dry-run: version={} 対象 {} 行", version, expected);
            return expected;
        }

        int inserted = jdbcTemplate.update(
                "INSERT INTO past_scores " +
                "  (user_id, version, title, artist, genre, difficulty_name, difficulty_level, score, " +
                "   clear_type, dj_level, pgreat, great, miss_count, play_count, last_played_at, imported_at) " +
                "SELECT t.user_id, ?, t.title, t.artist, t.genre, t.difficulty_name, t.difficulty_level, t.score, " +
                "       t.clear_type, t.dj_level, t.pgreat, t.great, t.miss_count, t.play_count, t.last_played_at, now() " +
                "FROM (" + selectSql + ") t " +
                "ON CONFLICT DO NOTHING",
                version);

        log.info("[世代切替] スコア複製完了: version={} {} 行を past_scores へ複製（scores は未変更）", version, inserted);
        return inserted;
    }

    /**
     * 【メソッドの役割】 難易度表の draft を active へ適用する。
     *
     * 実処理は既存の {@link GameDataService#applyDraftDifficultyTable()} に委譲する
     * （管理画面の「難易度表を適用」ボタンと同じ経路）。適用後は全ユーザーの
     * BEAT-PT 再計算がバックグラウンドで走る。
     *
     * @param dryRun true なら適用せず、未適用の draft があるかだけを返す
     * @return 適用した（dry-run 時は適用対象がある）なら true
     * @throws Exception 適用に失敗した場合。呼び出し側（スケジューラ）が失敗として記録できるよう、
     *                   握り潰さずそのまま伝播させる
     */
    public boolean applyDifficultyDraft(boolean dryRun) throws Exception {
        if (!gameDataService.hasDraftDifficultyTable()) {
            log.info("[世代切替] 難易度表: 未適用の draft が無いため何もしない");
            return false;
        }
        if (dryRun) {
            log.info("[世代切替] 難易度表 dry-run: 未適用の draft あり（適用すれば active に反映される）");
            return true;
        }
        gameDataService.applyDraftDifficultyTable();
        log.info("[世代切替] 難易度表: draft を active へ適用した（ポイント再計算は非同期）");
        return true;
    }

    /**
     * 【メソッドの役割】 現行作のスコアと、そこから派生したデータを一斉に初期化する。
     *
     * 新作初日に BEAT-PT / RATE-PT を 0 から積み直すための手順。<b>ここだけが破壊的</b>で、
     * 実行すると {@code scores} は空になる。前作の記録は先に {@link #captureSnapshot}（順位）と
     * {@link #copyScoresToPastScores}（譜面ごとのスコア）で退避されている前提であり、
     * その 2 つが済んでいなければ何もせず例外を投げる。
     *
     * ■ 何を初期化するか
     * <pre>
     *   scores                 全削除。現行作のスコアそのもの
     *   user_song_ranks        全削除。scores から作られる譜面ごとの順位キャッシュ
     *   user_comparison_stats  全削除。scores から作られるユーザー間の比較集計
     *   users                  total_beat_pt / total_kenban_pt / total_sara_pt を 0、
     *                          total_average_rank / total_average_rank_played / last_uploaded_at を NULL、
     *                          ranking_includes_infinitas を false
     *   score_history_logs     <b>消さずに、全員へ 0 の「世代リセット行」を 1 行ずつ追加する</b>
     * </pre>
     *
     * ■ なぜ履歴を消さずにリセット行を足すのか
     * ランキングは {@code score_history_logs} の<b>ユーザーごと最新行</b>を見る
     * （{@link com.beatseeker.backend.repository.ScoreHistoryLogRepository#getGlobalRanking()}）。
     * したがって履歴を放置すると、{@code scores} を空にしても順位表には前作の PT が出たままになる。
     * かといって履歴を消すと成長記録のグラフから前作の推移が丸ごと失われる。
     * そこで「全項目 0 の行を今の時刻で 1 行足す」ことにした。最新行が 0 になるので順位表は
     * 0 から始まり、過去の推移はそのまま残る。グラフ上は初日で 0 へ落ちる形になるが、
     * これは実際に起きたこと（世代交代によるリセット）そのものなので正しい見え方になる。
     *
     * ■ 冪等性
     * 上の 3 つと違い、この手順は<b>冪等ではない</b>（呼ぶたびにリセット行が増える）。
     * 二重実行の防止は {@link VersionTransitionScheduler} の {@code system_task_runs} に委ねる。
     *
     * 注意: 楽曲単位の集計キャッシュ（曲別ランキング・平均スコアレート・アリーナ平均）は
     * このメソッドでは触らない。トランザクションが確定してから
     * {@link #refreshSongCaches()} を呼ぶこと。
     *
     * @param fromVersion 退避済みかを確認する移行元バージョン（例: 33）
     * @param dryRun      true なら DB を変更せず、消える予定の行数だけを返す
     * @return 対象になった（dry-run 時はなる予定の）行数の内訳
     * @throws IllegalStateException 前作の退避（スナップショット / 過去作スコア）が済んでいない場合
     */
    @Transactional
    public Map<String, Object> resetCurrentScores(int fromVersion, boolean dryRun) {
        long snapshots = snapshotRepository.countByVersion(fromVersion);
        Long pastScores = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM past_scores WHERE version = ?", Long.class, fromVersion);
        long pastScoreRows = pastScores == null ? 0L : pastScores;

        // 退避が済んでいるかの確認。dry-run では何も壊さないので、止めずに警告だけ出す
        // （dry-run 中は手順 1・2 も書き込みを行わないため、ここで例外にすると件数を確かめられない）。
        if (snapshots == 0 || pastScoreRows == 0) {
            String reason = "version=" + fromVersion + " の退避が未完了（スナップショット " + snapshots +
                    " 件 / past_scores " + pastScoreRows + " 行）";
            if (!dryRun) {
                throw new IllegalStateException(reason + "。初期化すると前作の記録が永久に失われるため中断する");
            }
            log.warn("[世代切替] スコア初期化 dry-run: {}。本番実行はこの状態では中断される", reason);
        }

        Long scoreRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scores", Long.class);
        Long rankRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_song_ranks", Long.class);
        Long statRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_comparison_stats", Long.class);
        Long historyUsers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM score_history_logs) t", Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scoreRows", scoreRows == null ? 0L : scoreRows);
        result.put("userSongRankRows", rankRows == null ? 0L : rankRows);
        result.put("userComparisonStatRows", statRows == null ? 0L : statRows);
        result.put("resetLogRows", historyUsers == null ? 0L : historyUsers);

        if (dryRun) {
            log.info("[世代切替] スコア初期化 dry-run: scores {} 行 / user_song_ranks {} 行 / " +
                            "user_comparison_stats {} 行を削除し、{} 人へリセット行を追加する予定",
                    result.get("scoreRows"), result.get("userSongRankRows"),
                    result.get("userComparisonStatRows"), result.get("resetLogRows"));
            return result;
        }

        // 履歴のリセット行を先に入れる。scores を消す前でも後でも値は 0 固定なので結果は変わらないが、
        // 「順位表が前作の PT を出したまま」の時間を最小にするため先頭に置く。
        jdbcTemplate.update(
                "INSERT INTO score_history_logs " +
                "  (user_id, uploaded_at, tag, total_score, fc_count, exh_count, h_count, clear_count, easy_count, " +
                "   aaa_count, aa_count, a_count, total_beat_pt, beat_pt_increase, updated_count, " +
                "   total_precision_pt, total_rate_pt, total_kenban_pt, total_sara_pt) " +
                "SELECT DISTINCT user_id, now(), ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 " +
                "FROM score_history_logs",
                RESET_LOG_TAG);

        jdbcTemplate.update("DELETE FROM scores");
        jdbcTemplate.update("DELETE FROM user_song_ranks");
        jdbcTemplate.update("DELETE FROM user_comparison_stats");
        jdbcTemplate.update(
                "UPDATE users SET total_beat_pt = 0, total_kenban_pt = 0, total_sara_pt = 0, " +
                "       total_average_rank = NULL, total_average_rank_played = NULL, " +
                "       last_uploaded_at = NULL, ranking_includes_infinitas = false");

        log.warn("[世代切替] スコア初期化完了: scores {} 行 / user_song_ranks {} 行 / user_comparison_stats {} 行を削除し、" +
                        "{} 人へリセット行を追加した",
                result.get("scoreRows"), result.get("userSongRankRows"),
                result.get("userComparisonStatRows"), result.get("resetLogRows"));
        return result;
    }

    /**
     * 【メソッドの役割】 楽曲単位の集計キャッシュを作り直す。
     *
     * {@link #resetCurrentScores} は {@code scores} を空にするが、曲別ランキング・平均スコアレート・
     * アリーナ平均はメモリ上のキャッシュを返すため、作り直さないと消えたはずのスコアが表示され続ける。
     *
     * <b>トランザクションの外から呼ぶこと。</b> キャッシュは DB を読み直して自分を組み立てるので、
     * 初期化がコミットされる前に呼ぶと古い内容で温め直してしまう。
     */
    public void refreshSongCaches() {
        songRankingAggregateCache.refresh();
        songAvgScoreRatesCache.refresh();
        songArenaAveragesCache.refresh();
        log.info("[世代切替] 楽曲集計キャッシュを作り直した（曲別ランキング / 平均スコアレート / アリーナ平均）");
    }

    /**
     * 【メソッドの役割】 現状を数値で返す。管理画面と dry-run ログの共通データ源。
     *
     * @param version 対象の作品バージョン
     * @return スナップショット件数・過去作スコア件数・現行スコア件数・派生データの残量など
     */
    public Map<String, Object> describe(int version) {
        Long snapshots = snapshotRepository.countByVersion(version);
        Long pastScores = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM past_scores WHERE version = ?", Long.class, version);
        Long scores = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scores", Long.class);
        Long users = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM score_history_logs) t", Long.class);
        // 初期化（手順 4）が効いたかを管理画面から確かめられるよう、派生データの残量も返す。
        Long songRanks = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_song_ranks", Long.class);
        Long comparisonStats = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_comparison_stats", Long.class);
        Long resetLogs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM score_history_logs WHERE tag = ?", Long.class, RESET_LOG_TAG);

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("version", version);
        counts.put("snapshotRows", snapshots == null ? 0L : snapshots);
        counts.put("pastScoreRows", pastScores == null ? 0L : pastScores);
        counts.put("currentScoreRows", scores == null ? 0L : scores);
        counts.put("usersWithHistory", users == null ? 0L : users);
        counts.put("userSongRankRows", songRanks == null ? 0L : songRanks);
        counts.put("userComparisonStatRows", comparisonStats == null ? 0L : comparisonStats);
        counts.put("resetLogRows", resetLogs == null ? 0L : resetLogs);
        counts.put("hasDraftDifficultyTable", gameDataService.hasDraftDifficultyTable());
        return counts;
    }
}
