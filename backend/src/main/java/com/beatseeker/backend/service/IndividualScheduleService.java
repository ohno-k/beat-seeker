package com.beatseeker.backend.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 【Service の役割】 個人戦フォーマット ({@code Competition.format = "individual4"}) の予選試合表を生成する。
 *
 * 入力: 参加者 ID のリスト (12 名 or 16 名)。
 * 出力: 試合 (= 4 名のスロット) のリスト。
 * 制約:
 * <ul>
 *   <li>12 名の場合: 18 試合 / 各人 6 試合 (= 4 名 × 3 試合 × 6 ラウンド)。</li>
 *   <li>16 名の場合: 20 試合 / 各人 5 試合 (= 4 名 × 4 試合 × 5 ラウンド)。</li>
 *   <li>同じ相手と何度も当たらないよう、ペア出現回数の最大値を最小化する。</li>
 * </ul>
 *
 * <p>アプローチ: ラウンド単位で参加者を 4 人ずつ卓に振り分ける。
 * 1 ラウンド = 各参加者が 1 試合だけプレイするため、参加者数を 4 で割った数の卓が立つ。
 * ラウンド内の卓割りは、貪欲法 (これまでのペア出現回数が少ない 4 人を優先) で決める。
 * 全体としては複数回シャッフル試行のうち「ペア出現回数の最大値が最小、同じ最大値ならその出現数が少ない」
 * 解を採用する。
 */
@Service
public class IndividualScheduleService {

    private static final int PLAYERS_PER_MATCH = 4;
    /** 解探索の試行回数。複数のランダム順から始めて最良案を残す。 */
    private static final int SEARCH_TRIALS = 200;

    /**
     * 【メソッドの役割】 参加者数に応じた予選試合表を生成する。
     *
     * @param participantIds 参加者 ID リスト (12 or 16 件)
     * @return 試合のリスト。各要素は 4 件のスロット参加者 ID 配列 (slotPosition 1〜4 の順)。
     * @throws IllegalArgumentException 参加者数が 12/16 でない場合
     */
    public List<long[]> generatePrelimSchedule(List<Long> participantIds) {
        int n = participantIds.size();
        if (n != 12 && n != 16) {
            throw new IllegalArgumentException("参加者数は 12 名または 16 名のみ対応 (現在 " + n + " 名)");
        }
        int matchesPerPlayer = (n == 12) ? 6 : 5;
        int tablesPerRound = n / PLAYERS_PER_MATCH; // 12→3, 16→4
        int rounds = matchesPerPlayer;

        // 複数試行のうち「最大ペア出現回数 + そのペア数」が最小の解を採用
        List<long[]> bestSchedule = null;
        int bestMaxPair = Integer.MAX_VALUE;
        int bestMaxPairCount = Integer.MAX_VALUE;

        Random rng = new Random(0xBEA75EEDL); // 再現可能なシード

        for (int trial = 0; trial < SEARCH_TRIALS; trial++) {
            List<long[]> schedule = new ArrayList<>(rounds * tablesPerRound);
            // ペア出現回数: pairCount[i][j] (i<j) = i と j が同卓した回数
            int[][] pairCount = new int[n][n];

            for (int r = 0; r < rounds; r++) {
                // このラウンドで利用可能な参加者 (インデックス) の集合を順序込みでシャッフル
                List<Integer> remaining = new ArrayList<>();
                for (int i = 0; i < n; i++) remaining.add(i);
                Collections.shuffle(remaining, rng);

                for (int t = 0; t < tablesPerRound; t++) {
                    // 残りから 4 人を選ぶ: 先頭 1 人を seed とし、残り 3 人を「seed との pairCount が低い順」で貪欲
                    int seed = remaining.remove(0);
                    // remaining を「(seed と pairCount 昇順, ランダム tiebreak)」でソート
                    final int seedFinal = seed;
                    final int[][] pc = pairCount;
                    remaining.sort((a, b) -> {
                        int pa = pc[Math.min(seedFinal, a)][Math.max(seedFinal, a)];
                        int pb = pc[Math.min(seedFinal, b)][Math.max(seedFinal, b)];
                        if (pa != pb) return Integer.compare(pa, pb);
                        return 0; // 同点は元の順 (シャッフル済) を維持
                    });
                    int p2 = remaining.remove(0);
                    int p3 = remaining.remove(0);
                    int p4 = remaining.remove(0);

                    int[] table = new int[] { seed, p2, p3, p4 };
                    // ペアカウントを更新
                    for (int i = 0; i < table.length; i++) {
                        for (int j = i + 1; j < table.length; j++) {
                            int a = Math.min(table[i], table[j]);
                            int b = Math.max(table[i], table[j]);
                            pairCount[a][b]++;
                        }
                    }
                    long[] slotPlayers = new long[PLAYERS_PER_MATCH];
                    for (int k = 0; k < PLAYERS_PER_MATCH; k++) {
                        slotPlayers[k] = participantIds.get(table[k]);
                    }
                    schedule.add(slotPlayers);
                }
            }

            // この試行の評価: 最大ペア出現回数 + その値が現れたペア数
            int maxPair = 0;
            int maxPairCount = 0;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (pairCount[i][j] > maxPair) {
                        maxPair = pairCount[i][j];
                        maxPairCount = 1;
                    } else if (pairCount[i][j] == maxPair) {
                        maxPairCount++;
                    }
                }
            }
            if (maxPair < bestMaxPair
                    || (maxPair == bestMaxPair && maxPairCount < bestMaxPairCount)) {
                bestMaxPair = maxPair;
                bestMaxPairCount = maxPairCount;
                bestSchedule = schedule;
                // 完璧な解 (理論下限) に到達したら打ち切り
                if (n == 16 && maxPair == 1) break;
                if (n == 12 && maxPair == 2) {
                    // 12 名は理論下限 2、その出現数も最小 (42 ペア) なら打ち切り
                    if (maxPairCount <= 42) break;
                }
            }
        }
        return bestSchedule;
    }
}
