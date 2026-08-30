package com.beatseeker.backend.service;

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
 * バージョン判定自体はフロントエンド（CSV の「バージョン」列 = 楽曲の初出作品名）で行うため、
 * ここでは「サーバ側で受け入れて良い値か」の検証と、表示用の作品名だけを持つ。
 * フロント側の対応表は {@code frontend/src/constants/iidxVersions.ts}。
 */
public final class IidxVersions {

    /** 現行作のバージョン番号。現行作のスコアは {@code scores} テーブル側で管理する。 */
    public static final int CURRENT = 34;

    /** 過去作として取り込みを受け付ける下限バージョン。 */
    public static final int MIN_PAST = 30;

    /** 過去作として取り込みを受け付ける上限バージョン（現行作の 1 つ前）。 */
    public static final int MAX_PAST = CURRENT - 1;

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
     * 【メソッドの役割】 過去作テーブルへの保存を許可するバージョンかを判定する。
     *
     * 現行作（{@link #CURRENT}）は {@code scores} 側が正なので、ここでは意図的に false を返す。
     * 「どちらのテーブルが正か」が曖昧になるのを防ぐための線引き。
     *
     * @param version 判定対象のバージョン番号（null 可）
     * @return 30〜32 の範囲なら true
     */
    public static boolean isSupportedPast(Integer version) {
        return version != null && version >= MIN_PAST && version <= MAX_PAST;
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
