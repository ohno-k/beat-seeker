package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.Competition;
import com.beatseeker.backend.entity.CompetitionMatch;
import com.beatseeker.backend.entity.CompetitionMatchup;
import com.beatseeker.backend.entity.CompetitionParticipant;
import com.beatseeker.backend.entity.CompetitionTeam;
import com.beatseeker.backend.repository.CompetitionMatchRepository;
import com.beatseeker.backend.repository.CompetitionMatchupRepository;
import com.beatseeker.backend.repository.CompetitionRepository;
import com.beatseeker.backend.repository.CompetitionTeamRepository;
import com.beatseeker.backend.service.CompetitionMatchKinds;
import com.beatseeker.backend.service.CompetitionTeamStandingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 観戦客向けの 公開 (認証不要) 対戦表エンドポイント (team5 専用)。
 *
 * <p>大会主催が発行した {@code spectator_token} 1 つで、誰でも対戦表の一覧を読める。
 * SecurityConfig 側で {@code /api/competition-access/**} が {@code permitAll} のため、
 * 認証材料は URL に埋め込まれた {@code spectatorToken} のみ。
 *
 * <p>公開する情報は staged reveal を壊さないよう絞り込む:
 * <ul>
 *   <li>運営が「設定済み」にした matchup のみ (未設定は伏せる)</li>
 *   <li>起用 (アサインされたプレイヤー名) は、その側のラインアップが公開済みのときだけ可視</li>
 *   <li>指定ジャンルは常に可視 (運営が公開しているメタ情報)</li>
 *   <li>結果 (勝ち曲数・各曲タイトル・各曲スコア) は記録済みのときだけ可視</li>
 *   <li>順位表 / 途中経過マトリクスは記録済み結果だけの集計なので常に可視</li>
 * </ul>
 * 招待トークン / TL トークン / 個人情報は一切含めない。
 *
 * <p>トークン不一致や team5 以外のフォーマットは 404 を返す
 * (token が当たったかどうかを攻撃者に推測されないため一貫した応答にする)。
 */
@RestController
@RequestMapping("/api/competition-access/spectator")
public class CompetitionSpectatorController {

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamRepository teamRepository;
    private final CompetitionMatchupRepository matchupRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionTeamStandingsService teamStandingsService;

    public CompetitionSpectatorController(CompetitionRepository competitionRepository,
                                          CompetitionTeamRepository teamRepository,
                                          CompetitionMatchupRepository matchupRepository,
                                          CompetitionMatchRepository matchRepository,
                                          CompetitionTeamStandingsService teamStandingsService) {
        this.competitionRepository = competitionRepository;
        this.teamRepository = teamRepository;
        this.matchupRepository = matchupRepository;
        this.matchRepository = matchRepository;
        this.teamStandingsService = teamStandingsService;
    }

    /**
     * 【メソッドの役割】 観戦公開トークン経由で対戦表の読み取り専用ビューを返す。
     */
    @GetMapping("/{token}")
    public ResponseEntity<Map<String, Object>> getSpectatorView(@PathVariable String token) {
        Competition comp = competitionRepository.findBySpectatorToken(token).orElse(null);
        if (comp == null || !"team5".equals(comp.getFormat())) {
            return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("competition", competitionMap(comp));

        // チーム一覧 (順位表のヘッダ等で使えるよう全チームを返す)
        List<CompetitionTeam> teams = teamRepository.findByCompetitionOrderByTeamOrderAsc(comp);
        List<Map<String, Object>> teamMaps = new ArrayList<>();
        for (CompetitionTeam t : teams) teamMaps.add(teamMap(t));
        root.put("teams", teamMaps);

        // 設定済み matchup のみを matchupOrder 昇順で公開
        List<CompetitionMatchup> matchups = matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp);
        List<Map<String, Object>> matchupMaps = new ArrayList<>();
        for (CompetitionMatchup mu : matchups) {
            if (!Boolean.TRUE.equals(mu.getConfigured())) continue;

            // 起用公開は起用公開日時 到達で両サイド自動公開 (起用クローズとは独立)。
            // 決勝は決勝用の公開日時 (finalsLineupPublishAt) で判定する。未設定の間は観戦 URL にも起用を出さない。
            boolean lineupPublished =
                    comp.isLineupPublishedFor(Boolean.TRUE.equals(mu.getIsFinals()));
            boolean lineupPublishedA = lineupPublished;
            boolean lineupPublishedB = lineupPublished;

            Map<String, Object> mum = new LinkedHashMap<>();
            mum.put("matchupId", mu.getId());
            mum.put("matchupOrder", mu.getMatchupOrder());
            mum.put("isFinals", mu.getIsFinals());
            mum.put("teamA", mu.getTeamA() != null ? teamMap(mu.getTeamA()) : null);
            mum.put("teamB", mu.getTeamB() != null ? teamMap(mu.getTeamB()) : null);
            mum.put("lineupPublishedA", lineupPublishedA);
            mum.put("lineupPublishedB", lineupPublishedB);

            // この matchup の 3 試合を vanguard → middle → captain 順に並べる
            List<CompetitionMatch> matches = matchRepository.findByMatchupOrderByIdAsc(mu);
            matches.sort(Comparator.comparingInt(m -> matchKindOrder(m.getMatchKind())));

            List<Map<String, Object>> matchMaps = new ArrayList<>();
            for (CompetitionMatch match : matches) {
                matchMaps.add(matchMap(match, lineupPublishedA, lineupPublishedB));
            }
            mum.put("matches", matchMaps);
            matchupMaps.add(mum);
        }
        root.put("matchups", matchupMaps);

        // 順位表 + 途中経過マトリクス。運営画面と同じ集計 (CompetitionTeamStandingsService) をそのまま流用する。
        // 集計対象は結果記録済みの試合だけなので、staged reveal は壊れない
        // (未記録の試合は breakdown 上「?」のままで、起用も StrategyCard の発動予定も含まれない)。
        root.put("standings", teamStandingsService.compute(comp));
        return ResponseEntity.ok(root);
    }

    // ── 内部ヘルパ ───────────────────────────────────────────

    /** 戦種別の表示順 (先鋒 → … → 大将)。予選 3 戦 / 決勝 7 戦のどちらも同じ比較で並ぶ。 */
    private static int matchKindOrder(String kind) {
        return CompetitionMatchKinds.order(kind);
    }

    private Map<String, Object> competitionMap(Competition c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("deadlineAt", c.getDeadlineAt());
        m.put("lockedAt", c.getLockedAt());
        return m;
    }

    private Map<String, Object> teamMap(CompetitionTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("teamName", t.getTeamName());
        m.put("teamOrder", t.getTeamOrder());
        return m;
    }

    /**
     * 1 試合の観戦用表現。起用名はラインアップ公開済みの側だけ、結果は記録済みのときだけ含める。
     */
    private Map<String, Object> matchMap(CompetitionMatch match, boolean lineupPublishedA, boolean lineupPublishedB) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("matchId", match.getId());
        m.put("matchKind", match.getMatchKind());
        m.put("requiredGenre", match.getRequiredGenre());

        CompetitionParticipant pa = match.getPlayerA();
        CompetitionParticipant pb = match.getPlayerB();
        m.put("playerAName", (lineupPublishedA && pa != null) ? pa.getDisplayName() : null);
        m.put("playerBName", (lineupPublishedB && pb != null) ? pb.getDisplayName() : null);

        boolean recorded = match.getResultRecordedAt() != null;
        m.put("resultRecorded", recorded);
        if (recorded) {
            m.put("aSongsWon", match.getASongsWon());
            m.put("bSongsWon", match.getBSongsWon());
            m.put("resultRecordedAt", match.getResultRecordedAt());
            m.put("song1Title", match.getSong1Title());
            m.put("song1ScoreA", match.getSong1ScoreA());
            m.put("song1ScoreB", match.getSong1ScoreB());
            m.put("song2Title", match.getSong2Title());
            m.put("song2ScoreA", match.getSong2ScoreA());
            m.put("song2ScoreB", match.getSong2ScoreB());
        }
        return m;
    }
}
