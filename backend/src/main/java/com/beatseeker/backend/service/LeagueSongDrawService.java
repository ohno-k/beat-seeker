package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 【Service の役割】 リーグの週次課題曲 3 曲を DIVISION ごとに自動抽選するサービス。
 *
 * 抽選の考え方:
 *  - 抽選プールは非公式難易度表（difficulty_ranks / difficulty_rank_songs の active）を基準にし、
 *    DIVISION ごとに割り当てた難易度表ランク帯（例: LEGEND = 12.8 以上、DIVISION 10 = 11.0〜11.1）で絞る。
 *    固定の DIVISION → 帯マッピングなので、参加人数に依らず安定した難易度になる。
 *  - 対象は公式 Lv{@link #OFFICIAL_MIN_LEVEL} 以上の譜面のみ（難易度表は ANOTHER / LEGGENDARIA が中心で、
 *    タイトル末尾 "[L]" が LEGGENDARIA）。スコアレート計算のため notes 判明済みも必須。
 *  - 直近 8 週の同 DIVISION の出題曲（スコア/BP 両ラダー・draft 週含む）を除外して重複を避ける。
 *    除外の結果プールが 3 曲を割る場合は段階的に緩和する（重複除外解除 → 難易度表全体へ拡大）。
 */
@Service
public class LeagueSongDrawService {

    /** 1 週あたりの課題曲数。 */
    public static final int SONGS_PER_WEEK = 3;
    /** 出題重複を避ける遡り週数。 */
    static final int EXCLUDE_WEEKS = 8;
    /** 抽選対象の公式最小レベル（「公式 Lv11 から」）。 */
    static final int OFFICIAL_MIN_LEVEL = 11;

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
     * 【メソッドの役割】 指定週・指定 DIVISION の課題曲 3 曲を抽選して保存する。
     *
     * 既存の課題曲がある場合は削除してから引き直す（管理者の再抽選にも使う）。
     * 抽選時点の level / notes をスナップショットとして保存する。
     *
     * @param week        対象週（draft を想定。active 化後の呼び出しはコントローラ側でガードする）
     * @param tier        DIVISION（0=LEGEND .. 10）
     * @param tierMembers （現仕様では未使用。固定の DIVISION → 難易度表帯で抽選するため）
     * @return 保存した課題曲 3 曲
     */
    @Transactional
    public List<LeagueSong> drawSongsForTier(LeagueWeek week, int tier, List<User> tierMembers) {
        leagueSongRepository.deleteByWeekAndTier(week, tier);

        // 公式 Lv11 以上・active マスタの (title|difficulty) → SongDefinition 索引を 1 度だけ作る。
        Map<String, SongDefinition> masterIndex = buildMasterIndex();

        int[] band = rankBandTenths(tier);
        List<SongDefinition> pool = buildPool(masterIndex, band[0], band[1]);

        // 直近の出題曲を除外（同 DIVISION・両ラダー横断）。足りなければ段階的に緩和する。
        Set<String> recentTitles = new HashSet<>(leagueSongRepository.findRecentTitlesByTier(
                tier, week.getStartsAt().minusWeeks(EXCLUDE_WEEKS)));
        List<SongDefinition> candidates = pool.stream()
                .filter(sd -> !recentTitles.contains(sd.getTitle()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (distinctTitleCount(candidates) < SONGS_PER_WEEK) {
            candidates = new ArrayList<>(pool);
        }
        if (distinctTitleCount(candidates) < SONGS_PER_WEEK) {
            candidates = buildPool(masterIndex, 0, 9999); // 最終手段: 難易度表全体（Lv11 以上）へ拡大
        }
        if (distinctTitleCount(candidates) < SONGS_PER_WEEK) {
            throw new IllegalStateException("課題曲の抽選プールが不足しています (tier=" + tier + ")");
        }

        // 同一タイトルの別譜面を同時に出題しないよう、タイトル単位で 3 つ選ぶ。
        Collections.shuffle(candidates);
        List<LeagueSong> drawn = new ArrayList<>();
        Set<String> usedTitles = new HashSet<>();
        for (SongDefinition sd : candidates) {
            if (drawn.size() >= SONGS_PER_WEEK) break;
            if (!usedTitles.add(sd.getTitle())) continue;
            LeagueSong song = new LeagueSong();
            song.setWeek(week);
            song.setTier(tier);
            song.setSlot(drawn.size() + 1);
            song.setTitle(sd.getTitle());
            song.setDifficultyName(LeagueChartNotation.codeToName(sd.getDifficulty()));
            song.setLevel(sd.getLevel());
            song.setNotes(sd.getNotes());
            drawn.add(song);
        }
        return leagueSongRepository.saveAll(drawn);
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
     *   LEGEND : 12.5+        1 : 12.3-12.7   2 : 12.1-12.5   3 : 11.9-12.3
     *   4 : 11.8-12.2   5 : 11.7-12.0   6 : 11.6-11.9   7 : 11.5-11.8
     *   8 : 11.3-11.6   9 : 11.1-11.4   10 : 11.0-11.2
     * </pre>
     */
    private int[] rankBandTenths(int tier) {
        return switch (tier) {
            case 0  -> new int[]{125, 9999}; // LEGEND: 12.5 以上
            case 1  -> new int[]{123, 127};
            case 2  -> new int[]{121, 125};
            case 3  -> new int[]{119, 123};
            case 4  -> new int[]{118, 122};
            case 5  -> new int[]{117, 120};
            case 6  -> new int[]{116, 119};
            case 7  -> new int[]{115, 118};
            case 8  -> new int[]{113, 116};
            case 9  -> new int[]{111, 114};
            default -> new int[]{110, 112}; // DIVISION 10
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
