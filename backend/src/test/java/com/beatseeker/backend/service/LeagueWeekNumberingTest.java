package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.repository.LeagueWeekRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 【テストの目的】 開催回の通し番号（#1, #2, ...）の採番ルールを検証する。
 *
 * 検証したいこと:
 *  - {@code week-one-start}（2026-08-10）より前に始まる週＝プレシーズンには番号を振らない
 *  - #1 の週から「既存の最大 + 1」で連番になる（日付から計算しないので欠週があっても飛ばない）
 *  - 採番済みの週は再採番しない（冪等）
 *
 * DB は触らず、最大番号を返すリポジトリだけモックする。
 */
class LeagueWeekNumberingTest {

    private final LeagueWeekRepository weekRepository = mock(LeagueWeekRepository.class);

    private final LeagueWeekLifecycleService service =
            new LeagueWeekLifecycleService(null, weekRepository, null, null, null, null, null, null, null,
                    "2026-08-10");

    /** 開始日時だけを持つ週を作る。 */
    private LeagueWeek week(String startsAt, Integer weekNo) {
        LeagueWeek w = new LeagueWeek();
        w.setLadderType("score");
        w.setStartsAt(LocalDateTime.parse(startsAt));
        w.setWeekNo(weekNo);
        return w;
    }

    @Test
    void プレシーズンの週には番号を振らない() {
        when(weekRepository.findMaxWeekNo(anyString())).thenReturn(Optional.empty());

        LeagueWeek preseason = week("2026-08-03T12:00", null);
        assertThat(service.assignWeekNo(preseason)).isFalse();
        assertThat(preseason.getWeekNo()).isNull();
    }

    @Test
    void 最初の採番対象週は1番になる() {
        when(weekRepository.findMaxWeekNo(anyString())).thenReturn(Optional.empty());

        LeagueWeek first = week("2026-08-10T12:00", null);
        assertThat(service.assignWeekNo(first)).isTrue();
        assertThat(first.getWeekNo()).isEqualTo(1);
    }

    @Test
    void 以降の週は最大番号プラス1の連番になる() {
        when(weekRepository.findMaxWeekNo(anyString())).thenReturn(Optional.of(1));
        LeagueWeek second = week("2026-08-17T12:00", null);
        assertThat(service.assignWeekNo(second)).isTrue();
        assertThat(second.getWeekNo()).isEqualTo(2);

        // 障害等で 1 週飛んでも番号は飛ばない（#2 の次は日付が 2 週後でも #3）。
        when(weekRepository.findMaxWeekNo(anyString())).thenReturn(Optional.of(2));
        LeagueWeek afterGap = week("2026-08-31T12:00", null);
        assertThat(service.assignWeekNo(afterGap)).isTrue();
        assertThat(afterGap.getWeekNo()).isEqualTo(3);
    }

    @Test
    void 採番済みの週は再採番しない() {
        when(weekRepository.findMaxWeekNo(anyString())).thenReturn(Optional.of(5));

        LeagueWeek already = week("2026-08-17T12:00", 2);
        assertThat(service.assignWeekNo(already)).isFalse();
        assertThat(already.getWeekNo()).isEqualTo(2);
    }
}
