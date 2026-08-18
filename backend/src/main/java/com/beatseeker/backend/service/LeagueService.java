package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.LeagueEntryRepository;
import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     *
     * @param leagueEntryRepository       エントリーの永続化リポジトリ
     * @param versionPtSnapshotRepository 過去作の最終 PT アーカイブ
     */
    public LeagueService(LeagueEntryRepository leagueEntryRepository,
                         VersionPtSnapshotRepository versionPtSnapshotRepository) {
        this.leagueEntryRepository = leagueEntryRepository;
        this.versionPtSnapshotRepository = versionPtSnapshotRepository;
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
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
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
     * 初回参加時は BEAT-TIER（総合 BEAT-PT）を参照して DIVISION を即時配属する
     * （{@link LeagueDivision#forBeatPt}）。復帰の場合は以前の DIVISION を維持する。
     * 反映は次回の週開始（月曜 12:00 JST）から。途中参加は不可で、進行中の週には追加されない。
     *
     * 参照する BEAT-PT は<b>現行作と過去作アーカイブの高いほう（＝歴代最高）</b>。
     * 新作稼働直後は現行作の BEAT-PT が 0 に戻るため、現行作だけを見ると経験者まで
     * 最下位階級から始まってしまう。それを避けるための措置。
     * アーカイブが 1 件も無い間（＝初回の世代切り替え前）は現行作の値がそのまま使われるので、
     * 挙動はこれまでと変わらない。
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
            entry.setCurrentTier(LeagueDivision.forBeatPt(allTimeBeatPt(user)));
        }
        entry.setActive(true);
        entry.setInactiveWeeks(0);
        return leagueEntryRepository.save(entry);
    }

    /**
     * 【メソッドの役割】 初回参加の階級判定に使う「歴代最高 BEAT-PT」を返す。
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
