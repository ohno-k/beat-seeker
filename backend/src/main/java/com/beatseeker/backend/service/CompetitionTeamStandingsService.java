package com.beatseeker.backend.service;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionMatchup;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionStrategyUse;
import com.beatseeker.backend.entity.CompetitionTeam;
import com.beatseeker.backend.repository.CompetitionMatchRepository;
import com.beatseeker.backend.repository.CompetitionMatchupRepository;
import com.beatseeker.backend.repository.CompetitionStrategyUseRepository;
import com.beatseeker.backend.repository.CompetitionTeamRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【Service の役割】 team5 大会のチーム順位表 (+ 途中経過マトリクス) を計算する単一実装。
 *
 * <p>運営画面 ({@code CompetitionAdminController#getStandings}) と観戦 URL
 * ({@code CompetitionSpectatorController}) の両方がこのサービスを呼ぶ。以前は運営側にしか
 * 集計ロジックが無く、観戦側に同じ表を出すには複製が必要だったため 1 箇所に集約した。
 *
 * <p>計算方法 (予選 matchup のみ対象、決勝は除外):
 * <ul>
 *   <li>1 戦 (= 1 match) ごと: 勝った曲数 × 戦ポイント (先鋒2/中堅3/大将4) を勝った側のチームに加算</li>
 *   <li>1 matchup ごと: 3 戦の戦ポイント合計を比較し、多い側が matchup 勝利</li>
 *   <li>matchup 勝利 = +3pt / 引分 = +1pt / 敗北 = 0pt (= 勝ち点)</li>
 *   <li>順位 = 勝ち点合計の降順。同点は戦ポイント合計の降順 → teamOrder 昇順で決める</li>
 * </ul>
 *
 * <p>{@code totalPoints} (戦ポイント + 勝ち点) もレスポンスに含めているが、これは表示用であって
 * 順位の判定には使わない。

 *
 * <p>出力はそのまま JSON 化される {@code Map} (既存の運営 API のレスポンス形状を維持する)。
 * 観戦 URL に出しても staged reveal を壊さない: 集計対象は結果記録済みの試合だけなので、
 * 未記録の起用や StrategyCard の発動予定はここから漏れない。
 */
@Service
public class CompetitionTeamStandingsService {

    /** matchup 勝利時の勝ち点。 */
    public static final int MATCHUP_WIN_PT = 3;

    /** matchup 引分時の勝ち点 (両チームに加算)。 */
    public static final int MATCHUP_DRAW_PT = 1;

    /** 予選 matchup の総数 (5 チーム総当たり)。 */
    public static final int PRELIM_MATCHUP_COUNT = 10;

    /** 予選で 1 チームが StrategyCard を発動できる matchup 数の上限 (順位表の分母)。 */
    public static final int STRATEGY_MATCHUP_LIMIT_PER_TEAM = 2;

    private final CompetitionTeamRepository teamRepository;
    private final CompetitionMatchupRepository matchupRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionStrategyUseRepository strategyUseRepository;

    /**
     * 【コンストラクタ】 Spring が依存を注入する。
     */
    public CompetitionTeamStandingsService(CompetitionTeamRepository teamRepository,
                                           CompetitionMatchupRepository matchupRepository,
                                           CompetitionMatchRepository matchRepository,
                                           CompetitionStrategyUseRepository strategyUseRepository) {
        this.teamRepository = teamRepository;
        this.matchupRepository = matchupRepository;
        this.matchRepository = matchRepository;
        this.strategyUseRepository = strategyUseRepository;
    }

    /**
     * 【メソッドの役割】 大会のチーム順位表・途中経過マトリクス・決勝生成可否をまとめて計算する。
     *
     * @param comp 対象大会 (team5 前提)
     * @return {@code rows} / {@code matchupBreakdown} / {@code prelim*} / {@code finalsExists} /
     *         {@code strategyLimit} を含むレスポンス Map
     */
    public Map<String, Object> compute(Competition comp) {
        List<CompetitionTeam> teams = teamRepository.findByCompetitionOrderByTeamOrderAsc(comp);

        // 各チームの集計値
        Map<Long, Integer> songPts = new LinkedHashMap<>();
        Map<Long, Integer> matchupPts = new LinkedHashMap<>();
        Map<Long, Integer> wins = new LinkedHashMap<>();
        Map<Long, Integer> draws = new LinkedHashMap<>();
        Map<Long, Integer> losses = new LinkedHashMap<>();
        for (CompetitionTeam t : teams) {
            songPts.put(t.getId(), 0);
            matchupPts.put(t.getId(), 0);
            wins.put(t.getId(), 0);
            draws.put(t.getId(), 0);
            losses.put(t.getId(), 0);
        }

        Map<Long, Set<Long>> strategyMatchupIds = countStrategyMatchups(comp);

        List<CompetitionMatchup> matchups = matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp);
        int prelimRecordedCount = 0;
        // matchupBreakdown: マトリクス表示用に、各 matchup での両側総合ポイントを返す。
        // recorded=false の場合も entry は含めるが、ポイントは集計しない (画面で「?」表示にする)。
        List<Map<String, Object>> matchupBreakdown = new ArrayList<>();
        for (CompetitionMatchup mu : matchups) {
            if (Boolean.TRUE.equals(mu.getIsFinals())) continue; // 決勝は予選順位の集計対象外
            List<CompetitionMatch> matches = matchRepository.findByMatchupOrderByIdAsc(mu);
            // matchup 内の戦ポイント累計 (= この matchup で A 側 / B 側が獲得した曲ポイント合計)
            int matchupAPts = 0, matchupBPts = 0;
            boolean allRecorded = !matches.isEmpty();
            for (CompetitionMatch m : matches) {
                Integer aw = m.getASongsWon();
                Integer bw = m.getBSongsWon();
                if (aw == null || bw == null) { allRecorded = false; continue; }
                // 予選順位の集計なので予選のポイント表を使う (決勝 matchup はループ冒頭で除外済み)。
                int kindPt = CompetitionMatchKinds.pointsPerSong(m.getMatchKind(), false);
                matchupAPts += aw * kindPt;
                matchupBPts += bw * kindPt;
            }

            // matchup 勝者判定: 戦ポイント合計の大小で W/D/L (運営スペック)
            Long aId = mu.getTeamA().getId();
            Long bId = mu.getTeamB().getId();
            int aMatchupPt = 0, bMatchupPt = 0;
            if (allRecorded) {
                if (matchupAPts > matchupBPts) aMatchupPt = MATCHUP_WIN_PT;
                else if (matchupBPts > matchupAPts) bMatchupPt = MATCHUP_WIN_PT;
                else { aMatchupPt = MATCHUP_DRAW_PT; bMatchupPt = MATCHUP_DRAW_PT; }
            }

            // breakdown 用 entry (フロントが 5x5 マトリクスにピボット)
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("matchupId", mu.getId());
            entry.put("teamAId", aId);
            entry.put("teamBId", bId);
            entry.put("aSongPoints", matchupAPts);
            entry.put("bSongPoints", matchupBPts);
            entry.put("aMatchupPoints", aMatchupPt);
            entry.put("bMatchupPoints", bMatchupPt);
            entry.put("aTotalPoints", matchupAPts + aMatchupPt);
            entry.put("bTotalPoints", matchupBPts + bMatchupPt);
            entry.put("recorded", allRecorded);
            matchupBreakdown.add(entry);

            if (!allRecorded) continue;
            prelimRecordedCount++;

            // standings に集計
            songPts.merge(aId, matchupAPts, Integer::sum);
            songPts.merge(bId, matchupBPts, Integer::sum);
            matchupPts.merge(aId, aMatchupPt, Integer::sum);
            matchupPts.merge(bId, bMatchupPt, Integer::sum);
            if (aMatchupPt > bMatchupPt) {
                wins.merge(aId, 1, Integer::sum);
                losses.merge(bId, 1, Integer::sum);
            } else if (bMatchupPt > aMatchupPt) {
                wins.merge(bId, 1, Integer::sum);
                losses.merge(aId, 1, Integer::sum);
            } else {
                draws.merge(aId, 1, Integer::sum);
                draws.merge(bId, 1, Integer::sum);
            }
        }

        // 順位 = 勝ち点 (matchupPts) 降順、tie-break は 戦ポイント (songPts) 降順 → teamOrder 昇順
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CompetitionTeam t : teams) {
            int sp = songPts.get(t.getId());
            int mp = matchupPts.get(t.getId());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("teamId", t.getId());
            r.put("teamName", t.getTeamName());
            r.put("teamOrder", t.getTeamOrder());
            r.put("songPoints", sp);
            r.put("matchupPoints", mp);
            r.put("totalPoints", sp + mp);
            r.put("wins", wins.get(t.getId()));
            r.put("draws", draws.get(t.getId()));
            r.put("losses", losses.get(t.getId()));
            r.put("strategyUsedMatchupCount",
                    strategyMatchupIds.getOrDefault(t.getId(), Set.of()).size());
            r.put("strategyLimit", STRATEGY_MATCHUP_LIMIT_PER_TEAM);
            rows.add(r);
        }
        rows.sort((x, y) -> {
            int byMu = Integer.compare((Integer) y.get("matchupPoints"), (Integer) x.get("matchupPoints"));
            if (byMu != 0) return byMu;
            int bySong = Integer.compare((Integer) y.get("songPoints"), (Integer) x.get("songPoints"));
            if (bySong != 0) return bySong;
            return Integer.compare((Integer) x.get("teamOrder"), (Integer) y.get("teamOrder"));
        });
        for (int i = 0; i < rows.size(); i++) rows.get(i).put("rank", i + 1);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rows", rows);
        root.put("prelimMatchupCount", PRELIM_MATCHUP_COUNT);
        root.put("prelimRecordedCount", prelimRecordedCount);
        root.put("allPrelimRecorded", prelimRecordedCount >= PRELIM_MATCHUP_COUNT);
        // 決勝 matchup が既に存在するか
        root.put("finalsExists", matchups.stream().anyMatch(mu -> Boolean.TRUE.equals(mu.getIsFinals())));
        // 5x5 マトリクス表示用の matchup ごと総合ポイント (recorded=false の matchup も含む)
        root.put("matchupBreakdown", matchupBreakdown);
        root.put("strategyLimit", STRATEGY_MATCHUP_LIMIT_PER_TEAM);
        return root;
    }

    /**
     * 【メソッドの役割】 チームごとに「StrategyCard を発動した予選 matchup」の ID 集合を集計する。
     *
     * <p>数えるのは <b>結果が記録済みの試合</b> での発動だけ。発動の意思決定自体は結果記録より前に
     * 行われるが、それを即座に順位表へ出すと観戦・相手チームに手の内が漏れるため、
     * 「発動した試合の結果が記録された時点で使用回数に乗る」挙動にしている。
     *
     * <p>単位は matchup (試合ではなく組)。予選の上限が
     * {@value #STRATEGY_MATCHUP_LIMIT_PER_TEAM} matchup 制なので、同じ matchup 内で複数人が
     * 発動しても 1 とカウントする (= 分母と同じ単位になる)。決勝の発動は上限対象外なので数えない。
     *
     * @param comp 対象大会
     * @return teamId → 発動済み予選 matchup ID 集合
     */
    private Map<Long, Set<Long>> countStrategyMatchups(Competition comp) {
        Map<Long, Set<Long>> byTeam = new HashMap<>();
        for (CompetitionStrategyUse su : strategyUseRepository.findAllByCompetition(comp)) {
            if (!Boolean.TRUE.equals(su.getEnabled())) continue;
            CompetitionParticipant user = su.getUsedByParticipant();
            if (user == null || user.getTeam() == null) continue;
            CompetitionMatch match = su.getMatch();
            if (match == null || match.getResultRecordedAt() == null) continue; // 結果記録前は伏せる
            CompetitionMatchup mu = match.getMatchup();
            if (mu == null || Boolean.TRUE.equals(mu.getIsFinals())) continue;
            byTeam.computeIfAbsent(user.getTeam().getId(), k -> new HashSet<>()).add(mu.getId());
        }
        return byTeam;
    }
}
