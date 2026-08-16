package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.DifficultyRank;
import com.beatseeker.backend.entity.DifficultyRankSong;
import com.beatseeker.backend.entity.LeagueSong;
import com.beatseeker.backend.entity.LeagueWeek;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.SongDefinition;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.DifficultyRankRepository;
import com.beatseeker.backend.repository.LeagueSongRepository;
import com.beatseeker.backend.repository.ScoreRepository;
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

    /**
     * 抽選で選ばれた 1 曲。{@code fallback} = 通常の選曲基準を満たす候補が足りず、
     * プール全体からの補填（またはプールそのものの拡大）で埋めた枠であることを表す。
     * 集計上は通常曲と同じ扱いで、管理者が差し替え候補を見つけるための印。
     */
    public record DrawnSong(SongDefinition song, boolean fallback) {
    }

    private final SongDefinitionRepository songDefinitionRepository;
    private final DifficultyRankRepository difficultyRankRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final ScoreRepository scoreRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueSongDrawService(SongDefinitionRepository songDefinitionRepository,
                                 DifficultyRankRepository difficultyRankRepository,
                                 LeagueSongRepository leagueSongRepository,
                                 ScoreRepository scoreRepository) {
        this.songDefinitionRepository = songDefinitionRepository;
        this.difficultyRankRepository = difficultyRankRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.scoreRepository = scoreRepository;
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
        // 「その DIVISION の帯に入っていた曲」。緩和で帯の外から拾った曲を後で見分けるために控えておく。
        Set<String> bandTitles = new HashSet<>();
        for (SongDefinition sd : pool) bandTitles.add(sd.getTitle());

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
            // 緩和で拾った曲（直近出題の再出題／帯の外）はフォールバック扱いで印を付ける。
            song.setFallback(recentTitles.contains(sd.getTitle()) || !bandTitles.contains(sd.getTitle()));
            drawn.add(song);
        }
        return leagueSongRepository.saveAll(drawn);
    }

    /**
     * 【メソッドの役割】 指定週・階級・グループの課題曲 3 曲を、グループ参加者の実力に合わせて抽選・保存する。
     *
     * 抽選の考え方（2026-07-28 実装・ユーザー確定仕様）:
     *  - 候補 = ②（グループの全員が未プレー＝ライン無し） ∪ ③（2 人以上がプレー済みで、その人たちの
     *    自己ベストレートの最高−最低が {@link #tightSpread} 以内＝拮抗）。1 人だけプレー済みの曲は候補外。
     *  - 候補からランダムに 3 曲（タイトル単位・重複なし）。直近 {@link #EXCLUDE_WEEKS} 週の出題は除外。
     *  - ただし「ライン保持者（各曲の最高 EX の人）」がグループ内でなるべく重複しないよう選ぶ
     *    （1 人が複数ラインを持つのを避けてラインを分散。2026-07-29 ユーザー要望）。
     *  - 候補が 3 曲に満たなければ、プール全体からランダムに補充する（フォールバック）。
     *    補填した枠は {@link LeagueSong#getFallback()} = true で保存し、管理者画面で色分け表示する。
     *
     * @param week       対象週
     * @param tier       階級（プールの難易度帯を決める）
     * @param groupIndex グループ番号
     * @param members    そのグループの参加者
     * @return 保存した課題曲 3 曲
     */
    @Transactional
    public List<LeagueSong> drawSongsForGroup(LeagueWeek week, int tier, int groupIndex, List<User> members) {
        leagueSongRepository.deleteByWeekAndTierAndGroupIndex(week, tier, groupIndex);
        List<DrawnSong> chosen = selectSongsForGroup(tier, members, week.getStartsAt());

        List<LeagueSong> drawn = new ArrayList<>();
        for (DrawnSong pick : chosen) {
            SongDefinition sd = pick.song();
            LeagueSong song = new LeagueSong();
            song.setWeek(week);
            song.setTier(tier);
            song.setGroupIndex(groupIndex);
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

    /**
     * 【メソッドの役割】 グループの課題曲 3 曲を選定して返す（<b>永続化しない</b>）。
     *
     * {@link #drawSongsForGroup} の中核選定ロジックであり、管理者の「仮編成プレビュー」からも
     * 同じ基準で選曲するために共有する。選定基準は drawSongsForGroup の Javadoc 参照
     * （②全員未プレー ∪ ③2 人以上で拮抗、1 人のみは除外、ランダム 3 曲、直近除外、プール全体フォールバック）。
     *
     * @param tier           階級（プールの難易度帯を決める）
     * @param members        そのグループの参加者
     * @param referenceStart 「直近 N 週の出題除外」の基準となる週開始日時（プレビューでは開始予定日時）
     * @return 選定した課題曲（スロット順、通常 3 件）。フォールバック補填の枠には印が付く
     */
    public List<DrawnSong> selectSongsForGroup(int tier, List<User> members, LocalDateTime referenceStart) {
        return selectSongsForGroup(tier, members, referenceStart, java.util.Set.of());
    }

    /**
     * {@link #selectSongsForGroup(int, List, LocalDateTime)} の拡張版。{@code alsoExclude} に渡した
     * タイトルも出題対象から外す。本抽選（{@link #drawSongsForGroup}）は保存済みの他グループ課題曲を
     * DB の直近出題クエリ経由で自然に除外できるが、保存しない「仮編成プレビュー」では同一階級の
     * 他グループと課題曲が重複してしまうため、既に選んだタイトルを明示的に渡してもらって除外する。
     *
     * @param alsoExclude recent に追加で除外するタイトル（同一週・同一階級の他グループの出題曲など）
     */
    public List<DrawnSong> selectSongsForGroup(int tier, List<User> members, LocalDateTime referenceStart,
                                               Set<String> alsoExclude) {
        Map<String, SongDefinition> masterIndex = buildMasterIndex();
        int[] band = rankBandTenths(tier);
        List<SongDefinition> rawPool = buildPool(masterIndex, band[0], band[1]);
        // 帯のプールが薄くて難易度表全体へ広げた場合は、その階級の想定難易度から外れるので
        // 選ばれた曲すべてをフォールバック扱いにする。
        boolean poolWidened = false;
        if (distinctTitleCount(rawPool) < SONGS_PER_WEEK) {
            rawPool = buildPool(masterIndex, 0, 9999); // プールが薄い場合は難易度表全体（Lv11 以上）へ拡大
            poolWidened = true;
        }
        // タイトル単位に一意化（同一タイトルの別譜面は 1 つに）。
        LinkedHashMap<String, SongDefinition> poolByTitle = new LinkedHashMap<>();
        for (SongDefinition sd : rawPool) poolByTitle.putIfAbsent(sd.getTitle(), sd);
        List<SongDefinition> pool = new ArrayList<>(poolByTitle.values());

        Set<String> recent = new HashSet<>(leagueSongRepository.findRecentTitlesByTier(
                tier, referenceStart.minusWeeks(EXCLUDE_WEEKS)));
        recent.addAll(alsoExclude); // 同一週・同一階級で既に他グループへ出したタイトルも除外する

        // グループ参加者の「アーケード自己ベストレート」をタイトルごとに集める。
        List<String> titles = new ArrayList<>(poolByTitle.keySet());
        List<String> diffs = pool.stream()
                .map(sd -> LeagueChartNotation.codeToName(sd.getDifficulty())).distinct().toList();
        Map<String, List<Double>> ratesByTitle = new HashMap<>();
        Map<String, Long> holderByTitle = new HashMap<>();      // タイトル → ライン保持者(最高 EX の人)の userId
        Map<String, Integer> holderExByTitle = new HashMap<>(); // タイトル → その最高 EX
        for (User u : members) {
            Map<String, Integer> bestExByTitle = new HashMap<>();
            for (Score s : scoreRepository.findByUserAndTitlesAndDifficulties(u, titles, diffs)) {
                if (s.getSource() != null && !"arcade".equals(s.getSource())) continue; // アーケード限定
                if (s.getScore() == null || s.getScore() <= 0) continue;
                SongDefinition sd = poolByTitle.get(s.getTitle());
                if (sd == null) continue;
                if (!LeagueChartNotation.codeToName(sd.getDifficulty()).equals(s.getDifficultyName())) continue;
                bestExByTitle.merge(s.getTitle(), s.getScore(), Math::max);
            }
            for (Map.Entry<String, Integer> e : bestExByTitle.entrySet()) {
                String title = e.getKey();
                int ex = e.getValue();
                SongDefinition sd = poolByTitle.get(title);
                double rate = ex * 100.0 / (sd.getNotes() * 2);
                ratesByTitle.computeIfAbsent(title, k -> new ArrayList<>()).add(rate);
                // ライン保持者（最高 EX の人）を記録する（同点は先に記録した人を優先＝安定）。
                Integer curEx = holderExByTitle.get(title);
                if (curEx == null || ex > curEx) {
                    holderExByTitle.put(title, ex);
                    holderByTitle.put(title, u.getId());
                }
            }
        }

        double thr = tightSpread(tier);
        List<SongDefinition> candidates = new ArrayList<>();
        for (SongDefinition sd : pool) {
            if (recent.contains(sd.getTitle())) continue; // 直近出題は候補から除外
            List<Double> rs = ratesByTitle.getOrDefault(sd.getTitle(), List.of());
            boolean cand;
            if (rs.isEmpty()) {
                cand = true;                                 // ② 全員未プレー
            } else if (rs.size() >= 2) {
                cand = (Collections.max(rs) - Collections.min(rs)) <= thr; // ③ 2 人以上で拮抗
            } else {
                cand = false;                                // 1 人のみプレー → 除外
            }
            if (cand) candidates.add(sd);
        }

        // 候補からランダムに 3 曲選ぶ。ただし「ライン保持者（各曲の最高 EX の人）」がグループ内で
        // なるべく重複しないようにする（1 人が複数のラインを持つのを避け、卓の中でラインを分散させる）。
        // ② 曲（誰も未プレー＝ラインなし）は保持者が居ないので自由に選べる。
        Collections.shuffle(candidates);
        List<DrawnSong> chosen = new ArrayList<>();
        Set<String> used = new HashSet<>();
        Set<Long> usedHolders = new HashSet<>();
        // パス1: ライン保持者がまだ登場していない曲（または②曲）を優先して埋める。
        for (SongDefinition sd : candidates) {
            if (chosen.size() >= SONGS_PER_WEEK) break;
            if (used.contains(sd.getTitle())) continue;
            Long holder = holderByTitle.get(sd.getTitle()); // null = ②曲（ラインなし）
            if (holder != null && !usedHolders.add(holder)) continue; // 既出の保持者はパス1では飛ばす
            used.add(sd.getTitle());
            chosen.add(new DrawnSong(sd, poolWidened));
        }
        // パス2: まだ3曲に満たなければ、保持者の重複を許して候補から補充する。
        // （選曲基準②③は満たしているのでフォールバック扱いにはしない）
        for (SongDefinition sd : candidates) {
            if (chosen.size() >= SONGS_PER_WEEK) break;
            if (used.add(sd.getTitle())) chosen.add(new DrawnSong(sd, poolWidened));
        }
        // パス3: それでも足りなければプール全体から補充（直近出題を優先的に避ける）。
        // ここで埋めた枠は選曲基準を満たしていないので、フォールバックの印を付けて管理者に見せる。
        if (chosen.size() < SONGS_PER_WEEK) {
            List<SongDefinition> fallback = new ArrayList<>(pool);
            Collections.shuffle(fallback);
            for (SongDefinition sd : fallback) { // まずは直近出題を避けて補充
                if (chosen.size() >= SONGS_PER_WEEK) break;
                if (recent.contains(sd.getTitle())) continue;
                if (used.add(sd.getTitle())) chosen.add(new DrawnSong(sd, true));
            }
            for (SongDefinition sd : fallback) { // それでも足りなければ直近出題も許容
                if (chosen.size() >= SONGS_PER_WEEK) break;
                if (used.add(sd.getTitle())) chosen.add(new DrawnSong(sd, true));
            }
        }
        return chosen;
    }

    /**
     * 【メソッドの役割】 指定 DIVISION の選曲プール（抽選候補の母集団）を返す。
     *
     * 管理者が課題曲を差し替えるとき「その DIVISION で出題され得る曲」から選べるようにするために使う。
     * {@link #selectSongsForGroup} と同じ難易度帯・同じフォールバック（プールが薄い場合は難易度表全体）で
     * 作り、タイトル単位に一意化する。実際の抽選で掛かる絞り込み（直近出題の除外・グループの拮抗判定）は
     * かけない（管理者が任意に選べるようにするため）。
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
        LinkedHashMap<String, SongDefinition> byTitle = new LinkedHashMap<>();
        for (SongDefinition sd : rawPool) byTitle.putIfAbsent(sd.getTitle(), sd);
        return new ArrayList<>(byTitle.values());
    }

    /**
     * 【メソッドの役割】 「拮抗」判定のレート差しきい値（%）を DIVISION（tier）ごとに段階的に返す。
     *
     * 上位ほど実力が団子なので厳しく、下位ほど広く: LEGEND(0)=0.25% 〜 DIVISION10=2.5% の線形。
     * （2026-08-10 ユーザー要望で従来の半分に引き締め。旧: 0.5% 〜 5.0%）
     *
     * @param tier 階級（0=LEGEND .. 10）
     * @return 拮抗とみなす最高−最低レート差（%）
     */
    private double tightSpread(int tier) {
        return 0.25 + tier * 0.225;
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
