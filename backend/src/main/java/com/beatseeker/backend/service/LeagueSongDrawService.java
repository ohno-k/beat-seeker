package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【Service の役割】 リーグの週次課題曲 3 曲を DIVISION ごとに自動抽選するサービス。
 *
 * 抽選の考え方:
 *  - 抽選プールは非公式難易度表（difficulty_ranks / difficulty_rank_songs の active）を基準にし、
 *    DIVISION ごとに割り当てた難易度表ランク帯（例: LEGEND = 12.5 以上、DIVISION 10 = 11.0〜11.3）で絞る。
 *    固定の DIVISION → 帯マッピングなので、参加人数に依らず安定した難易度になる。
 *  - <b>その帯の中から完全ランダムに 3 曲</b>（タイトル単位・重複なし）を引く。
 *  - 対象は公式 Lv{@link #OFFICIAL_MIN_LEVEL} 以上の譜面のみ（難易度表は ANOTHER / LEGGENDARIA が中心で、
 *    タイトル末尾 "[L]" が LEGGENDARIA）。スコアレート計算のため notes 判明済みも必須。
 *  - 直近 8 週の同 DIVISION の出題曲（両ラダー・draft 週含む）を除外して重複を避ける。
 *    除外の結果プールが 3 曲を割る場合は段階的に緩和する（重複除外解除 → 難易度表全体へ拡大）。
 *
 * <p><b>各プレイヤーのスコアは一切参照しない</b>（2026-09-21 ユーザー指示で撤廃）。
 * 撤廃前は「② グループ全員が未プレー ∪ ③ 2 人以上がプレー済みで自己ベストレートが拮抗」を
 * 候補条件にし、さらにライン保持者がグループ内で重複しないよう選んでいた。この仕組みは
 * 「煮詰まっていない曲」を選ぶためのものだったが、参加者の実力差が広がると候補が痩せて
 * フォールバック（基準を満たさない補填）が増えるため、難易度帯の中の素のランダムに戻した。
 */
@Service
public class LeagueSongDrawService {

    /** 1 週あたりの課題曲数。 */
    public static final int SONGS_PER_WEEK = 3;
    /** 出題重複を避ける遡り週数。 */
    static final int EXCLUDE_WEEKS = 8;
    /** 抽選対象の公式最小レベル（「公式 Lv11 から」）。 */
    static final int OFFICIAL_MIN_LEVEL = 11;

    /**
     * 抽選で選ばれた 1 曲。{@code fallback} = 通常の選曲基準（帯のプール・直近出題の除外）を
     * 満たす候補が足りず、直近出題の再登板やプールそのものの拡大で埋めた枠であることを表す。
     * 集計上は通常曲と同じ扱いで、管理者が差し替え候補を見つけるための印。
     */
    public record DrawnSong(SongDefinition song, boolean fallback) {
    }

    private final SongDefinitionRepository songDefinitionRepository;
    private final DifficultyRankRepository difficultyRankRepository;
    private final LeagueSongRepository leagueSongRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueSongDrawService(SongDefinitionRepository songDefinitionRepository,
                                 DifficultyRankRepository difficultyRankRepository,
                                 LeagueSongRepository leagueSongRepository) {
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.leagueSongRepository = leagueSongRepository;
    }

    /**
     * 【メソッドの役割】 指定週・指定 DIVISION の課題曲 3 曲を抽選して保存する（グループ分けなし）。
     *
     * グループが未編成の draft 週を管理者が再抽選するときに使う。既存の課題曲は削除してから引き直す。
     * 抽選時点の level / notes をスナップショットとして保存する。
     *
     * @param week 対象週（draft を想定。active 化後の呼び出しはコントローラ側でガードする）
     * @param tier DIVISION（0=LEGEND .. 10）
     * @return 保存した課題曲 3 曲
     */
    @Transactional
    public List<LeagueSong> drawSongsForTier(LeagueWeek week, int tier) {
        leagueSongRepository.deleteByWeekAndTier(week, tier);
        return saveDrawn(week, tier, null, selectSongs(tier, week.getStartsAt(), Set.of()));
    }

    /**
     * 【メソッドの役割】 指定週・階級・グループの課題曲 3 曲を抽選して保存する。
     *
     * 課題曲はグループ単位なので、同じ階級でもグループごとに別の 3 曲になる
     * （同一週・同一階級の他グループの曲は、保存済みの曲を見る直近出題クエリ経由で自然に除外される）。
     *
     * @param week       対象週
     * @param tier       階級（プールの難易度帯を決める）
     * @param groupIndex グループ番号
     * @return 保存した課題曲 3 曲
     */
    @Transactional
    public List<LeagueSong> drawSongsForGroup(LeagueWeek week, int tier, int groupIndex) {
        leagueSongRepository.deleteByWeekAndTierAndGroupIndex(week, tier, groupIndex);
        return saveDrawn(week, tier, groupIndex, selectSongs(tier, week.getStartsAt(), Set.of()));
    }

    /**
     * 【メソッドの役割】 課題曲 3 曲を選定して返す（<b>永続化しない</b>）。
     *
     * 本抽選（{@link #drawSongsForGroup}）と管理者の「仮編成プレビュー」が同じ基準で選ぶための共有処理。
     * 難易度帯のプールから直近出題を除いてシャッフルし、先頭から 3 曲（タイトル重複なし）を採る。
     *
     * @param tier           階級（プールの難易度帯を決める）
     * @param referenceStart 「直近 N 週の出題除外」の基準となる週開始日時（プレビューでは開始予定日時）
     * @param alsoExclude    追加で除外するタイトル。保存しないプレビューで、同一階級の他グループへ
     *                       既に割り当てたタイトルを渡して重複を避けるために使う
     * @return 選定した課題曲（スロット順、3 件）。緩和で埋めた枠には印が付く
     */
    public List<DrawnSong> selectSongs(int tier, LocalDateTime referenceStart, Set<String> alsoExclude) {
        // 帯のプールが薄い場合は難易度表全体（Lv11 以上）へ拡大する。その階級の想定難易度から
        // 外れるので、拡大したときは選ばれた曲すべてをフォールバック扱いにする。
        Map<String, SongDefinition> masterIndex = buildMasterIndex();
        int[] band = rankBandTenths(tier);
        List<SongDefinition> rawPool = buildPool(masterIndex, band[0], band[1]);
        boolean poolWidened = false;
        if (distinctTitleCount(rawPool) < SONGS_PER_WEEK) {
            rawPool = buildPool(masterIndex, 0, 9999);
            poolWidened = true;
        }
        List<SongDefinition> pool = uniqueByTitle(rawPool);

        Set<String> recent = new HashSet<>(leagueSongRepository.findRecentTitlesByTier(
                tier, referenceStart.minusWeeks(EXCLUDE_WEEKS)));
        recent.addAll(alsoExclude);

        List<SongDefinition> candidates = new ArrayList<>(pool.size());
        for (SongDefinition sd : pool) {
            if (!recent.contains(sd.getTitle())) candidates.add(sd);
        }
        Collections.shuffle(candidates);

        List<DrawnSong> chosen = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (SongDefinition sd : candidates) {
            if (chosen.size() >= SONGS_PER_WEEK) break;
            if (used.add(sd.getTitle())) chosen.add(new DrawnSong(sd, poolWidened));
        }
        // 直近出題を除くと 3 曲に満たない場合だけ、再登板を許して埋める（フォールバックの印）。
        if (chosen.size() < SONGS_PER_WEEK) {
            List<SongDefinition> rest = new ArrayList<>(pool);
            Collections.shuffle(rest);
            for (SongDefinition sd : rest) {
                if (chosen.size() >= SONGS_PER_WEEK) break;
                if (used.add(sd.getTitle())) chosen.add(new DrawnSong(sd, true));
            }
        }
        if (chosen.size() < SONGS_PER_WEEK) {
            throw new IllegalStateException("課題曲の抽選プールが不足しています (tier=" + tier + ")");
        }
        return chosen;
    }

    /**
     * 【メソッドの役割】 「その週その DIVISION で出題され得る曲」＝抽選の母集団を返す。
     *
     * 帯のプールから直近 {@link #EXCLUDE_WEEKS} 週の出題を除いたもので、抽選が 3 曲を引く母集団と
     * 同じ。管理者が課題曲を差し替えるときの選択肢に使う。
     *
     * @param tier           階級（0=LEGEND .. 10）
     * @param referenceStart 「直近 N 週の出題除外」の基準となる週開始日時
     * @return 候補の譜面（難易度表のランク順、タイトル重複なし）
     */
    public List<SongDefinition> candidatesForTier(int tier, LocalDateTime referenceStart) {
        Set<String> recent = new HashSet<>(leagueSongRepository.findRecentTitlesByTier(
                tier, referenceStart.minusWeeks(EXCLUDE_WEEKS)));
        List<SongDefinition> out = new ArrayList<>();
        for (SongDefinition sd : poolForTier(tier)) {
            if (!recent.contains(sd.getTitle())) out.add(sd);
        }
        return out;
    }

    /**
     * 【メソッドの役割】 指定 DIVISION の選曲プール（難易度帯の全曲）を返す。
     *
     * 直近出題の除外はかけない（管理者が任意に選べるようにするため）。プールが薄い場合は
     * {@link #selectSongs} と同じく難易度表全体へ拡大する。
     *
     * @param tier 階級（0=LEGEND .. 10）
     * @return プールの譜面（難易度表のランク順 → 登録順、タイトル重複なし）
     */
    public List<SongDefinition> poolForTier(int tier) {
        Map<String, SongDefinition> masterIndex = buildMasterIndex();
        int[] band = rankBandTenths(tier);
        List<SongDefinition> rawPool = buildPool(masterIndex, band[0], band[1]);
        if (distinctTitleCount(rawPool) < SONGS_PER_WEEK) {
            rawPool = buildPool(masterIndex, 0, 9999); // プールが薄い場合は難易度表全体（Lv11 以上）へ拡大
        }
        return uniqueByTitle(rawPool);
    }

    /**
     * 選定結果を {@link LeagueSong} として保存する。抽選時点の level / notes を焼き付ける。
     *
     * @param groupIndex グループ番号。null なら既定（0）のまま＝グループ分けなしの卓一括抽選
     */
    private List<LeagueSong> saveDrawn(LeagueWeek week, int tier, Integer groupIndex, List<DrawnSong> picks) {
        List<LeagueSong> drawn = new ArrayList<>();
        for (DrawnSong pick : picks) {
            SongDefinition sd = pick.song();
            LeagueSong song = new LeagueSong();
            song.setWeek(week);
            song.setTier(tier);
            if (groupIndex != null) {
                song.setGroupIndex(groupIndex);
            }
            song.setSlot(drawn.size() + 1);
            song.setTitle(sd.getTitle());
            song.setDifficultyName(LeagueChartNotation.codeToName(sd.getDifficulty()));
            song.setLevel(sd.getLevel());
            song.setNotes(sd.getNotes());
            song.setFallback(pick.fallback());
            drawn.add(song);
        }
        return leagueSongRepository.saveAll(drawn);
    }

    /** 同一タイトルの別譜面を同時に出題しないよう、タイトル単位で先頭の 1 譜面に絞る。 */
    private List<SongDefinition> uniqueByTitle(List<SongDefinition> list) {
        LinkedHashMap<String, SongDefinition> byTitle = new LinkedHashMap<>();
        for (SongDefinition sd : list) byTitle.putIfAbsent(sd.getTitle(), sd);
        return new ArrayList<>(byTitle.values());
    }

    /**
     * active マスタから「公式 Lv{@link #OFFICIAL_MIN_LEVEL} 以上・notes 判明済み」の譜面を
     * (title | difficulty) キーで索引化する。difficulty は "4"(ANOTHER) / "10"(LEGGENDARIA)。
     */
    private Map<String, SongDefinition> buildMasterIndex() {
        Map<String, SongDefinition> map = new HashMap<>();
        for (SongDefinition sd : songDefinitionRepository.findByRevision("active")) {
            if (sd.getNotes() == null || sd.getNotes() <= 0) continue;
            if (sd.getLevel() == null || sd.getLevel() < OFFICIAL_MIN_LEVEL) continue;
            map.putIfAbsent(sd.getTitle() + "|" + sd.getDifficulty(), sd);
        }
        return map;
    }

    /**
     * 非公式難易度表（active）のうち、ランク値が [minTenths, maxTenths]（0.1 = 1）に入る譜面を
     * マスタ索引で解決してプールにする。難易度表のタイトル末尾 "[L]" は LEGGENDARIA、
     * それ以外は ANOTHER として突合する。マスタに無い・Lv11 未満・notes 不明の曲は除外される。
     *
     * @param masterIndex buildMasterIndex() の結果
     * @param minTenths   ランク値下限（例: 12.6 → 126）
     * @param maxTenths   ランク値上限（例: 12.7 → 127）
     */
    private List<SongDefinition> buildPool(Map<String, SongDefinition> masterIndex, int minTenths, int maxTenths) {
        List<SongDefinition> pool = new ArrayList<>();
        for (DifficultyRank rank : difficultyRankRepository.findByRevisionOrderBySortOrderAsc("active")) {
            int tenths = parseTenths(rank.getRankValue());
            if (tenths < 0 || tenths < minTenths || tenths > maxTenths) continue;
            for (DifficultyRankSong drs : rank.getSongs()) {
                String raw = drs.getSongTitle();
                if (raw == null) continue;
                boolean legg = raw.endsWith("[L]");
                String title = legg ? raw.substring(0, raw.length() - 3) : raw;
                String difficulty = legg ? "10" : "4";
                SongDefinition sd = masterIndex.get(title + "|" + difficulty);
                if (sd != null) {
                    pool.add(sd);
                }
            }
        }
        return pool;
    }

    /**
     * DIVISION → 難易度表ランク帯（0.1 単位の整数、[min, max] 内包）。
     *
     * 隣接 DIVISION と帯を重複させて広めのプールを取る（同じレベル/ランクの曲が複数 DIVISION に
     * またがって出題され得る）。中心値が上位ほど高難度になるよう単調に下げ、LEGEND は 12.5 以上。
     * 本番の難易度表分布ではどの帯も 100 曲以上のプールになる。
     * <pre>
     *   LEGEND : 12.5+        1 : 12.3-12.8   2 : 12.1-12.6   3 : 11.9-12.4
     *   4 : 11.8-12.3   5 : 11.7-12.1   6 : 11.6-12.0   7 : 11.5-11.9
     *   8 : 11.3-11.7   9 : 11.1-11.5   10 : 11.0-11.3
     * </pre>
     *
     * <p>2026-09-20 に全帯の難易度を引き上げた（ユーザー要望）。下限はそのままに、上限だけを
     * 1 ランク（0.1）上へ広げてある。LEGEND は元から上限なしのため変更なし。
     * フロントの説明モーダル（LeagueInfoModal.vue の divisions）も同時に更新すること。
     */
    private int[] rankBandTenths(int tier) {
        return switch (tier) {
            case 0  -> new int[]{125, 9999}; // LEGEND: 12.5 以上
            case 1  -> new int[]{123, 128};
            case 2  -> new int[]{121, 126};
            case 3  -> new int[]{119, 124};
            case 4  -> new int[]{118, 123};
            case 5  -> new int[]{117, 121};
            case 6  -> new int[]{116, 120};
            case 7  -> new int[]{115, 119};
            case 8  -> new int[]{113, 117};
            case 9  -> new int[]{111, 115};
            default -> new int[]{110, 113}; // DIVISION 10
        };
    }

    /** ランク値文字列（"12.5" 等）を 0.1 単位の整数（125）に変換する。数値でなければ -1。 */
    private int parseTenths(String rankValue) {
        if (rankValue == null) return -1;
        try {
            return (int) Math.round(Double.parseDouble(rankValue.trim()) * 10);
        } catch (NumberFormatException e) {
            return -1; // "Uncategorized" 等
        }
    }

    /** 候補リストの中の異なりタイトル数。 */
    private long distinctTitleCount(List<SongDefinition> list) {
        return list.stream().map(SongDefinition::getTitle).distinct().count();
    }
}
