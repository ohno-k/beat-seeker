package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

/**
 * 【テストの目的】 「課題曲のスコアを更新したのに順位表へ反映されない」という不具合報告に対して、
 * <b>scores にライン超えの記録が入っていれば順位表は必ずそれを拾う</b>ことを固定する。
 *
 * リーグの有効判定は 2 条件の AND（{@code LeagueStandingsService#computeMemberStats}）:
 *  1. 週内プレー … ベースライン（週開始時点のスナップショット）から前進しているか
 *  2. ライン超え … グループ内の週開始時点の最高 EX を<b>超えて</b>いるか（同点は不可）
 *
 * ここで検証するのは「ライン保持者本人が自分のラインを塗り替えた」ケース。ライン = 自分の
 * ベースラインなので 1 と 2 が同時に成立し、有効曲 1 曲・着順 1 位として計上される。
 * このケースが落ちると「更新したのに 0/3 のまま」になるため、リグレッションを固定しておく。
 *
 * 併せて、更新前（scores がまだ古い値のまま）の状態では有効化されず、ライン保持者の +1 pt
 * だけが付くことも確認する。順位表が 0/3 のままなら、原因は集計ではなく scores 側に無い
 * （＝アップロードが保存されていない / 別アカウントに入った）ことの切り分けに使える。
 */
class LeagueScoreReflectionTest {

    private static final String TITLE = "AsiaN distractive";
    private static final String DIFF = "ANOTHER";
    /** AsiaN distractive [A] のノーツ数（MAX = notes * 2 = 2674）。 */
    private static final int NOTES = 1337;
    /** 週開始時点のグループ最高 EX（= ライン）。 */
    private static final int LINE_EX = 2554;

    @Test
    void ライン保持者が自分のラインを超えたら有効曲に計上される() {
        Map<String, Object> row = standingsRowOfLineHolder(LINE_EX + 7);

        assertThat(row.get("validSongs")).isEqualTo(1);
        assertThat(row.get("rank")).isEqualTo(1);
        // 有効化した本人は「グループ人数」pt を総取りする（2 人グループなら 2 pt）。
        assertThat((Double) row.get("resultValue")).isEqualTo(2.0);

        Map<String, Object> perSong = slotOf(row, 3);
        assertThat(perSong.get("valid")).isEqualTo(true);
        assertThat(perSong.get("bestEx")).isEqualTo(LINE_EX + 7);
        assertThat(perSong.get("lineEx")).isEqualTo(LINE_EX);
    }

    @Test
    void ラインちょうどのままなら有効化されずライン保持者の1ptだけ付く() {
        Map<String, Object> row = standingsRowOfLineHolder(LINE_EX);

        assertThat(row.get("validSongs")).isEqualTo(0);
        // 誰も有効化できない曲はライン保持者にだけ +1 pt。
        assertThat((Double) row.get("resultValue")).isEqualTo(1.0);
        assertThat(slotOf(row, 3).get("valid")).isEqualTo(false);
    }

    /**
     * ライン保持者（谷口）と対戦相手 1 人のグループを組み、谷口の現在 EX を {@code currentEx} にした
     * 順位表から谷口の行を返す。課題曲は 3 曲で、勝負が付くのは slot 3（AsiaN distractive）だけ。
     */
    private Map<String, Object> standingsRowOfLineHolder(int currentEx) {
        LeagueWeek week = new LeagueWeek();
        week.setId(1L);
        week.setLadderType("score");
        week.setStatus("active");
        week.setStartsAt(LocalDateTime.of(2026, 8, 31, 12, 0));
        week.setEndsAt(LocalDateTime.of(2026, 9, 6, 21, 0));
        week.setSnapshotAt(LocalDateTime.of(2026, 8, 31, 3, 0)); // サーバー時計（UTC）

        List<LeagueSong> songs = List.of(
                song(week, 1, "Kung-fu Empire", "LEGGENDARIA", 1999),
                song(week, 2, "Uh-Oh", DIFF, 1228),
                song(week, 3, TITLE, DIFF, NOTES));

        User holder = user(10L, "谷口");
        User rival = user(11L, "GALA");
        LeagueMember me = member(week, holder);
        LeagueMember other = member(week, rival);

        // 週開始時点のベースライン: 谷口が LINE_EX を持っている（= グループのライン）。
        LeagueBaseline baseline = new LeagueBaseline();
        baseline.setWeek(week);
        baseline.setUser(holder);
        baseline.setTitle(TITLE);
        baseline.setDifficultyName(DIFF);
        baseline.setSource("arcade");
        baseline.setBaseScore(LINE_EX);
        baseline.setBasePlayCount(5);
        baseline.setBaseClearType("CLEAR");

        // 現在の scores（アップロード反映後の状態）。
        Score score = new Score();
        score.setUser(holder);
        score.setTitle(TITLE);
        score.setDifficultyName(DIFF);
        score.setDifficultyLevel(11);
        score.setScore(currentEx);
        score.setSource("arcade");
        score.setPlayCount(6);
        score.setClearType("CLEAR");
        score.setUploadedAt(LocalDateTime.of(2026, 9, 1, 2, 57));
        score.setLastPlayedAt(LocalDateTime.of(2026, 8, 31, 22, 30));

        LeagueMemberRepository memberRepo = Mockito.mock(LeagueMemberRepository.class);
        LeagueSongRepository songRepo = Mockito.mock(LeagueSongRepository.class);
        LeagueBaselineRepository baselineRepo = Mockito.mock(LeagueBaselineRepository.class);
        LeagueEntryRepository entryRepo = Mockito.mock(LeagueEntryRepository.class);
        ScoreRepository scoreRepo = Mockito.mock(ScoreRepository.class);
        SongDefinitionRepository definitionRepo = Mockito.mock(SongDefinitionRepository.class);
        LeagueMemberSongRepository memberSongRepo = Mockito.mock(LeagueMemberSongRepository.class);

        Mockito.when(memberRepo.findByWeekAndTierAndGroupIndex(week, 5, 0)).thenReturn(List.of(me, other));
        Mockito.when(songRepo.findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, 5, 0)).thenReturn(songs);
        Mockito.when(baselineRepo.findByWeekAndUserIn(any(), anyList())).thenReturn(List.of(baseline));
        Mockito.when(entryRepo.findByLadderTypeAndUserIn(any(), anyList())).thenReturn(List.of());
        Mockito.when(definitionRepo.findAllByTitleAndDifficultyAndRevision(any(), any(), any())).thenReturn(List.of());
        Mockito.when(scoreRepo.findByUserAndTitlesAndDifficulties(any(), anyList(), anyList()))
                .thenAnswer(inv -> inv.getArgument(0) == holder ? List.of(score) : List.of());

        LeagueStandingsService service = new LeagueStandingsService(memberRepo, songRepo, baselineRepo,
                entryRepo, scoreRepo, definitionRepo, memberSongRepo);

        return service.computeGroupStandings(week, 5, 0).stream()
                .filter(r -> "谷口".equals(r.get("displayName")))
                .findFirst()
                .orElseThrow();
    }

    /** 順位表の 1 行から指定スロットの曲別内訳を取り出す。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> slotOf(Map<String, Object> row, int slot) {
        return ((List<Map<String, Object>>) row.get("perSong")).stream()
                .filter(ps -> Integer.valueOf(slot).equals(ps.get("slot")))
                .findFirst()
                .orElseThrow();
    }

    private User user(long id, String displayName) {
        User u = new User();
        u.setId(id);
        u.setDisplayName(displayName);
        u.setTotalBeatPt(100.0);
        return u;
    }

    private LeagueSong song(LeagueWeek week, int slot, String title, String difficultyName, int notes) {
        LeagueSong s = new LeagueSong();
        s.setWeek(week);
        s.setTier(5);
        s.setGroupIndex(0);
        s.setSlot(slot);
        s.setTitle(title);
        s.setDifficultyName(difficultyName);
        s.setNotes(notes);
        s.setLevel(11);
        return s;
    }

    private LeagueMember member(LeagueWeek week, User user) {
        LeagueMember m = new LeagueMember();
        m.setWeek(week);
        m.setUser(user);
        m.setTier(5);
        m.setHomeTier(5);
        m.setGroupIndex(0);
        m.setRole("normal");
        return m;
    }
}
