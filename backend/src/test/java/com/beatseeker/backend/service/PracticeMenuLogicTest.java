package com.beatseeker.backend.service;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 練習メニューのうち、DB にも回帰キャッシュにも依存しない土台部分を押さえる。
 *
 * 対象:
 *  - ティア境界の表（{@link BeatTierScale}）が {@code beatTier.ts} と同じ区切りになっているか
 *  - 週の単位が「月曜 0:00 〜 日曜 23:59」になっているか
 *  - 人数不足の上位ティアが 1 バンドに合算されるか
 *
 * ティア境界は
 *  1. フロントの {@code beatTier.ts}
 *  2. {@link BeatTierScale}
 *  3. {@code ScoreRepository.findChartTierBenchmarks()} の CASE 式
 * の 3 箇所に同じ数値が書かれている意図的な重複なので、少なくとも 2 が壊れたら気づけるようにする。
 */
class PracticeMenuLogicTest {

    @Test
    void ティア名が総BEATPTの境界どおりに決まる() {
        assertThat(BeatTierScale.tierOf(0)).isEqualTo("Beginner");
        assertThat(BeatTierScale.tierOf(9999.9)).isEqualTo("Beginner");
        assertThat(BeatTierScale.tierOf(10000)).isEqualTo("Novice");
        assertThat(BeatTierScale.tierOf(12000)).isEqualTo("Intermediate");
        assertThat(BeatTierScale.tierOf(13000)).isEqualTo("Advanced");
        assertThat(BeatTierScale.tierOf(14000)).isEqualTo("Expert");
        assertThat(BeatTierScale.tierOf(14999.9)).isEqualTo("Expert");
        assertThat(BeatTierScale.tierOf(15000)).isEqualTo("Veteran");
        assertThat(BeatTierScale.tierOf(15500)).isEqualTo("Commander");
        assertThat(BeatTierScale.tierOf(16000)).isEqualTo("Elite");
        assertThat(BeatTierScale.tierOf(16500)).isEqualTo("Master");
        assertThat(BeatTierScale.tierOf(17000)).isEqualTo("Ancient");
        assertThat(BeatTierScale.tierOf(17500)).isEqualTo("Mythic");
        assertThat(BeatTierScale.tierOf(18000)).isEqualTo("Legend");
        assertThat(BeatTierScale.tierOf(25000)).isEqualTo("Legend");
    }

    @Test
    void 次のティアは現在ptより上で一番近い境界になる() {
        // Expert のまん中にいるなら、次は Veteran（15000）。Expert IV のような副ティアは見ない。
        BeatTierScale.Tier next = BeatTierScale.nextTierOf(14412.3);
        assertThat(next).isNotNull();
        assertThat(next.name()).isEqualTo("Veteran");
        assertThat(next.minPoints()).isEqualTo(15000);

        // 境界ちょうどに立っている場合、そのティアは「到達済み」なので次は 1 つ上。
        assertThat(BeatTierScale.nextTierOf(15000).name()).isEqualTo("Commander");

        // Beginner の 0 pt からは Novice が次。
        assertThat(BeatTierScale.nextTierOf(0).name()).isEqualTo("Novice");

        // 最上位に到達していれば「次」は存在しない。
        assertThat(BeatTierScale.nextTierOf(18000)).isNull();
    }

    @Test
    void 並び順は下位から上位へ連番になる() {
        assertThat(BeatTierScale.ordinalOf("Beginner")).isZero();
        assertThat(BeatTierScale.ordinalOf("Legend")).isEqualTo(BeatTierScale.TIERS.size() - 1);
        assertThat(BeatTierScale.ordinalOf("Expert")).isLessThan(BeatTierScale.ordinalOf("Veteran"));
        assertThat(BeatTierScale.ordinalOf("知らないティア")).isEqualTo(-1);

        // ordinal → Tier の往復で元に戻る。登竜門譜面の「隣のティア」判定がこの往復に乗っている。
        for (BeatTierScale.Tier tier : BeatTierScale.TIERS) {
            int ord = BeatTierScale.ordinalOf(tier.name());
            assertThat(BeatTierScale.byOrdinal(ord)).isNotNull();
            assertThat(BeatTierScale.byOrdinal(ord).name()).isEqualTo(tier.name());
        }
        assertThat(BeatTierScale.byOrdinal(-1)).isNull();
        assertThat(BeatTierScale.byOrdinal(BeatTierScale.TIERS.size())).isNull();
    }

    @Test
    void 週の始まりは常に月曜になる() {
        LocalDate weekStart = PracticeMenuService.currentWeekStart();
        assertThat(weekStart.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        // 週の終わりは同じ週の日曜。月曜 0:00 〜 日曜 23:59 の 7 日間になっている。
        assertThat(weekStart.plusDays(6).getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);

        // JST の「今日」は必ずこの週に含まれる。
        LocalDate todayJst = LocalDate.now(java.time.ZoneId.of("Asia/Tokyo"));
        assertThat(todayJst).isBetween(weekStart, weekStart.plusDays(6));
    }

    @Test
    void 人数不足の上位ティアは一つのバンドに合算される() {
        // 実測 2026-09-04 で Mythic 20 人 / Legend 4 人。単独ではティア別統計が成立しないため
        // Ancient 以上をまとめて扱う。
        assertThat(TierBenchmarkCacheService.mergeTierName("Ancient"))
                .isEqualTo(TierBenchmarkCacheService.MERGED_TIER_NAME);
        assertThat(TierBenchmarkCacheService.mergeTierName("Mythic"))
                .isEqualTo(TierBenchmarkCacheService.MERGED_TIER_NAME);
        assertThat(TierBenchmarkCacheService.mergeTierName("Legend"))
                .isEqualTo(TierBenchmarkCacheService.MERGED_TIER_NAME);

        // Master 以下は人数が足りているのでそのまま。
        assertThat(TierBenchmarkCacheService.mergeTierName("Master")).isEqualTo("Master");
        assertThat(TierBenchmarkCacheService.mergeTierName("Expert")).isEqualTo("Expert");
        assertThat(TierBenchmarkCacheService.mergeTierName("Beginner")).isEqualTo("Beginner");
        assertThat(TierBenchmarkCacheService.mergeTierName(null)).isNull();
    }

    @Test
    void 枠の大きさは週プレイ数に比例する() {
        // 基準（週 20 プレイ）は 計測 2 / 課題 6 / 埋め 4。
        assertThat(PracticeMenuService.slotsFor(20)).containsExactly(2, 6, 4);

        // 倍にすれば倍。半分にすれば半分。
        assertThat(PracticeMenuService.slotsFor(40)).containsExactly(4, 12, 8);
        assertThat(PracticeMenuService.slotsFor(10)).containsExactly(1, 3, 2);

        // 想定プレイ回数の合計（計測 1 回 / 課題 2 回 / 埋め 1 回）が、
        // 設定した週プレイ数を大きく超えない。
        for (int plays : new int[]{4, 8, 12, 20, 40, 100, 200, 400}) {
            int[] s = PracticeMenuService.slotsFor(plays);
            int totalPlays = s[0] * 1 + s[1] * 2 + s[2] * 1;
            assertThat(totalPlays)
                    .as("週 %d 曲のときの想定曲数", plays)
                    .isLessThanOrEqualTo(plays + 2);
        }
    }

    @Test
    void 週プレイ数はクレジット単位の範囲に丸められる() {
        // 1 クレジット 4 曲。下限 1 クレジット、上限 100 クレジット。
        assertThat(PracticeMenuService.PLAYS_PER_CREDIT).isEqualTo(4);
        assertThat(PracticeMenuService.MIN_WEEKLY_PLAYS).isEqualTo(4);
        assertThat(PracticeMenuService.MAX_WEEKLY_PLAYS).isEqualTo(400);

        assertThat(PracticeMenuService.clampWeeklyPlays(null))
                .isEqualTo(PracticeMenuService.DEFAULT_WEEKLY_PLAYS);
        assertThat(PracticeMenuService.clampWeeklyPlays(0))
                .isEqualTo(PracticeMenuService.MIN_WEEKLY_PLAYS);
        assertThat(PracticeMenuService.clampWeeklyPlays(-30))
                .isEqualTo(PracticeMenuService.MIN_WEEKLY_PLAYS);
        assertThat(PracticeMenuService.clampWeeklyPlays(9999))
                .isEqualTo(PracticeMenuService.MAX_WEEKLY_PLAYS);
        assertThat(PracticeMenuService.clampWeeklyPlays(400)).isEqualTo(400);
        assertThat(PracticeMenuService.clampWeeklyPlays(32)).isEqualTo(32);
    }

    @Test
    void 下限の一クレジットでも三つの枠が全て残る() {
        // 週 4 曲（1 クレジット）でも「計測はしないが課題だけ出る」片寄りにならない。
        int[] s = PracticeMenuService.slotsFor(PracticeMenuService.MIN_WEEKLY_PLAYS);
        assertThat(s[0]).isEqualTo(1);
        assertThat(s[1]).isEqualTo(1);
        assertThat(s[2]).isEqualTo(1);
        // 想定曲数はちょうど 1 クレジット（計測 1 + 課題 2 + 埋め 1）。
        assertThat(s[0] + s[1] * 2 + s[2]).isEqualTo(PracticeMenuService.PLAYS_PER_CREDIT);
    }

    @Test
    void 上限の百クレジットでは枠が二十倍になる() {
        int[] s = PracticeMenuService.slotsFor(PracticeMenuService.MAX_WEEKLY_PLAYS);
        assertThat(s).containsExactly(40, 120, 80);
    }

    @Test
    void 傾向軸は八本で和音ではなく同時押しと呼ぶ() {
        assertThat(TendencyAxisService.AXES).hasSize(8);
        // 元の列名は chordPct（和音）だが、プレイヤーが使う語に合わせて表示名は「同時押し」で統一する。
        assertThat(TendencyAxisService.AXES).contains("同時押し");
        assertThat(TendencyAxisService.AXES).doesNotContain("和音");
        assertThat(TendencyAxisService.AXES)
                .containsExactly("皿", "乱打", "縦連", "トリル", "階段", "同時押し", "ソフラン", "CN");
    }
}
