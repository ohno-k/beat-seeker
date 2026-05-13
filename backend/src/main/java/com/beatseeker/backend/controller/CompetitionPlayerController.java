package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【クラスの役割】 招待トークン認証だけで使える参加者向け API。
 *
 * <p>ログイン不要・JWT 不要で、URL に埋め込まれた {@code inviteToken} がそのまま本人確認材料となる。
 * SecurityConfig 側で {@code /api/competition-access/**} を {@code permitAll} に開けておくこと。
 *
 * <p>提供エンドポイント:
 * <ul>
 *   <li>{@code GET /api/competition-access/player/{token}}: 自分の情報 + チーム + 大会 + 担当試合 (各試合に
 *       自選曲 + 相手選曲 + StrategyCard 使用フラグを内包)</li>
 *   <li>{@code PUT /api/competition-access/player/{token}/picks/match/{matchId}}: 試合別自選曲を upsert</li>
 *   <li>{@code DELETE /api/competition-access/player/{token}/picks/match/{matchId}}: 試合別自選曲を取消し</li>
 *   <li>{@code PUT /api/competition-access/player/{token}/strategy/{matchId}}: 相手選曲に対する StrategyCard ON/OFF</li>
 * </ul>
 *
 * <p>新モデル (Phase 4):
 * <ul>
 *   <li>自選曲は (プレイヤー × 試合) 単位。同じ matchKind の試合でも matchup ごとに別曲を出せる。</li>
 *   <li>各試合に主催が設定する {@code requiredGenre} があり、プレイヤーはそのジャンル内からしか選曲できない。
 *       requiredGenre が未指定の試合では自選曲提出 UI 自体が開かない (フロント側の責務)。</li>
 *   <li>提出時にサーバ側でも {@code requiredGenre} 一致を再検証する。</li>
 * </ul>
 *
 * <p>ロック挙動:
 * <ul>
 *   <li>自選曲は対象試合の自分側が locked になっていたら編集不可。</li>
 *   <li>StrategyCard 決定は対象試合の相手側が locked になっている時のみ可。</li>
 *   <li>大会 status === {@code finished} の場合は何も書き込めない。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/competition-access/player")
public class CompetitionPlayerController {

    private static final Set<String> ALLOWED_GENRES =
            Set.of("NOTES", "PEAK", "CHORD", "CHARGE", "SCRATCH", "SOF-LAN", "INSANE");

    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionPickRepository pickRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionStrategyUseRepository strategyUseRepository;

    public CompetitionPlayerController(CompetitionParticipantRepository participantRepository,
                                       CompetitionPickRepository pickRepository,
                                       CompetitionMatchRepository matchRepository,
                                       CompetitionStrategyUseRepository strategyUseRepository) {
        this.participantRepository = participantRepository;
        this.pickRepository = pickRepository;
        this.matchRepository = matchRepository;
        this.strategyUseRepository = strategyUseRepository;
    }

    /**
     * 【メソッドの役割】 招待トークンに対応する参加者ビューを 1 回の GET で返す。
     *
     * <p>レスポンス構造:
     * <pre>
     * {
     *   participant: { id, displayName, isTl, teamId },
     *   team:        { id, teamName, teamOrder },
     *   competition: { id, name, status, deadlineAt, lockedAt },
     *   matches:     [ Match ]
     * }
     * Match: {
     *   matchId, matchKind, requiredGenre (nullable),
     *   mySide ('a' | 'b'), myLocked, opponentLocked,
     *   opponent:     { participantId, displayName, teamName },
     *   myPick:       { songGenre, songLevel, songStrategyId, songTitle, songDiff, submittedAt, updatedAt } | null,
     *   opponentPick: { songGenre, songLevel, songStrategyId, songTitle, songDiff } | null (相手側ロック時のみ),
     *   myStrategyUse: { enabled, decidedAt } | null
     * }
     * </pre>
     */
    @GetMapping("/{token}")
    public ResponseEntity<Map<String, Object>> getPlayerView(@PathVariable String token) {
        CompetitionParticipant me = participantRepository.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid invite token"));

        Competition comp = me.getCompetition();
        CompetitionTeam myTeam = me.getTeam();

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("participant", participantSelfMap(me));
        root.put("team", teamMap(myTeam));
        root.put("competition", competitionMap(comp));

        // 担当試合: 大会全試合をスキャンして自分が player_a / player_b の物を抽出
        List<CompetitionMatch> allMatches = matchRepository.findAllByCompetition(comp);
        List<Map<String, Object>> matchMaps = new ArrayList<>();
        for (CompetitionMatch m : allMatches) {
            CompetitionParticipant pa = m.getPlayerA();
            CompetitionParticipant pb = m.getPlayerB();
            boolean iAmA = pa != null && pa.getId().equals(me.getId());
            boolean iAmB = pb != null && pb.getId().equals(me.getId());
            if (!iAmA && !iAmB) continue;

            CompetitionParticipant opponent = iAmA ? pb : pa;
            boolean opponentLocked = iAmA ? Boolean.TRUE.equals(m.getLockedB()) : Boolean.TRUE.equals(m.getLockedA());
            boolean myLocked = iAmA ? Boolean.TRUE.equals(m.getLockedA()) : Boolean.TRUE.equals(m.getLockedB());
            // 公開フラグ: 相手側の起用と自選曲がそれぞれ「自分から見える」状態か
            CompetitionMatchup mu = m.getMatchup();
            boolean opponentLineupPublished = iAmA
                    ? Boolean.TRUE.equals(mu.getLineupPublishedB())
                    : Boolean.TRUE.equals(mu.getLineupPublishedA());
            boolean opponentPickPublished = iAmA
                    ? Boolean.TRUE.equals(m.getPickPublishedB())
                    : Boolean.TRUE.equals(m.getPickPublishedA());

            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("matchId", m.getId());
            mm.put("matchKind", m.getMatchKind());
            mm.put("requiredGenre", m.getRequiredGenre());
            mm.put("mySide", iAmA ? "a" : "b");
            mm.put("myLocked", myLocked);
            mm.put("opponentLocked", opponentLocked);
            mm.put("opponentLineupPublished", opponentLineupPublished);
            mm.put("opponentPickPublished", opponentPickPublished);

            // 相手起用名は「相手側のラインアップが公開済」のときだけ可視
            if (opponent != null && opponentLineupPublished) {
                Map<String, Object> opp = new LinkedHashMap<>();
                opp.put("participantId", opponent.getId());
                opp.put("displayName", opponent.getDisplayName());
                opp.put("teamName", opponent.getTeam() != null ? opponent.getTeam().getTeamName() : null);
                mm.put("opponent", opp);
            } else {
                mm.put("opponent", null);
            }

            // 自分の自選曲
            mm.put("myPick", pickRepository.findByMatchAndParticipant(m, me)
                    .map(this::pickMap).orElse(null));

            // 相手の自選曲は「相手側の自選曲が公開済」のときだけ可視
            if (opponentPickPublished && opponent != null) {
                mm.put("opponentPick", pickRepository.findByMatchAndParticipant(m, opponent)
                        .map(this::pickPublicMap).orElse(null));
            } else {
                mm.put("opponentPick", null);
            }

            // 自分の StrategyCard 使用記録
            mm.put("myStrategyUse", strategyUseRepository.findByMatchAndUsedByParticipant(m, me)
                    .map(this::strategyUseMap).orElse(null));

            matchMaps.add(mm);
        }
        root.put("matches", matchMaps);

        return ResponseEntity.ok(root);
    }

    /**
     * 【メソッドの役割】 試合別自選曲を upsert する。
     *
     * <p>同じ {@code (match, participant)} がすでにあれば中身を更新、無ければ新規作成。
     *
     * <p>検証:
     * <ul>
     *   <li>該当 match の自分側が locked → 400</li>
     *   <li>該当 match の {@code requiredGenre} が null → 400 (運営がまだジャンル未指定)</li>
     *   <li>payload.songGenre が match.requiredGenre と不一致 → 400</li>
     *   <li>Lv が matchKind の Lv 帯外 → 400</li>
     * </ul>
     */
    @PutMapping("/{token}/picks/match/{matchId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> upsertPick(
            @PathVariable String token,
            @PathVariable Long matchId,
            @RequestBody PickRequest req) {
        if (req == null
                || req.songGenre() == null || !ALLOWED_GENRES.contains(req.songGenre())
                || req.songLevel() == null
                || req.songStrategyId() == null
                || req.songTitle() == null || req.songTitle().isBlank()
                || req.songDiff() == null
                || (!"A".equals(req.songDiff()) && !"L".equals(req.songDiff()))) {
            return ResponseEntity.badRequest().body(Map.of("message", "曲データが不正です"));
        }

        CompetitionParticipant me = participantRepository.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid invite token"));

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        boolean iAmA = match.getPlayerA() != null && match.getPlayerA().getId().equals(me.getId());
        boolean iAmB = match.getPlayerB() != null && match.getPlayerB().getId().equals(me.getId());
        if (!iAmA && !iAmB) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合に出場予定がありません"));
        }

        Competition comp = match.getMatchup().getCompetition();
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会には提出できません"));
        }

        boolean myLocked = iAmA ? Boolean.TRUE.equals(match.getLockedA())
                                : Boolean.TRUE.equals(match.getLockedB());
        if (myLocked) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合はロック済のため変更できません"));
        }

        String requiredGenre = match.getRequiredGenre();
        if (requiredGenre == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "ジャンル未指定の試合です。運営の指定をお待ちください"));
        }
        if (!requiredGenre.equals(req.songGenre())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "この試合は " + requiredGenre + " のみ提出可能です"));
        }
        if (!isLevelInRange(req.songLevel(), match.getMatchKind())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Lv " + req.songLevel() + " は " + match.getMatchKind() + " 戦に提出できません"));
        }

        CompetitionPick pick = pickRepository.findByMatchAndParticipant(match, me)
                .orElseGet(() -> {
                    CompetitionPick fresh = new CompetitionPick();
                    fresh.setMatch(match);
                    fresh.setParticipant(me);
                    return fresh;
                });
        pick.setSongGenre(req.songGenre());
        pick.setSongLevel(req.songLevel());
        pick.setSongStrategyId(req.songStrategyId());
        pick.setSongTitle(req.songTitle().trim());
        pick.setSongDiff(req.songDiff());
        pick.setUpdatedAt(LocalDateTime.now());
        pick = pickRepository.save(pick);

        return ResponseEntity.ok(pickMap(pick));
    }

    /**
     * 【メソッドの役割】 試合別自選曲を取消す (DELETE)。ロック後は不可。
     */
    @DeleteMapping("/{token}/picks/match/{matchId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deletePick(
            @PathVariable String token,
            @PathVariable Long matchId) {
        CompetitionParticipant me = participantRepository.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid invite token"));
        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        boolean iAmA = match.getPlayerA() != null && match.getPlayerA().getId().equals(me.getId());
        boolean iAmB = match.getPlayerB() != null && match.getPlayerB().getId().equals(me.getId());
        if (!iAmA && !iAmB) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合に出場予定がありません"));
        }

        if ("finished".equals(match.getMatchup().getCompetition().getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では編集できません"));
        }
        boolean myLocked = iAmA ? Boolean.TRUE.equals(match.getLockedA())
                                : Boolean.TRUE.equals(match.getLockedB());
        if (myLocked) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合はロック済のため取消できません"));
        }

        pickRepository.findByMatchAndParticipant(match, me).ifPresent(pickRepository::delete);
        return ResponseEntity.ok(Map.of("message", "削除しました"));
    }

    /**
     * 【メソッドの役割】 ある試合について、相手選曲に対する StrategyCard 使用フラグを upsert する。
     *
     * <p>必須条件: 対象 match で相手側が locked であること (= 相手の自選曲が開示済み)。
     */
    @PutMapping("/{token}/strategy/{matchId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> upsertStrategy(
            @PathVariable String token,
            @PathVariable Long matchId,
            @RequestBody StrategyRequest req) {
        if (req == null || req.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "enabled フラグが必要です"));
        }
        CompetitionParticipant me = participantRepository.findByInviteToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid invite token"));

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));

        boolean iAmA = match.getPlayerA() != null && match.getPlayerA().getId().equals(me.getId());
        boolean iAmB = match.getPlayerB() != null && match.getPlayerB().getId().equals(me.getId());
        if (!iAmA && !iAmB) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合の参加者ではありません"));
        }

        boolean opponentPickPublished = iAmA
                ? Boolean.TRUE.equals(match.getPickPublishedB())
                : Boolean.TRUE.equals(match.getPickPublishedA());
        if (!opponentPickPublished) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "相手の自選曲がまだ公開されていません。公開後に決定できます"));
        }

        if ("finished".equals(match.getMatchup().getCompetition().getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では決定できません"));
        }

        CompetitionStrategyUse su = strategyUseRepository.findByMatchAndUsedByParticipant(match, me)
                .orElseGet(() -> {
                    CompetitionStrategyUse fresh = new CompetitionStrategyUse();
                    fresh.setMatch(match);
                    fresh.setUsedByParticipant(me);
                    return fresh;
                });
        su.setEnabled(req.enabled());
        su.setDecidedAt(LocalDateTime.now());
        su = strategyUseRepository.save(su);

        return ResponseEntity.ok(strategyUseMap(su));
    }

    // ── 内部ヘルパ ───────────────────────────────────────────

    /** matchKind ごとの Lv 制約。vanguard: 8-10 / middle: 11 / captain: 12 */
    private boolean isLevelInRange(int level, String matchKind) {
        return switch (matchKind) {
            case "vanguard" -> level >= 8 && level <= 10;
            case "middle" -> level == 11;
            case "captain" -> level == 12;
            default -> false;
        };
    }

    // ── レスポンス整形 ───────────────────────────────────────

    private Map<String, Object> participantSelfMap(CompetitionParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("displayName", p.getDisplayName());
        m.put("isTl", p.getIsTl());
        m.put("teamId", p.getTeam() != null ? p.getTeam().getId() : null);
        return m;
    }

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

    /** 自分用 pick 表現 (submittedAt 等メタ込み)。 */
    private Map<String, Object> pickMap(CompetitionPick p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songGenre", p.getSongGenre());
        m.put("songLevel", p.getSongLevel());
        m.put("songStrategyId", p.getSongStrategyId());
        m.put("songTitle", p.getSongTitle());
        m.put("songDiff", p.getSongDiff());
        m.put("submittedAt", p.getSubmittedAt());
        m.put("updatedAt", p.getUpdatedAt());
        return m;
    }

    /** 公開用 (相手から見える) pick 表現。submittedAt 等メタは出さず本質情報だけ。 */
    private Map<String, Object> pickPublicMap(CompetitionPick p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songGenre", p.getSongGenre());
        m.put("songLevel", p.getSongLevel());
        m.put("songStrategyId", p.getSongStrategyId());
        m.put("songTitle", p.getSongTitle());
        m.put("songDiff", p.getSongDiff());
        return m;
    }

    private Map<String, Object> strategyUseMap(CompetitionStrategyUse su) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", su.getEnabled());
        m.put("decidedAt", su.getDecidedAt());
        return m;
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 自選曲提出リクエスト。 */
    public record PickRequest(
            String songGenre,
            Integer songLevel,
            Integer songStrategyId,
            String songTitle,
            String songDiff) {}

    /** StrategyCard 使用フラグ更新リクエスト。 */
    public record StrategyRequest(Boolean enabled) {}
}
