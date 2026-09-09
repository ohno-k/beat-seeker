package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.service.DifficultyTableDiff.Added;
import com.beatseeker.backend.service.DifficultyTableDiff.Changed;
import com.beatseeker.backend.service.DifficultyTableDiff.Removed;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【テストの目的】 「難易度表を適用」時に更新履歴へ自動記録する差分（{@link DifficultyTableDiff}）が、
 * 更新履歴ページの 3 分類（新規追加 / 既存変更 / 表から除外）どおりに出ることを固定する。
 *
 * 特に押さえたいのは「Uncategorized は表に載っていない扱い」という約束:
 *  - Uncategorized → 数値帯 は新規追加（第4版までの手書き履歴と同じ意味）
 *  - 数値帯 → Uncategorized は表から除外
 *  - Uncategorized への新曲追加だけでは何も記録されない
 */
class DifficultyTableDiffTest {

    /** 帯 1 本を組み立てる。親への逆参照は付けない（Lombok の hashCode 相互再帰を避けるため）。 */
    private static DifficultyRank rank(String value, int order, String... titles) {
        DifficultyRank r = new DifficultyRank();
        r.setRankValue(value);
        r.setSortOrder(order);
        List<DifficultyRankSong> songs = new ArrayList<>();
        for (int i = 0; i < titles.length; i++) {
            DifficultyRankSong s = new DifficultyRankSong();
            s.setSongTitle(titles[i]);
            s.setSortOrder(i);
            songs.add(s);
        }
        r.setSongs(songs);
        return r;
    }

    @Test
    void 新規追加_既存変更_表から除外を数値帯だけで判定する() {
        List<DifficultyRank> before = List.of(
                rank("12.6", 0, "冥", "BRAINSTORM"),
                rank("12.4", 1, "quasar"),
                rank("11.0", 2, "タンポポ"),
                rank("Uncategorized", 3, "Smintheus", "Any%"));
        List<DifficultyRank> after = List.of(
                rank("12.6", 0, "冥"),
                rank("12.4", 1, "quasar", "BRAINSTORM", "Smintheus"),
                rank("11.0", 2),
                rank("Uncategorized", 3, "タンポポ", "Any%"));

        DifficultyTableDiff.Result r = DifficultyTableDiff.compute(before, after);

        assertThat(r.added()).containsExactly(new Added("Smintheus", "12.4"));
        assertThat(r.changed()).containsExactly(new Changed("BRAINSTORM", "12.6", "12.4"));
        assertThat(r.removed()).containsExactly(new Removed("タンポポ", "11.0"));
        assertThat(r.isEmpty()).isFalse();
    }

    @Test
    void 同じ表を適用し直しても差分は空() {
        List<DifficultyRank> table = List.of(
                rank("12.6", 0, "冥"),
                rank("Uncategorized", 1, "Any%"));

        DifficultyTableDiff.Result r = DifficultyTableDiff.compute(table, table);

        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void Uncategorizedへの出入りだけなら記録しない() {
        List<DifficultyRank> before = List.of(rank("12.6", 0, "冥"), rank("Uncategorized", 1));
        List<DifficultyRank> after = List.of(rank("12.6", 0, "冥"), rank("Uncategorized", 1, "新曲A", "新曲B"));

        assertThat(DifficultyTableDiff.compute(before, after).isEmpty()).isTrue();
    }

    @Test
    void 表から消えた曲も除外として記録する() {
        List<DifficultyRank> before = List.of(rank("12.6", 0, "冥", "削除曲"));
        List<DifficultyRank> after = List.of(rank("12.6", 0, "冥"));

        DifficultyTableDiff.Result r = DifficultyTableDiff.compute(before, after);

        assertThat(r.removed()).containsExactly(new Removed("削除曲", "12.6"));
        assertThat(r.added()).isEmpty();
        assertThat(r.changed()).isEmpty();
    }

    @Test
    void 並び順は帯のsortOrder順_帯内のsortOrder順() {
        // 帯はわざと逆順に渡し、sortOrder で並べ直されることを確認する
        List<DifficultyRank> before = List.of(rank("Uncategorized", 9, "a", "b", "c"));
        List<DifficultyRank> after = List.of(
                rank("11.2", 5, "c"),
                rank("12.8", 0, "b", "a"));

        DifficultyTableDiff.Result r = DifficultyTableDiff.compute(before, after);

        assertThat(r.added()).containsExactly(
                new Added("b", "12.8"),
                new Added("a", "12.8"),
                new Added("c", "11.2"));
    }

    @Test
    void 数値帯の判定() {
        assertThat(DifficultyTableDiff.isNumericRank("12.5")).isTrue();
        assertThat(DifficultyTableDiff.isNumericRank("13.1")).isTrue();
        assertThat(DifficultyTableDiff.isNumericRank(" 11.0 ")).isTrue();
        assertThat(DifficultyTableDiff.isNumericRank("Uncategorized")).isFalse();
        assertThat(DifficultyTableDiff.isNumericRank("Uncategorized(other)")).isFalse();
        assertThat(DifficultyTableDiff.isNumericRank("")).isFalse();
        assertThat(DifficultyTableDiff.isNumericRank(null)).isFalse();
    }

    @Test
    void 空やnullの表も扱える() {
        assertThat(DifficultyTableDiff.compute(null, null).isEmpty()).isTrue();
        assertThat(DifficultyTableDiff.compute(List.of(), null).isEmpty()).isTrue();

        DifficultyTableDiff.Result r = DifficultyTableDiff.compute(null, List.of(rank("12.0", 0, "x")));
        assertThat(r.added()).containsExactly(new Added("x", "12.0"));
    }
}
