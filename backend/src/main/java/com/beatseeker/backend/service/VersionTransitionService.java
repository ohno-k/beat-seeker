package com.beatseeker.backend.service;

import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 【Service の役割】 新作稼働にともなう「作品の世代切り替え」の各手順を実装する。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされる。beat-seeker でも初日に
 * 前作のスコアを過去作へ移し、BEAT-PT / RATE-PT を積み直す。その一連の作業のうち、
 * <b>機械的に実行してよい部分</b>をここに実装する。起動そのものは
 * {@link VersionTransitionScheduler} が担当する。
 *
 * ここに実装してある手順（いずれも追記のみ・非破壊）:
 *  1. {@link #captureSnapshot} … 前作の最終 PT を {@code version_pt_snapshots} へ焼き付ける
 *  2. {@link #copyScoresToPastScores} … {@code scores} を {@code past_scores} へ複製する
 *  3. {@link #applyDifficultyDraft} … 難易度表 draft を active へ適用する
 *
 * <b>ここに実装していない手順（意図的）</b>:
 *  4. スコアの初期化（{@code scores} の削除と各種派生データのリセット）。
 *     これは取り返しがつかず、かつ {@code users.total_beat_pt} / {@code user_song_ranks} /
 *     各種キャッシュなど波及先が広い。設計を確定させるまでは自動実行させない。
 *     {@link VersionTransitionScheduler} は手順 3 まで終えたらそこで止まり、
 *     残りが手作業であることをログに残す。
 *
 * 実行順序の不変条件: <b>1 → 2 → 4</b> の順を必ず守る。1 を撮る前に 4 を行うと前作の順位が
 *   永久に失われる。{@link VersionTransitionScheduler} 側でも前段の成否を見て順序を守る。
 *
 * 冪等性: 1 と 2 は既存行があれば何もしない（{@code ON CONFLICT DO NOTHING}）ため、
 *   二重に走っても壊れない。
 */
@Service
public class VersionTransitionService {

    private static final Logger log = LoggerFactory.getLogger(VersionTransitionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final VersionPtSnapshotRepository snapshotRepository;
    private final GameDataService gameDataService;

    public VersionTransitionService(JdbcTemplate jdbcTemplate,
                                    VersionPtSnapshotRepository snapshotRepository,
                                    GameDataService gameDataService) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
        this.gameDataService = gameDataService;
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
     * 【メソッドの役割】 現状を数値で返す。管理画面と dry-run ログの共通データ源。
     *
     * @param version 対象の作品バージョン
     * @return スナップショット件数・過去作スコア件数・現行スコア件数など
     */
    public Map<String, Object> describe(int version) {
        Long snapshots = snapshotRepository.countByVersion(version);
        Long pastScores = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM past_scores WHERE version = ?", Long.class, version);
        Long scores = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scores", Long.class);
        Long users = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM score_history_logs) t", Long.class);
        return Map.of(
                "version", version,
                "snapshotRows", snapshots == null ? 0 : snapshots,
                "pastScoreRows", pastScores == null ? 0 : pastScores,
                "currentScoreRows", scores == null ? 0 : scores,
                "usersWithHistory", users == null ? 0 : users,
                "hasDraftDifficultyTable", gameDataService.hasDraftDifficultyTable()
        );
    }
}
