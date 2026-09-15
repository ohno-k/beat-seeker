package com.beatseeker.backend.service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 【ユーティリティの役割】 IIDX の作品バージョン（1st&substream 〜 現行作）に関する定数を集約する。
 *
 * 現実世界の概念: IIDX は 1 年ごとに新作へ切り替わり、その都度スコアがリセットされる。
 * beat-seeker では「現行作のスコア」を {@code scores} テーブルに、
 * 「過去作のスコア」を {@code past_scores} テーブル（{@link com.beatseeker.backend.entity.PastScore}）に
 * 分けて保持する。この分離により、ランキング・BEAT-PT・リーグ・大会などの既存集計 SQL は
 * 一切変更せずに「現行作のみ」を集計し続けられる（過去作が混入することが構造上あり得ない）。
 *
 * 採番は {@link com.beatseeker.backend.entity.VirtualRival#getVersionNum()} や
 * top-rankers の manifest.json と同一（30=RESIDENT, 31=EPOLIS, ...）。
 *
 * ■ 現行作は「切替日時」で自動的に切り替わる（2026-09-16 ZINRAI 稼働対応）
 * 稼働当日に手作業でデプロイしなくて済むよう、{@link #current()} は固定値ではなく
 * 「切替日時（JST）を過ぎたら {@link #NEXT}、それまでは {@link #PREVIOUS}」を返す。
 * 切替日時は {@code app.version-transition.launch-at} と同じ値を
 * {@link VersionSwitchConfigurer} が起動時に流し込む（未設定なら下のコード既定値）。
 * 世代切り替えの本体（スナップショット・過去作への複製・初期化）は
 * {@link VersionTransitionScheduler} が同じ日時に実行する。
 *
 * バージョン判定自体はフロントエンド（CSV の「バージョン」列 = 楽曲の初出作品名）で行うため、
 * ここでは「サーバ側で受け入れて良い値か」の検証と、表示用の作品名だけを持つ。
 * フロント側の対応表は {@code frontend/src/utils/iidxVersions.ts}（切替日時も同じ値を持つ）。
 */
public final class IidxVersions {

    /** 切替後の現行作（IIDX 34 ZINRAI）。 */
    public static final int NEXT = 34;

    /** 切替前の現行作（IIDX 33 Sparkle Shower）。切替後は「最新の過去作」になる。 */
    public static final int PREVIOUS = 33;

    /** 過去作として取り込みを受け付ける下限バージョン。 */
    public static final int MIN_PAST = 30;

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /**
     * 現行作が {@link #PREVIOUS} → {@link #NEXT} に切り替わる日時（JST）。
     * 既定は ZINRAI 稼働日の朝。{@link VersionSwitchConfigurer} が設定値で上書きする。
     */
    private static volatile LocalDateTime switchAt = LocalDateTime.of(2026, 9, 16, 7, 0);

    /** バージョン番号 → 作品名。表示用途のみ。 */
    private static final Map<Integer, String> NAMES;

    static {
        Map<Integer, String> m = new LinkedHashMap<>();
        m.put(30, "RESIDENT");
        m.put(31, "EPOLIS");
        m.put(32, "Pinky Crush");
        m.put(33, "Sparkle Shower");
        m.put(34, "ZINRAI");
        NAMES = Collections.unmodifiableMap(m);
    }

    private IidxVersions() {
        // ユーティリティクラスのためインスタンス化しない
    }

    /**
     * 【メソッドの役割】 切替日時を設定する（起動時に {@link VersionSwitchConfigurer} が呼ぶ）。
     *
     * @param at 切替日時（JST）。null なら既定値のまま
     */
    public static void configureSwitchAt(LocalDateTime at) {
        if (at != null) {
            switchAt = at;
        }
    }

    /** 【メソッドの役割】 現在設定されている切替日時（JST）を返す。 */
    public static LocalDateTime switchAt() {
        return switchAt;
    }

    /**
     * 【メソッドの役割】 現行作のバージョン番号を返す。現行作のスコアは {@code scores} テーブル側で管理する。
     *
     * 切替日時（JST）を過ぎていれば {@link #NEXT}、それまでは {@link #PREVIOUS}。
     * 稼働当日に再デプロイせずに切り替えるための仕組み。
     */
    public static int current() {
        return LocalDateTime.now(JST).isBefore(switchAt) ? PREVIOUS : NEXT;
    }

    /** 【メソッドの役割】 過去作として取り込みを受け付ける上限バージョン（現行作の 1 つ前）。 */
    public static int maxPast() {
        return current() - 1;
    }

    /**
     * 【メソッドの役割】 過去作テーブルへの保存を許可するバージョンかを判定する。
     *
     * 現行作（{@link #current()}）は {@code scores} 側が正なので、ここでは意図的に false を返す。
     * 「どちらのテーブルが正か」が曖昧になるのを防ぐための線引き。
     *
     * @param version 判定対象のバージョン番号（null 可）
     * @return MIN_PAST〜現行作の 1 つ前 の範囲なら true
     */
    public static boolean isSupportedPast(Integer version) {
        return version != null && version >= MIN_PAST && version <= maxPast();
    }

    /**
     * 【メソッドの役割】 バージョン番号に対応する作品名を返す。
     *
     * @param version バージョン番号
     * @return 作品名。未知のバージョンなら "IIDX {version}" 形式のフォールバック文字列
     */
    public static String nameOf(Integer version) {
        if (version == null) return "";
        String name = NAMES.get(version);
        return name != null ? name : ("IIDX " + version);
    }
}
