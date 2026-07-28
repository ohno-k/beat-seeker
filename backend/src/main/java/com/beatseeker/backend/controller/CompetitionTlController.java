package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import com.beatseeker.backend.service.AdminAuthService;
import com.beatseeker.backend.service.EmailService;
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

    /** 各プレイヤーの予選初期コスト。 */
    public static final int INITIAL_COST = 80;
    /** 戦種別ごとの消費コスト。決勝 (isFinals) では適用しない。 */
    public static final Map<String, Integer> COST_PER_KIND = Map.of(
            "vanguard", 10,
            "middle", 20,
            "captain", 30
    );
    /** 1 チームが予選で StrategyCard を使える matchup 数の上限 (決勝は対象外)。 */
    public static final int STRATEGY_MATCHUP_LIMIT_PER_TEAM = 2;

    private final CompetitionTeamRepository teamRepository;
    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionMatchupRepository matchupRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionPickRepository pickRepository;
    private final CompetitionStrategyUseRepository strategyUseRepository;
    private final CompetitionChatMessageRepository chatRepository;
    private final UserRepository userRepository;
    private final AdminAuthService adminAuthService;
    private final EmailService emailService;

    public CompetitionTlController(CompetitionTeamRepository teamRepository,
                                   CompetitionParticipantRepository participantRepository,
                                   CompetitionMatchupRepository matchupRepository,
                                   CompetitionMatchRepository matchRepository,
                                   CompetitionPickRepository pickRepository,
                                   CompetitionStrategyUseRepository strategyUseRepository,
                                   CompetitionChatMessageRepository chatRepository,
                                   UserRepository userRepository,
                                   AdminAuthService adminAuthService,
                                   EmailService emailService) {
        this.teamRepository = teamRepository;
        this.participantRepository = participantRepository;
        this.matchupRepository = matchupRepository;
        this.matchRepository = matchRepository;
        this.pickRepository = pickRepository;
        this.strategyUseRepository = strategyUseRepository;
        this.chatRepository = chatRepository;
        this.userRepository = userRepository;
        this.adminAuthService = adminAuthService;
        this.emailService = emailService;
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
     *       opponentLocked,
     *       opponentPickPublished,
     *       opponentPick: { songGenre, songLevel, songStrategyId, songTitle, songDiff } | null
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

        // 自チームの 4 人 + 各人の予選コスト消費・残量を計算
        List<CompetitionParticipant> members =
                participantRepository.findByTeamOrderByCreatedAtAsc(myTeam);

        // 大会全試合をスキャンして、自チームメンバーが何戦に起用されているか集計
        List<CompetitionMatch> allMatches = matchRepository.findAllByCompetition(comp);
        Map<Long, Integer> spentByParticipantId = new HashMap<>();
        for (CompetitionMatch m : allMatches) {
            // 決勝 matchup はコスト計算対象外
            if (Boolean.TRUE.equals(m.getMatchup().getIsFinals())) continue;
            int cost = COST_PER_KIND.getOrDefault(m.getMatchKind(), 0);
            for (CompetitionParticipant p : new CompetitionParticipant[] { m.getPlayerA(), m.getPlayerB() }) {
                if (p == null) continue;
                if (!myTeam.getId().equals(p.getTeam().getId())) continue;
                spentByParticipantId.merge(p.getId(), cost, Integer::sum);
            }
        }

        List<Map<String, Object>> memberMaps = new ArrayList<>();
        for (CompetitionParticipant m : members) {
            int spent = spentByParticipantId.getOrDefault(m.getId(), 0);
            memberMaps.add(memberMap(m, spent));
        }
        root.put("members", memberMaps);
        root.put("initialCost", INITIAL_COST);
        root.put("costPerKind", COST_PER_KIND);

        // StrategyCard: 自チームが現在 enabled にしている予選 matchup の集合 (上限判定/表示用)。決勝は対象外。
        Set<Long> myEnabledPrelimMatchupIds = new HashSet<>();
        for (CompetitionStrategyUse su : strategyUseRepository.findAllByCompetition(comp)) {
            if (!Boolean.TRUE.equals(su.getEnabled())) continue;
            CompetitionParticipant user = su.getUsedByParticipant();
            if (user == null || user.getTeam() == null || !myTeam.getId().equals(user.getTeam().getId())) continue;
            CompetitionMatchup mu2 = su.getMatch().getMatchup();
            if (Boolean.TRUE.equals(mu2.getIsFinals())) continue;
            myEnabledPrelimMatchupIds.add(mu2.getId());
        }
        root.put("strategyLimit", STRATEGY_MATCHUP_LIMIT_PER_TEAM);
        root.put("strategyUsedMatchupCount", myEnabledPrelimMatchupIds.size());

        boolean compFinished = "finished".equals(comp.getStatus());

        // 自チームが関与する matchup を 4 件抽出
        List<CompetitionMatchup> allMatchups =
                matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp);
        List<Map<String, Object>> matchupMaps = new ArrayList<>();
        for (CompetitionMatchup mu : allMatchups) {
            // 運営が未設定の matchup は TL に表示しない (設定済みになって初めて公開)
            if (!Boolean.TRUE.equals(mu.getConfigured())) continue;
            boolean iAmA = mu.getTeamA() != null && mu.getTeamA().getId().equals(myTeam.getId());
            boolean iAmB = mu.getTeamB() != null && mu.getTeamB().getId().equals(myTeam.getId());
            if (!iAmA && !iAmB) continue;

            CompetitionTeam opponent = iAmA ? mu.getTeamB() : mu.getTeamA();

            // 起用公開は起用公開日時 (lineupPublishAt) 到達で両サイド一斉に自動公開 (起用クローズとは独立)。
            boolean lineupPublished = comp.isLineupPublished();
            boolean myLineupPublished = lineupPublished;
            boolean opponentLineupPublished = lineupPublished;

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
            // 手動ロックは廃止。起用クローズ日時 (comp.deadlineAt) を過ぎたら両サイド一斉にロック扱い。
            boolean closed = comp.isLineupClosed();
            for (CompetitionMatch match : matches) {
                CompetitionParticipant mine = iAmA ? match.getPlayerA() : match.getPlayerB();
                CompetitionParticipant theirs = iAmA ? match.getPlayerB() : match.getPlayerA();
                boolean myLocked = closed;
                boolean theirLocked = closed;

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

                // 相手の自選曲は「相手側の自選曲が公開済 (pickPublished)」のときだけ可視。
                // StrategyCard 発動 (相手曲を同ジャンル×Lv帯でランダム化) を TL が判断するのに必要。
                boolean opponentPickPublished = iAmA
                        ? Boolean.TRUE.equals(match.getPickPublishedB())
                        : Boolean.TRUE.equals(match.getPickPublishedA());
                mm.put("opponentPickPublished", opponentPickPublished);
                mm.put("opponentPick",
                        (theirs == null || !opponentPickPublished) ? null
                                : pickRepository.findByMatchAndParticipant(match, theirs)
                                        .map(this::pickPublicMap).orElse(null));

                // StrategyCard 発動: 起用ロック (closed) + 相手起用公開 + 両者アサイン済 で TL が決定可能。
                // 決定の本体は「発動予定」フラグのみ。相手曲のランダム化抽選は Reveal 時に確定する。
                boolean strategyDecidable = closed && opponentLineupPublished
                        && mine != null && theirs != null && !compFinished;
                boolean myStrategyEnabled = mine != null
                        && strategyUseRepository.findByMatchAndUsedByParticipant(match, mine)
                                .map(su -> Boolean.TRUE.equals(su.getEnabled())).orElse(false);
                mm.put("strategyDecidable", strategyDecidable);
                mm.put("myStrategyEnabled", myStrategyEnabled);
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
        // 手動ロック廃止: 起用クローズ日時を過ぎていたら編成変更不可。
        if (comp.isLineupClosed()) {
            return ResponseEntity.badRequest().body(Map.of("message", "起用クローズ日時を過ぎたため編成変更できません"));
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

        // 予選コスト制限のチェック (決勝 matchup は対象外)。
        // 既に他の予選試合で消費したコスト + 今回の戦コストが INITIAL_COST を超えないこと。
        if (!Boolean.TRUE.equals(matchup.getIsFinals())) {
            int costForThisKind = COST_PER_KIND.getOrDefault(match.getMatchKind(), 0);
            int spentExceptThis = 0;
            List<CompetitionMatch> allMatches = matchRepository.findAllByCompetition(comp);
            for (CompetitionMatch m : allMatches) {
                if (Boolean.TRUE.equals(m.getMatchup().getIsFinals())) continue;
                if (m.getId().equals(match.getId())) continue; // 今回上書きする slot は除外
                CompetitionParticipant pa = m.getPlayerA();
                CompetitionParticipant pb = m.getPlayerB();
                if ((pa != null && pa.getId().equals(assignee.getId()))
                        || (pb != null && pb.getId().equals(assignee.getId()))) {
                    spentExceptThis += COST_PER_KIND.getOrDefault(m.getMatchKind(), 0);
                }
            }
            int wouldSpend = spentExceptThis + costForThisKind;
            if (wouldSpend > INITIAL_COST) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", assignee.getDisplayName() + " のコストが不足しています "
                                + "(消費予定 " + wouldSpend + " > 初期 " + INITIAL_COST + ")"));
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

    // ── StrategyCard 発動の決定 (TL が起用ロック&公開後に判断) ──────────

    /**
     * 【メソッドの役割】 自チーム側プレイヤーの StrategyCard 発動予定を ON/OFF する。
     *
     * <p>発動可能条件 (起用フェーズ完了後): 起用クローズ済み + 相手のオーダー(起用)公開済み + 両者アサイン済み。
     * 予選は 1 チーム最大 {@value #STRATEGY_MATCHUP_LIMIT_PER_TEAM} matchup まで (決勝は無制限)。
     *
     * <p>ここでは「発動予定」フラグ ({@code enabled}) を更新するだけ。相手の自選曲をランダム化する抽選は
     * Reveal データ生成時 (運営側) に確定するため、本エンドポイントでは抽選しない。
     * OFF に戻した場合は確定済み抽選結果をクリアする。
     */
    @PutMapping("/{token}/match/{matchId}/strategy")
    @Transactional
    public ResponseEntity<Map<String, Object>> setStrategy(
            @PathVariable String token,
            @PathVariable Long matchId,
            @RequestBody StrategyRequest req) {
        if (req == null || req.enabled() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "enabled フラグが必要です"));
        }
        CompetitionTeam myTeam = teamRepository.findByTlToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid TL token"));
        Competition comp = myTeam.getCompetition();
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では変更できません"));
        }

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        CompetitionMatchup matchup = match.getMatchup();
        boolean iAmA = matchup.getTeamA() != null && matchup.getTeamA().getId().equals(myTeam.getId());
        boolean iAmB = matchup.getTeamB() != null && matchup.getTeamB().getId().equals(myTeam.getId());
        if (!iAmA && !iAmB) {
            return ResponseEntity.badRequest().body(Map.of("message", "この試合は自チームの担当ではありません"));
        }

        final CompetitionParticipant mine = iAmA ? match.getPlayerA() : match.getPlayerB();
        CompetitionParticipant theirs = iAmA ? match.getPlayerB() : match.getPlayerA();

        // 発動可能ゲート
        if (!comp.isLineupClosed()) {
            return ResponseEntity.badRequest().body(Map.of("message", "起用クローズ後に発動を決定できます"));
        }
        // 相手のオーダー(起用)が公開日時 (lineupPublishAt) を過ぎて公開されてから発動を決定できる (起用クローズとは独立)。
        if (!comp.isLineupPublished()) {
            return ResponseEntity.badRequest().body(Map.of("message", "相手のオーダー(起用)公開後に発動を決定できます"));
        }
        if (mine == null || theirs == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "両者の起用が揃ってから発動を決定できます"));
        }

        boolean willEnable = Boolean.TRUE.equals(req.enabled());

        // 予選上限チェック (決勝は対象外)。新しい matchup を ON にするときだけ。
        if (willEnable && !Boolean.TRUE.equals(matchup.getIsFinals())) {
            boolean alreadyEnabledHere = strategyUseRepository.findByMatchAndUsedByParticipant(match, mine)
                    .map(su -> Boolean.TRUE.equals(su.getEnabled())).orElse(false);
            if (!alreadyEnabledHere) {
                Set<Long> usedPrelimMatchupIds = new HashSet<>();
                for (CompetitionStrategyUse su : strategyUseRepository.findAllByCompetition(comp)) {
                    if (!Boolean.TRUE.equals(su.getEnabled())) continue;
                    CompetitionParticipant user = su.getUsedByParticipant();
                    if (user == null || user.getTeam() == null
                            || !myTeam.getId().equals(user.getTeam().getId())) continue;
                    CompetitionMatchup mu = su.getMatch().getMatchup();
                    if (Boolean.TRUE.equals(mu.getIsFinals())) continue;
                    usedPrelimMatchupIds.add(mu.getId());
                }
                if (!usedPrelimMatchupIds.contains(matchup.getId())
                        && usedPrelimMatchupIds.size() >= STRATEGY_MATCHUP_LIMIT_PER_TEAM) {
                    return ResponseEntity.badRequest().body(Map.of("message",
                            "予選では 1 チーム最大 " + STRATEGY_MATCHUP_LIMIT_PER_TEAM
                                    + " matchup までしか発動できません (上限到達)"));
                }
            }
        }

        CompetitionStrategyUse su = strategyUseRepository.findByMatchAndUsedByParticipant(match, mine)
                .orElseGet(() -> {
                    CompetitionStrategyUse fresh = new CompetitionStrategyUse();
                    fresh.setMatch(match);
                    fresh.setUsedByParticipant(mine);
                    return fresh;
                });
        // OFF に戻したら確定済み抽選結果をクリア (Reveal で再抽選される)。
        if (!willEnable) {
            su.setResultSongStrategyId(null);
            su.setResultSongTitle(null);
            su.setResultSongVersion(null);
            su.setResultSongDiff(null);
            su.setResultSongLevel(null);
            su.setResultSongGenre(null);
        }
        su.setEnabled(willEnable);
        su.setDecidedAt(java.time.LocalDateTime.now());
        strategyUseRepository.save(su);

        return ResponseEntity.ok(Map.of("matchId", match.getId(), "myStrategyEnabled", willEnable));
    }

    // ── 運営チャット ─────────────────────────────────────────

    /**
     * 【メソッドの役割】 自チームスレッドの運営チャット履歴を古い順で返す。
     *
     * <p>TL がポーリングして運営からの返信を受け取るのにも使う。
     */
    @GetMapping("/{token}/chat")
    public ResponseEntity<List<Map<String, Object>>> getChat(@PathVariable String token) {
        CompetitionTeam myTeam = teamRepository.findByTlToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid TL token"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CompetitionChatMessage m : chatRepository.findByTeamOrderByCreatedAtAsc(myTeam)) {
            out.add(chatMap(m));
        }
        return ResponseEntity.ok(out);
    }

    /**
     * 【メソッドの役割】 TL から運営へチャットメッセージを送信する。
     *
     * <p>保存後、運営 (管理者ユーザー) のメールアドレス宛に「新着メッセージが届いた」旨を
     * 非同期通知する。メール失敗は握り潰してメッセージ保存自体は成功させる。
     */
    @PostMapping("/{token}/chat")
    @Transactional
    public ResponseEntity<Map<String, Object>> postChat(
            @PathVariable String token,
            @RequestBody ChatRequest req) {
        CompetitionTeam myTeam = teamRepository.findByTlToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid TL token"));
        if (req == null || req.body() == null || req.body().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "メッセージを入力してください"));
        }
        String body = req.body().trim();
        if (body.length() > 2000) body = body.substring(0, 2000);

        CompetitionChatMessage m = new CompetitionChatMessage();
        m.setTeam(myTeam);
        m.setSender("tl");
        m.setBody(body);
        m.setReadByAdmin(false);
        m = chatRepository.save(m);

        // 運営 (管理者ユーザー) にメール通知。失敗してもメッセージ送信は成功扱い。
        final String notifyBody = body;
        try {
            userRepository.findById(adminAuthService.getAdminUserId()).ifPresent(admin -> {
                if (admin.getEmail() != null && !admin.getEmail().isBlank()) {
                    emailService.sendTlChatNotification(
                            admin.getEmail(),
                            myTeam.getCompetition().getName(),
                            myTeam.getTeamName(),
                            notifyBody);
                }
            });
        } catch (Exception e) {
            System.err.println("Failed to enqueue TL chat notification: " + e.getMessage());
        }

        return ResponseEntity.ok(chatMap(m));
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

    /** チャットメッセージ 1 件を表示用 Map に整形 (id / sender / body / createdAt)。 */
    static Map<String, Object> chatMap(CompetitionChatMessage m) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", m.getId());
        map.put("sender", m.getSender());
        map.put("body", m.getBody());
        map.put("createdAt", m.getCreatedAt());
        return map;
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

    /**
     * メンバー 1 人の表示用 Map。予選コスト消費 (spent) と残量 (remaining = INITIAL - spent) を含める。
     * 決勝 matchup は呼び出し側で除外している前提。
     */
    private Map<String, Object> memberMap(CompetitionParticipant p, int spentCost) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("displayName", p.getDisplayName());
        m.put("isTl", p.getIsTl());
        m.put("spentCost", spentCost);
        m.put("remainingCost", INITIAL_COST - spentCost);
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

    /**
     * 公開用 (相手から見える) 自選曲表現。submittedAt 等メタは出さず本質情報だけ。
     * TL が相手の自選曲を見て StrategyCard 発動を判断するのに使う。
     */
    private Map<String, Object> pickPublicMap(CompetitionPick p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songGenre", p.getSongGenre());
        m.put("songLevel", p.getSongLevel());
        m.put("songStrategyId", p.getSongStrategyId());
        m.put("songTitle", p.getSongTitle());
        m.put("songDiff", p.getSongDiff());
        return m;
    }

    // ── DTO ──────────────────────────────────────────────────

    /**
     * アサイン更新リクエスト。
     * {@code participantId} が null の場合は空席に戻す。
     */
    public record AssignRequest(Long participantId) {}

    /**
     * 運営チャット送信リクエスト。{@code body} が本文。
     */
    public record ChatRequest(String body) {}

    /**
     * StrategyCard 発動予定の ON/OFF リクエスト。{@code enabled} = true で発動予定。
     */
    public record StrategyRequest(Boolean enabled) {}
}
