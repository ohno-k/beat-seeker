package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.LeagueBaselineRepository;
import com.beatseeker.backend.repository.LeagueEntryRepository;
import com.beatseeker.backend.repository.LeagueMemberRepository;
import com.beatseeker.backend.repository.LeagueMemberSongRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【テストの目的】 ライン（週開始時点のグループ最高記録）が無い曲で、「記録と言えないもの」が
 * 有効化されないことを検証する。
 *
 * 新作稼働直後はほとんどの曲にラインが無く、「週内に記録を出せばそのまま有効」になる。
 * このとき次の 2 つを弾かないと、遊んでいない人が有効曲を持ってしまう:
 *  - EX=0・プレー回数 0 の<b>空記録</b>（譜面を所持しているだけ。IIDX の CSV に混ざる）
 *  - 週の<b>前</b>に出した記録を、週内にアップロードしただけのもの（最終プレー日時が期間外）
 *
 * 2026-09-21 の本番で、初回アップロードが週の途中だった参加者が空記録のまま 3/3 有効・
 * 着順 2 位になっていた事象の再発防止。
 */
class LeagueEmptyRecordTest {

    private final LeagueMemberRepository memberRepository = mock(LeagueMemberRepository.class);
    private final LeagueSongRepository songRepository = mock(LeagueSongRepository.class);
    private final LeagueBaselineRepository baselineRepository = mock(LeagueBaselineRepository.class);
    private final LeagueEntryRepository entryRepository = mock(LeagueEntryRepository.class);
    private final ScoreRepository scoreRepository = mock(ScoreRepository.class);
    private final SongDefinitionRepository songDefinitionRepository = mock(SongDefinitionRepository.class);
    private final LeagueMemberSongRepository memberSongRepository = mock(LeagueMemberSongRepository.class);
    private final PreviousVersionPtService previousVersionPtService = mock(PreviousVersionPtService.class);

    private static final String TITLE = "IDOL syndrome.";
    private static final String DIFF = "LEGGENDARIA";
    /** 週: 月曜 12:00 〜 日曜 21:00（JST 壁時計）。 */
    private static final LocalDateTime STARTS = LocalDateTime.of(2026, 9, 21, 12, 0);
    private static final LocalDateTime ENDS = LocalDateTime.of(2026, 9, 27, 21, 0);
    /** 開始スナップショット（サーバー時計）。 */
    private static final LocalDateTime SNAPSHOT = LocalDateTime.of(2026, 9, 21, 12, 0);

    private final LeagueWeek week = new LeagueWeek();
    private final User user = new User();
    private final LeagueMember member = new LeagueMember();

    LeagueEmptyRecordTest() {
        week.setId(15L);
        week.setLadderType("score");
        week.setStatus("active");
        week.setStartsAt(STARTS);
        week.setEndsAt(ENDS);
        week.setSnapshotAt(SNAPSHOT);

        user.setId(7L);
        user.setDisplayName("TESTER");
        member.setWeek(week);
        member.setUser(user);
        member.setTier(6);
        member.setGroupIndex(2);
        member.setHomeTier(6);
        member.setRole("normal");

        LeagueSong song = new LeagueSong();
        song.setWeek(week);
        song.setTier(6);
        song.setGroupIndex(2);
        song.setSlot(1);
        song.setTitle(TITLE);
        song.setDifficultyName(DIFF);
        song.setNotes(1000);

        when(memberRepository.findByWeekAndTierAndGroupIndex(week, 6, 2)).thenReturn(List.of(member));
        when(songRepository.findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, 6, 2)).thenReturn(List.of(song));
        // ライン無し（週開始時点の記録が誰にも無い）
        when(baselineRepository.findByWeekAndUserIn(eq(week), anyList())).thenReturn(List.of());
        when(entryRepository.findByLadderTypeAndUserIn(anyString(), anyList())).thenReturn(List.of());
        SongDefinition def = new SongDefinition();
        def.setTitle(TITLE);
        def.setNotes(1000);
        when(songDefinitionRepository.findAllByTitleAndDifficultyAndRevision(eq(TITLE), anyString(), eq("active")))
                .thenReturn(List.of(def));
    }

    private LeagueStandingsService service() {
        return new LeagueStandingsService(memberRepository, songRepository, baselineRepository, entryRepository,
                scoreRepository, songDefinitionRepository, memberSongRepository, previousVersionPtService);
    }

    /** 週内にアップロードされた 1 行だけを返すようにする。 */
    private void givenScore(Integer ex, Integer playCount, LocalDateTime lastPlayedAt) {
        Score s = new Score();
        s.setTitle(TITLE);
        s.setDifficultyName(DIFF);
        s.setScore(ex);
        s.setPlayCount(playCount);
        s.setClearType("EX HARD CLEAR");
        s.setSource("arcade");
        s.setUploadedAt(SNAPSHOT.plusHours(10)); // 週内にアップロード
        s.setLastPlayedAt(lastPlayedAt);
        when(scoreRepository.findByUserAndTitlesAndDifficulties(any(User.class), anyList(), anyList()))
                .thenReturn(List.of(s));
    }

    private Map<String, Object> firstSong(List<Map<String, Object>> standings) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> perSong = (List<Map<String, Object>>) standings.get(0).get("perSong");
        return perSong.get(0);
    }

    @Test
    void 空記録は有効にならない() {
        // 所持しているだけ（EX=0・プレー回数 0）。初回アップロードが週の途中だったケース。
        givenScore(0, 0, LocalDateTime.of(2026, 9, 16, 10, 57)); // 最終プレーは週の前

        List<Map<String, Object>> standings = service().computeGroupStandings(week, 6, 2);

        assertThat(standings).hasSize(1);
        assertThat(standings.get(0).get("validSongs")).isEqualTo(0);
        assertThat(firstSong(standings).get("valid")).isEqualTo(false);
        assertThat(firstSong(standings).get("participated")).isEqualTo(false);
    }

    @Test
    void 最終プレーが週の前なら記録があっても有効にならない() {
        // 週の前に出した記録を、週内にアップロードしただけ。
        givenScore(2500, 3, LocalDateTime.of(2026, 9, 18, 21, 39));

        List<Map<String, Object>> standings = service().computeGroupStandings(week, 6, 2);

        assertThat(standings.get(0).get("validSongs")).isEqualTo(0);
        assertThat(firstSong(standings).get("valid")).isEqualTo(false);
        assertThat(firstSong(standings).get("participated")).isEqualTo(false);
    }

    @Test
    void 週内に遊んだ記録はライン無しでそのまま有効() {
        givenScore(2500, 3, LocalDateTime.of(2026, 9, 21, 18, 48));

        List<Map<String, Object>> standings = service().computeGroupStandings(week, 6, 2);

        assertThat(standings.get(0).get("validSongs")).isEqualTo(1);
        assertThat(firstSong(standings).get("valid")).isEqualTo(true);
        assertThat(firstSong(standings).get("participated")).isEqualTo(true);
        assertThat(firstSong(standings).get("bestEx")).isEqualTo(2500);
    }

    @Test
    void 最終プレー日時が無い経路は従来どおり週内の記録として扱う() {
        // ブックマークレット CSV は最終プレー日時の列が空。記録があるなら従来どおり有効。
        givenScore(2500, null, null);

        List<Map<String, Object>> standings = service().computeGroupStandings(week, 6, 2);

        assertThat(standings.get(0).get("validSongs")).isEqualTo(1);
        assertThat(firstSong(standings).get("valid")).isEqualTo(true);
    }

    @Test
    void 最終プレー日時が無くても空記録は有効にならない() {
        givenScore(0, 0, null);

        List<Map<String, Object>> standings = service().computeGroupStandings(week, 6, 2);

        assertThat(standings.get(0).get("validSongs")).isEqualTo(0);
        assertThat(firstSong(standings).get("valid")).isEqualTo(false);
        assertThat(firstSong(standings).get("participated")).isEqualTo(false);
    }
}
