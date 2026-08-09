package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueEntry;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.service.LeagueWeekLifecycleService.Seat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【テストの役割】 少人数 DIVISION の卓を「隣から補充して成立させる」割り当てロジックの検証。
 *
 * 検証したいこと:
 *  - 人数の足りない LEGEND 卓が、格下（DIVISION 1）の BEAT-PT 上位を引き上げて成立すること
 *    （＝ LEGEND の人が DIVISION 1 に降りてこないこと）
 *  - 派遣されるのは「格下からは BEAT-PT 上位」「格上からは BEAT-PT 下位」であること
 *  - 貸した側が {@code MIN_STANDALONE}（4 人）を割らないこと
 *  - 補充できない場合は従来どおり束ね / 吸収にフォールバックすること
 *
 * DB もリポジトリも触らないロジックなので、依存はすべて null で組み立てる。
 */
class LeagueSeatAssignmentTest {

    /** 依存を使わないロジックだけを試すためのインスタンス。 */
    private final LeagueWeekLifecycleService service =
            new LeagueWeekLifecycleService(null, null, null, null, null, null, null, null, null, "2026-08-10");

    private long nextUserId = 1;

    /** BEAT-PT を指定してエントリーを作る。表示名は "D{tier}-{pt}" で読みやすくする。 */
    private LeagueEntry entry(int tier, double beatPt) {
        User user = new User();
        user.setId(nextUserId++);
        user.setIidxId(String.format("%04d-0000", nextUserId));
        user.setDisplayName("D" + tier + "-" + (int) beatPt);
        user.setTotalBeatPt(beatPt);
        LeagueEntry e = new LeagueEntry();
        e.setUser(user);
        e.setLadderType("score");
        e.setCurrentTier(tier);
        return e;
    }

    /** 指定 DIVISION に n 人（BEAT-PT は base から 10 ずつ下がる）を足す。 */
    private void add(List<LeagueEntry> out, int tier, int n, double base) {
        for (int i = 0; i < n; i++) out.add(entry(tier, base - i * 10));
    }

    private List<Seat> assign(List<LeagueEntry> entries) {
        return service.assignSeats(entries, e -> e.getCurrentTier());
    }

    /** 卓ごとの人数（デバッグ表示・検証用）。 */
    private Map<Integer, Long> countByHost(List<Seat> seats) {
        return seats.stream().collect(Collectors.groupingBy(Seat::host, TreeMap::new, Collectors.counting()));
    }

    @Test
    void legendTableIsCompletedByPullingUpTheStrongestFromDivision1() {
        List<LeagueEntry> entries = new ArrayList<>();
        add(entries, 0, 1, 18000);  // LEGEND は 1 人だけ
        add(entries, 1, 8, 17500);  // DIVISION 1 は 8 人（17500, 17490, ... ）
        add(entries, 2, 6, 17000);

        List<Seat> seats = assign(entries);

        // LEGEND 卓が 4 人で成立している（1 人 + 引き上げ 3 人）
        assertEquals(4L, countByHost(seats).get(0), "LEGEND 卓が成立していない");

        // 引き上げられたのは DIVISION 1 の BEAT-PT 上位 3 人で、立場はチャレンジ
        List<Seat> pulledUp = seats.stream()
                .filter(s -> s.host() == 0 && s.homeTier() == 1).toList();
        assertEquals(3, pulledUp.size());
        assertTrue(pulledUp.stream().allMatch(s -> "challenge".equals(s.role())), "立場がチャレンジでない");
        List<Double> pts = pulledUp.stream()
                .map(s -> s.entry().getUser().getTotalBeatPt()).sorted().toList();
        assertEquals(List.of(17480.0, 17490.0, 17500.0), pts, "BEAT-PT 上位が選ばれていない");

        // LEGEND の本人は自分の卓に残る（DIVISION 1 に降りない）
        assertTrue(seats.stream().anyMatch(s -> s.homeTier() == 0 && s.host() == 0 && "normal".equals(s.role())),
                "LEGEND の参加者が自分の卓に居ない");

        // 貸した DIVISION 1 は 5 人残って単独卓を維持（MIN_STANDALONE を割らない）
        assertEquals(5L, countByHost(seats).get(1));
    }

    @Test
    void borrowsFromAboveWhenNoLowerDivisionHasSlack() {
        List<LeagueEntry> entries = new ArrayList<>();
        add(entries, 5, 8, 16000);  // 格上に余裕あり
        add(entries, 6, 2, 15500);  // ここが 2 人で不成立
        // DIVISION 7 は居ない → 格下から借りられないので格上(5)から降ろす

        List<Seat> seats = assign(entries);

        assertEquals(4L, countByHost(seats).get(6), "DIVISION 6 の卓が成立していない");
        List<Seat> sentDown = seats.stream()
                .filter(s -> s.host() == 6 && s.homeTier() == 5).toList();
        assertEquals(2, sentDown.size());
        assertTrue(sentDown.stream().allMatch(s -> "defense".equals(s.role())), "立場がディフェンスでない");
        // 降ろされるのは DIVISION 5 の BEAT-PT 下位 2 人（16000 - 70 / -60）
        List<Double> sentPts = sentDown.stream()
                .map(s -> s.entry().getUser().getTotalBeatPt()).sorted().toList();
        assertEquals(List.of(15930.0, 15940.0), sentPts, "BEAT-PT 下位が選ばれていない");
    }

    @Test
    void doesNotBreakTheDonorBelowMinimum() {
        List<LeagueEntry> entries = new ArrayList<>();
        add(entries, 3, 1, 16800);  // 1 人
        add(entries, 4, 4, 16400);  // ちょうど MIN_STANDALONE（4 人）→ 貸すと自分が割れるので貸せない

        List<Seat> seats = assign(entries);

        // 誰も貸せないので補充は成立せず、従来どおり束ねて 1 卓になる（5 人）
        Map<Integer, Long> byHost = countByHost(seats);
        assertEquals(1, byHost.size(), "卓が 1 つに束ねられていない: " + byHost);
        assertEquals(5L, byHost.values().iterator().next());
    }

    @Test
    void printsFormationForReview() {
        // 実データに近い分布（LEGEND が薄く、DIVISION 1 に強い人が溜まっている状態）
        List<LeagueEntry> entries = new ArrayList<>();
        add(entries, 0, 2, 18000);
        add(entries, 1, 5, 17500);
        add(entries, 2, 12, 17100);
        add(entries, 3, 20, 16800);
        add(entries, 4, 25, 16400);
        add(entries, 5, 18, 16000);
        add(entries, 6, 14, 15500);
        add(entries, 7, 9, 15000);
        add(entries, 8, 6, 14400);
        add(entries, 9, 3, 13400);
        add(entries, 10, 2, 13000);

        List<Seat> seats = assign(entries);

        // 端末の文字コードに左右されないよう、出力は ASCII だけで組み立てる。
        System.out.println("=== TABLES (host: size / breakdown by homeTier+role) ===");
        Map<Integer, List<Seat>> byHost = seats.stream()
                .collect(Collectors.groupingBy(Seat::host, TreeMap::new, Collectors.toList()));
        byHost.forEach((host, list) -> {
            String detail = list.stream()
                    .collect(Collectors.groupingBy(s -> "D" + s.homeTier() + ":" + s.role(), TreeMap::new, Collectors.counting()))
                    .toString();
            System.out.printf("  table D%-2d : %2d players  %s%n", host, list.size(), detail);
        });

        assertEquals(entries.size(), seats.size(), "全員が座れていない");
        byHost.forEach((host, list) ->
                assertTrue(list.size() >= 4, "4 人未満の卓が残っている: host=" + host));
    }
}
