package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 【Service の役割】 リーグの 1 グループ分の順位表を遅延計算するサービス（リーグモードの中核）。
 *
 * 設計方針:
 *  - 進行中（active）の週は、呼ばれるたびに scores とベースラインから順位を計算し直す。
 *    アップロード経路にリーグ固有の処理を差し込まず、読み取り時に全て判定する。
 *  - 締め済み（closed）の週は、週次締め処理が {@link LeagueMember} に凍結した値から復元する。
 *    （締め後も scores は更新され続けるため、再計算すると結果がズレる）
 *  - ライブ表示と週次締めの両方が {@link #computeGroupStandings} を使う単一実装
 *    （CompetitionIndividualAdminController.computeStandings と同じ思想）。
 *
 * 集計対象はアーケードの記録（source = "arcade"、CSV / ブックマークレット経由）のみ。
 * INFINITAS（source = "infinitas"）の記録はライン算出・有効判定・採用値のすべてから除外する。
 *
 * 「週内プレー」の判定（メンバー × 課題曲、arcade 行）:
 *  - ベースライン行あり: プレー回数増加 or EX スコア更新 or ミスカウント改善 or ランプ改善
 *  - ベースライン行なし: Score.uploadedAt >= week.snapshotAt（週内に初記録が現れた）
 *
 * 昇降格はポイント制:
 *  - 週の順位に応じてポイントが増減する（{@link #deltaForRank}: 8 人なら 1 位 +4 〜 8 位 -4。
 *    人数が少ないほど幅が縮小し、奇数人数では中央順位が ±0）。
 *  - 累積ポイントが +{@link #POINT_CAP} に到達すると昇格、-{@link #POINT_CAP} で降格
 *    （8 人グループの 1 位/最下位は一撃で ±4 なので即昇降格になる）。
 *  - 有効曲 0 の週はプラス分を獲得できない（過疎グループでの放置昇格を防ぐ）。
 */
@Service
public class LeagueStandingsService {

    /** 昇降格ポイントの上限（この値に到達で昇格、負側到達で降格。保持値もこの範囲にクランプ）。 */
    public static final int POINT_CAP = 4;

    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final LeagueBaselineRepository leagueBaselineRepository;
    private final LeagueEntryRepository leagueEntryRepository;
    private final ScoreRepository scoreRepository;
    private final SongDefinitionRepository songDefinitionRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueStandingsService(LeagueMemberRepository leagueMemberRepository,
                                  LeagueSongRepository leagueSongRepository,
                                  LeagueBaselineRepository leagueBaselineRepository,
                                  LeagueEntryRepository leagueEntryRepository,
                                  ScoreRepository scoreRepository,
                                  SongDefinitionRepository songDefinitionRepository) {
        this.leagueMemberRepository = leagueMemberRepository;
        this.leagueSongRepository = leagueSongRepository;
        this.leagueBaselineRepository = leagueBaselineRepository;
        this.leagueEntryRepository = leagueEntryRepository;
        this.scoreRepository = scoreRepository;
        this.songDefinitionRepository = songDefinitionRepository;
    }

    /**
     * 【メソッドの役割】 グループ人数と順位から昇降格ポイントの増減を返す。
     *
     * 上位半分がプラス・下位半分がマイナスで、順位が端に近いほど絶対値が大きい:
     *  - 8 人: 1 位 +4, 2 位 +3, 3 位 +2, 4 位 +1, 5 位 -1, ... 8 位 -4
     *  - 6 人: +3 〜 -3
     *  - 奇数人数（例 7 人）: +3 〜 -3 で中央の 4 位は ±0
     *  - 1 人: 常に 0
     *
     * @param size グループ人数
     * @param rank 順位（1 始まり）
     * @return ポイント増減（-POINT_CAP..+POINT_CAP にクランプ済み）
     */
    public static int deltaForRank(int size, int rank) {
        if (size <= 1) return 0;
        int delta;
        if (size % 2 == 0) {
            int half = size / 2;
            delta = rank <= half ? half - rank + 1 : -(rank - half);
        } else {
            delta = (size + 1) / 2 - rank; // 中央順位で 0、上下対称
        }
        return Math.max(-POINT_CAP, Math.min(POINT_CAP, delta));
    }

    /**
     * 【メソッドの役割】 1 グループ分の順位表を計算して返す。
     *
     * 返り値の 1 行 = 1 メンバーで、順位順に並ぶ。各行には昇降格ゾーン
     * （{@code zone}: promote / stay / relegate）と課題曲ごとの内訳（{@code perSong}）が付く。
     * 週次締め処理はこの {@code zone} をそのまま movement として凍結する。
     *
     * @param week       対象週（active または closed）
     * @param tier       階級
     * @param groupIndex グループ番号
     * @return 順位表（Map のリスト）。メンバー 0 件なら空リスト
     */
    public List<Map<String, Object>> computeGroupStandings(LeagueWeek week, int tier, int groupIndex) {
        List<LeagueMember> members = leagueMemberRepository.findByWeekAndTierAndGroupIndex(week, tier, groupIndex);
        if (members.isEmpty()) {
            return List.of();
        }
        if ("closed".equals(week.getStatus())) {
            return frozenStandings(members);
        }
        return liveStandings(week, tier, members);
    }

    /**
     * 【メソッドの役割】 指定グループの課題曲ごとの「ライン」（週開始時点の最高記録）を slot 順に返す。
     *
     * 観戦（他グループ閲覧）でライン値を表示するために使う。ラインは個人スコアではなく
     * 「グループ共通の閾値」（＝最高ベースライン。設定者は匿名）なので、プライバシー除去の
     * 対象外として扱う。締め済み週や課題曲不在の場合も slot ぶんの行（lineEx=null）を返す。
     *
     * @param week       対象週
     * @param tier       階級
     * @param groupIndex グループ番号
     * @return {@code [{slot, lineEx, lineMiss, lineRate}]}（slot 昇順）
     */
    public List<Map<String, Object>> computeGroupSongLines(LeagueWeek week, int tier, int groupIndex) {
        List<LeagueSong> songs = leagueSongRepository.findByWeekAndTierOrderBySlotAsc(week, tier);
        if (songs.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> notesBySong = resolveNotes(songs);
        List<LeagueMember> members = leagueMemberRepository.findByWeekAndTierAndGroupIndex(week, tier, groupIndex);

        Map<String, Integer> lineExBySong = new HashMap<>();
        Map<String, Integer> lineMissBySong = new HashMap<>();
        if (!members.isEmpty()) {
            List<User> users = members.stream().map(LeagueMember::getUser).toList();
            for (LeagueBaseline b : leagueBaselineRepository.findByWeekAndUserIn(week, users)) {
                if (!"arcade".equals(b.getSource())) continue; // アーケード記録限定
                String key = songKey(b.getTitle(), b.getDifficultyName());
                // EX=0 は空記録（所持のみ・未プレー）なのでラインに含めない（liveStandings と同じ扱い）。
                if (b.getBaseScore() != null && b.getBaseScore() > 0) lineExBySong.merge(key, b.getBaseScore(), Math::max);
                if (b.getBaseMiss() != null) lineMissBySong.merge(key, b.getBaseMiss(), Math::min);
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (LeagueSong s : songs) {
            String key = songKey(s.getTitle(), s.getDifficultyName());
            Integer lineEx = lineExBySong.get(key);
            Integer lineMiss = lineMissBySong.get(key);
            Integer notes = notesBySong.get(key);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", s.getSlot());
            m.put("lineEx", lineEx);
            m.put("lineMiss", lineMiss);
            m.put("lineRate", (lineEx != null && notes != null && notes > 0)
                    ? Math.round(lineEx * 100.0 / (notes * 2) * 100.0) / 100.0 : null);
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // 締め済み週: 凍結値からの復元
    // ---------------------------------------------------------------------

    /**
     * 締め済み週の順位表を {@link LeagueMember} の凍結値から復元する。
     * 課題曲ごとの内訳は凍結していないため perSong は空リストになる。
     */
    private List<Map<String, Object>> frozenStandings(List<LeagueMember> members) {
        return members.stream()
                .sorted(Comparator.comparing(m -> m.getFinalRank() != null ? m.getFinalRank() : Integer.MAX_VALUE))
                .map(m -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("rank", m.getFinalRank());
                    putUserFields(row, m.getUser());
                    row.put("validSongs", m.getValidSongs() != null ? m.getValidSongs() : 0);
                    row.put("resultValue", m.getResultValue());
                    row.put("pointDelta", m.getPointDelta());
                    row.put("zone", m.getMovement() != null ? m.getMovement() : "stay");
                    row.put("role", m.getRole() != null ? m.getRole() : "normal");
                    row.put("homeTier", m.getHomeTier() != null ? m.getHomeTier() : m.getTier());
                    row.put("perSong", List.of());
                    return row;
                })
                .toList();
    }

    // ---------------------------------------------------------------------
    // 進行中の週: scores + ベースラインからのライブ計算
    // ---------------------------------------------------------------------

    /**
     * 進行中の週の順位表を計算する。処理の流れ:
     *  手順1: 課題曲 3 曲と各曲のノーツ数（マスタ優先、消失時はスナップショット）を確定する
     *  手順2: グループ全員のベースラインを一括取得し (userId, 譜面, source) で索引化する
     *  手順3: メンバーごとに現在の scores を取得し、曲 × source で有効判定と自己ベスト集約を行う
     *  手順4: 決定的な比較器でソートし、グループ人数と階級から昇降格ゾーンを付与する
     */
    private List<Map<String, Object>> liveStandings(LeagueWeek week, int tier, List<LeagueMember> members) {
        boolean isScoreLadder = "score".equals(week.getLadderType());
        List<LeagueSong> songs = leagueSongRepository.findByWeekAndTierOrderBySlotAsc(week, tier);
        Map<String, Integer> notesBySong = resolveNotes(songs);

        List<User> users = members.stream().map(LeagueMember::getUser).toList();
        List<LeagueBaseline> baselines = leagueBaselineRepository.findByWeekAndUserIn(week, users);
        // (userId | title | difficultyName | source) → ベースライン
        Map<String, LeagueBaseline> baselineIndex = baselines.stream()
                .collect(Collectors.toMap(
                        b -> baselineKey(b.getUser().getId(), b.getTitle(), b.getDifficultyName(), b.getSource()),
                        b -> b,
                        (a, b) -> a));

        // 曲ごとの「ライン」= 週開始時点でその曲をプレー済みのグループメンバーの最高記録。
        // スコアは最大 EX、BP は最小ミス。ラインを超える記録は全員のベースライン以下ではあり得ないため、
        // 「週内に出した新記録」であることが CSV（歴代ベスト）だけから保証できる。
        // アーケード記録限定のため、INFINITAS 由来のベースラインはラインに含めない。
        Map<String, Integer> lineExBySong = new HashMap<>();
        Map<String, Integer> lineMissBySong = new HashMap<>();
        for (LeagueBaseline b : baselines) {
            if (!"arcade".equals(b.getSource())) continue;
            String key = songKey(b.getTitle(), b.getDifficultyName());
            // EX=0 は「所持のみ・未プレー」の空記録（play_count=0 で混ざる）なのでラインに含めない。
            // 0 をラインにすると「EX=0 の空記録保持者」全員がライン設定者扱いで +1 されてしまう。
            if (b.getBaseScore() != null && b.getBaseScore() > 0) {
                lineExBySong.merge(key, b.getBaseScore(), Math::max);
            }
            if (b.getBaseMiss() != null) {
                lineMissBySong.merge(key, b.getBaseMiss(), Math::min);
            }
        }

        List<String> titles = songs.stream().map(LeagueSong::getTitle).distinct().toList();
        List<String> diffs = songs.stream().map(LeagueSong::getDifficultyName).distinct().toList();

        List<MemberStats> stats = new ArrayList<>();
        for (LeagueMember member : members) {
            stats.add(computeMemberStats(week, member, songs, notesBySong, baselineIndex,
                    lineExBySong, lineMissBySong, titles, diffs, isScoreLadder));
        }

        // 曲ごとの着順ポイントを配分し、3 曲合計の「得点」を各メンバーに持たせる（順位の主指標）。
        // 併せて曲ごとの着順（rank）を perSong に記録する（順位表の曲別セルは着順のみ表示する）。
        assignSongPoints(stats, songs, baselineIndex, lineExBySong);
        stats.sort(comparator());

        // 現在の昇降格ポイントを一括で引く（順位から今週の増減と昇降格見込みを計算する）
        Map<Long, Integer> pointsByUserId = leagueEntryRepository
                .findByLadderTypeAndUserIn(week.getLadderType(), users).stream()
                .collect(Collectors.toMap(e -> e.getUser().getId(),
                        e -> e.getPoints() != null ? e.getPoints() : 0, (a, b) -> a));

        List<Map<String, Object>> result = new ArrayList<>();
        int size = stats.size();

        // ポイント配分: 完全同成績のタイ集団は、その順位帯のデルタ合計の平均（0 方向丸め）を
        // 等しく受け取る。主に「有効 0 曲の塊」に効き、実力と無関係な表示用タイブレーク順で
        // ±4 が付くのを防ぐ。さらに有効 0 曲の集団はプラスを獲得できない（放置昇格の防止）。
        int[] deltas = new int[size];
        int cursor = 0;
        while (cursor < size) {
            int groupEnd = cursor;
            int sum = 0;
            while (groupEnd < size && samePerformance(stats.get(cursor), stats.get(groupEnd))) {
                sum += deltaForRank(size, groupEnd + 1);
                groupEnd++;
            }
            int shared = (int) (sum / (double) (groupEnd - cursor)); // 0 方向丸め
            if (stats.get(cursor).validCount == 0 && shared > 0) {
                shared = 0;
            }
            for (int k = cursor; k < groupEnd; k++) {
                deltas[k] = shared;
            }
            cursor = groupEnd;
        }

        for (int i = 0; i < size; i++) {
            MemberStats st = stats.get(i);
            int rank = i + 1;
            String role = st.member.getRole() != null ? st.member.getRole() : "normal";
            // チャレンジ/ディフェンスの倍率を基本デルタに適用する。
            int delta = applyRole(deltas[i], role);
            // 昇降格の可否・向きはホーム DIVISION で判定する（卓がホストと違っても昇降格はホームを ±1）。
            int homeTier = st.member.getHomeTier() != null ? st.member.getHomeTier() : tier;
            int points = pointsByUserId.getOrDefault(st.member.getUser().getId(), 0);
            int projectedRaw = points + delta;

            // このまま週が終わった場合の昇降格見込み。LEGEND は昇格なし・DIVISION 10 は降格なし。
            String zone;
            if (projectedRaw >= POINT_CAP && homeTier > LeagueDivision.LEGEND && st.validCount > 0) {
                zone = "promote";
            } else if (projectedRaw <= -POINT_CAP && homeTier < LeagueDivision.LOWEST) {
                zone = "relegate";
            } else {
                zone = "stay";
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", rank);
            putUserFields(row, st.member.getUser());
            row.put("validSongs", st.validCount);
            row.put("resultValue", st.resultValue());
            row.put("points", points);
            row.put("pointDelta", delta);
            row.put("projectedPoints", Math.max(-POINT_CAP, Math.min(POINT_CAP, projectedRaw)));
            row.put("zone", zone);
            row.put("role", role);
            row.put("homeTier", homeTier);
            row.put("perSong", st.perSong);
            result.add(row);
        }
        return result;
    }

    /**
     * 課題曲ごとのノーツ数を確定する。active マスタを (title, difficulty) で引き、
     * 引けない・notes が無い場合は抽選時のスナップショット値にフォールバックする。
     */
    private Map<String, Integer> resolveNotes(List<LeagueSong> songs) {
        Map<String, Integer> map = new HashMap<>();
        for (LeagueSong song : songs) {
            Integer notes = song.getNotes();
            String code = LeagueChartNotation.nameToCode(song.getDifficultyName());
            if (code != null) {
                // (title, difficulty, revision) に UNIQUE 制約が無いため List で引いて notes 有りの先頭を使う
                Integer masterNotes = songDefinitionRepository
                        .findAllByTitleAndDifficultyAndRevision(song.getTitle(), code, "active").stream()
                        .map(SongDefinition::getNotes)
                        .filter(n -> n != null && n > 0)
                        .findFirst().orElse(null);
                if (masterNotes != null) {
                    notes = masterNotes;
                }
            }
            map.put(songKey(song.getTitle(), song.getDifficultyName()), notes);
        }
        return map;
    }

    /**
     * 1 メンバー分の集計。課題曲ごとに source 横断の自己ベストと有効判定（週内プレー + ライン超え）をまとめる。
     */
    private MemberStats computeMemberStats(LeagueWeek week, LeagueMember member, List<LeagueSong> songs,
                                           Map<String, Integer> notesBySong, Map<String, LeagueBaseline> baselineIndex,
                                           Map<String, Integer> lineExBySong, Map<String, Integer> lineMissBySong,
                                           List<String> titles, List<String> diffs, boolean isScoreLadder) {
        User user = member.getUser();
        // 課題 3 曲分の現在スコアを 1 クエリで取得。title IN × difficultyName IN の直積で
        // 余分な行も返り得るため、(title, difficultyName) の完全一致で絞り込む。
        // リーグはアーケード記録限定のため INFINITAS 行は除外する（null source は arcade 扱い）。
        // 同一譜面に difficultyLevel 違いの重複行があり得るので source 単位で集約する。
        Map<String, List<Score>> rowsBySongSource = scoreRepository
                .findByUserAndTitlesAndDifficulties(user, titles, diffs).stream()
                .filter(s -> s.getSource() == null || "arcade".equals(s.getSource()))
                .filter(s -> songs.stream().anyMatch(ls ->
                        ls.getTitle().equals(s.getTitle()) && ls.getDifficultyName().equals(s.getDifficultyName())))
                .collect(Collectors.groupingBy(s -> songKey(s.getTitle(), s.getDifficultyName())
                        + "|" + (s.getSource() != null ? s.getSource() : "arcade")));

        int validCount = 0;
        double rateSum = 0;
        long bpSum = 0;
        Double bestSingleRate = null;
        Integer bestSingleMiss = null;
        List<Map<String, Object>> perSong = new ArrayList<>();

        for (LeagueSong song : songs) {
            String key = songKey(song.getTitle(), song.getDifficultyName());
            boolean playedThisWeek = false;
            Integer bestEx = null;
            Integer bestMiss = null;

            for (String source : List.of("arcade")) {
                List<Score> rows = rowsBySongSource.get(key + "|" + source);
                if (rows == null || rows.isEmpty()) continue;
                SourceBest cur = aggregate(rows);

                if (cur.score != null && (bestEx == null || cur.score > bestEx)) bestEx = cur.score;
                if (cur.miss != null && (bestMiss == null || cur.miss < bestMiss)) bestMiss = cur.miss;

                if (playedThisWeek) continue;
                LeagueBaseline base = baselineIndex.get(baselineKey(user.getId(), song.getTitle(), song.getDifficultyName(), source));
                if (base == null) {
                    // 週開始時点で記録が無かった source に行が現れた = 週内の新規記録
                    playedThisWeek = cur.uploadedAt != null && week.getSnapshotAt() != null
                            && !cur.uploadedAt.isBefore(week.getSnapshotAt());
                } else {
                    playedThisWeek = improvedFromBaseline(cur, base);
                }
            }

            // ライン判定: グループ内の「週開始時点の最高記録」（スコア=最大EX / BP=最小ミス）を
            // 超えた記録のみ有効。ライン以下のスコアは CSV から週内の実プレー値を観測できないため、
            // 全員共通のラインを超えること（= 週内の新記録であることの保証）を有効条件にする。
            // 誰もプレーしていない曲はライン無し（週内に記録を出せばそのまま有効）。
            Integer lineEx = lineExBySong.get(key);
            Integer lineMiss = lineMissBySong.get(key);
            boolean beatLine = isScoreLadder
                    ? (bestEx != null && (lineEx == null || bestEx > lineEx))
                    : (bestMiss != null && (lineMiss == null || bestMiss < lineMiss));
            boolean valid = playedThisWeek && beatLine;

            Integer notes = notesBySong.get(key);
            Double rate = null;
            if (bestEx != null && notes != null && notes > 0) {
                rate = Math.round(bestEx * 100.0 / (notes * 2) * 100.0) / 100.0;
            }

            if (valid) {
                validCount++;
                if (isScoreLadder) {
                    double r = rate != null ? rate : 0.0;
                    rateSum += r;
                    if (bestSingleRate == null || r > bestSingleRate) bestSingleRate = r;
                } else {
                    bpSum += bestMiss;
                    if (bestSingleMiss == null || bestMiss < bestSingleMiss) bestSingleMiss = bestMiss;
                }
            }

            Map<String, Object> songRow = new LinkedHashMap<>();
            songRow.put("slot", song.getSlot());
            songRow.put("title", song.getTitle());
            songRow.put("difficultyName", song.getDifficultyName());
            songRow.put("valid", valid);
            songRow.put("bestEx", bestEx);
            songRow.put("rate", rate);
            songRow.put("bestMiss", bestMiss);
            songRow.put("lineEx", lineEx);
            songRow.put("lineMiss", lineMiss);
            perSong.add(songRow);
        }

        MemberStats st = new MemberStats();
        st.member = member;
        st.validCount = validCount;
        st.avgRate = validCount > 0 && isScoreLadder ? Math.round(rateSum / validCount * 100.0) / 100.0 : null;
        st.totalBp = validCount > 0 && !isScoreLadder ? bpSum : null;
        st.bestSingleRate = bestSingleRate;
        st.bestSingleMiss = bestSingleMiss;
        st.perSong = perSong;
        return st;
    }

    /**
     * ベースラインと現在値を比較して「週内にプレーした形跡があるか」を判定する。
     * プレー回数増加・EX 更新・ミスカウント改善・ランプ改善のいずれかで true。
     * null の基準値は「未記録」として、ミスカウントは +∞、EX は 0 とみなす。
     */
    private boolean improvedFromBaseline(SourceBest cur, LeagueBaseline base) {
        if (cur.playCount != null && base.getBasePlayCount() != null && cur.playCount > base.getBasePlayCount()) {
            return true;
        }
        int baseScore = base.getBaseScore() != null ? base.getBaseScore() : 0;
        if (cur.score != null && cur.score > baseScore) {
            return true;
        }
        int baseMiss = base.getBaseMiss() != null ? base.getBaseMiss() : Integer.MAX_VALUE;
        if (cur.miss != null && cur.miss < baseMiss) {
            return true;
        }
        return LeagueChartNotation.clearTypeRank(cur.clearType) > LeagueChartNotation.clearTypeRank(base.getBaseClearType());
    }

    /**
     * 同一 (譜面, source) の複数行（difficultyLevel 違いの重複があり得る）を 1 つのベスト値に集約する。
     */
    private SourceBest aggregate(List<Score> rows) {
        SourceBest best = new SourceBest();
        for (Score s : rows) {
            if (s.getScore() != null && (best.score == null || s.getScore() > best.score)) best.score = s.getScore();
            if (s.getMissCount() != null && (best.miss == null || s.getMissCount() < best.miss)) best.miss = s.getMissCount();
            if (s.getPlayCount() != null && (best.playCount == null || s.getPlayCount() > best.playCount)) best.playCount = s.getPlayCount();
            if (LeagueChartNotation.clearTypeRank(s.getClearType()) > LeagueChartNotation.clearTypeRank(best.clearType)) {
                best.clearType = s.getClearType();
            }
            if (s.getUploadedAt() != null && (best.uploadedAt == null || s.getUploadedAt().isAfter(best.uploadedAt))) {
                best.uploadedAt = s.getUploadedAt();
            }
        }
        return best;
    }

    /**
     * 【メソッドの役割】 曲ごとの着順ポイントを算出して各メンバーに配分する。
     *
     * 各課題曲について、グループ全員を「有効化した人はレート降順、未有効の人は最下位（同着）」で
     * 並べ、1 位 = グループ人数 pt、以下 1 pt ずつ下げて最下位 = 1 pt を与える。同着（同レート、
     * および未有効者の集団）は該当順位の生ポイントの平均を等分する。
     * 3 曲分を合計した {@link MemberStats#totalSongPoints} が順位の主指標になる。
     *
     * <p>これにより「常に同じ曲の中でしか比較しない」ため、曲ごとの難易度差が順位に影響しない
     * （課題曲 A,B が有効な人と A,C が有効な人を平均レートで比べる不公平を解消する）。
     *
     * <p>特例: その曲を誰も有効化できなかった（有効ラインを越えた人が 0 人）場合は、
     * 全員 0 pt とし、ライン設定者（グループ内の週開始時点最高記録＝ラインの保持者）にのみ +1 pt を与える。
     */
    private void assignSongPoints(List<MemberStats> stats, List<LeagueSong> songs,
                                  Map<String, LeagueBaseline> baselineIndex, Map<String, Integer> lineExBySong) {
        int n = stats.size();
        if (n == 0) return;
        for (LeagueSong song : songs) {
            int slot = song.getSlot();
            String key = songKey(song.getTitle(), song.getDifficultyName());
            // ソートキー: 有効ならレート、未有効は -∞（最下位で同着）
            double[] rateKey = new double[n];
            int validatorCount = 0;
            for (int i = 0; i < n; i++) {
                Map<String, Object> ps = findSlot(stats.get(i).perSong, slot);
                boolean valid = ps != null && Boolean.TRUE.equals(ps.get("valid"));
                Object r = ps != null ? ps.get("rate") : null;
                rateKey[i] = valid && r != null ? ((Number) r).doubleValue() : Double.NEGATIVE_INFINITY;
                if (valid) validatorCount++;
            }

            double[] pts = new double[n];
            Integer[] rank = new Integer[n]; // 有効化した人の着順（1 始まり、同着は同順位）。未有効は null。
            if (validatorCount == 0) {
                // 誰も有効ラインを越えられなかった → 全員 0 pt、ライン設定者（最高ベースライン保持者）に +1 pt。
                // この +1 は総合得点にのみ反映される（曲別は着順表示なので null のまま = 未達成扱い）。
                // ライン = グループ内アーケードベースラインの最大 EX。同値保持者が複数居れば各人 +1。
                Integer lineEx = lineExBySong.get(key);
                for (int i = 0; i < n; i++) {
                    long uid = stats.get(i).member.getUser().getId();
                    LeagueBaseline b = baselineIndex.get(
                            baselineKey(uid, song.getTitle(), song.getDifficultyName(), "arcade"));
                    boolean holder = lineEx != null && b != null && b.getBaseScore() != null
                            && b.getBaseScore().intValue() == lineEx.intValue();
                    pts[i] = holder ? 1.0 : 0.0;
                }
            } else {
                // 通常: レート降順で 1 位 = n pt 〜 最下位 1 pt。同着（同レート・未有効の集団）は該当順位の平均。
                Integer[] order = new Integer[n];
                for (int i = 0; i < n; i++) order[i] = i;
                Arrays.sort(order, (a, b) -> Double.compare(rateKey[b], rateKey[a]));
                int p = 0;
                while (p < n) {
                    int q = p;
                    double sum = 0;
                    while (q < n && rateKey[order[q]] == rateKey[order[p]]) { sum += (n - q); q++; }
                    double avg = sum / (q - p);
                    for (int k = p; k < q; k++) pts[order[k]] = avg;
                    p = q;
                }
                // 着順（有効化した人だけ）。同レートは同着（競争順位: 1,1,3...）。
                int r = 0, idx = 0;
                double prev = Double.NaN;
                for (int k = 0; k < n; k++) {
                    if (rateKey[order[k]] == Double.NEGATIVE_INFINITY) break; // 以降は未有効
                    idx++;
                    if (rateKey[order[k]] != prev) { r = idx; prev = rateKey[order[k]]; }
                    rank[order[k]] = r;
                }
            }
            for (int i = 0; i < n; i++) {
                stats.get(i).totalSongPoints += pts[i];
                Map<String, Object> ps = findSlot(stats.get(i).perSong, slot);
                if (ps != null) {
                    ps.put("points", Math.round(pts[i] * 2) / 2.0);
                    ps.put("rank", rank[i]);
                }
            }
        }
    }

    /** perSong リストから指定スロットの Map を取り出す（無ければ null）。 */
    private Map<String, Object> findSlot(List<Map<String, Object>> perSong, int slot) {
        for (Map<String, Object> ps : perSong) {
            Object s = ps.get("slot");
            if (s instanceof Number && ((Number) s).intValue() == slot) return ps;
        }
        return null;
    }

    /**
     * 順位比較器（完全に決定的）:
     *  ① 着順ポイント合計（得点）降順 → ② 有効曲数 降順 → ③ ベスト単曲レート 降順
     *  → ④ totalBeatPt 降順 → ⑤ userId 昇順
     */
    private Comparator<MemberStats> comparator() {
        return Comparator.comparingDouble((MemberStats s) -> s.totalSongPoints).reversed()
                .thenComparing(Comparator.comparingInt((MemberStats s) -> s.validCount).reversed())
                .thenComparing(s -> s.bestSingleRate != null ? s.bestSingleRate : -1.0, Comparator.reverseOrder())
                .thenComparing(s -> {
                    Double pt = s.member.getUser().getTotalBeatPt();
                    return pt != null ? pt : 0.0;
                }, Comparator.reverseOrder())
                .thenComparing(s -> s.member.getUser().getId());
    }

    /**
     * PT 等分のための「完全同成績」判定。着順ポイント合計（得点）が等しい場合のみ true。
     * 表示順の決定に使う totalBeatPt / userId のタイブレークは成績ではないため含めない。
     */
    private boolean samePerformance(MemberStats a, MemberStats b) {
        return Math.abs(a.totalSongPoints - b.totalSongPoints) < 1e-6;
    }

    /**
     * 【メソッドの役割】 立場（チャレンジ/ディフェンス）に応じて昇降格 PT の増減を補正する。
     *
     * <ul>
     *   <li>challenge（格上の卓に挑戦）: 通常の増減に <b>+2</b>。</li>
     *   <li>defense（格下の卓を防衛）: 通常の増減に <b>-2</b>。</li>
     *   <li>normal: そのまま。</li>
     * </ul>
     * 補正後は ±{@link #POINT_CAP} にクランプする（週の増減は PT レンジ内に収める）。
     * 2 段階の昇降格は起きない（累積が ±{@link #POINT_CAP} に到達した時点で昇降格し 0 リセットされるため、
     * 補正で行き過ぎても移動は常に 1 DIVISION）。
     *
     * @param delta 基本の増減
     * @param role  立場
     * @return 補正後の増減（±POINT_CAP にクランプ）
     */
    private int applyRole(int delta, String role) {
        int adjusted = delta;
        if ("challenge".equals(role)) adjusted = delta + 2;
        else if ("defense".equals(role)) adjusted = delta - 2;
        return Math.max(-POINT_CAP, Math.min(POINT_CAP, adjusted));
    }

    /** 順位表の 1 行にユーザー情報を詰める共通処理。 */
    private void putUserFields(Map<String, Object> row, User user) {
        row.put("userId", user.getId());
        row.put("displayName", displayName(user));
        row.put("totalBeatPt", user.getTotalBeatPt() != null ? user.getTotalBeatPt() : 0.0);
    }

    /** 表示名（null は空文字）。 */
    private String displayName(User user) {
        return user.getDisplayName() != null ? user.getDisplayName() : "";
    }

    private static String songKey(String title, String difficultyName) {
        return title + "|" + difficultyName;
    }

    private static String baselineKey(Long userId, String title, String difficultyName, String source) {
        return userId + "|" + title + "|" + difficultyName + "|" + source;
    }

    /** 1 (譜面, source) の現在値の集約結果。 */
    private static final class SourceBest {
        Integer score;
        Integer miss;
        Integer playCount;
        String clearType;
        java.time.LocalDateTime uploadedAt;
    }

    /** 1 メンバー分の集計結果（ソート・ゾーン付与前の中間表現）。 */
    private static final class MemberStats {
        LeagueMember member;
        int validCount;
        Double avgRate;
        Long totalBp;
        Double bestSingleRate;
        Integer bestSingleMiss;
        /** 3 曲分の着順ポイント合計（= 得点。順位の主指標）。 */
        double totalSongPoints;
        List<Map<String, Object>> perSong;

        /** 成績値（着順ポイント合計＝得点）。0.5 刻みに丸める。 */
        Double resultValue() {
            return Math.round(totalSongPoints * 2) / 2.0;
        }
    }
}
