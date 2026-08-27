package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * 管理者が無効化した課題曲（{@code LeagueSong.disabled}）は勝負の対象外として扱う。誰も有効化できず
 * （{@code valid = false}）、着順ポイントも配らない（ライン保持者の +1 も無し）ので、そのグループは
 * 残りの曲だけで競うことになる。解禁できない譜面が抽選で入った場合の救済。
 *
 * 「週内プレー」の判定（メンバー × 課題曲、arcade 行）:
 *  - ベースライン行あり: プレー回数増加 or EX スコア更新 or ミスカウント改善 or ランプ改善
 *  - ベースライン行なし: Score.uploadedAt >= week.snapshotAt（週内に初記録が現れた）
 *
 * 曲別の着順ポイントは「有効化した人 &gt; 有効ラインに届かなかった人」の 2 段で配る。
 * 未到達者は遊んだかどうかに関わらず<b>まず全員で山分け</b>し、そのあと週内に遊んだ形跡が無い人
 * （不参加）だけを 0 pt に落とす（{@link #distributeSongPoints}）。つまり不参加者が居ても
 * 参加した未到達者の取り分は増えず、消えた 0 pt ぶんは誰にも配られない。参加の判定は公式 CSV の
 * 最終プレー日時（記録が伸びなくても進む）を主に見るので、当該列を持たない取り込み経路では不参加へ倒れる。
 *
 * 昇降格はポイント制:
 *  - 週の順位に応じてポイントが増減する（{@link #deltaForRank}: 8 人なら 1 位 +4 〜 8 位 -4。
 *    人数が少ないほど幅が縮小し、奇数人数では中央順位が ±0）。1 週の増減幅は
 *    ±{@link #WEEKLY_DELTA_CAP} が上限。
 *  - 累積ポイントが +{@link #POINT_CAP} に到達すると昇格、-{@link #POINT_CAP} で降格
 *    （1 週の増減は最大 ±{@link #WEEKLY_DELTA_CAP} なので、昇降格には最短でも 2 週かかる）。
 *  - 昇降格した直後は 0 ではなく「移動先での立ち位置」を反映した PT から始める:
 *    昇格後は -{@link #POST_MOVE_POINTS}（新 DIVISION では下位スタート）、
 *    降格後は +{@link #POST_MOVE_POINTS}（旧 DIVISION へ戻りやすい上位スタート）。
 *  - 有効曲 0 の週はプラス分を獲得できない（過疎グループでの放置昇格を防ぐ）。
 */
@Service
public class LeagueStandingsService {

    private static final Logger log = LoggerFactory.getLogger(LeagueStandingsService.class);

    /** 昇降格ポイントの上限（この値に到達で昇格、負側到達で降格。保持値もこの範囲にクランプ）。 */
    public static final int POINT_CAP = 8;

    /** 1 週で動くポイントの上限（順位由来の増減とチャレンジ/ディフェンス補正の両方に効く）。 */
    public static final int WEEKLY_DELTA_CAP = 4;

    /**
     * 昇降格した直後の開始ポイント（絶対値）。昇格後は -{@code POST_MOVE_POINTS}、
     * 降格後は +{@code POST_MOVE_POINTS} から始まる（{@link #pointsAfterMovement}）。
     *
     * <p>移動先での立ち位置を PT に反映するための下駄。昇格した人は新しい DIVISION では
     * 下位スタート（連続昇格しにくく、力不足ならすぐ戻る）、降格した人は上位スタート
     * （元の DIVISION へ復帰しやすい）になる。
     */
    public static final int POST_MOVE_POINTS = 4;

    /**
     * チャレンジ（格上の卓）/ ディフェンス（格下の卓）の PT 補正幅。
     * challenge は +{@code ROLE_BONUS}、defense は -{@code ROLE_BONUS}（{@link #applyRole}）。
     */
    public static final int ROLE_BONUS = 2;

    private final LeagueMemberRepository leagueMemberRepository;
    private final LeagueSongRepository leagueSongRepository;
    private final LeagueBaselineRepository leagueBaselineRepository;
    private final LeagueEntryRepository leagueEntryRepository;
    private final ScoreRepository scoreRepository;
    private final SongDefinitionRepository songDefinitionRepository;
    /** 締め済み週の曲別確定結果（過去週の順位表を不変にするための凍結値）。 */
    private final LeagueMemberSongRepository leagueMemberSongRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public LeagueStandingsService(LeagueMemberRepository leagueMemberRepository,
                                  LeagueSongRepository leagueSongRepository,
                                  LeagueBaselineRepository leagueBaselineRepository,
                                  LeagueEntryRepository leagueEntryRepository,
                                  ScoreRepository scoreRepository,
                                  SongDefinitionRepository songDefinitionRepository,
                                  LeagueMemberSongRepository leagueMemberSongRepository) {
        this.leagueMemberSongRepository = leagueMemberSongRepository;
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
     * @return ポイント増減（-WEEKLY_DELTA_CAP..+WEEKLY_DELTA_CAP にクランプ済み）
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
        return Math.max(-WEEKLY_DELTA_CAP, Math.min(WEEKLY_DELTA_CAP, delta));
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
            return frozenStandings(week, tier, members);
        }
        return liveStandings(week, tier, members);
    }

    /**
     * 【メソッドの役割】 指定グループの課題曲ごとの「ライン」（週開始時点の最高記録）を slot 順に返す。
     *
     * 観戦（他グループ閲覧）でライン値を表示するために使う。ラインは個人スコアではなく
     * 「グループ共通の閾値」（＝週開始時点の最高ベースライン）。
     * 締め済み週や課題曲不在の場合も slot ぶんの行（lineEx=null）を返す。
     *
     * @param week       対象週
     * @param tier       階級
     * @param groupIndex グループ番号
     * @return {@code [{slot, lineEx, lineMiss, lineRate}]}（slot 昇順）
     */
    public List<Map<String, Object>> computeGroupSongLines(LeagueWeek week, int tier, int groupIndex) {
        List<LeagueSong> songs = leagueSongRepository.findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, tier, groupIndex);
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
     *
     * 課題曲ごとの内訳は締め時に {@link LeagueMemberSong} へ凍結してあるのでそれを復元する
     * （進行中の週と同じ形の表を過去週でも出せる）。この機能より前に締まった週には凍結行が
     * 無いため、その場合に限り現在の scores から内訳だけを再計算して補う
     * （順位・得点・PT は凍結値のまま。再計算なので締め後のアップロードで内訳が動き得る）。
     *
     * @param week    対象週（締め済み）
     * @param tier    階級
     * @param members 対象グループのメンバー
     * @return 順位表（順位昇順）
     */
    private List<Map<String, Object>> frozenStandings(LeagueWeek week, int tier, List<LeagueMember> members) {
        Map<Long, List<Map<String, Object>>> perSongByUser = frozenPerSong(members);
        if (perSongByUser.isEmpty()) {
            perSongByUser = recomputePerSong(week, tier, members);
        }
        Map<Long, List<Map<String, Object>>> perSong = perSongByUser;
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
                    row.put("perSong", perSong.getOrDefault(m.getUser().getId(), List.of()));
                    return row;
                })
                .toList();
    }

    /**
     * 締め時に凍結した曲別結果を userId → perSong の形に復元する。凍結行が無ければ空 Map。
     */
    private Map<Long, List<Map<String, Object>>> frozenPerSong(List<LeagueMember> members) {
        List<LeagueMemberSong> rows = leagueMemberSongRepository.findByMemberIn(members);
        if (rows.isEmpty()) {
            return Map.of();
        }
        // 課題曲のタイトルは league_songs 側にあるので、スロット順に突き合わせて表示名を補う。
        Map<Integer, LeagueSong> songBySlot = new HashMap<>();
        LeagueMember any = members.get(0);
        for (LeagueSong s : leagueSongRepository.findByWeekAndTierAndGroupIndexOrderBySlotAsc(
                any.getWeek(), any.getTier(), any.getGroupIndex())) {
            songBySlot.put(s.getSlot(), s);
        }

        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        for (LeagueMemberSong r : rows) {
            Map<String, Object> ps = new LinkedHashMap<>();
            LeagueSong song = songBySlot.get(r.getSlot());
            ps.put("slot", r.getSlot());
            ps.put("title", song != null ? song.getTitle() : null);
            ps.put("difficultyName", song != null ? song.getDifficultyName() : null);
            ps.put("valid", Boolean.TRUE.equals(r.getValid()));
            // この列より前に締まった週は null（不明）。有効化できた人は必ず遊んでいるので valid で代替する。
            ps.put("participated", r.getParticipated() != null
                    ? r.getParticipated() : Boolean.TRUE.equals(r.getValid()));
            ps.put("bestEx", r.getBestEx());
            ps.put("rate", r.getRate());
            ps.put("bestMiss", r.getBestMiss());
            ps.put("lineEx", r.getLineEx());
            ps.put("lineMiss", r.getLineMiss());
            ps.put("points", r.getSongPoints());
            ps.put("rank", r.getSongRank());
            // 無効化フラグは league_songs 側に残っているので、締め済み週でも「無効」表示を復元できる。
            ps.put("disabled", song != null && song.isDisabled());
            out.computeIfAbsent(r.getMember().getUser().getId(), k -> new ArrayList<>()).add(ps);
        }
        out.values().forEach(list -> list.sort(Comparator.comparingInt(p -> (Integer) p.get("slot"))));
        return out;
    }

    /**
     * 凍結行が無い旧い締め済み週のために、曲別内訳だけを現在の scores から再計算する。
     * 順位・得点・PT には使わない（それらは凍結値が正）。
     */
    private Map<Long, List<Map<String, Object>>> recomputePerSong(LeagueWeek week, int tier,
                                                                 List<LeagueMember> members) {
        Map<Long, List<Map<String, Object>>> out = new HashMap<>();
        List<Map<String, Object>> live;
        try {
            live = liveStandings(week, tier, members);
        } catch (RuntimeException e) {
            // 課題曲やベースラインが消えている等で再計算できない場合は内訳なしで表示する
            // （順位・得点・PT は凍結値があるので順位表自体は成立する）。
            log.warn("締め済み週の曲別内訳の再計算に失敗: weekId={} tier={} - {}",
                    week.getId(), tier, e.toString());
            return out;
        }
        for (Map<String, Object> row : live) {
            Object userId = row.get("userId");
            Object perSong = row.get("perSong");
            if (userId instanceof Long id && perSong instanceof List<?> list) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> typed = (List<Map<String, Object>>) list;
                out.put(id, typed);
            }
        }
        return out;
    }

    /**
     * 【メソッドの役割】 週の締め時に、その時点の曲別結果を {@link LeagueMemberSong} へ凍結する。
     *
     * 進行中の週の順位表（{@link #liveStandings} が返す行）の {@code perSong} をそのまま保存する。
     * 締めの再実行に備えて、対象メンバーの既存行を削除してから入れ直す（冪等）。
     *
     * @param members   対象グループのメンバー
     * @param standings そのグループの確定順位表（{@link #computeGroupStandings} の結果）
     */
    @Transactional
    public void freezePerSong(List<LeagueMember> members, List<Map<String, Object>> standings) {
        if (members.isEmpty()) {
            return;
        }
        Map<Long, LeagueMember> byUserId = members.stream()
                .collect(Collectors.toMap(m -> m.getUser().getId(), m -> m, (a, b) -> a));
        leagueMemberSongRepository.deleteByMemberIn(members);

        List<LeagueMemberSong> rows = new ArrayList<>();
        for (Map<String, Object> row : standings) {
            LeagueMember member = byUserId.get((Long) row.get("userId"));
            if (member == null || !(row.get("perSong") instanceof List<?> perSong)) {
                continue;
            }
            for (Object o : perSong) {
                if (!(o instanceof Map<?, ?> ps)) continue;
                LeagueMemberSong entity = new LeagueMemberSong();
                entity.setMember(member);
                entity.setSlot(asInt(ps.get("slot")));
                entity.setValid(Boolean.TRUE.equals(ps.get("valid")));
                entity.setParticipated(Boolean.TRUE.equals(ps.get("participated")));
                entity.setBestEx(asInt(ps.get("bestEx")));
                entity.setRate(asDouble(ps.get("rate")));
                entity.setBestMiss(asInt(ps.get("bestMiss")));
                entity.setLineEx(asInt(ps.get("lineEx")));
                entity.setLineMiss(asInt(ps.get("lineMiss")));
                entity.setSongRank(asInt(ps.get("rank")));
                entity.setSongPoints(asDouble(ps.get("points")));
                rows.add(entity);
            }
        }
        leagueMemberSongRepository.saveAll(rows);
    }

    /** Number → Integer（null 安全）。 */
    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    /** Number → Double（null 安全）。 */
    private static Double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
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
        // 課題曲はグループ単位。members は同一グループなので先頭から groupIndex を得る（呼び出し側で非空を保証）。
        int groupIndex = members.get(0).getGroupIndex();
        List<LeagueSong> songs = leagueSongRepository.findByWeekAndTierAndGroupIndexOrderBySlotAsc(week, tier, groupIndex);
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
            // チャレンジ/ディフェンスの補正（±ROLE_BONUS）を基本デルタに適用する。
            int delta = applyRole(deltas[i], role);
            // 有効 0 曲はプラスを獲得できない（放置昇格の防止）。基本デルタ側でも同じ判定をしているが、
            // チャレンジ補正はその後に効くため、ここでも押さえる（1 曲もライン超えせず +2 されるのを防ぐ）。
            if (st.validCount == 0 && delta > 0) {
                delta = 0;
            }
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
            java.time.LocalDateTime lastPlayedAt = null;

            for (String source : List.of("arcade")) {
                List<Score> rows = rowsBySongSource.get(key + "|" + source);
                if (rows == null || rows.isEmpty()) continue;
                SourceBest cur = aggregate(rows);

                if (cur.lastPlayedAt != null && (lastPlayedAt == null || cur.lastPlayedAt.isAfter(lastPlayedAt))) {
                    lastPlayedAt = cur.lastPlayedAt;
                }
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
            // 管理者が無効化した曲（解禁不可能な選曲など）は誰も有効化できない扱いにする。
            // 着順ポイントも配らない（assignSongPoints 側でスキップする）。
            boolean valid = !song.isDisabled() && playedThisWeek && beatLine;

            // 「参加」= 週内にこの課題曲を遊んだ形跡があるか（ライン到達は問わない）。
            // 着順ポイントの配分で「遊んだが未到達」と「そもそも遊んでいない」を分けるために使う。
            //  - 公式 CSV: 最終プレー日時が開催期間内（記録が伸びなくても進む唯一の値）
            //  - それ以外: 記録・プレー回数の前進（playedThisWeek）を保険にする
            // 最終プレー日時を持たない取り込み経路（ブックマークレット CSV は当該列が空欄）では
            // 「記録が伸びなかったプレー」を観測できないため不参加へ倒れる。判定不能を参加扱いにすると
            // 遊ばずに山分けを受け取れてしまうため、活動判定（自動休止）とは違って保険は張らない。
            boolean participated = playedThisWeek || LeagueWeekLifecycleService.withinWeek(lastPlayedAt, week);

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
            songRow.put("participated", participated);
            songRow.put("bestEx", bestEx);
            songRow.put("rate", rate);
            songRow.put("bestMiss", bestMiss);
            songRow.put("lineEx", lineEx);
            songRow.put("lineMiss", lineMiss);
            songRow.put("disabled", song.isDisabled());
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
            if (s.getLastPlayedAt() != null
                    && (best.lastPlayedAt == null || s.getLastPlayedAt().isAfter(best.lastPlayedAt))) {
                best.lastPlayedAt = s.getLastPlayedAt();
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
     * <p>未有効者は「遊んだが届かなかった人」と「そもそも遊んでいない人」に分かれる。
     * 詳細は {@link #distributeSongPoints}。
     *
     * <p>特例: その曲を誰も有効化できなかった（有効ラインを越えた人が 0 人）場合は、
     * 全員 0 pt とし、ライン設定者（グループ内の週開始時点最高記録＝ラインの保持者）にのみ +1 pt を与える。
     * <b>この特例では参加者の山分けは行わない</b>（遊んだかどうかに関わらず全員 0 pt）。
     */
    private void assignSongPoints(List<MemberStats> stats, List<LeagueSong> songs,
                                  Map<String, LeagueBaseline> baselineIndex, Map<String, Integer> lineExBySong) {
        int n = stats.size();
        if (n == 0) return;
        for (LeagueSong song : songs) {
            int slot = song.getSlot();
            // 無効化された曲は勝負の対象外。着順もポイントも付けない（ライン保持者の +1 も無し）。
            if (song.isDisabled()) {
                for (MemberStats st : stats) {
                    Map<String, Object> ps = findSlot(st.perSong, slot);
                    if (ps != null) {
                        ps.put("points", null);
                        ps.put("rank", null);
                    }
                }
                continue;
            }
            String key = songKey(song.getTitle(), song.getDifficultyName());
            // ソートキーは 2 段構え: 段（有効 > 参加 > 不参加）→ 同じ段の中はレート降順。
            // 有効者以外はレートを持たない（-∞）ので、未有効者（参加・不参加）は全員が同着として
            // まとめて山分けされ、そのあと不参加者だけが 0 pt に落ちる。
            double[] rateKey = new double[n];
            int[] band = new int[n];
            int validatorCount = 0;
            for (int i = 0; i < n; i++) {
                Map<String, Object> ps = findSlot(stats.get(i).perSong, slot);
                boolean valid = ps != null && Boolean.TRUE.equals(ps.get("valid"));
                boolean participated = ps != null && Boolean.TRUE.equals(ps.get("participated"));
                Object r = ps != null ? ps.get("rate") : null;
                rateKey[i] = valid && r != null ? ((Number) r).doubleValue() : Double.NEGATIVE_INFINITY;
                band[i] = valid ? BAND_VALID : (participated ? BAND_PLAYED : BAND_ABSENT);
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
                pts = distributeSongPoints(band, rateKey);
                // 着順（有効化した人だけ）。同レートは同着（競争順位: 1,1,3...）。
                Integer[] order = sortedOrder(band, rateKey);
                int r = 0, idx = 0;
                double prev = Double.NaN;
                for (int k = 0; k < n; k++) {
                    if (band[order[k]] != BAND_VALID) break; // 以降は未有効（参加・不参加）
                    if (rateKey[order[k]] == Double.NEGATIVE_INFINITY) break; // レート不明（ノーツ数が引けない譜面）
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

    /** 着順ポイント配分の段（{@link #distributeSongPoints}）。有効化した人。 */
    static final int BAND_VALID = 2;
    /** 着順ポイント配分の段。遊んだがラインへ届かなかった人。 */
    static final int BAND_PLAYED = 1;
    /** 着順ポイント配分の段。週内にその課題曲を遊んだ形跡が無い人。 */
    static final int BAND_ABSENT = 0;

    /**
     * 【メソッドの役割】 1 曲分の着順ポイントを配分する（純粋計算）。
     *
     * 並びは「段 → 同じ段の中はレート降順」の 2 段階:
     * <ol>
     *   <li>{@link #BAND_VALID} 有効化した人（レート降順で 1 位から）</li>
     *   <li>{@link #BAND_PLAYED} 遊んだがラインへ届かなかった人</li>
     *   <li>{@link #BAND_ABSENT} 週内に遊んだ形跡が無い人</li>
     * </ol>
     *
     * 生ポイントは 1 位 = n pt 〜 最下位 1 pt で、同着集団は自分たちが占める順位帯の平均を等分する。
     * <b>有効ラインに届かなかった人（{@link #BAND_PLAYED} と {@link #BAND_ABSENT}）は 1 つの同着集団</b>
     * として扱い、まず全員でその順位帯を山分けする。そのあと {@link #BAND_ABSENT}（不参加）だけを
     * 一律 0 pt に落とし、落ちたぶんの生ポイントは誰にも配られずに消える
     * （参加した未到達者の取り分は不参加者の人数に左右されない）。
     *
     * <p>例（8 人・有効 2 人・参加未到達 3 人・不参加 3 人）: 有効者が 8 pt / 7 pt、
     * 未到達の 6 人が 3〜8 位の帯を山分けして (6+5+4+3+2+1)/6 = 3.5 pt ずつ。
     * そのうち不参加の 3 人は 0 pt になり、参加した 3 人だけが 3.5 pt を受け取る。
     *
     * @param band    メンバーごとの段
     * @param rateKey メンバーごとのレート（有効化していない人は -∞）
     * @return メンバーごとの着順ポイント（添字は band / rateKey と対応）
     */
    static double[] distributeSongPoints(int[] band, double[] rateKey) {
        int n = band.length;
        double[] pts = new double[n];
        Integer[] order = sortedOrder(band, rateKey);
        int p = 0;
        while (p < n) {
            int q = p;
            double sum = 0;
            // 同着集団が続く限り、その順位帯の生ポイントを足し込む。
            // 未有効（参加・不参加）は 1 つの同着集団としてまとめて山分けする。
            while (q < n && sameTieGroup(band, rateKey, order[q], order[p])) {
                sum += (n - q);
                q++;
            }
            double avg = sum / (q - p);
            // 山分けの後で不参加者だけを 0 pt に落とす（その分は誰にも配られずに消える）。
            for (int k = p; k < q; k++) pts[order[k]] = band[order[k]] == BAND_ABSENT ? 0.0 : avg;
            p = q;
        }
        return pts;
    }

    /**
     * 同着集団（生ポイントを山分けする単位）が同じかを返す。
     *
     * <ul>
     *   <li>有効化した人どうし: レートが等しければ同着。</li>
     *   <li>未有効（{@link #BAND_PLAYED} / {@link #BAND_ABSENT}）どうし: 常に同着。
     *       遊んだかどうかに関わらずまずは全員で山分けし、不参加者の取り分を後から 0 pt にする。</li>
     *   <li>有効と未有効: 別集団。</li>
     * </ul>
     */
    private static boolean sameTieGroup(int[] band, double[] rateKey, int a, int b) {
        boolean validA = band[a] == BAND_VALID;
        boolean validB = band[b] == BAND_VALID;
        if (validA != validB) return false;
        if (!validA) return true; // 未有効は参加・不参加をまとめて 1 つの同着集団
        return rateKey[a] == rateKey[b];
    }

    /** 「段の降順 → レートの降順」でメンバー添字を並べる。 */
    private static Integer[] sortedOrder(int[] band, double[] rateKey) {
        Integer[] order = new Integer[band.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> band[b] != band[a]
                ? Integer.compare(band[b], band[a])
                : Double.compare(rateKey[b], rateKey[a]));
        return order;
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
     *   <li>challenge（格上の卓に挑戦）: 通常の増減に <b>+{@link #ROLE_BONUS}</b>。</li>
     *   <li>defense（格下の卓を防衛）: 通常の増減に <b>-{@link #ROLE_BONUS}</b>。</li>
     *   <li>normal: そのまま。</li>
     * </ul>
     *
     * <p><b>符号は反転させない（0 で止める）。</b> 補正はあくまで「同じ結果なら格上への挑戦を優遇する」
     * ものなので、負け越した人がプラスになったり、勝った人がマイナスになったりしてはいけない。
     * 例: 3 人グループの着順デルタは +1 / 0 / -1 なので、
     * <ul>
     *   <li>チャレンジで最下位（-1）: -1+2 = +1 ではなく <b>0（増減なし）</b></li>
     *   <li>ディフェンスで 1 位（+1）: +1-2 = -1 ではなく <b>0（増減なし）</b></li>
     * </ul>
     * 元から負け側（例 8 人の 8 位 = -4）はチャレンジでも -2 のままマイナスで残る。
     *
     * <p>補正後は ±{@link #WEEKLY_DELTA_CAP} にクランプする（1 週で動く PT は週の増減幅に収める）。
     * 2 段階の昇降格は起きない（累積が ±{@link #POINT_CAP} に到達した時点で昇降格し PT が
     * {@link #pointsAfterMovement} で入れ直されるため、補正で行き過ぎても移動は常に 1 DIVISION）。
     *
     * @param delta 基本の増減（順位由来）
     * @param role  立場
     * @return 補正後の増減（±WEEKLY_DELTA_CAP にクランプ）
     */
    static int applyRole(int delta, String role) {
        int adjusted = delta;
        if ("challenge".equals(role)) {
            adjusted = delta + ROLE_BONUS;
            // 負け側（順位デルタがマイナス）をプラスに反転させない: 0 で止める
            if (delta < 0) adjusted = Math.min(adjusted, 0);
        } else if ("defense".equals(role)) {
            adjusted = delta - ROLE_BONUS;
            // 勝ち側（順位デルタがプラス）をマイナスに反転させない: 0 で止める
            if (delta > 0) adjusted = Math.max(adjusted, 0);
        }
        return Math.max(-WEEKLY_DELTA_CAP, Math.min(WEEKLY_DELTA_CAP, adjusted));
    }

    /**
     * 【メソッドの役割】 週の締め後に {@link com.beatseeker.backend.entity.LeagueEntry} へ保存する
     * 昇降格ポイントを返す。
     *
     * <ul>
     *   <li>昇格（{@code "promote"}）: -{@link #POST_MOVE_POINTS} から再スタート。
     *       昇格先では下位スタートになるので、連続昇格はしにくく、力不足ならすぐ元の DIVISION に戻る。</li>
     *   <li>降格（{@code "relegate"}）: +{@link #POST_MOVE_POINTS} から再スタート。
     *       降格先では上位スタートになるので、元の DIVISION へ復帰しやすい。</li>
     *   <li>移動なし: 累積に今週の増減を足し、±{@link #POINT_CAP} にクランプして保持する
     *       （LEGEND のプラス超過・DIVISION 10 のマイナス超過はここで頭打ちになる）。</li>
     * </ul>
     *
     * @param movement  週の結果（{@code "promote"} / {@code "relegate"} / それ以外は移動なし）
     * @param oldPoints 週開始時点の累積ポイント
     * @param delta     今週の増減
     * @return 保存すべき新しい累積ポイント
     */
    static int pointsAfterMovement(String movement, int oldPoints, int delta) {
        if ("promote".equals(movement)) {
            return -POST_MOVE_POINTS;
        }
        if ("relegate".equals(movement)) {
            return POST_MOVE_POINTS;
        }
        return Math.max(-POINT_CAP, Math.min(POINT_CAP, oldPoints + delta));
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
        /** 公式 CSV 由来の最終プレー日時（JST 壁時計）。記録が伸びなくても進む。 */
        java.time.LocalDateTime lastPlayedAt;
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
