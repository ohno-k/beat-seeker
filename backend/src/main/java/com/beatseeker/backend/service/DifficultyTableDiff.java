package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 【クラスの役割】 難易度表 2 世代（適用前の active と適用する draft）の差分を、
 * 更新履歴ページの「第N版」に載せる 3 分類（新規追加 / 既存変更 / 表から除外）へ整理する純粋関数。
 *
 * 判定は「数値帯（11.0〜13.1 のような {@code 数字.数字}）に居るかどうか」だけを見る。
 * {@code Uncategorized} などの非数値帯は「表に載っていない」扱いなので、
 *  - Uncategorized → 数値帯 … 新規追加
 *  - 数値帯 → 別の数値帯 … 既存変更
 *  - 数値帯 → Uncategorized / 表から消えた … 表から除外
 *  - Uncategorized への出入りだけ … 記録しない（難易度の改訂ではないため）
 * となる。
 *
 * 並び順は表の表示順（帯の sortOrder → 帯内の sortOrder）に揃える。新規追加と既存変更は
 * 適用後の表の順、表から除外は適用前の表の順。DB に依存しないので単体テストで固定できる。
 */
public final class DifficultyTableDiff {

    private DifficultyTableDiff() {
    }

    /** 新規追加 1 件。{@code rank} は入った帯。 */
    public record Added(String title, String rank) {
    }

    /** 既存変更 1 件。{@code from} → {@code to}。 */
    public record Changed(String title, String from, String to) {
    }

    /** 表から除外 1 件。{@code rank} は除外前に居た帯。 */
    public record Removed(String title, String rank) {
    }

    /** 差分の集計結果。 */
    public record Result(List<Added> added, List<Changed> changed, List<Removed> removed) {
        /** 3 分類とも空（＝更新履歴に載せるものが無い）なら true。 */
        public boolean isEmpty() {
            return added.isEmpty() && changed.isEmpty() && removed.isEmpty();
        }
    }

    /** 数値帯の表記。"12.5" や "13.1" にマッチし、"Uncategorized" にはマッチしない。 */
    private static final Pattern NUMERIC_RANK = Pattern.compile("\\d+\\.\\d+");

    /** 数値帯（難易度が付いている帯）かどうか。 */
    public static boolean isNumericRank(String rank) {
        return rank != null && NUMERIC_RANK.matcher(rank.trim()).matches();
    }

    /**
     * 【メソッドの役割】 適用前 → 適用後の差分を計算する。
     *
     * @param before 適用前（active）の帯一覧。null なら空とみなす
     * @param after  適用後（draft）の帯一覧。null なら空とみなす
     * @return 3 分類に整理した差分
     */
    public static Result compute(List<DifficultyRank> before, List<DifficultyRank> after) {
        Map<String, String> was = placements(before);
        Map<String, String> now = placements(after);

        List<Added> added = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        List<Removed> removed = new ArrayList<>();

        for (Map.Entry<String, String> e : now.entrySet()) {
            String prev = was.get(e.getKey());
            if (prev == null) {
                added.add(new Added(e.getKey(), e.getValue()));
            } else if (!prev.equals(e.getValue())) {
                changed.add(new Changed(e.getKey(), prev, e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : was.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                removed.add(new Removed(e.getKey(), e.getValue()));
            }
        }
        return new Result(added, changed, removed);
    }

    /**
     * 帯一覧を「曲名 → 数値帯」に平坦化する（表示順を保つ LinkedHashMap）。
     * 非数値帯の曲は含めない。同じ曲が複数の帯に居る異常データは最初の帯を採用する。
     */
    static Map<String, String> placements(List<DifficultyRank> ranks) {
        Map<String, String> out = new LinkedHashMap<>();
        if (ranks == null) return out;

        Comparator<Integer> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        List<DifficultyRank> ordered = new ArrayList<>(ranks);
        ordered.sort(Comparator.comparing(DifficultyRank::getSortOrder, nullsLast));

        for (DifficultyRank rank : ordered) {
            String value = rank.getRankValue() == null ? null : rank.getRankValue().trim();
            if (!isNumericRank(value) || rank.getSongs() == null) continue;

            List<DifficultyRankSong> songs = new ArrayList<>(rank.getSongs());
            songs.sort(Comparator.comparing(DifficultyRankSong::getSortOrder, nullsLast));
            for (DifficultyRankSong song : songs) {
                if (song.getSongTitle() == null) continue;
                out.putIfAbsent(song.getSongTitle().trim(), value);
            }
        }
        return out;
    }
}
