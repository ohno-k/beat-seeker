package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.LeagueMember;
import com.beatseeker.backend.entity.User;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 【テストの役割】 編成中の週で 2 人の座席を入れ替えるロジックの検証。
 *
 * 検証したいこと:
 *  - 卓(tier)とグループ(groupIndex)だけが入れ替わり、ホーム DIVISION は本人に付いて回ること
 *  - 立場（normal / challenge / defense）が移った先の卓との関係で計算し直されること
 *  - 同じ卓の中でグループだけを入れ替えた場合は立場が変わらないこと
 *
 * DB もリポジトリも触らない純粋なロジックなので、依存はすべて null で組み立てる。
 */
class LeagueMemberSwapTest {

    private long nextUserId = 1;

    /** 座席（卓・グループ）とホーム DIVISION を指定してメンバーを作る。 */
    private LeagueMember member(int tier, int groupIndex, int homeTier, String role) {
        User user = new User();
        user.setId(nextUserId++);
        user.setDisplayName("U" + user.getId());
        LeagueMember m = new LeagueMember();
        m.setUser(user);
        m.setTier(tier);
        m.setGroupIndex(groupIndex);
        m.setHomeTier(homeTier);
        m.setRole(role);
        return m;
    }

    /** 同じ卓の中でグループをまたぐ入れ替え: 座席だけが入れ替わり、立場は normal のまま。 */
    @Test
    void swapsGroupsWithinSameTier() {
        LeagueMember a = member(2, 0, 2, "normal");
        LeagueMember b = member(2, 1, 2, "normal");

        LeagueWeekLifecycleService.swapSeats(a, b);

        assertEquals(2, a.getTier());
        assertEquals(1, a.getGroupIndex());
        assertEquals(2, b.getTier());
        assertEquals(0, b.getGroupIndex());
        assertEquals("normal", a.getRole());
        assertEquals("normal", b.getRole());
        assertEquals(2, a.getHomeTier());
        assertEquals(2, b.getHomeTier());
    }

    /** 卓をまたぐ入れ替え: ホーム DIVISION は動かず、立場が挑戦 / 防衛に付け替わる。 */
    @Test
    void recomputesRoleWhenSwappedAcrossTiers() {
        LeagueMember upper = member(1, 0, 1, "normal"); // DIVISION 1 の人が DIVISION 1 の卓
        LeagueMember lower = member(2, 1, 2, "normal"); // DIVISION 2 の人が DIVISION 2 の卓

        LeagueWeekLifecycleService.swapSeats(upper, lower);

        // DIVISION 1 の人は格下の卓へ = 防衛
        assertEquals(2, upper.getTier());
        assertEquals(1, upper.getGroupIndex());
        assertEquals(1, upper.getHomeTier());
        assertEquals("defense", upper.getRole());
        // DIVISION 2 の人は格上の卓へ = 挑戦
        assertEquals(1, lower.getTier());
        assertEquals(0, lower.getGroupIndex());
        assertEquals(2, lower.getHomeTier());
        assertEquals("challenge", lower.getRole());
    }

    /** 既にチャレンジで他卓に居る人がホーム DIVISION の卓へ戻ると normal に戻る。 */
    @Test
    void returningToHomeTierBecomesNormal() {
        LeagueMember challenger = member(1, 0, 3, "challenge"); // DIVISION 3 の人が DIVISION 1 の卓
        LeagueMember home = member(3, 0, 3, "normal");          // DIVISION 3 の人が DIVISION 3 の卓

        LeagueWeekLifecycleService.swapSeats(challenger, home);

        assertEquals(3, challenger.getTier());
        assertEquals("normal", challenger.getRole());
        assertEquals(1, home.getTier());
        assertEquals("challenge", home.getRole());
    }

    /** ホーム DIVISION 未設定の古いデータは「入れ替え前の座席」がホームとして確定される。 */
    @Test
    void fillsMissingHomeTierFromSeatBeforeSwap() {
        LeagueMember legacy = member(5, 0, 5, "normal");
        legacy.setHomeTier(null); // home_tier カラムが入る前に作られた行を想定
        LeagueMember other = member(6, 0, 6, "normal");

        LeagueWeekLifecycleService.swapSeats(legacy, other);

        // 入れ替え前の座席 DIVISION 5 がホームとして残り、DIVISION 6 の卓では防衛になる。
        assertEquals(5, legacy.getHomeTier());
        assertEquals(6, legacy.getTier());
        assertEquals("defense", legacy.getRole());
        assertEquals(6, other.getHomeTier());
        assertEquals(5, other.getTier());
        assertEquals("challenge", other.getRole());
    }

    /** 立場の判定（格上の卓 = challenge / 格下の卓 = defense / 同じ = normal）。 */
    @Test
    void roleFollowsSeatRelativeToHome() {
        assertEquals("normal", LeagueWeekLifecycleService.roleFor(4, 4));
        assertEquals("challenge", LeagueWeekLifecycleService.roleFor(4, 2));
        assertEquals("defense", LeagueWeekLifecycleService.roleFor(4, 6));
    }
}
