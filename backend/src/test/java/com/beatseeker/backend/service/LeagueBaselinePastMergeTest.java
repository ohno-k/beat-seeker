package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueBaseline;
import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.PastScore;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.LeagueBaselineRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.PastScoreRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 【テストの目的】 有効ライン（週開始時点のベースライン）へ過去作スコアを合算する一時措置の検証。
 *
 * 検証したいこと:
 *  - {@code baseline-includes-past} が有効なら、現行作より高い過去作の EX がベースラインになる
 *  - 現行作に記録が無くても過去作に記録があれば行が作られる（プレー回数は null のまま）
 *  - 無効なら従来どおり現行作（アーケード）のみを見る
 *  - INFINITAS の現行作記録は従来どおり無視される
 *
 * DB は触らず、課題曲・現行作スコア・過去作スコアを返すリポジトリだけモックする。
 */
class LeagueBaselinePastMergeTest {

    private final LeagueSongRepository songRepository = mock(LeagueSongRepository.class);
    private final LeagueBaselineRepository baselineRepository = mock(LeagueBaselineRepository.class);
    private final ScoreRepository scoreRepository = mock(ScoreRepository.class);
    private final PastScoreRepository pastScoreRepository = mock(PastScoreRepository.class);

    private final LeagueWeek week = new LeagueWeek();
    private final User user = new User();
    private final LeagueMember member = new LeagueMember();

    LeagueBaselinePastMergeTest() {
        week.setId(15L);
        week.setLadderType("score");
        user.setId(7L);
        member.setWeek(week);
        member.setUser(user);
        member.setTier(5);
        member.setGroupIndex(0);
        when(songRepository.findByWeekOrderByTierAscSlotAsc(week)).thenReturn(List.of(song("SongA", "ANOTHER")));
    }

    private LeagueWeekLifecycleService service(boolean includesPast) {
        return new LeagueWeekLifecycleService(null, null, null, songRepository, baselineRepository,
                scoreRepository, null, null, null, pastScoreRepository, null, "2026-08-10", includesPast);
    }

    private LeagueSong song(String title, String diff) {
        LeagueSong s = new LeagueSong();
        s.setWeek(week);
        s.setTier(5);
        s.setGroupIndex(0);
        s.setSlot(0);
        s.setTitle(title);
        s.setDifficultyName(diff);
        return s;
    }

    private Score score(String title, String diff, int ex, Integer playCount, String source) {
        Score s = new Score();
        s.setUser(user);
        s.setTitle(title);
        s.setDifficultyName(diff);
        s.setScore(ex);
        s.setMissCount(10);
        s.setPlayCount(playCount);
        s.setClearType("CLEAR");
        s.setSource(source);
        return s;
    }

    private PastScore past(String title, String diff, int ex) {
        PastScore p = new PastScore();
        p.setUser(user);
        p.setVersion(33);
        p.setTitle(title);
        p.setDifficultyName(diff);
        p.setScore(ex);
        p.setMissCount(3);
        p.setPlayCount(120);
        p.setClearType("HARD CLEAR");
        return p;
    }

    private List<LeagueBaseline> snapshot(boolean includesPast) {
        service(includesPast).snapshotBaselines(week, List.of(member));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LeagueBaseline>> captor = ArgumentCaptor.forClass(List.class);
        verify(baselineRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void 過去作の方が高ければ過去作のEXがベースラインになりプレー回数は現行作の値を保つ() {
        when(scoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of(score("SongA", "ANOTHER", 2000, 10, "arcade")));
        when(pastScoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of(past("SongA", "ANOTHER", 2500)));

        List<LeagueBaseline> rows = snapshot(true);

        assertThat(rows).hasSize(1);
        LeagueBaseline b = rows.get(0);
        assertThat(b.getSource()).isEqualTo("arcade");
        assertThat(b.getBaseScore()).isEqualTo(2500);
        assertThat(b.getBaseMiss()).isEqualTo(3);
        assertThat(b.getBaseClearType()).isEqualTo("HARD CLEAR");
        assertThat(b.getBasePlayCount()).isEqualTo(10); // 過去作の 120 は混ぜない
    }

    @Test
    void 現行作に記録が無くても過去作があれば行を作りプレー回数はnull() {
        when(scoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of());
        when(pastScoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of(past("SongA", "ANOTHER", 2500)));

        List<LeagueBaseline> rows = snapshot(true);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBaseScore()).isEqualTo(2500);
        assertThat(rows.get(0).getBasePlayCount()).isNull();
    }

    @Test
    void 無効なら過去作を見ず現行作のみ() {
        when(scoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of(score("SongA", "ANOTHER", 2000, 10, "arcade")));

        List<LeagueBaseline> rows = snapshot(false);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBaseScore()).isEqualTo(2000);
        verify(pastScoreRepository, org.mockito.Mockito.never())
                .findByUserAndTitlesAndDifficulties(any(), anyList(), anyList());
    }

    @Test
    void INFINITASの現行作記録は有効時も無視される() {
        when(scoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of(score("SongA", "ANOTHER", 3000, 10, "infinitas")));
        when(pastScoreRepository.findByUserAndTitlesAndDifficulties(eq(user), anyList(), anyList()))
                .thenReturn(List.of(past("SongA", "ANOTHER", 2500)));

        List<LeagueBaseline> rows = snapshot(true);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBaseScore()).isEqualTo(2500);
        assertThat(rows.get(0).getBasePlayCount()).isNull();
    }
}
