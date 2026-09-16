package com.beatseeker.backend.controller;

import com.beatseeker.backend.controller.ScoreController.ScoreUploadRequest;
import com.beatseeker.backend.controller.StaleUploadGuard.Verdict;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 前作のスコアデータを現行作として取り込もうとした送信だけを拒否し、
 * 稼働初日の正常な CSV・新曲未プレーの CSV・INFINITAS・旧クライアントは通すことを検証する。
 *
 * 判定材料は 2026-09-16（34 ZINRAI 稼働初日）に実際に起きた 3 件（前作ページのブックマークレット 1 件、
 * 切替前にダウンロードした前作 CSV 2 件）と、同日の正常な取り込み 2 件の特徴から決めている。
 */
class StaleUploadGuardTest {

    private static final LocalDateTime SWITCH = LocalDateTime.of(2026, 9, 16, 7, 0);
    private static final int CURRENT = 34;
    private static final int MIN = 5;

    private static ScoreUploadRequest rec(String title, int score, String lastPlay, String source, Integer sourceVersion) {
        return new ScoreUploadRequest(title, "artist", "genre", "ANOTHER", 12, score, score > 0 ? "HARD CLEAR" : "NO PLAY",
                "A", 0, 0, null, 1, lastPlay, source, sourceVersion);
    }

    private static List<ScoreUploadRequest> staleCsv(int scored, int lampOnly) {
        List<ScoreUploadRequest> list = new ArrayList<>();
        for (int i = 0; i < scored; i++) list.add(rec("stale" + i, 2000 + i, "2026-09-15 20:1" + (i % 10), "arcade", null));
        for (int i = 0; i < lampOnly; i++) list.add(rec("lamp" + i, 0, "2026-03-0" + (1 + i % 9) + " 12:00", "arcade", null));
        return list;
    }

    @Test
    void 切替前にダウンロードした前作CSVは拒否する() {
        Optional<Verdict> v = StaleUploadGuard.check(staleCsv(50, 1000), CURRENT, SWITCH, MIN);
        assertThat(v).isPresent();
        assertThat(v.get().code()).isEqualTo(StaleUploadGuard.CODE_STALE_VERSION_DATA);
        assertThat(v.get().staleScored()).isEqualTo(50);
        assertThat(v.get().fresh()).isZero();
        assertThat(v.get().message()).contains("Sparkle Shower").contains("2026/09/16 07:00").contains("50 件");
    }

    @Test
    void 稼働初日に新作で遊んだCSVは通す() {
        // 新作で 2 譜面プレー（切替以降の日時・スコアあり）＋ 前作から持ち越したランプだけの譜面（スコア 0）
        List<ScoreUploadRequest> list = staleCsv(0, 300);
        list.add(rec("BENiZAKURA", 1300, "2026-09-16 12:22", "arcade", null));
        list.add(rec("9-1", 1500, "2026-09-16 12:25", "arcade", null));
        assertThat(StaleUploadGuard.check(list, CURRENT, SWITCH, MIN)).isEmpty();
    }

    @Test
    void 新作でまだ遊んでいない人のCSVは全譜面スコア0なので通す() {
        assertThat(StaleUploadGuard.check(staleCsv(0, 800), CURRENT, SWITCH, MIN)).isEmpty();
    }

    @Test
    void 切替以降のプレーが1件でもあれば前作扱いにしない() {
        List<ScoreUploadRequest> list = staleCsv(50, 100);
        list.add(rec("new", 900, "2026-09-16 07:00", "arcade", null)); // 切替ちょうどは「以降」
        assertThat(StaleUploadGuard.check(list, CURRENT, SWITCH, MIN)).isEmpty();
    }

    @Test
    void 閾値未満なら通す() {
        assertThat(StaleUploadGuard.check(staleCsv(MIN - 1, 100), CURRENT, SWITCH, MIN)).isEmpty();
        assertThat(StaleUploadGuard.check(staleCsv(MIN, 100), CURRENT, SWITCH, MIN)).isPresent();
    }

    @Test
    void 切替前は判定しない() {
        assertThat(StaleUploadGuard.check(staleCsv(50, 100), 33, SWITCH, MIN)).isEmpty();
    }

    @Test
    void 最終プレー日時が無い旧ブックマークレットの送信は判定材料が無いので通す() {
        List<ScoreUploadRequest> list = new ArrayList<>();
        for (int i = 0; i < 400; i++) list.add(rec("chart" + i, 2500, "", "arcade", null));
        assertThat(StaleUploadGuard.check(list, CURRENT, SWITCH, MIN)).isEmpty();
    }

    @Test
    void INFINITAS由来のレコードは対象外() {
        List<ScoreUploadRequest> list = new ArrayList<>();
        for (int i = 0; i < 20; i++) list.add(rec("inf" + i, 2500, "2026-09-15 20:00", "infinitas", null));
        assertThat(StaleUploadGuard.check(list, CURRENT, SWITCH, MIN)).isEmpty();
    }

    @Test
    void 前作ページで実行したブックマークレットは作品番号で拒否する() {
        List<ScoreUploadRequest> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) list.add(rec("chart" + i, 2500, "", "arcade", 33));
        Optional<Verdict> v = StaleUploadGuard.check(list, CURRENT, SWITCH, MIN);
        assertThat(v).isPresent();
        assertThat(v.get().code()).isEqualTo(StaleUploadGuard.CODE_STALE_PAGE_VERSION);
        assertThat(v.get().sourceVersion()).isEqualTo(33);
        assertThat(v.get().message()).contains("/game/2dx/34/");
    }

    @Test
    void 現行作ページのブックマークレットは通す() {
        List<ScoreUploadRequest> list = new ArrayList<>();
        for (int i = 0; i < 300; i++) list.add(rec("chart" + i, 2500, "", "arcade", 34));
        assertThat(StaleUploadGuard.check(list, CURRENT, SWITCH, MIN)).isEmpty();
    }

    @Test
    void 空の送信は通す() {
        assertThat(StaleUploadGuard.check(List.of(), CURRENT, SWITCH, MIN)).isEmpty();
        assertThat(StaleUploadGuard.check(null, CURRENT, SWITCH, MIN)).isEmpty();
    }
}
