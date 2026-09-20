package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.VersionPtSnapshot;
import com.beatseeker.backend.repository.LeagueEntryRepository;
import com.beatseeker.backend.repository.PastScoreRepository;
import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import com.beatseeker.backend.util.JstTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 【Service の役割】 リーグモードのプレイヤー操作（参加・離脱・自分の状態取得）を担うサービス。
 *
 * 責務:
 *  - エントリー（{@link LeagueEntry}）の作成・復帰・休止
 *  - 参加は「次週編成から反映」の方針のため、ここでは進行中の週には一切触れない
 *    （週次編成は {@link LeagueWeekLifecycleService} の責務）
 */
@Service
public class LeagueService {

    private static final Logger log = LoggerFactory.getLogger(LeagueService.class);

    /**
     * 有効なラダー種別。現在はスコアリーグ（3曲平均スコアレート）のみ運用する。
     * （BP リーグは廃止。将来復活させる場合は "bp" を LADDERS に戻し、フロントのタブ・
     *  LeagueStandingsService の BP 分岐・i18n を復元すればよい。）
     */
    public static final String LADDER_SCORE = "score";
    public static final List<String> LADDERS = List.of(LADDER_SCORE);

    /** エントリーの永続化リポジトリ。 */
    private final LeagueEntryRepository leagueEntryRepository;

    /** 過去作の最終 PT アーカイブ。初回参加時の階級判定で「歴代最高」を参照するために使う。 */
    private final VersionPtSnapshotRepository versionPtSnapshotRepository;

    /** 過去作スコア。参加ゲート（{@link #joinBlockedReason}）で「前作までの記録があるか」を見るために使う。 */
    private final PastScoreRepository pastScoreRepository;

    /**
     * 【一時措置】過去作スコア（{@code past_scores}）が 1 件も無いユーザーの参加を受け付けないか。
     *
     * 新作稼働直後はリーグの有効ライン（週開始時点の自己ベスト）を過去作の記録から取る
     * （{@code app.league.baseline-includes-past}）。現行作（ZINRAI）のスコアしか無い人は
     * ラインの基準を持てず、同じグループの他の人と条件が揃わないため、その間は新規参加を止める。
     * {@code app.league.require-past-scores-to-join}（2026-09-13 に導入 → 09-14 に一旦解除 →
     * 09-20 に {@link #transitionMeasuresUntil} 付きで再開）。
     * 止めるのは<b>新規参加と休止からの復帰</b>だけで、すでに参加中のエントリーには触れない。
     * 過去作アーカイブ（{@code version_pt_snapshots}）が空の間＝世代切り替え前は
     * 「前作の記録が無い」を判定できないので、この設定に関わらずゲートしない。
     */
    private final boolean requirePastScoresToJoin;

    /**
     * 【一時措置】初回参加の DIVISION を前作（Sparkle Shower）の最終 BEAT-PT で決めるか。
     *
     * 新作稼働直後の現行作 BEAT-PT は「どれだけ遊んだか」でしかなく実力を表さないため、
     * 前作の到達点で配属するほうが初週の対戦が噛み合う。
     * {@code app.league.initial-tier-from-previous-version}（{@link #initialBeatPt}）。
     */
    private final boolean initialTierFromPreviousVersion;

    /**
     * 【一時措置の期限（JST 壁時計）】{@link #requirePastScoresToJoin} と
     * {@link #initialTierFromPreviousVersion} が効く期限。この日時を過ぎると設定値に関わらず
     * 両方とも無効になる（解除のためのデプロイを要らなくするため）。
     *
     * 既定は ZINRAI 稼働（2026-09-16 07:00 JST）から 2 か月。
     * 未設定（空文字）なら期限なしで、設定値のまま効き続ける。
     */
    private final LocalDateTime transitionMeasuresUntil;

    /** {@link #joinBlockedReason} が返す理由コード: 過去作スコアが無い。フロントの i18n キーと対応させる。 */
    public static final String JOIN_BLOCKED_NO_PAST_SCORES = "noPastScores";

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     *
     * @param leagueEntryRepository          エントリーの永続化リポジトリ
     * @param versionPtSnapshotRepository    過去作の最終 PT アーカイブ
     * @param pastScoreRepository            過去作スコア
     * @param requirePastScoresToJoin        過去作スコアが無いユーザーの参加を止めるか（一時措置）
     * @param initialTierFromPreviousVersion 初回配属を前作の最終 BEAT-PT で決めるか（一時措置）
     * @param transitionMeasuresUntil        上記 2 つの一時措置の期限（JST、{@code 2026-11-16T07:00} 形式。空なら無期限）
     */
    public LeagueService(LeagueEntryRepository leagueEntryRepository,
                         VersionPtSnapshotRepository versionPtSnapshotRepository,
                         PastScoreRepository pastScoreRepository,
                         @Value("${app.league.require-past-scores-to-join:false}") boolean requirePastScoresToJoin,
                         @Value("${app.league.initial-tier-from-previous-version:false}") boolean initialTierFromPreviousVersion,
                         @Value("${app.league.transition-measures-until:}") String transitionMeasuresUntil) {
        this.leagueEntryRepository = leagueEntryRepository;
        this.versionPtSnapshotRepository = versionPtSnapshotRepository;
        this.pastScoreRepository = pastScoreRepository;
        this.requirePastScoresToJoin = requirePastScoresToJoin;
        this.initialTierFromPreviousVersion = initialTierFromPreviousVersion;
        this.transitionMeasuresUntil = parseJstDateTime(transitionMeasuresUntil);
    }

    /**
     * 設定値（JST 壁時計の ISO 形式）を {@link LocalDateTime} にする。空なら null＝期限なし。
     * 書式が不正な場合も null（＝期限なし）にして措置を効かせたままにする。
     * 「読めなかったから解除」にすると、設定ミスで一時措置が黙って外れてしまうため。
     */
    private static LocalDateTime parseJstDateTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            log.warn("app.league.transition-measures-until を解釈できません（期限なしとして扱います）: {}", raw);
            return null;
        }
    }

    /**
     * 【メソッドの役割】 世代切り替えの一時措置（参加ゲート・前作基準の初回配属）が今も効く期間か。
     *
     * @return 期限前なら true。期限が未設定なら常に true
     */
    public boolean withinTransitionMeasures() {
        return transitionMeasuresUntil == null
                || LocalDateTime.now(JstTime.JST).isBefore(transitionMeasuresUntil);
    }

    /**
     * 【メソッドの役割】 いまの初回配属が「前作の最終 BEAT-TIER 基準」かを返す（説明表示用）。
     *
     * ルール説明モーダルに一時措置の注記を出すかの判定に使う。期限を過ぎれば false に戻るので、
     * フロント側に終了日を持たせなくてよい。
     *
     * @return 前作基準なら true
     */
    public boolean usesPreviousVersionTier() {
        return initialTierFromPreviousVersion && withinTransitionMeasures();
    }

    /**
     * 【メソッドの役割】 このユーザーがいま参加（復帰を含む）できない理由コードを返す。
     *
     * 参加できるなら null。理由は現状 {@link #JOIN_BLOCKED_NO_PAST_SCORES} のみ
     * （{@link #requirePastScoresToJoin} が有効で期限内、過去作アーカイブが存在し、本人に過去作スコアが無い）。
     * 参加受付のロック（{@link #isRegistrationLocked}）は時間帯の都合であって本人の状態ではないので、
     * ここには含めない。
     *
     * @param user 対象ユーザー
     * @return 理由コード。参加できるなら null
     */
    public String joinBlockedReason(User user) {
        if (!requirePastScoresToJoin || !withinTransitionMeasures() || user == null || user.getId() == null) {
            return null;
        }
        if (versionPtSnapshotRepository.count() == 0) {
            return null; // 世代切り替え前（アーカイブ無し）は「前作の記録が無い」を判定できない
        }
        return pastScoreRepository.existsByUser(user) ? null : JOIN_BLOCKED_NO_PAST_SCORES;
    }

    /**
     * 【メソッドの役割】 参加をブロックしたときにユーザーへ返す説明メッセージを返す。
     *
     * @param reason {@link #joinBlockedReason} の理由コード
     * @return 案内文
     */
    public String joinBlockedMessage(String reason) {
        if (JOIN_BLOCKED_NO_PAST_SCORES.equals(reason)) {
            return "前作までのスコア（歴代スコア）が登録されていないため、現在はリーグに参加できません。"
                    + "スコア取り込みから前作の CSV を取り込むと参加できます。";
        }
        return "現在はリーグに参加できません。";
    }

    /**
     * 【メソッドの役割】 いま参加受付がロックされているか（週次の編成確定窓の中か）を返す。
     *
     * 毎週「月曜 0:00 〜 月曜 12:00（= {@link LeagueWeekLifecycleService#START_HOUR}）」だけ true。
     * この 12 時間はロスターを固定し、{@code LeagueScheduler.autoFormWeeks} が本番と同じ
     * 卓・グループ・課題曲を先に確定させ、管理者が開始前に確認・調整できるようにする。
     * 開始（月曜 12:00）と同時にロックは解け、以降の参加は翌週分として受け付ける。
     *
     * @return ロック中なら true
     */
    public boolean isRegistrationLocked() {
        LocalDateTime now = LocalDateTime.now(JstTime.JST);
        return now.getDayOfWeek() == DayOfWeek.MONDAY
                && now.getHour() < LeagueWeekLifecycleService.START_HOUR;
    }

    /**
     * 【メソッドの役割】 参加ロック中にユーザーへ返す説明メッセージを返す。
     *
     * @return 締切と再開時刻を含む案内文
     */
    public String registrationLockMessage() {
        return "今週分の参加受付は締め切りました（月曜 0:00）。"
                + LeagueWeekLifecycleService.START_HOUR + ":00 の開始後に次週分の参加を受け付けます。";
    }

    /**
     * 【メソッドの役割】 ラダー種別文字列が有効か判定する。
     *
     * @param ladderType 検査対象（null 可）
     * @return 有効なラダー（現在は "score" のみ）なら true
     */
    public boolean isValidLadder(String ladderType) {
        return ladderType != null && LADDERS.contains(ladderType);
    }

    /**
     * 【メソッドの役割】 指定ラダーへ参加（または休止から復帰）する。
     *
     * 初回参加時は BEAT-TIER を参照して DIVISION を即時配属する
     * （{@link LeagueDivision#forBeatPt}・参照する PT は {@link #initialBeatPt}）。
     * 復帰の場合は以前の DIVISION を維持する。
     * 反映は次回の週開始（月曜 12:00 JST）から。途中参加は不可で、進行中の週には追加されない。
     *
     * @param user       参加ユーザー
     * @param ladderType ラダー種別（呼び出し前に {@link #isValidLadder} で検証済みであること）
     * @return 作成または更新されたエントリー
     */
    @Transactional
    public LeagueEntry join(User user, String ladderType) {
        LeagueEntry entry = leagueEntryRepository.findByUserAndLadderType(user, ladderType).orElse(null);
        if (entry == null) {
            entry = new LeagueEntry();
            entry.setUser(user);
            entry.setLadderType(ladderType);
            // 初回参加: BEAT-TIER に応じた DIVISION へ配属（参加した瞬間に確定・表示できる）
            entry.setCurrentTier(LeagueDivision.forBeatPt(initialBeatPt(user)));
        }
        entry.setActive(true);
        entry.setInactiveWeeks(0);
        return leagueEntryRepository.save(entry);
    }

    /**
     * 【メソッドの役割】 初回配属（と未参加者へのプレビュー表示）に使う BEAT-PT を返す。
     *
     * <ul>
     *   <li><b>一時措置の期間中</b>（{@link #initialTierFromPreviousVersion} が有効かつ
     *       {@link #withinTransitionMeasures}）: 前作（Sparkle Shower）の最終 BEAT-PT をそのまま使う。
     *       新作稼働直後の現行作 PT は「どれだけ遊んだか」でしかなく実力を表さないため、
     *       前作の到達点で配属したほうが初週の対戦が噛み合う。</li>
     *   <li><b>それ以外</b>: 現行作と過去作アーカイブの高いほう（＝歴代最高、{@link #allTimeBeatPt}）。</li>
     * </ul>
     *
     * 前作のアーカイブが無い人（前作を遊んでいない・スナップショット後に登録して前作 CSV も
     * 未取り込み）は歴代最高へフォールバックする。
     *
     * @param user 対象ユーザー
     * @return DIVISION 判定に使う BEAT-PT
     */
    public double initialBeatPt(User user) {
        if (user == null) {
            return 0.0;
        }
        if (initialTierFromPreviousVersion && withinTransitionMeasures() && user.getId() != null) {
            // 前作 = 現行作の 1 つ前（ZINRAI 稼働中なら 33 = Sparkle Shower）。
            Double previous = versionPtSnapshotRepository
                    .findByVersionAndUserId(IidxVersions.current() - 1, user.getId())
                    .map(VersionPtSnapshot::getTotalBeatPt)
                    .orElse(null);
            if (previous != null && previous > 0) {
                return previous;
            }
        }
        return allTimeBeatPt(user);
    }

    /**
     * 【メソッドの役割】 階級判定の基準になる「歴代最高 BEAT-PT」を返す（{@link #initialBeatPt} の既定）。
     *
     * 現行作の {@code users.total_beat_pt} と、過去作アーカイブ
     * （{@code version_pt_snapshots}）の最大値のうち高いほうを採る。
     *
     * なぜ現行作だけでは駄目なのか: 新作稼働時に BEAT-PT は 0 へリセットされる。
     * 稼働直後にリーグへ参加すると、前作で上位だった人まで最下位階級に配属されてしまい、
     * 初週の対戦が成立しない。前作の実力を初期配置の手がかりとして使う。
     *
     * @param user 対象ユーザー
     * @return 歴代最高 BEAT-PT（いずれも記録が無ければ 0.0）
     */
    private double allTimeBeatPt(User user) {
        double current = user.getTotalBeatPt() != null ? user.getTotalBeatPt() : 0.0;
        if (user.getId() == null) return current;
        Double archived = versionPtSnapshotRepository.findMaxBeatPtByUserId(user.getId());
        return archived != null ? Math.max(current, archived) : current;
    }

    /**
     * 【メソッドの役割】 指定ラダーから離脱（休止）する。
     *
     * active=false にするだけで行は消さない（階級と履歴を保持し、復帰を容易にする）。
     * 進行中の週のメンバーシップはそのまま残る（週の途中離脱でグループの母数を壊さない）。
     *
     * @param user       離脱ユーザー
     * @param ladderType ラダー種別
     * @return エントリーが存在して休止にできたら true、元々未参加なら false
     */
    @Transactional
    public boolean leave(User user, String ladderType) {
        LeagueEntry entry = leagueEntryRepository.findByUserAndLadderType(user, ladderType).orElse(null);
        if (entry == null) {
            return false;
        }
        entry.setActive(false);
        leagueEntryRepository.save(entry);
        return true;
    }

    /**
     * 【メソッドの役割】 指定ユーザーの全ラダー分のエントリーを返す。
     *
     * @param user 対象ユーザー
     * @return エントリー一覧（0..2 件）
     */
    public List<LeagueEntry> myEntries(User user) {
        return leagueEntryRepository.findByUser(user);
    }
}
