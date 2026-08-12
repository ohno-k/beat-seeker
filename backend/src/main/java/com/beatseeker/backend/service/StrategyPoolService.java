package com.beatseeker.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.*;

/**
 * 【クラスの役割】 StrategyCard 抽選プール ({@code strategy_card_songs.json}) を
 * 起動時にメモリに読み込み、ランダム抽選を提供するシングルトン Service。
 *
 * <p>JSON のフォーマット: {@code { genre: { level: [ { id, version, title, diff, level } ] } }}
 * ジャンル: NOTES / PEAK / CHORD / CHARGE / SCRATCH / SOF-LAN / INSANE
 * Lv: "8" 〜 "12"
 *
 * <p>抽選は matchKind の Lv 帯 (先鋒=8-10, 次鋒=10, 五将/中堅=11, 三将/副将/大将=12) 全体から
 * ジャンル指定で 1 曲ランダムに返す。StrategyCardView の {@code buildSpinPool} と同じ仕様。
 * ただし置き換え対象の自選曲は候補から除外する ({@code excludeSongStrategyId})。
 */
@Component
public class StrategyPoolService {

    // 戦種別 → Lv 帯は CompetitionMatchKinds に集約 (予選 3 戦 / 決勝 7 戦の両方を含む)。

    /** JSON エントリ 1 件分。strategy_card_songs.json のスキーマに対応。 */
    public static class PoolSong {
        public int id;
        public String version;
        public String title;
        public String diff;   // "A" or "L"
        public int level;
    }

    /** 起動時に読み込んだプール。{@code [genre][level] = List<PoolSong>}。 */
    private Map<String, Map<String, List<PoolSong>>> pool = Map.of();

    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    @Autowired
    public StrategyPoolService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 起動時に classpath から JSON を読み込む。読み込み失敗時は空のプールで動作 (抽選は null を返す)。
     */
    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("strategy_card_songs.json").getInputStream()) {
            this.pool = objectMapper.readValue(is,
                    new TypeReference<Map<String, Map<String, List<PoolSong>>>>() {});
        } catch (Exception e) {
            // 読み込み失敗時は空のプール。StrategyCard 抽選は機能しなくなるが
            // 他の API は通常通り動かす。本番ログには出力されるよう printStackTrace。
            e.printStackTrace();
            this.pool = Map.of();
        }
    }

    /**
     * 【メソッドの役割】 指定ジャンル × matchKind の Lv 帯から、指定曲を除いて 1 曲ランダム抽選する。
     *
     * <p>StrategyCard は「相手の自選曲をランダムな別曲に差し替える」カードなので、
     * 元々の自選曲 (= 置き換え対象の曲) を引いてしまうと発動しなかったのと同じになる。
     * そのため {@code excludeSongStrategyId} に一致する曲は候補から外して抽選する。
     * 除外した結果候補が 0 件になる場合 (Lv 帯にその 1 曲しか存在しない) のみ、
     * 抽選不能を避けるため除外前の候補から引く。
     *
     * @param genre                 'NOTES' / 'PEAK' / ... / 'INSANE'
     * @param matchKind             'vanguard' / 'second' / 'fifth' / 'middle' / 'third' / 'vice' / 'captain'
     * @param excludeSongStrategyId 候補から除外する曲の管理番号 (元々の自選曲)。null なら除外なし
     * @return ランダムに選ばれた曲。プールが空 / 未知の genre or matchKind なら null
     */
    public PoolSong drawRandom(String genre, String matchKind, Integer excludeSongStrategyId) {
        if (genre == null || matchKind == null) return null;
        Map<String, List<PoolSong>> byLevel = pool.get(genre);
        if (byLevel == null) return null;
        List<Integer> levels = CompetitionMatchKinds.levels(matchKind);
        if (levels.isEmpty()) return null;
        List<PoolSong> candidates = new ArrayList<>();
        for (Integer lv : levels) {
            List<PoolSong> arr = byLevel.get(String.valueOf(lv));
            if (arr != null) candidates.addAll(arr);
        }
        if (candidates.isEmpty()) return null;
        if (excludeSongStrategyId != null) {
            List<PoolSong> filtered = new ArrayList<>(candidates.size());
            for (PoolSong s : candidates) {
                if (s.id != excludeSongStrategyId) filtered.add(s);
            }
            if (!filtered.isEmpty()) candidates = filtered;
        }
        return candidates.get(random.nextInt(candidates.size()));
    }
}
