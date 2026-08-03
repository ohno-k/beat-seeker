package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.LeagueEntryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    /**
     * 参加受付の締切（JST）。この時刻 〜 シーズン開始（月曜 12:00）の間は参加/離脱をロックし、
     * 管理者が本番と同じ編成を事前確認できるようにする。{@code app.league.signup-close} で変更可。
     */
    private final LocalDateTime signupClose;
    /**
     * シーズン開始日の編成・開始時刻（JST・月曜 12:00）。ロック窓の終端に使う。
     * {@code app.league.season-start} の日付に {@link LeagueWeekLifecycleService#START_HOUR} を合わせる。
     */
    private final LocalDateTime seasonActivation;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     *
     * @param leagueEntryRepository エントリーの永続化リポジトリ
     * @param signupClose           参加受付の締切（ISO ローカル日時、既定 2026-08-02T23:59:00）
     * @param seasonStart           シーズン開始日（ISO ローカル日付、既定 2026-08-03）
     */
    public LeagueService(LeagueEntryRepository leagueEntryRepository,
                         @Value("${app.league.signup-close:2026-08-02T23:59:00}") String signupClose,
                         @Value("${app.league.season-start:2026-08-03}") String seasonStart) {
        this.leagueEntryRepository = leagueEntryRepository;
        this.signupClose = LocalDateTime.parse(signupClose);
        this.seasonActivation = LocalDate.parse(seasonStart)
                .atTime(LeagueWeekLifecycleService.START_HOUR, 0);
    }

    /**
     * 【メソッドの役割】 いま参加受付がロックされているか（プレシーズン確定窓の中か）を返す。
     *
     * 締切（{@code signup-close}）〜シーズン開始（月曜 12:00）の間だけ true。締切より前と
     * 開始後は false（通常どおり参加/離脱できる）。この窓の間はロスターを固定し、管理者が
     * 本番と同じ編成（グループ・課題曲）を確認・調整できるようにする。
     *
     * @return ロック中なら true
     */
    public boolean isRegistrationLocked() {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Tokyo"));
        return !now.isBefore(signupClose) && now.isBefore(seasonActivation);
    }

    /**
     * 【メソッドの役割】 参加ロック中にユーザーへ返す説明メッセージを返す。
     *
     * @return 締切日時を含む案内文
     */
    public String registrationLockMessage() {
        String when = signupClose.format(DateTimeFormatter.ofPattern("M/d HH:mm"));
        return "リーグの参加受付は締め切りました（" + when + "）。開始後は次週分の参加を受け付けます。";
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
            entry.setCurrentTier(LeagueDivision.forBeatPt(user.getTotalBeatPt()));
        }
        entry.setActive(true);
        entry.setInactiveWeeks(0);
        return leagueEntryRepository.save(entry);
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
