package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 【Service の役割】 新作稼働にともなう「作品の世代切り替え」の各手順を実装する。
 *
 * 現実世界の概念: IIDX は新作稼働のたびにスコアがリセットされる。beat-seeker でも初日に
 * 前作のスコアを過去作へ移し、BEAT-PT / RATE-PT を積み直す。その一連の作業のうち、
 * <b>機械的に実行してよい部分</b>をここに実装する。起動そのものは
 * {@link VersionTransitionScheduler} が担当する。
 *
 * ここに実装してある手順:
 *  1. {@link #captureSnapshot} … 前作の最終 PT を {@code version_pt_snapshots} へ焼き付ける（追記のみ）
 *  1b. {@link #freezeDifficultyTable} … 公開中の難易度表を前作の「終了時点の表」として凍結する（追記のみ）
 *  2. {@link #copyScoresToPastScores} … {@code scores} を {@code past_scores} へ複製する（追記のみ）
 *  3. {@link #resetCurrentVersionData} … 現行作のスコアと派生値を初期化する（<b>破壊的</b>。1・2 の後にだけ呼ぶ）
 *  4. {@link #applyDifficultyDraft} … 難易度表 draft を active へ適用する
 *
 * 実行順序の不変条件: <b>1 → 2 → 3</b> の順を必ず守る。1 を撮る前に 3 を行うと前作の順位が
 *   永久に失われ、2 の前に 3 を行うと前作のスコアが失われる。
 *   {@link VersionTransitionScheduler} 側でも前段の成否を見て順序を守る。
 *
 * 冪等性: 1 と 2 は既存行があれば何もしない（{@code ON CONFLICT DO NOTHING}）ため、
 *   二重に走っても壊れない。3 も「0PT 行が既にあるユーザーには入れない」ガード付き。
 *
 * ■ statement_timeout について
 * 本番は Hikari の init SQL で全接続に {@code statement_timeout = 30s} が掛かる（application.yml）。
 * 2 の複製は 100 万行規模の INSERT で 30 秒では足りないため、各手順のトランザクション冒頭で
 * {@code SET LOCAL statement_timeout} を延ばす（トランザクション終了で元に戻る）。
 * H2（ローカル）はこの文を解釈できないので失敗は無視する。
 */
@Service
public class VersionTransitionService {

    private static final Logger log = LoggerFactory.getLogger(VersionTransitionService.class);

    /** 初期化で入れる 0PT 履歴行の tag。成長記録・統計で「世代切り替えの区切り」として見分けるため。 */
    public static final String RESET_TAG = "version-transition";

    private final JdbcTemplate jdbcTemplate;
    private final VersionPtSnapshotRepository snapshotRepository;
    private final GameDataService gameDataService;
    private final DifficultyRankRepository difficultyRankRepository;

    public VersionTransitionService(JdbcTemplate jdbcTemplate,
                                    VersionPtSnapshotRepository snapshotRepository,
                                    GameDataService gameDataService,
                                    DifficultyRankRepository difficultyRankRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.snapshotRepository = snapshotRepository;
        this.gameDataService = gameDataService;
        this.difficultyRankRepository = difficultyRankRepository;
    }

    /**
     * 【メソッドの役割】 前作の最終 PT を全ユーザーぶん {@code version_pt_snapshots} へ焼き付ける。
     *
     * 集計元は {@code score_history_logs} の<b>ユーザーごとの最新行</b>。現行のランキング
     * （{@code ScoreHistoryLogRepository#getGlobalRanking}）と同じ取り方にしてあるので、
     * 撮影直前に画面で見えている順位がそのまま保存される。
     *
     * 注意: {@link #resetCurrentVersionData} が入れる 0PT 行はここで拾ってはいけない
     * （最新行が 0 になってしまう）ので、初期化行（tag）は除外する。順序を守っていれば無関係だが、
     * 万一の再実行に備えた保険。
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
        extendStatementTimeout();

        Integer candidates = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM score_history_logs " +
                "  WHERE tag IS NULL OR tag <> '" + RESET_TAG + "') t", Integer.class);
        int expected = candidates == null ? 0 : candidates;

        if (dryRun) {
            log.info("[世代切替] スナップショット dry-run: version={} 対象 {} 人", version, expected);
            return expected;
        }

        int inserted = jdbcTemplate.update(
                "INSERT INTO version_pt_snapshots " +
                "  (version, user_id, iidx_id, display_name, total_beat_pt, total_rate_pt, " +
                "   privacy_level, last_uploaded_at, captured_at) " +
                "SELECT ?, l.user_id, u.iidx_id, u.display_name, l.total_beat_pt, l.total_rate_pt, " +
                "       u.privacy_level, l.uploaded_at, now() " +
                "FROM ( " +
                "  SELECT DISTINCT ON (user_id) user_id, total_beat_pt, total_rate_pt, uploaded_at " +
                "  FROM score_history_logs " +
                "  WHERE tag IS NULL OR tag <> '" + RESET_TAG + "' " +
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
     * 【メソッドの役割】 公開中（active）の難易度表を、前作の「終了時点の表」として凍結する。
     *
     * revision {@code archive:<version>}（{@link ArchivedVersionPtService#archiveRevision}）へ帯と曲を複製する。
     * 追記のみで、既に同じ revision があれば何もしない（冪等）。
     *
     * なぜ要るか: 新作初日には難易度表の大改訂を適用するが、前作ランキング（スナップショット）は
     * 旧表で計算した値でできている。切り替え後に前作の CSV を取り込んだ人の値を計算し直すとき、
     * 現行の表を使うと物差しが変わってしまう。スナップショットと同じ瞬間の表を残しておけば、
     * 追加取り込みの無い人はスナップショットと同じ値が再現できる（{@link ArchivedVersionPtService}）。
     *
     * {@code archive:} 接頭辞は管理画面のプロファイル一覧（{@code profile:} 接頭辞）に出ないので、
     * 画面操作で誤って消される心配がない。
     *
     * @param version 凍結する作品バージョン（例: 33。＝この表で集計されていた作品）
     * @param dryRun  true なら DB を変更せず、複製する帯数だけを返す
     * @return 複製した（dry-run 時は複製する予定の）帯数。既に凍結済みなら 0
     */
    @Transactional
    public int freezeDifficultyTable(int version, boolean dryRun) {
        String revision = ArchivedVersionPtService.archiveRevision(version);
        if (difficultyRankRepository.countByRevision(revision) > 0) {
            log.info("[世代切替] 難易度表の凍結: {} は既にあるため何もしない", revision);
            return 0;
        }
        List<DifficultyRank> active = difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active");
        if (dryRun) {
            log.info("[世代切替] 難易度表の凍結 dry-run: active {} 帯を {} へ複製する予定", active.size(), revision);
            return active.size();
        }
        int songs = 0;
        for (DifficultyRank src : active) {
            DifficultyRank copy = new DifficultyRank();
            copy.setRankValue(src.getRankValue());
            copy.setSortOrder(src.getSortOrder());
            copy.setRevision(revision);
            for (DifficultyRankSong s : src.getSongs()) {
                DifficultyRankSong cs = new DifficultyRankSong();
                cs.setDifficultyRank(copy);
                cs.setSongTitle(s.getSongTitle());
                cs.setSortOrder(s.getSortOrder());
                copy.getSongs().add(cs);
                songs++;
            }
            difficultyRankRepository.save(copy);
        }
        log.info("[世代切替] 難易度表の凍結完了: active {} 帯 / {} 曲を {} へ複製", active.size(), songs, revision);
        return active.size();
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
     *  - <b>INFINITAS の記録（source = "infinitas"）は移さない。</b> {@code past_scores} は source 列を
     *    持たず、リーグの有効ライン（{@code app.league.baseline-includes-past}）や課題曲選定など
     *    「アーケード記録のみ」の用途から参照されるため、混ぜると INFINITAS のベストがラインになってしまう。
     *  - {@code past_scores.last_played_at} は文字列カラム（"YYYY-MM-DD HH24:MI" 書式）なので
     *    {@code to_char} で変換する。{@code scores} 側は timestamp 型。
     *
     * @param version 移送先の作品バージョン（例: 33）
     * @param dryRun  true なら DB を変更せず、対象行数だけを返す
     * @return 書き込んだ（dry-run 時は書き込む予定の）行数
     */
    @Transactional
    public int copyScoresToPastScores(int version, boolean dryRun) {
        extendStatementTimeout();

        String selectSql =
                "SELECT DISTINCT ON (s.user_id, s.title, s.difficulty_name) " +
                "       s.user_id, s.title, s.artist, s.genre, s.difficulty_name, s.difficulty_level, " +
                "       s.score, s.clear_type, s.dj_level, s.pgreat, s.great, s.miss_count, s.play_count, " +
                "       to_char(s.last_played_at, 'YYYY-MM-DD HH24:MI') AS last_played_at " +
                "FROM scores s " +
                "WHERE s.score > 0 AND (s.source IS NULL OR s.source = 'arcade') " +
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

    /** 【メソッドの役割】 指定作品の {@code version_pt_snapshots} 行数（スナップショットが撮れているかの確認用）。 */
    public long countSnapshots(int version) {
        return snapshotRepository.countByVersion(version);
    }

    /** 【メソッドの役割】 指定作品の {@code past_scores} 行数（複製が揃っているかの確認用）。 */
    public long countPastScores(int version) {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM past_scores WHERE version = ?", Long.class, version);
        return n == null ? 0 : n;
    }

    /**
     * 【メソッドの役割】 現行作のスコアと派生値を初期化し、新作の起点を作る。<b>破壊的</b>。
     *
     * 必ず {@link #captureSnapshot} と {@link #copyScoresToPastScores} が完了した後に呼ぶこと
     * （呼び出し側 {@link VersionTransitionScheduler} が記録を見て順序を守る）。
     *
     * やること（1 トランザクション）:
     *  1. {@code score_history_logs.version} が null の行を前作の番号で埋める（成長記録の作品切り替え用）
     *  2. {@code scores} / {@code user_song_ranks} を全削除（TRUNCATE。どちらも FK 参照なし）
     *     → アップロードは「ベスト更新のみ書き換える」upsert なので、前作のスコアを残したまま
     *       新作の CSV を入れると低い記録が全部無視される。空にしておかないと新作の取り込みが成立しない。
     *  3. {@code users} の PT キャッシュ（total_beat_pt / average_rank）を 0 に戻す
     *  4. 履歴を持つ全ユーザーに「0PT の履歴行」を 1 本入れる（version = 新作、tag = {@link #RESET_TAG}）
     *     → ランキングは履歴の最新行を見るため、この行が無いと前作の PT がランキングに残り続ける。
     *       各ランキング SQL は「最新行の値が 0 なら除外」なので、新作で 1 度もアップロードしていない
     *       ユーザーはランキングから消え、アップロードした人から順に並ぶ。
     *       成長記録ページ（updated_count = 0 かつ PT 変動なし）ではこの行は表示されない。
     *
     * やらないこと: {@code past_scores} / {@code version_pt_snapshots} / リーグ・大会・タイムライン・
     *   曲別オプション・リザルト画像などは触らない。
     *
     * @param fromVersion 前作（履歴のバックフィルに使う。例: 33）
     * @param toVersion   新作（0PT 行の version。例: 34）
     * @param dryRun      true なら DB を変更せず、対象件数のログだけ出す
     * @return 各件数の要約文字列（実行記録に残す）
     */
    @Transactional
    public String resetCurrentVersionData(int fromVersion, int toVersion, boolean dryRun) {
        extendStatementTimeout();

        long scoreRows = count("SELECT COUNT(*) FROM scores");
        long rankRows = count("SELECT COUNT(*) FROM user_song_ranks");
        long historyUsers = count("SELECT COUNT(*) FROM (SELECT DISTINCT user_id FROM score_history_logs) t");
        long nullVersionRows = count("SELECT COUNT(*) FROM score_history_logs WHERE version IS NULL");

        if (dryRun) {
            String summary = String.format("dry-run: scores %d 行 / user_song_ranks %d 行 / 履歴ユーザー %d 人 / version 未設定の履歴 %d 行",
                    scoreRows, rankRows, historyUsers, nullVersionRows);
            log.info("[世代切替] 初期化 {}", summary);
            return summary;
        }

        int backfilled = jdbcTemplate.update(
                "UPDATE score_history_logs SET version = ? WHERE version IS NULL", fromVersion);

        jdbcTemplate.execute("TRUNCATE TABLE scores");
        jdbcTemplate.execute("TRUNCATE TABLE user_song_ranks");

        int usersReset = jdbcTemplate.update(
                "UPDATE users SET total_beat_pt = 0, " +
                "                 total_average_rank = NULL, total_average_rank_played = 0");

        int zeroRows = jdbcTemplate.update(
                "INSERT INTO score_history_logs " +
                "  (user_id, uploaded_at, version, tag, total_score, fc_count, exh_count, h_count, clear_count, " +
                "   easy_count, aaa_count, aa_count, a_count, total_beat_pt, beat_pt_increase, updated_count, " +
                "   total_precision_pt, total_rate_pt, diff_json) " +
                "SELECT DISTINCT h.user_id, ?, ?, ?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, '[]' " +
                "FROM score_history_logs h " +
                "WHERE NOT EXISTS (SELECT 1 FROM score_history_logs z " +
                "                  WHERE z.user_id = h.user_id AND z.tag = ? AND z.version = ?)",
                LocalDateTime.now(), toVersion, RESET_TAG, RESET_TAG, toVersion);

        String summary = String.format(
                "scores %d 行・user_song_ranks %d 行を削除 / users %d 件の PT を 0 に / 履歴 version バックフィル %d 行 / 0PT 行 %d 件（履歴ユーザー %d 人）",
                scoreRows, rankRows, usersReset, backfilled, zeroRows, historyUsers);
        log.warn("[世代切替] 初期化完了: {}", summary);
        return summary;
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
        Long resetRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM score_history_logs WHERE tag = ?", Long.class, RESET_TAG);
        return Map.of(
                "version", version,
                "currentVersion", IidxVersions.current(),
                "switchAt", IidxVersions.switchAt().toString(),
                "snapshotRows", snapshots == null ? 0 : snapshots,
                "pastScoreRows", pastScores == null ? 0 : pastScores,
                "currentScoreRows", scores == null ? 0 : scores,
                "usersWithHistory", users == null ? 0 : users,
                "resetHistoryRows", resetRows == null ? 0 : resetRows,
                "hasDraftDifficultyTable", gameDataService.hasDraftDifficultyTable()
        );
    }

    // ── 内部ヘルパー ──────────────────────────────────────

    /**
     * 現在のトランザクションに限って statement_timeout を延ばす。
     * 本番（PostgreSQL）では Hikari init SQL の 30 秒を上書きする。H2 は構文を解釈できないので失敗は無視。
     */
    private void extendStatementTimeout() {
        try {
            jdbcTemplate.execute("SET LOCAL statement_timeout = '900s'");
        } catch (Exception e) {
            log.debug("[世代切替] SET LOCAL statement_timeout を適用できない（H2 など）: {}", e.getMessage());
        }
    }

    private long count(String sql) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
