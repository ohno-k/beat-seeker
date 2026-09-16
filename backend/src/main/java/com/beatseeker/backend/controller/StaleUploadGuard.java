package com.beatseeker.backend.controller;

import com.beatseeker.backend.controller.ScoreController.ScoreUploadRequest;
import com.beatseeker.backend.service.IidxVersions;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 【クラスの役割】 現行作のスコア取り込み（{@code POST /api/scores/upload}）に前作のデータが混ざるのを止める判定。
 *
 * 現実世界の背景: 作品が切り替わるとスコアは 0 から始まるが、前作の e-amusement ページや手元に保存した
 * 前作の CSV は切替後もそのまま読み込める。取り込みは「ベスト更新のみ書き換える」upsert なので、前作の
 * 高いスコアを一度入れると新作の低い記録が全部無視され、ランキング・BEAT-PT・リーグが前作の値で汚染される。
 * 2026-09-16（34 ZINRAI 稼働初日）に 3 人がこの状態になり、手作業で past_scores へ移した。
 *
 * 判定は 2 段（どちらかに当たれば拒否）:
 *  1. ページの作品番号: ブックマークレットは実行したページの URL（/game/2dx/33/ など）から作品番号を読んで
 *     {@link ScoreUploadRequest#sourceVersion()} に載せる。現行作より古ければ前作のページで実行している。
 *  2. 最終プレー日時: 公式 CSV の「最終プレー日時」が切替日時以降の譜面が 1 件も無く、切替前の日時で
 *     スコアが付いている譜面が一定数以上あれば前作のデータ。新作では切替前にプレーした譜面のスコアは
 *     必ず 0 なので、正常な新作 CSV でこの組み合わせは起きない（新曲を 1 曲も遊んでいない人の CSV は
 *     全譜面スコア 0 で通る。稼働初日に遊んだ人の CSV は切替以降の日時が付くので通る）。
 *
 * 判定しないもの: 切替前（現行作がまだ前作のとき）、INFINITAS 由来のレコード、最終プレー日時も
 * 作品番号も無い旧クライアントの送信（判定材料が無い）。
 *
 * 依存: {@link IidxVersions}（切替日時・作品名）。Spring や DB には依存しない（ユニットテスト可能）。
 */
public final class StaleUploadGuard {

    /** 拒否理由コード: ブックマークレットを前作のページで実行している。 */
    public static final String CODE_STALE_PAGE_VERSION = "STALE_PAGE_VERSION";
    /** 拒否理由コード: 最終プレー日時から前作の CSV と判断した。 */
    public static final String CODE_STALE_VERSION_DATA = "STALE_VERSION_DATA";

    /** 「前作のデータ」と断定するのに必要な、切替前の最終プレー日時でスコアのある譜面数の既定下限。 */
    public static final int DEFAULT_MIN_STALE_SCORED = 5;

    private static final DateTimeFormatter SWITCH_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    /**
     * 判定結果（拒否するとき）。
     *
     * @param code          拒否理由コード（{@link #CODE_STALE_PAGE_VERSION} / {@link #CODE_STALE_VERSION_DATA}）
     * @param message       利用者にそのまま見せる説明（日本語）
     * @param staleScored   切替前の最終プレー日時でスコアのある譜面数
     * @param fresh         切替以降の最終プレー日時の譜面数
     * @param sourceVersion 送られてきたページの作品番号（コード 1 のときだけ）
     */
    public record Verdict(String code, String message, int staleScored, int fresh, Integer sourceVersion) {}

    private StaleUploadGuard() {
        // ユーティリティクラスのためインスタンス化しない
    }

    /**
     * 【メソッドの役割】 取り込み全体を見て、前作のデータなら拒否理由を返す。
     *
     * @param requests       フロントから届いた譜面ごとのレコード
     * @param currentVersion 現行作（{@link IidxVersions#current()}）
     * @param switchAt       前作 → 現行作の切替日時（JST 壁時計。{@link IidxVersions#switchAt()}）
     * @param minStaleScored 段 2 で必要な譜面数の下限（設定値。1 未満は 1 として扱う）
     * @return 拒否するならその理由。通してよければ空
     */
    public static Optional<Verdict> check(List<ScoreUploadRequest> requests, int currentVersion,
                                          LocalDateTime switchAt, int minStaleScored) {
        if (requests == null || requests.isEmpty() || switchAt == null) return Optional.empty();
        // 切替前は現行作が前作そのものなので判定しない。
        if (currentVersion <= IidxVersions.PREVIOUS) return Optional.empty();

        // 段 1: ページの作品番号（ブックマークレット）。
        Integer oldest = null;
        for (ScoreUploadRequest r : requests) {
            Integer v = r.sourceVersion();
            if (v != null && v < currentVersion && (oldest == null || v < oldest)) oldest = v;
        }
        if (oldest != null) {
            String message = "ブックマークレットを前作（" + IidxVersions.nameOf(oldest) + "）のページで実行しています。"
                    + IidxVersions.nameOf(currentVersion) + " の e-amusement ページ（/game/2dx/" + currentVersion
                    + "/）を開いてから実行してください。前作の記録は「過去データ取り込み」で登録できます。";
            return Optional.of(new Verdict(CODE_STALE_PAGE_VERSION, message, 0, 0, oldest));
        }

        // 段 2: 最終プレー日時（公式 CSV）。
        int fresh = 0;
        int staleScored = 0;
        for (ScoreUploadRequest r : requests) {
            if (!"arcade".equals(r.effectiveSource())) continue;
            LocalDateTime lastPlayed = r.parsedLastPlayTime();
            if (lastPlayed == null) continue;
            if (!lastPlayed.isBefore(switchAt)) {
                fresh++;
            } else if (r.score() != null && r.score() > 0) {
                staleScored++;
            }
        }
        int threshold = Math.max(1, minStaleScored);
        if (fresh == 0 && staleScored >= threshold) {
            String message = "前作（" + IidxVersions.nameOf(currentVersion - 1) + "）のスコアデータのようです。"
                    + "最終プレー日時が切替（" + switchAt.format(SWITCH_FMT) + "）より前でスコアのある譜面が "
                    + staleScored + " 件、切替以降にプレーした譜面が 0 件でした。"
                    + IidxVersions.nameOf(currentVersion) + " でプレーした後の CSV を取り込むか、"
                    + "前作の記録は「過去データ取り込み」から登録してください。";
            return Optional.of(new Verdict(CODE_STALE_VERSION_DATA, message, staleScored, fresh, null));
        }
        return Optional.empty();
    }
}
