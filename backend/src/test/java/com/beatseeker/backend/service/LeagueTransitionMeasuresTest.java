package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.entity.VersionPtSnapshot;
import com.beatseeker.backend.repository.LeagueEntryRepository;
import com.beatseeker.backend.repository.PastScoreRepository;
import com.beatseeker.backend.repository.VersionPtSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【テストの目的】 世代切り替え（ZINRAI 稼働）の一時措置 2 つを検証する。
 *
 *  - 過去作スコアが 1 件も無い人（＝現行作のスコアしか無い人）の新規参加・復帰を止める
 *  - 初回配属を前作（Sparkle Shower）の最終 BEAT-PT で決める
 *
 * どちらも {@code app.league.transition-measures-until} の期限を過ぎたら効かなくなる。
 * DB は触らず、判定に使うリポジトリだけモックする。
 */
class LeagueTransitionMeasuresTest {

    /** 期限内（十分に先の日時）。 */
    private static final String FUTURE = "2999-01-01T00:00";
    /** 期限切れ（十分に過去の日時）。 */
    private static final String PAST = "2000-01-01T00:00";

    private final LeagueEntryRepository entryRepository = mock(LeagueEntryRepository.class);
    private final VersionPtSnapshotRepository snapshotRepository = mock(VersionPtSnapshotRepository.class);
    private final PastScoreRepository pastScoreRepository = mock(PastScoreRepository.class);

    /** id と現行作 BEAT-PT だけ持つテスト用ユーザー。 */
    private static User user(long id, double currentBeatPt) {
        User u = new User();
        u.setId(id);
        u.setTotalBeatPt(currentBeatPt);
        return u;
    }

    private LeagueService service(boolean requirePastScores, boolean previousVersionTier, String until) {
        return new LeagueService(entryRepository, snapshotRepository, pastScoreRepository,
                requirePastScores, previousVersionTier, until);
    }

    /** 前作（現行作の 1 つ前）のスナップショットを返すようにモックする。 */
    private void givenPreviousSnapshot(long userId, Double beatPt) {
        VersionPtSnapshot snapshot = new VersionPtSnapshot();
        snapshot.setUserId(userId);
        snapshot.setTotalBeatPt(beatPt);
        when(snapshotRepository.findByVersionAndUserId(eq(IidxVersions.current() - 1), eq(userId)))
                .thenReturn(Optional.of(snapshot));
    }

    // ── 参加ゲート ────────────────────────────────────────────────

    @Test
    void 過去作スコアが無ければ参加できない() {
        User u = user(1L, 120.0);
        when(snapshotRepository.count()).thenReturn(500L);
        when(pastScoreRepository.existsByUser(u)).thenReturn(false);

        assertThat(service(true, false, FUTURE).joinBlockedReason(u))
                .isEqualTo(LeagueService.JOIN_BLOCKED_NO_PAST_SCORES);
    }

    @Test
    void 過去作スコアがあれば参加できる() {
        User u = user(2L, 120.0);
        when(snapshotRepository.count()).thenReturn(500L);
        when(pastScoreRepository.existsByUser(u)).thenReturn(true);

        assertThat(service(true, false, FUTURE).joinBlockedReason(u)).isNull();
    }

    @Test
    void 期限を過ぎたら過去作スコアが無くても参加できる() {
        User u = user(3L, 120.0);
        when(snapshotRepository.count()).thenReturn(500L);
        when(pastScoreRepository.existsByUser(u)).thenReturn(false);

        assertThat(service(true, false, PAST).joinBlockedReason(u)).isNull();
    }

    @Test
    void 過去作アーカイブが空なら判定できないので参加できる() {
        User u = user(4L, 120.0);
        when(snapshotRepository.count()).thenReturn(0L);

        assertThat(service(true, false, FUTURE).joinBlockedReason(u)).isNull();
    }

    @Test
    void 設定がoffなら参加を止めない() {
        User u = user(5L, 120.0);
        when(snapshotRepository.count()).thenReturn(500L);
        when(pastScoreRepository.existsByUser(u)).thenReturn(false);

        assertThat(service(false, false, FUTURE).joinBlockedReason(u)).isNull();
    }

    // ── 初回配属 ─────────────────────────────────────────────────

    @Test
    void 期限内は前作の最終BEATPTで配属する() {
        // 現行作 16500pt（= DIVISION 4 相当）より、前作 15200pt（= DIVISION 7 相当）を優先する。
        User u = user(10L, 16500.0);
        givenPreviousSnapshot(10L, 15200.0);

        LeagueService svc = service(false, true, FUTURE);

        assertThat(svc.initialBeatPt(u)).isEqualTo(15200.0);
        assertThat(LeagueDivision.forBeatPt(svc.initialBeatPt(u))).isEqualTo(7);
        assertThat(svc.usesPreviousVersionTier()).isTrue();
    }

    @Test
    void 期限を過ぎたら歴代最高で配属する() {
        User u = user(11L, 16500.0);
        givenPreviousSnapshot(11L, 15200.0);
        when(snapshotRepository.findMaxBeatPtByUserId(11L)).thenReturn(15200.0);

        LeagueService svc = service(false, true, PAST);

        assertThat(svc.initialBeatPt(u)).isEqualTo(16500.0); // 現行作のほうが高い
        assertThat(LeagueDivision.forBeatPt(svc.initialBeatPt(u))).isEqualTo(4);
        assertThat(svc.usesPreviousVersionTier()).isFalse();
    }

    @Test
    void 前作のスナップショットが無ければ歴代最高にフォールバックする() {
        User u = user(12L, 100.0);
        when(snapshotRepository.findByVersionAndUserId(eq(IidxVersions.current() - 1), eq(12L)))
                .thenReturn(Optional.empty());
        when(snapshotRepository.findMaxBeatPtByUserId(12L)).thenReturn(17200.0); // もっと前の作品の記録

        assertThat(service(false, true, FUTURE).initialBeatPt(u)).isEqualTo(17200.0);
    }

    @Test
    void 初回参加のDIVISIONは前作基準で保存される() {
        User u = user(13L, 100.0); // 稼働直後で現行作 PT はほぼ 0
        givenPreviousSnapshot(13L, 17550.0); // 前作は DIVISION 1 相当
        when(entryRepository.findByUserAndLadderType(eq(u), anyString())).thenReturn(Optional.empty());
        when(entryRepository.save(any(LeagueEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        LeagueEntry entry = service(false, true, FUTURE).join(u, LeagueService.LADDER_SCORE);

        assertThat(entry.getCurrentTier()).isEqualTo(1);
        assertThat(entry.getActive()).isTrue();
    }
}
