package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.PastScore;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.VersionPtSnapshot;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.PastScoreRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 【Service の役割】 過去作ランキング（{@code version_pt_snapshots}）を、切り替え後に取り込まれた
 * 前作の CSV（{@code past_scores}）で更新する。
 *
 * 現実世界の概念: 新作稼働日の 07:00 に前作の最終 PT を焼き付けても、その後に「前作の最後のプレー分を
 * まだ取り込んでいなかった」利用者が前作の CSV を歴代スコアとして入れてくる。前作ランキングは
 * その人の本当の到達点を映すべきなので、past_scores から前作の 2 指標
 * （BEAT / RATE）を計算し直し、スナップショットの本人の行に反映する。
 *
 * 設計上の判断:
 *  - <b>難易度表は「切り替え時点で凍結した表」（revision {@code archive:<version>}）を使う。</b>
 *    新作初日に難易度表の大改訂（第6版）を適用したため、現行の active で計算すると
 *    スナップショットの値（旧表で計算）と別の物差しになってしまう。凍結表で計算すると、
 *    追加取り込みが無い人はスナップショットと 1 ビットも違わない値が再現できる
 *    （2026-09-16 に本番 56 人で検証: 54 人一致、残り 2 人は INFINITAS 由来）。
 *    凍結表は {@link VersionTransitionService#freezeDifficultyTable} が世代切替時に作る。
 *    無ければ active で代用する（警告ログ）。
 *  - <b>更新は「指標ごとに上回ったときだけ」。</b> past_scores はアーケードの記録だけを持つので、
 *    INFINITAS の記録込みで撮ったスナップショットより低く出ることがある。過去作の到達点が
 *    取り込みで下がるのは利用者の期待に反するため、下がる方向には書かない。
 *  - <b>スナップショットが無い人（切り替え後に登録した新規ユーザー）は行を新規作成する。</b>
 *    前作の記録があるのに前作ランキングに載らない、という不整合を作らないため。
 *  - 順位列（beat_rank / rate_rank）は更新のたびに振り直す（1,000 行程度の window 関数 2 本で軽い）。
 *  - {@link PreviousVersionPtService} のメモリキャッシュ（ティアアイコンの外枠用）は更新後に捨てる。
 *
 * 不変条件: このサービスは現行作の集計（scores / score_history_logs / users.total_beat_pt）には触れない。
 */
@Service
public class ArchivedVersionPtService {

    private static final Logger log = LoggerFactory.getLogger(ArchivedVersionPtService.class);

    /** 世代切替時に凍結した難易度表の revision 接頭辞（{@code archive:33} など）。 */
    public static final String ARCHIVE_REVISION_PREFIX = "archive:";

    private final PastScoreRepository pastScoreRepository;
    private final VersionPtSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final ScoreRecalculationService recalcService;
    private final DifficultyRankRepository difficultyRankRepository;
    private final PreviousVersionPtService previousVersionPtService;
    private final JdbcTemplate jdbcTemplate;

    public ArchivedVersionPtService(PastScoreRepository pastScoreRepository,
                                    VersionPtSnapshotRepository snapshotRepository,
                                    UserRepository userRepository,
                                    ScoreRecalculationService recalcService,
                                    DifficultyRankRepository difficultyRankRepository,
                                    PreviousVersionPtService previousVersionPtService,
                                    JdbcTemplate jdbcTemplate) {
        this.pastScoreRepository = pastScoreRepository;
        this.snapshotRepository = snapshotRepository;
        this.userRepository = userRepository;
        this.recalcService = recalcService;
        this.difficultyRankRepository = difficultyRankRepository;
        this.previousVersionPtService = previousVersionPtService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 【メソッドの役割】 作品バージョン → 凍結した難易度表の revision 名。 */
    public static String archiveRevision(int version) {
        return ARCHIVE_REVISION_PREFIX + version;
    }

    /**
     * 【メソッドの役割】 その作品にランキングのアーカイブ（スナップショット）があるか。
     * 無い作品（32 以前）の CSV を取り込んでもランキングは動かさない。
     */
    public boolean isArchived(int version) {
        return snapshotRepository.countByVersion(version) > 0;
    }

    /**
     * 計算に要るマスタをまとめたもの。1 回のバッチで使い回す。
     *
     * @param version            作品バージョン
     * @param difficultyRevision 実際に使った難易度表の revision（凍結表が無ければ "active"）
     * @param songMaxScores      title_difficultyCode → 理論値（notes×2）
     * @param informalRanks      title_diffName → 非公式ランク
     */
    public record Context(int version,
                          String difficultyRevision,
                          Map<String, Integer> songMaxScores,
                          Map<String, String> informalRanks) {
    }

    /**
     * 1 ユーザーぶんの更新結果。{@code before} / {@code after} は [BEAT, RATE]。
     *
     * @param created スナップショット行を新規作成した
     * @param changed いずれかの指標を書き換えた（dry-run では「書き換える予定」）
     */
    public record RefreshResult(Long userId, boolean created, boolean changed, double[] before, double[] after) {

        /** API 応答・ログ用の Map 表現。 */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", userId);
            m.put("created", created);
            m.put("changed", changed);
            m.put("beforeBeatPt", before[0]);
            m.put("afterBeatPt", after[0]);
            m.put("beforeRatePt", before[1]);
            m.put("afterRatePt", after[1]);
            return m;
        }
    }

    /**
     * 【メソッドの役割】 指定作品の計算マスタを読み込む。
     *
     * 難易度表は凍結表（{@code archive:<version>}）を優先し、無ければ active で代用する。
     */
    public Context loadContext(int version) {
        String revision = archiveRevision(version);
        if (difficultyRankRepository.countByRevision(revision) == 0) {
            log.warn("[前作PT] 凍結した難易度表 {} が無いため active で代用する（切替後の改訂が混ざるとスナップショットと物差しがずれる）", revision);
            revision = "active";
        }
        return new Context(version, revision,
                recalcService.loadSongMaxScores(),
                recalcService.loadInformalRanks(revision));
    }

    /**
     * 【メソッドの役割】 1 ユーザーの前作 PT を past_scores から計算し直してスナップショットへ反映する
     * （マスタの読み込み込み。単発の呼び出し向け）。
     *
     * @param user    対象ユーザー
     * @param version 作品バージョン（例: 33）
     * @param dryRun  true なら DB を変更しない
     * @return 結果。past_scores にその作品の行が無ければ空
     */
    @Transactional
    public Optional<RefreshResult> refreshUser(User user, int version, boolean dryRun) {
        return refreshUser(user, pastScoreRepository.findByUserAndVersion(user, version), loadContext(version), dryRun);
    }

    /**
     * 【メソッドの役割】 1 ユーザーの前作 PT を、渡された past_scores の行から計算し直してスナップショットへ反映する。
     *
     * 呼び出し側のトランザクションに参加する（取り込み API の同一トランザクション内で呼ぶ想定）。
     * 順位の振り直しとキャッシュ破棄は行わないので、変更があれば {@link #finalizeVersion(int)} を呼ぶこと。
     *
     * @param user   対象ユーザー
     * @param rows   その作品の past_scores（取り込み直後のメモリ上の行でよい）
     * @param ctx    {@link #loadContext(int)} の戻り
     * @param dryRun true なら DB を変更しない
     * @return 結果。行が無い、または新規作成しても全指標 0 になる場合は空
     */
    public Optional<RefreshResult> refreshUser(User user, Collection<PastScore> rows, Context ctx, boolean dryRun) {
        if (user == null || user.getId() == null || rows == null || rows.isEmpty()) return Optional.empty();

        double[] computed = compute(rows, ctx);
        Optional<VersionPtSnapshot> existing = snapshotRepository.findByVersionAndUserId(ctx.version(), user.getId());

        if (existing.isEmpty()) {
            boolean allZero = computed[0] <= 0 && computed[1] <= 0;
            if (allZero) return Optional.empty();
            if (!dryRun) {
                VersionPtSnapshot s = new VersionPtSnapshot();
                s.setVersion(ctx.version());
                s.setUserId(user.getId());
                s.setIidxId(user.getIidxId());
                s.setDisplayName(user.getDisplayName());
                s.setPrivacyLevel(user.getPrivacyLevel());
                s.setTotalBeatPt(computed[0]);
                s.setTotalRatePt(computed[1]);
                s.setLastUploadedAt(LocalDateTime.now());
                s.setCapturedAt(LocalDateTime.now());
                // 順位の振り直し（JDBC）が新しい値を見られるよう、ここで DB に流す。
                snapshotRepository.saveAndFlush(s);
                log.info("[前作PT] version={} user={} のスナップショットを新規作成: BEAT {} / RATE {}（難易度表 {}）",
                        ctx.version(), user.getId(), computed[0], computed[1], ctx.difficultyRevision());
            }
            return Optional.of(new RefreshResult(user.getId(), true, true, new double[2], computed));
        }

        VersionPtSnapshot s = existing.get();
        double[] before = { nz(s.getTotalBeatPt()), nz(s.getTotalRatePt()) };
        double[] after = new double[2];
        boolean changed = false;
        for (int i = 0; i < 2; i++) {
            // 指標ごとに「上回ったときだけ」置き換える（INFINITAS 込みで撮った値を下げない）。
            after[i] = Math.max(before[i], computed[i]);
            if (after[i] > before[i] + 1e-9) changed = true;
        }
        if (changed && !dryRun) {
            s.setTotalBeatPt(after[0]);
            s.setTotalRatePt(after[1]);
            s.setLastUploadedAt(LocalDateTime.now());
            // JPA の保留 UPDATE は既定ではコミット時まで流れないため、続く順位の振り直し（JdbcTemplate）が
            // 古い値で順位を付けてしまう。ここで明示的に flush する。
            snapshotRepository.saveAndFlush(s);
            log.info("[前作PT] version={} user={} を更新: BEAT {}→{} / RATE {}→{}（難易度表 {}）",
                    ctx.version(), user.getId(), before[0], after[0], before[1], after[1],
                    ctx.difficultyRevision());
        }
        return Optional.of(new RefreshResult(user.getId(), false, changed, before, after));
    }

    /**
     * 【メソッドの役割】 更新の後始末。順位列を振り直し、前作 PT のメモリキャッシュを捨てる。
     * 1 人の更新でも全体の順位が動くので、変更があった呼び出しごとに 1 回呼ぶ。
     */
    public void finalizeVersion(int version) {
        assignRanks(version);
        previousVersionPtService.invalidate();
    }

    /**
     * 【メソッドの役割】 指定作品のスナップショットに順位（beat_rank / rate_rank）を振り直す。
     * {@link VersionTransitionService#captureSnapshot} と同じ規則（RANK() で同値は同順位、RATE は > 0 のみ）。
     */
    public void assignRanks(int version) {
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
    }

    /**
     * 【メソッドの役割】 「指定日時より後に past_scores へ取り込んだ人」を全員まとめて計算し直す（管理用バッチ）。
     *
     * 世代切替の複製（{@link VersionTransitionService#copyScoresToPastScores}）は全行が同じ imported_at を持つので、
     * {@code since} を省略すると「その作品の最古の imported_at + 1 秒」（＝複製より後の取り込み）を使う。
     *
     * @param version 作品バージョン
     * @param since   この日時より後に取り込んだ人が対象（サーバー時刻。null なら上記の既定）
     * @param userId  指定すればその 1 人だけ（since は無視）
     * @param dryRun  true なら DB を変更せず、変更予定だけを返す
     * @return 対象人数・更新人数と 1 人ずつの before/after
     */
    @Transactional
    public Map<String, Object> refreshUploadedSince(int version, LocalDateTime since, Long userId, boolean dryRun) {
        List<Long> userIds;
        if (userId != null) {
            userIds = List.of(userId);
        } else {
            if (since == null) {
                since = jdbcTemplate.queryForObject(
                        "SELECT MIN(imported_at) + INTERVAL '1 second' FROM past_scores WHERE version = ?",
                        LocalDateTime.class, version);
            }
            if (since == null) {
                return Map.of("version", version, "candidates", 0, "message", "past_scores に該当作品の行が無い");
            }
            userIds = jdbcTemplate.queryForList(
                    "SELECT DISTINCT user_id FROM past_scores WHERE version = ? AND imported_at > ? ORDER BY user_id",
                    Long.class, version, since);
        }

        Context ctx = loadContext(version);
        List<Map<String, Object>> results = new ArrayList<>();
        int updated = 0;
        int created = 0;
        for (Long id : userIds) {
            User user = userRepository.findById(id).orElse(null);
            if (user == null) continue;
            Optional<RefreshResult> r = refreshUser(user, pastScoreRepository.findByUserAndVersion(user, version), ctx, dryRun);
            if (r.isEmpty()) continue;
            Map<String, Object> row = r.get().toMap();
            row.put("displayName", user.getDisplayName());
            results.add(row);
            if (r.get().created()) created++;
            else if (r.get().changed()) updated++;
        }
        if (!dryRun && (updated + created) > 0) {
            finalizeVersion(version);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", version);
        out.put("difficultyRevision", ctx.difficultyRevision());
        out.put("since", since);
        out.put("dryRun", dryRun);
        out.put("candidates", userIds.size());
        out.put("updated", updated);
        out.put("created", created);
        out.put("results", results);
        log.info("[前作PT] version={} 一括更新{}: 対象 {} 人 / 更新 {} 人 / 新規 {} 人（難易度表 {}）",
                version, dryRun ? "(dry-run)" : "", userIds.size(), updated, created, ctx.difficultyRevision());
        return out;
    }

    // ── 内部ヘルパー ──────────────────────────────────────

    /** past_scores の行から [BEAT, RATE] を計算する。 */
    private double[] compute(Collection<PastScore> rows, Context ctx) {
        List<Score> scores = new ArrayList<>(rows.size());
        for (PastScore p : rows) scores.add(toScore(p));
        return recalcService.calculatePtTotals(scores, ctx.songMaxScores(), ctx.informalRanks());
    }

    /**
     * 計算に要る項目だけを {@link Score} に写す（永続化しない一時オブジェクト）。
     * 既存の集計ロジック（{@link ScoreRecalculationService}）が {@link Score} を受け取る作りなのでそれに合わせる。
     */
    private static Score toScore(PastScore p) {
        Score s = new Score();
        s.setTitle(p.getTitle());
        s.setDifficultyName(p.getDifficultyName());
        s.setDifficultyLevel(p.getDifficultyLevel());
        s.setScore(p.getScore());
        s.setClearType(p.getClearType());
        s.setDjLevel(p.getDjLevel());
        s.setSource("arcade");
        return s;
    }

    private static double nz(Double d) {
        return d == null ? 0.0 : d;
    }
}
