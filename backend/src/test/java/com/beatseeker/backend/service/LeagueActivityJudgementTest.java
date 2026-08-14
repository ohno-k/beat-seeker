package com.beatseeker.backend.service;

import com.beatseeker.backend.controller.ScoreController.ScoreUploadRequest;
import com.beatseeker.backend.entity.LeagueWeek;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.beatseeker.backend.service.LeagueWeekLifecycleService.withinWeek;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 リーグの活動判定「課題曲の最終プレー日時が開催期間内か」を検証する。
 *
 * 旧仕様は「有効曲（ライン超え）が 0 の週」を不参加と数えていたため、期間中に課題曲を
 * 遊んでいてもスコアが伸びなければ自動休止に近づいてしまっていた。新仕様は
 * 「遊んだ形跡（最終プレー日時）が開催期間に入っているか」だけを見る。
 *
 * 最終プレー日時は eagate の公式 CSV の列（JST 壁時計・"yyyy-MM-dd HH:mm"）に由来し、
 * 週の startsAt/endsAt も JST 壁時計なので、そのまま突き合わせられることが前提。
 * 別書式（INFINITAS が送る ISO-8601 の UTC 文字列など）を混ぜると 9 時間ズレるため、
 * パース側で弾いていることも併せて検証する。
 */
class LeagueActivityJudgementTest {

    /** 開催期間が 2026-08-10(月) 12:00 〜 2026-08-16(日) 21:00 の週を作る。 */
    private LeagueWeek week() {
        LeagueWeek w = new LeagueWeek();
        w.setLadderType("score");
        w.setStartsAt(LocalDateTime.of(2026, 8, 10, 12, 0));
        w.setEndsAt(LocalDateTime.of(2026, 8, 16, 21, 0));
        return w;
    }

    @Test
    void 期間中のプレーは活動ありと判定される() {
        assertThat(withinWeek(LocalDateTime.of(2026, 8, 13, 23, 45), week())).isTrue();
    }

    @Test
    void 開始直前と終了直後は活動なし() {
        // 月曜 12:00 開始の 1 分前 = 前の週の空白時間のプレー
        assertThat(withinWeek(LocalDateTime.of(2026, 8, 10, 11, 59), week())).isFalse();
        // 日曜 21:00 終了の 1 分後 = 締め後のプレー
        assertThat(withinWeek(LocalDateTime.of(2026, 8, 16, 21, 1), week())).isFalse();
    }

    @Test
    void 開始時刻と終了時刻ちょうどは期間内に含む() {
        assertThat(withinWeek(LocalDateTime.of(2026, 8, 10, 12, 0), week())).isTrue();
        assertThat(withinWeek(LocalDateTime.of(2026, 8, 16, 21, 0), week())).isTrue();
    }

    @Test
    void 最終プレー日時が不明なら活動なしに倒す() {
        // 列を持たない取り込み経路（ブックマークレット CSV）や旧データは null になる。
        // ここは false だが、締め処理側で「有効曲 1 曲以上」を保険に OR している。
        assertThat(withinWeek(null, week())).isFalse();
    }

    @Test
    void 公式CSV書式の最終プレー日時をパースできる() {
        assertThat(request("2026-08-13 23:45").parsedLastPlayTime())
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 23, 45));
        // 秒付きで届いた場合も受ける
        assertThat(request("2026-08-13 23:45:07").parsedLastPlayTime())
                .isEqualTo(LocalDateTime.of(2026, 8, 13, 23, 45, 7));
    }

    @Test
    void 空欄や別書式の最終プレー日時は不明として捨てる() {
        assertThat(request(null).parsedLastPlayTime()).isNull();          // 旧クライアント
        assertThat(request("").parsedLastPlayTime()).isNull();            // ブックマークレット CSV の空欄
        assertThat(request("---").parsedLastPlayTime()).isNull();
        assertThat(request("2026/08/13 23:45").parsedLastPlayTime()).isNull();
        // INFINITAS 経路が送る ISO-8601（UTC）。JST 壁時計として扱うと 9 時間ズレるので捨てる。
        assertThat(request("2026-08-13T14:45:00.000Z").parsedLastPlayTime()).isNull();
    }

    /** 最終プレー日時だけを差し替えたアップロードリクエストを作る。 */
    private ScoreUploadRequest request(String lastPlayTime) {
        return new ScoreUploadRequest("Test Song", "artist", "genre", "ANOTHER", 12,
                1500, "CLEAR", "AA", 500, 500, 30, 10, lastPlayTime, "arcade");
    }
}
