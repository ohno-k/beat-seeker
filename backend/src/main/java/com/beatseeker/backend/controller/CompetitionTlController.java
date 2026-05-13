package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 【クラスの役割】 TL (チームリーダー) 専用 URL でのみアクセスできる、自チームのラインアップ管理 API。
 *
 * <p>SecurityConfig 側で {@code /api/competition-access/**} が {@code permitAll} に開けてあるため、
 * 認証材料は URL に埋め込まれた {@code tlToken} のみ。
 *
 * <p>TL ができる操作:
 * <ul>
 *   <li>{@code GET /api/competition-access/tl/{token}}: 自チーム + 自分が関わる 4 matchup × 3 試合の現在編成</li>
 *   <li>{@code PUT /api/competition-access/tl/{token}/match/{matchId}/assign}:
 *       自チーム側の slot にメンバーをアサイン (body: {@code participantId})</li>
 *   <li>{@code DELETE /api/competition-access/tl/{token}/match/{matchId}/assign}:
 *       自チーム側の slot を空席に戻す</li>
 * </ul>
 *
 * <p>制約:
 * <ul>
 *   <li>当該 match の「自チーム側」が locked になっていれば変更不可</li>
 *   <li>アサインする participant は自チームのメンバーでなければならない</li>
 *   <li>同一 matchup 内で同じ participant を 2 つの slot にアサインできない (1 人 1 試合まで)</li>
 *   <li>大会 status === {@code finished} で全操作不可</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/competition-access/tl")
public class CompetitionTlController {

    private final CompetitionTeamRepository teamRepository;
    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionMatchupRepository matchupRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionPickRepository pickRepository;

    public CompetitionTlController(CompetitionTeamRepository teamRepository,
                                   CompetitionParticipantRepository participantRepository,
                                   CompetitionMatchupRepository matchupRepository,
                                   CompetitionMatchRepository matchRepository,
                                   CompetitionPickRepository pickRepository) {
        this.teamRepository = teamRepository;
        this.participantRepository = participantRepository;
        this.matchupRepository = matchupRepository;
        this.matchRepository = matchRepository;
        this.pickRepository = pickRepository;
    }

    /**
     * 【メソッドの役割】 TL トークンに対応する自チームのラインアップ全景を返す。
     *
     * <p>レスポンス構造 (概略):
     * <pre>
     * {
     *   team:        { id, teamName },
     *   competition: { id, name, status, deadlineAt, lockedAt },
     *   members:     [ { id, displayName, isTl } ],
     *   matchups:    [ {
     *     matchupId, mySide ('a'|'b'),
     *     opponentTeam: { id, teamName },
     *     matches: [ {
     *       matchId, matchKind,
     *       myAssigned: { participantId, displayName, pickSubmitted } | null,
     *       myLocked,
     *       opponentAssigned: { participantId, displayName } | null,
     *       opponentLocked
     *     } ]
     *   } ]
     * }
     * </pre>
     */
    @GetMapping("/{token}")
    public ResponseEntity<Map<String, Object>> getTlView(@PathVariable String token) {
        CompetitionTeam myTeam = teamRepository.findByTlToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid TL token"));
        Competition comp = myTeam.getCompetition();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("team", teamMap(myTeam));
        root.put("competition", competitionMap(comp));

        // 自チームの 4 人
        List<CompetitionParticipant> members =
                participantRepository.findByTeamOrderByCreatedAtAsc(myTeam);
        List<Map<String, Object>> memberMaps = new ArrayList<>();
        for (CompetitionParticipant m : members) memberMaps.add(memberMap(m));
        root.put("members", memberMaps);

        // 自チームが関与する matchup を 4 件抽出
        List<CompetitionMatchup> allMatchups =
                matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp);
        List<Map<String, Object>> matchupMaps = new ArrayList<>();
        for (CompetitionMatchup mu : allMatchups) {
            boolean iAmA = mu.getTeamA() != null && mu.getTeamA().getId().equals(myTeam.getId());
            boolean iAmB = mu.getTeamB() != null && mu.getTeamB().getId().equals(myTeam.getId());
            if (!iAmA && !iAmB) continue;

            CompetitionTeam opponent = iAmA ? mu.getTeamB() : mu.getTeamA();

            // 自軍の公開状態と相手の公開状態。後者は「相手起用が自分から見えるか」の判定に使う。
            boolean myLineupPublished = iAmA
                    ? Boolean.TRUE.equals(mu.getLineupPublishedA())
                    : Boolean.TRUE.equals(mu.getLineupPublishedB());
            boolean opponentLineupPublished = iAmA
                    ? Boolean.TRUE.equals(mu.getLineupPublishedB())
                    : Boolean.TRUE.equals(mu.getLineupPublishedA());

            Map<String, Object> mum = new LinkedHashMap<>();
            mum.put("matchupId", mu.getId());
            mum.put("matchupOrder", mu.getMatchupOrder());
            mum.put("mySide", iAmA ? "a" : "b");
            mum.put("myLineupPublished", myLineupPublished);
            mum.put("opponentLineupPublished", opponentLineupPublished);
            Map<String, Object> oppTeam = new LinkedHashMap<>();
            oppTeam.put("id", opponent != null ? opponent.getId() : null);
            oppTeam.put("teamName", opponent != null ? opponent.getTeamName() : null);
            mum.put("opponentTeam", oppTeam);

            // この matchup の 3 試合を取得し vanguard→middle→captain 順に並べる
            List<CompetitionMatch> matches = matchRepository.findByMatchupOrderByIdAsc(mu);
            matches.sort(Comparator.comparingInt(m -> matchKindOrder(m.getMatchKind())));

            List<Map<String, Object>> matchMaps = new ArrayList<>();
            for (CompetitionMatch match : matches) {
                CompetitionParticipant mine = iAmA ? match.getPlayerA() : match.getPlayerB();
                CompetitionParticipant theirs = iAmA ? match.getPlayerB() : match.getPlayerA();
                boolean myLocked = iAmA ? Boolean.TRUE.equals(match.getLockedA())
                                        : Boolean.TRUE.equals(match.getLockedB());
                boolean theirLocked = iAmA ? Boolean.TRUE.equals(match.getLockedB())
                                           : Boolean.TRUE.equals(match.getLockedA());

                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("matchId", match.getId());
                mm.put("matchKind", match.getMatchKind());
                mm.put("requiredGenre", match.getRequiredGenre());
                mm.put("myLocked", myLocked);
                mm.put("opponentLocked", theirLocked);
                mm.put("myAssigned", mine == null ? null : assignedMap(mine, match));
                // 相手の起用名は「相手チーム側のラインアップ公開済」のときだけ可視
                mm.put("opponentAssigned",
                        (theirs == null || !opponentLineupPublished) ? null : assignedOpponentMap(theirs));
                matchMaps.add(mm);
            }
            mum.put("matches", matchMaps);
            matchupMaps.add(mum);
        }
        root.put("matchups", matchupMaps);
        return ResponseEntity.ok(root);
    }

    /**
     * 【メソッドの役割】 自チーム側の slot にメンバーをアサインする (上書き)。
     *
     * <p>{@code participantId} が null の場合は {@link #unassign} と同等扱い。
     */
    @PutMapping("/{token}/match/{matchId}/assign")
    @Transactional
    public ResponseEntity<Map<String, Object>> assign(
            @PathVariable String token,
            @PathVariable Long matchId,
            @RequestBody AssignRequest req) {
        CompetitionTeam myTeam = teamRepository.findByTlToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid TL token"));
        Competition comp = myTeam.getCompetition();
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では編成変更できません"));
        }

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        CompetitionMatchup matchup = match.getMatchup();

        boolean iAmA = matchup.getTeamA() != null && matchup.getTeamA().getId().equals(myTeam.getId());
        boolean iAmB = matchup.getTeamB() != null && matchup.getTeamB().getId().equals(myTeam.getId());
        if (!iAmA && !iAmB) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合は自チームの担当ではありません"));
        }
        boolean myLocked = iAmA ? Boolean.TRUE.equals(match.getLockedA())
                                : Boolean.TRUE.equals(match.getLockedB());
        if (myLocked) {
            return ResponseEntity.badRequest().body(Map.of("message", "ロック済の slot は変更できません"));
        }

        if (req == null || req.participantId() == null) {
            // 空席に戻す
            if (iAmA) match.setPlayerA(null); else match.setPlayerB(null);
            matchRepository.save(match);
            return ResponseEntity.ok(Map.of("message", "未割当に戻しました"));
        }

        CompetitionParticipant assignee = participantRepository.findById(req.participantId())
                .orElseThrow(() -> new RuntimeException("Participant not found: " + req.participantId()));
        if (!assignee.getTeam().getId().equals(myTeam.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "自チームのメンバーではありません"));
        }

        // 同一 matchup 内で同じ participant を別 slot にアサインしていないかチェック
        List<CompetitionMatch> sibling = matchRepository.findByMatchupOrderByIdAsc(matchup);
        for (CompetitionMatch s : sibling) {
            if (s.getId().equals(match.getId())) continue;
            CompetitionParticipant mineInSibling = iAmA ? s.getPlayerA() : s.getPlayerB();
            if (mineInSibling != null && mineInSibling.getId().equals(assignee.getId())) {
                return ResponseEntity.badRequest().body(Map.of("message",
                        assignee.getDisplayName() + " はすでに " + matchKindLabel(s.getMatchKind())
                                + " にアサイン済みです。先に外してください"));
            }
        }

        if (iAmA) match.setPlayerA(assignee); else match.setPlayerB(assignee);
        matchRepository.save(match);
        return ResponseEntity.ok(Map.of(
                "matchId", match.getId(),
                "matchKind", match.getMatchKind(),
                "myAssigned", assignedMap(assignee, match)
        ));
    }

    /**
     * 【メソッドの役割】 自チーム側の slot を空席に戻す。
     */
    @DeleteMapping("/{token}/match/{matchId}/assign")
    @Transactional
    public ResponseEntity<Map<String, Object>> unassign(
            @PathVariable String token,
            @PathVariable Long matchId) {
        return assign(token, matchId, new AssignRequest(null));
    }

    // ── 内部ヘルパ ───────────────────────────────────────────

    private static int matchKindOrder(String kind) {
        return switch (kind) {
            case "vanguard" -> 0;
            case "middle" -> 1;
            case "captain" -> 2;
            default -> 99;
        };
    }

    private static String matchKindLabel(String kind) {
        return switch (kind) {
            case "vanguard" -> "先鋒戦";
            case "middle" -> "中堅戦";
            case "captain" -> "大将戦";
            default -> kind;
        };
    }

    // ── レスポンス整形 ───────────────────────────────────────

    private Map<String, Object> teamMap(CompetitionTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("teamName", t.getTeamName());
        m.put("teamOrder", t.getTeamOrder());
        return m;
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

    private Map<String, Object> memberMap(CompetitionParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("displayName", p.getDisplayName());
        m.put("isTl", p.getIsTl());
        return m;
    }

    /**
     * 自チーム slot 表現。TL は自チームメンバーの提出状況も把握したいので {@code pickSubmitted} を含める。
     *
     * <p>Phase 4 以降は pick が (プレイヤー × 試合) 単位になったため、提出有無は試合単位で問い合わせる。
     */
    private Map<String, Object> assignedMap(CompetitionParticipant p, CompetitionMatch match) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participantId", p.getId());
        m.put("displayName", p.getDisplayName());
        m.put("isTl", p.getIsTl());
        m.put("pickSubmitted", pickRepository.findByMatchAndParticipant(match, p).isPresent());
        return m;
    }

    /**
     * 相手チーム slot 表現。pick の提出状況は相手のロック状態を見て別途判断する想定なのでここでは含めない。
     */
    private Map<String, Object> assignedOpponentMap(CompetitionParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("participantId", p.getId());
        m.put("displayName", p.getDisplayName());
        return m;
    }

    // ── DTO ──────────────────────────────────────────────────

    /**
     * アサイン更新リクエスト。
     * {@code participantId} が null の場合は空席に戻す。
     */
    public record AssignRequest(Long participantId) {}
}
