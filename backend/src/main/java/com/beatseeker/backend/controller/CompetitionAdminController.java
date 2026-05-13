package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import com.beatseeker.backend.service.OrganizerAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 【クラスの役割】 大会主催 (Competition セクションのホワイトリスト 4 ID) 向けの管理 API。
 *
 * <p>本 Controller のすべてのエンドポイントは {@link OrganizerAuthService#isOrganizer(User)} を通り、
 * 主催権限を持たないユーザーは 403 で弾かれる。
 *
 * <p>Phase 1 (バックエンド土台) でのスコープ:
 * <ul>
 *   <li>大会の作成 (5 チーム枠と TL トークンを同時生成)</li>
 *   <li>大会の一覧/詳細取得</li>
 *   <li>チーム名のリネーム</li>
 *   <li>参加者の追加/更新/削除 (1 チーム 4 名、各チーム TL 1 名のルール)</li>
 *   <li>{@code draft → open} 遷移時の matchup 10 件 + match 30 件の自動生成</li>
 * </ul>
 *
 * <p>TL のラインアップ管理 (試合の player_a/b アサイン) と
 * 参加者の自選曲提出/StrategyCard 決定はそれぞれ別 Controller で扱う想定 (Phase 2 以降)。
 *
 * <p>cascade 削除は今 Phase では未対応のため、{@code DELETE /api/competitions/{id}} は提供しない。
 */
@RestController
@RequestMapping("/api/competitions")
public class CompetitionAdminController {

    /** 戦種別。先鋒 → 中堅 → 大将 の順で 1 matchup ぶんの 3 試合を生成する。 */
    private static final List<String> MATCH_KINDS = List.of("vanguard", "middle", "captain");

    /** 1 大会あたり 5 チーム固定。 */
    private static final int TEAMS_PER_COMPETITION = 5;

    /** 1 チームあたり 4 人固定。 */
    private static final int PARTICIPANTS_PER_TEAM = 4;

    /** 試合に指定可能なジャンル文字列 (StrategyCard プールと同じ 7 種)。 */
    private static final Set<String> ALLOWED_GENRES =
            Set.of("NOTES", "PEAK", "CHORD", "CHARGE", "SCRATCH", "SOF-LAN", "INSANE");

    /** 戦種別ごとの 1 曲あたり獲得ポイント (予選: 先鋒2 / 中堅3 / 大将4)。 */
    private static final Map<String, Integer> POINTS_PER_SONG_BY_KIND = Map.of(
            "vanguard", 2,
            "middle", 3,
            "captain", 4
    );

    /** matchup 勝利時のチーム勝ち点。 */
    private static final int MATCHUP_WIN_PT = 3;
    /** matchup 引き分け時のチーム勝ち点。 */
    private static final int MATCHUP_DRAW_PT = 1;
    /** 予選 matchup 数 (= C(5,2))。これらすべての結果が記録されたら決勝生成可能。 */
    private static final int PRELIM_MATCHUP_COUNT = 10;

    private final CompetitionRepository competitionRepository;
    private final CompetitionTeamRepository teamRepository;
    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionMatchupRepository matchupRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionPickRepository pickRepository;
    private final CompetitionStrategyUseRepository strategyUseRepository;
    private final UserRepository userRepository;
    private final OrganizerAuthService organizerAuthService;

    public CompetitionAdminController(CompetitionRepository competitionRepository,
                                      CompetitionTeamRepository teamRepository,
                                      CompetitionParticipantRepository participantRepository,
                                      CompetitionMatchupRepository matchupRepository,
                                      CompetitionMatchRepository matchRepository,
                                      CompetitionPickRepository pickRepository,
                                      CompetitionStrategyUseRepository strategyUseRepository,
                                      UserRepository userRepository,
                                      OrganizerAuthService organizerAuthService) {
        this.competitionRepository = competitionRepository;
        this.teamRepository = teamRepository;
        this.participantRepository = participantRepository;
        this.matchupRepository = matchupRepository;
        this.matchRepository = matchRepository;
        this.pickRepository = pickRepository;
        this.strategyUseRepository = strategyUseRepository;
        this.userRepository = userRepository;
        this.organizerAuthService = organizerAuthService;
    }

    // ── 大会本体 ──────────────────────────────────────────────

    /**
     * 【メソッドの役割】 新しい大会を作成する。同時に 5 チーム枠と各チームの TL トークンを発行する。
     *
     * チーム名のデフォルトは "チーム1" 〜 "チーム5"。主催側 UI で後からリネーム可能。
     *
     * @param req {@code name} (大会名) を含むリクエストボディ
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> createCompetition(
            Authentication auth, @RequestBody CreateCompetitionRequest req) {
        User organizer = requireOrganizer(auth);
        if (req == null || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "大会名を入力してください"));
        }

        Competition comp = new Competition();
        comp.setName(req.name().trim());
        comp.setStatus("draft");
        comp.setCreatedBy(organizer);
        comp = competitionRepository.save(comp);

        // 5 チーム枠を初期生成 (TL トークン込み)
        for (int i = 1; i <= TEAMS_PER_COMPETITION; i++) {
            CompetitionTeam team = new CompetitionTeam();
            team.setCompetition(comp);
            team.setTeamName("チーム" + i);
            team.setTeamOrder(i);
            team.setTlToken(newToken());
            teamRepository.save(team);
        }

        return ResponseEntity.ok(toCompetitionDetailMap(comp));
    }

    /**
     * 【メソッドの役割】 主催権限を持つユーザーから見える全大会を新しい順で返す。
     *
     * Phase 1 では「全主催が全大会を共同管理できる」運用に倒している。
     * 個別所有制が必要になったら createdBy フィルタを足す。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listCompetitions(Authentication auth) {
        requireOrganizer(auth);
        List<Competition> all = competitionRepository.findAll();
        all.sort(Comparator.comparing(Competition::getCreatedAt).reversed());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Competition c : all) result.add(toCompetitionSummaryMap(c));
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 大会 1 件の詳細 (チーム/参加者/matchup/試合まで) を返す。
     *
     * Phase 1 時点では matchups/matches は status=open 以降に存在する。
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCompetition(
            Authentication auth, @PathVariable Long id) {
        requireOrganizer(auth);
        Competition comp = competitionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Competition not found: " + id));
        return ResponseEntity.ok(toCompetitionDetailMap(comp));
    }

    // ── チーム ────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 チーム名をリネームする。draft の間のみ受け付ける。
     */
    @PutMapping("/{competitionId}/teams/{teamId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> renameTeam(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long teamId,
            @RequestBody RenameTeamRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "open 以降の大会はチーム名を変更できません"));
        }
        if (req == null || req.teamName() == null || req.teamName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "チーム名を入力してください"));
        }
        CompetitionTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
        if (!team.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "指定チームはこの大会に属していません"));
        }
        team.setTeamName(req.teamName().trim());
        teamRepository.save(team);
        return ResponseEntity.ok(toTeamMap(team));
    }

    // ── 参加者 ────────────────────────────────────────────────

    /**
     * 【メソッドの役割】 チームに参加者を追加する。招待トークンを自動採番。
     *
     * 制約:
     * <ul>
     *   <li>1 チーム最大 {@value #PARTICIPANTS_PER_TEAM} 名</li>
     *   <li>{@code isTl=true} 指定時、既存 TL があれば自動で false に降格 (TL は常に 1 名)</li>
     *   <li>draft の間のみ追加可</li>
     * </ul>
     */
    @PostMapping("/{competitionId}/teams/{teamId}/participants")
    @Transactional
    public ResponseEntity<Map<String, Object>> addParticipant(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long teamId,
            @RequestBody UpsertParticipantRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "open 以降の大会は参加者を追加できません"));
        }
        if (req == null || req.displayName() == null || req.displayName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "表示名を入力してください"));
        }

        CompetitionTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
        if (!team.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "指定チームはこの大会に属していません"));
        }

        List<CompetitionParticipant> existing = participantRepository.findByTeamOrderByCreatedAtAsc(team);
        if (existing.size() >= PARTICIPANTS_PER_TEAM) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "1 チームの参加者は " + PARTICIPANTS_PER_TEAM + " 名までです"));
        }

        boolean wantsTl = Boolean.TRUE.equals(req.isTl());
        if (wantsTl) {
            demoteAllTlsInTeam(existing);
        }

        CompetitionParticipant p = new CompetitionParticipant();
        p.setCompetition(comp);
        p.setTeam(team);
        p.setDisplayName(req.displayName().trim());
        p.setInviteToken(newToken());
        p.setIsTl(wantsTl);
        p = participantRepository.save(p);
        return ResponseEntity.ok(toParticipantMap(p));
    }

    /**
     * 【メソッドの役割】 参加者の表示名 / TL フラグを更新する。
     *
     * isTl=true への昇格時は同チームの既存 TL を自動降格。
     */
    @PutMapping("/{competitionId}/participants/{participantId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateParticipant(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long participantId,
            @RequestBody UpsertParticipantRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        CompetitionParticipant p = participantRepository.findById(participantId)
                .orElseThrow(() -> new RuntimeException("Participant not found: " + participantId));
        if (!p.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "指定参加者はこの大会に属していません"));
        }

        if (req.displayName() != null && !req.displayName().isBlank()) {
            p.setDisplayName(req.displayName().trim());
        }
        if (req.isTl() != null) {
            if (Boolean.TRUE.equals(req.isTl()) && !Boolean.TRUE.equals(p.getIsTl())) {
                // 昇格: 同チームの既存 TL を降格
                List<CompetitionParticipant> teammates =
                        participantRepository.findByTeamOrderByCreatedAtAsc(p.getTeam());
                demoteAllTlsInTeam(teammates);
            }
            p.setIsTl(req.isTl());
        }
        p = participantRepository.save(p);
        return ResponseEntity.ok(toParticipantMap(p));
    }

    /**
     * 【メソッドの役割】 参加者を削除する。draft の間のみ受け付ける。
     */
    @DeleteMapping("/{competitionId}/participants/{participantId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteParticipant(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long participantId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "open 以降の大会は参加者を削除できません"));
        }
        CompetitionParticipant p = participantRepository.findById(participantId)
                .orElseThrow(() -> new RuntimeException("Participant not found: " + participantId));
        if (!p.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "指定参加者はこの大会に属していません"));
        }
        participantRepository.delete(p);
        return ResponseEntity.ok(Map.of("message", "削除しました"));
    }

    // ── Reveal 用データ取得 (SongRevealView 連携) ─────────

    /**
     * 【メソッドの役割】 Song Reveal 画面が 1 回の GET で全試合分の演出用データを取得できるよう、
     * 大会内のすべての試合とその両サイドの自選曲 + StrategyCard 使用フラグをまとめて返す。
     *
     * <p>権限: 主催 (4 ID) のみ。SongRevealView は通常ログイン状態で開く想定なのでこれで足りる。
     *
     * <p>返却内容 (matches[] の各要素):
     * <pre>
     * {
     *   matchId, matchKind, requiredGenre,
     *   matchupOrder, teamAName, teamBName,
     *   playerAName, playerAPick: { songGenre, songLevel, songTitle, songDiff } | null,
     *   playerBName, playerBPick: { songGenre, songLevel, songTitle, songDiff } | null,
     *   playerAStrategyUsed, playerBStrategyUsed
     * }
     * </pre>
     * <p>未アサインの slot は playerXName が null。フロントは「両側 null」の試合は表示しない想定。
     */
    @GetMapping("/{competitionId}/reveal")
    public ResponseEntity<Map<String, Object>> getRevealData(
            Authentication auth, @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("competitionId", comp.getId());
        root.put("competitionName", comp.getName());

        List<CompetitionMatch> matches = matchRepository.findAllByCompetition(comp);
        // matchup の順、その中で vanguard → middle → captain
        matches.sort((a, b) -> {
            int byMu = Integer.compare(a.getMatchup().getMatchupOrder(), b.getMatchup().getMatchupOrder());
            if (byMu != 0) return byMu;
            return Integer.compare(matchKindRank(a.getMatchKind()), matchKindRank(b.getMatchKind()));
        });

        List<Map<String, Object>> entries = new ArrayList<>();
        for (CompetitionMatch m : matches) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("matchId", m.getId());
            e.put("matchKind", m.getMatchKind());
            e.put("requiredGenre", m.getRequiredGenre());
            CompetitionMatchup mu = m.getMatchup();
            e.put("matchupOrder", mu.getMatchupOrder());
            e.put("teamAName", mu.getTeamA() != null ? mu.getTeamA().getTeamName() : null);
            e.put("teamBName", mu.getTeamB() != null ? mu.getTeamB().getTeamName() : null);

            CompetitionParticipant pa = m.getPlayerA();
            CompetitionParticipant pb = m.getPlayerB();
            e.put("playerAName", pa != null ? pa.getDisplayName() : null);
            e.put("playerBName", pb != null ? pb.getDisplayName() : null);

            e.put("playerAPick", pa != null
                    ? pickRepository.findByMatchAndParticipant(m, pa).map(this::pickRevealMap).orElse(null)
                    : null);
            e.put("playerBPick", pb != null
                    ? pickRepository.findByMatchAndParticipant(m, pb).map(this::pickRevealMap).orElse(null)
                    : null);

            e.put("playerAStrategyUsed", pa != null
                    && strategyUseRepository.findByMatchAndUsedByParticipant(m, pa)
                            .map(CompetitionStrategyUse::getEnabled).orElse(false));
            e.put("playerBStrategyUsed", pb != null
                    && strategyUseRepository.findByMatchAndUsedByParticipant(m, pb)
                            .map(CompetitionStrategyUse::getEnabled).orElse(false));

            entries.add(e);
        }
        root.put("matches", entries);
        return ResponseEntity.ok(root);
    }

    /** matchKind の並び順 (vanguard → middle → captain)。 */
    private static int matchKindRank(String kind) {
        return switch (kind) {
            case "vanguard" -> 0;
            case "middle" -> 1;
            case "captain" -> 2;
            default -> 99;
        };
    }

    /** Reveal 用 pick 表現。SongReveal が songTitle + songDiff から SongDataEntry を逆引きする。 */
    private Map<String, Object> pickRevealMap(CompetitionPick p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("songGenre", p.getSongGenre());
        m.put("songLevel", p.getSongLevel());
        m.put("songStrategyId", p.getSongStrategyId());
        m.put("songTitle", p.getSongTitle());
        m.put("songDiff", p.getSongDiff());
        return m;
    }

    // ── 試合結果記録 / 集計 / 決勝生成 ─────────────────────

    /**
     * 【メソッドの役割】 試合 1 件の詳細スコア (2 曲分) を記録/更新する。
     *
     * <p>R-4 要件: 曲管理番号 + スコアを入れて、勝敗を自動で導く。
     * 1 戦 = 2 曲制を想定。リクエストには両曲ぶんの管理番号 / タイトル / A スコア / B スコアを含む。
     * 入力された値から {@code aSongsWon} / {@code bSongsWon} を計算してフィールドへ反映する。
     *
     * <p>scoreA == scoreB の場合はその曲は引分扱いとし、どちらの win count にも加算しない。
     */
    @PutMapping("/{competitionId}/matches/{matchId}/result")
    @Transactional
    public ResponseEntity<Map<String, Object>> setMatchResult(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId,
            @RequestBody SetMatchResultRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if (req == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "リクエストボディが必要です"));
        }
        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getMatchup().getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }

        // 各曲のスコアは正の整数。null は未入力扱いで、当該曲は勝敗判定からスキップ。
        for (Integer v : new Integer[] {
                req.song1ScoreA(), req.song1ScoreB(), req.song2ScoreA(), req.song2ScoreB() }) {
            if (v != null && v < 0) {
                return ResponseEntity.badRequest().body(Map.of("message", "スコアは 0 以上の整数で入力してください"));
            }
        }

        match.setSong1StrategyId(req.song1StrategyId());
        match.setSong1Title(req.song1Title());
        match.setSong1ScoreA(req.song1ScoreA());
        match.setSong1ScoreB(req.song1ScoreB());
        match.setSong2StrategyId(req.song2StrategyId());
        match.setSong2Title(req.song2Title());
        match.setSong2ScoreA(req.song2ScoreA());
        match.setSong2ScoreB(req.song2ScoreB());

        // スコアから勝ち曲数を導出。両スコアが揃っている曲だけ判定対象。
        int aWon = 0, bWon = 0;
        boolean anyEntered = false;
        if (req.song1ScoreA() != null && req.song1ScoreB() != null) {
            anyEntered = true;
            if (req.song1ScoreA() > req.song1ScoreB()) aWon++;
            else if (req.song1ScoreA() < req.song1ScoreB()) bWon++;
        }
        if (req.song2ScoreA() != null && req.song2ScoreB() != null) {
            anyEntered = true;
            if (req.song2ScoreA() > req.song2ScoreB()) aWon++;
            else if (req.song2ScoreA() < req.song2ScoreB()) bWon++;
        }
        if (anyEntered) {
            match.setASongsWon(aWon);
            match.setBSongsWon(bWon);
            match.setResultRecordedAt(java.time.LocalDateTime.now());
        } else {
            // 全曲のスコアが揃っていない場合は集計値もクリア
            match.setASongsWon(null);
            match.setBSongsWon(null);
            match.setResultRecordedAt(null);
        }
        matchRepository.save(match);
        return ResponseEntity.ok(toMatchMap(match));
    }

    /**
     * 【メソッドの役割】 結果を未記録に戻す (誤入力修正用)。
     */
    @DeleteMapping("/{competitionId}/matches/{matchId}/result")
    @Transactional
    public ResponseEntity<Map<String, Object>> clearMatchResult(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getMatchup().getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }
        match.setASongsWon(null);
        match.setBSongsWon(null);
        match.setResultRecordedAt(null);
        matchRepository.save(match);
        return ResponseEntity.ok(toMatchMap(match));
    }

    /**
     * 【メソッドの役割】 大会のチーム順位表を計算して返す。
     *
     * <p>計算方法 (予選 matchup のみ対象、決勝は除外):
     * <ul>
     *   <li>1 戦 (= 1 match) ごと: 勝った曲数 × 戦ポイント (先鋒2/中堅3/大将4) を勝った側のチームに加算</li>
     *   <li>1 matchup ごと: 3 戦の勝ち数 (戦の勝者カウント) を比較し、より多い側が matchup 勝利</li>
     *   <li>matchup 勝利 = +3pt / 引分 = +1pt / 敗北 = 0pt</li>
     *   <li>合計 = 戦ポイント合計 + matchup 勝ち点合計</li>
     * </ul>
     */
    @GetMapping("/{competitionId}/standings")
    public ResponseEntity<Map<String, Object>> getStandings(
            Authentication auth, @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);

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

        List<CompetitionMatchup> matchups = matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp);
        int prelimRecordedCount = 0;
        for (CompetitionMatchup mu : matchups) {
            if (Boolean.TRUE.equals(mu.getIsFinals())) continue; // 決勝は予選順位の集計対象外
            List<CompetitionMatch> matches = matchRepository.findByMatchupOrderByIdAsc(mu);
            // 戦単位の勝者カウント
            int aWonMatches = 0, bWonMatches = 0;
            boolean allRecorded = true;
            for (CompetitionMatch m : matches) {
                Integer aw = m.getASongsWon();
                Integer bw = m.getBSongsWon();
                if (aw == null || bw == null) { allRecorded = false; continue; }
                int kindPt = POINTS_PER_SONG_BY_KIND.getOrDefault(m.getMatchKind(), 0);
                songPts.merge(mu.getTeamA().getId(), aw * kindPt, Integer::sum);
                songPts.merge(mu.getTeamB().getId(), bw * kindPt, Integer::sum);
                if (aw > bw) aWonMatches++;
                else if (bw > aw) bWonMatches++;
                // 同点 (aw == bw) は戦引分扱い、どちらにも加算しない
            }
            if (!allRecorded) continue;
            prelimRecordedCount++;
            // matchup 勝者判定 (戦勝ち数で比較)
            Long aId = mu.getTeamA().getId();
            Long bId = mu.getTeamB().getId();
            if (aWonMatches > bWonMatches) {
                matchupPts.merge(aId, MATCHUP_WIN_PT, Integer::sum);
                wins.merge(aId, 1, Integer::sum);
                losses.merge(bId, 1, Integer::sum);
            } else if (bWonMatches > aWonMatches) {
                matchupPts.merge(bId, MATCHUP_WIN_PT, Integer::sum);
                wins.merge(bId, 1, Integer::sum);
                losses.merge(aId, 1, Integer::sum);
            } else {
                matchupPts.merge(aId, MATCHUP_DRAW_PT, Integer::sum);
                matchupPts.merge(bId, MATCHUP_DRAW_PT, Integer::sum);
                draws.merge(aId, 1, Integer::sum);
                draws.merge(bId, 1, Integer::sum);
            }
        }

        // 順位 = total 降順、tie-break は songPts 降順 → matchupPts 降順 → teamOrder 昇順
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
            rows.add(r);
        }
        rows.sort((x, y) -> {
            int byTotal = Integer.compare((Integer) y.get("totalPoints"), (Integer) x.get("totalPoints"));
            if (byTotal != 0) return byTotal;
            int bySong = Integer.compare((Integer) y.get("songPoints"), (Integer) x.get("songPoints"));
            if (bySong != 0) return bySong;
            int byMu = Integer.compare((Integer) y.get("matchupPoints"), (Integer) x.get("matchupPoints"));
            if (byMu != 0) return byMu;
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
        return ResponseEntity.ok(root);
    }

    /**
     * 【メソッドの役割】 予選 10 試合全結果記録後に、上位 2 チームで決勝 matchup を 1 件生成する。
     *
     * <p>決勝 matchup は {@code isFinals=true} としてマークされ、コスト計算・StrategyCard 2-of-4 制限の対象外。
     * 既に決勝が生成済みの場合は 400。
     */
    @PostMapping("/{competitionId}/generate-finals")
    @Transactional
    public ResponseEntity<Map<String, Object>> generateFinals(
            Authentication auth, @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);

        List<CompetitionMatchup> existing = matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp);
        boolean finalsAlready = existing.stream().anyMatch(mu -> Boolean.TRUE.equals(mu.getIsFinals()));
        if (finalsAlready) {
            return ResponseEntity.badRequest().body(Map.of("message", "決勝はすでに生成済みです"));
        }

        // standings を内部的に同ロジックで計算
        ResponseEntity<Map<String, Object>> standingsResp = getStandings(auth, competitionId);
        Map<String, Object> standings = standingsResp.getBody();
        if (standings == null
                || !Boolean.TRUE.equals(standings.get("allPrelimRecorded"))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "予選 " + PRELIM_MATCHUP_COUNT + " 試合の結果がすべて記録されていません"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) standings.get("rows");
        if (rows.size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("message", "チーム数が不足しています"));
        }
        Long firstTeamId = ((Number) rows.get(0).get("teamId")).longValue();
        Long secondTeamId = ((Number) rows.get(1).get("teamId")).longValue();
        CompetitionTeam first = teamRepository.findById(firstTeamId)
                .orElseThrow(() -> new RuntimeException("Top team not found"));
        CompetitionTeam second = teamRepository.findById(secondTeamId)
                .orElseThrow(() -> new RuntimeException("Second team not found"));

        // 既存 matchupOrder の最大値 + 1 を採番 (10+1=11 想定)
        int nextOrder = existing.stream().mapToInt(CompetitionMatchup::getMatchupOrder).max().orElse(0) + 1;

        CompetitionMatchup finalsMu = new CompetitionMatchup();
        finalsMu.setCompetition(comp);
        finalsMu.setTeamA(first);
        finalsMu.setTeamB(second);
        finalsMu.setMatchupOrder(nextOrder);
        finalsMu.setIsFinals(true);
        finalsMu = matchupRepository.save(finalsMu);

        for (String kind : MATCH_KINDS) {
            CompetitionMatch m = new CompetitionMatch();
            m.setMatchup(finalsMu);
            m.setMatchKind(kind);
            matchRepository.save(m);
        }

        return ResponseEntity.ok(Map.of(
                "message", "決勝を生成しました: " + first.getTeamName() + " vs " + second.getTeamName(),
                "matchupId", finalsMu.getId(),
                "teamAId", first.getId(),
                "teamBId", second.getId()
        ));
    }

    // ── 大会削除 (cascade) ────────────────────────────────

    /**
     * 【メソッドの役割】 大会 1 件を関連レコードごと完全削除する。
     *
     * <p>削除順序 (FK 依存の逆順):
     * <ol>
     *   <li>競合する子レコード: CompetitionStrategyUse</li>
     *   <li>CompetitionPick (match と participant の両方を参照)</li>
     *   <li>CompetitionMatch (matchup / 参加者を参照)</li>
     *   <li>CompetitionMatchup (competition / team を参照)</li>
     *   <li>CompetitionParticipant (competition / team を参照)</li>
     *   <li>CompetitionTeam (competition を参照)</li>
     *   <li>Competition 本体</li>
     * </ol>
     *
     * <p>1 大会あたりせいぜい数百レコードなので、効率面より分かりやすさを優先して
     * fetch + {@code deleteAll} 方式で実装する。トランザクション 1 つで全削除。
     */
    @DeleteMapping("/{competitionId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteCompetition(
            Authentication auth, @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);

        strategyUseRepository.deleteAll(strategyUseRepository.findAllByCompetition(comp));
        pickRepository.deleteAll(pickRepository.findAllByCompetition(comp));
        matchRepository.deleteAll(matchRepository.findAllByCompetition(comp));
        matchupRepository.deleteAll(matchupRepository.findByCompetitionOrderByMatchupOrderAsc(comp));
        participantRepository.deleteAll(participantRepository.findByCompetitionOrderByCreatedAtAsc(comp));
        teamRepository.deleteAll(teamRepository.findByCompetitionOrderByTeamOrderAsc(comp));
        competitionRepository.delete(comp);

        return ResponseEntity.ok(Map.of("message", "大会を削除しました"));
    }

    // ── トークン再発行 (誤公開時のリカバリ用) ───────────

    /**
     * 【メソッドの役割】 参加者の招待トークンを再採番する。
     *
     * <p>使い道: 招待 URL を誤って公開掲示板に貼ってしまった等の事故時に、旧 URL を即無効化する。
     * 既存提出済の自選曲・StrategyCard 決定は残るが、新トークンを当該参加者に再配布する必要がある。
     */
    @PostMapping("/{competitionId}/participants/{participantId}/regenerate-token")
    @Transactional
    public ResponseEntity<Map<String, Object>> regenerateParticipantToken(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long participantId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        CompetitionParticipant p = participantRepository.findById(participantId)
                .orElseThrow(() -> new RuntimeException("Participant not found: " + participantId));
        if (!p.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定参加者はこの大会に属していません"));
        }
        p.setInviteToken(newToken());
        participantRepository.save(p);
        return ResponseEntity.ok(toParticipantMap(p));
    }

    /**
     * 【メソッドの役割】 チームの TL トークンを再採番する。誤公開時のリカバリ用。
     */
    @PostMapping("/{competitionId}/teams/{teamId}/regenerate-tl-token")
    @Transactional
    public ResponseEntity<Map<String, Object>> regenerateTlToken(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long teamId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        CompetitionTeam team = teamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("Team not found: " + teamId));
        if (!team.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定チームはこの大会に属していません"));
        }
        team.setTlToken(newToken());
        teamRepository.save(team);
        return ResponseEntity.ok(toTeamMap(team));
    }

    // ── 状態遷移 ─────────────────────────────────────────────

    /**
     * 【メソッドの役割】 status を {@code draft → open} に遷移させる。
     *
     * 必須条件:
     * <ul>
     *   <li>5 チームそれぞれが {@value #PARTICIPANTS_PER_TEAM} 名揃っている</li>
     *   <li>各チームに TL がちょうど 1 名いる</li>
     * </ul>
     * 同トランザクション内で 10 matchup (C(5,2)) と 30 match (各 matchup × 3 戦) を自動生成する。
     */
    @PostMapping("/{competitionId}/open")
    @Transactional
    public ResponseEntity<Map<String, Object>> openCompetition(
            Authentication auth, @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "draft 状態の大会のみ open に遷移できます"));
        }

        List<CompetitionTeam> teams = teamRepository.findByCompetitionOrderByTeamOrderAsc(comp);
        if (teams.size() != TEAMS_PER_COMPETITION) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "チーム数が不正です (" + teams.size() + " / "
                            + TEAMS_PER_COMPETITION + ")"));
        }
        for (CompetitionTeam team : teams) {
            List<CompetitionParticipant> members = participantRepository.findByTeamOrderByCreatedAtAsc(team);
            if (members.size() != PARTICIPANTS_PER_TEAM) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", team.getTeamName() + " の参加者が "
                                + PARTICIPANTS_PER_TEAM + " 名に達していません (" + members.size() + " 名)"));
            }
            long tlCount = members.stream().filter(m -> Boolean.TRUE.equals(m.getIsTl())).count();
            if (tlCount != 1) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", team.getTeamName() + " の TL が "
                                + tlCount + " 名です (1 名必要)"));
            }
        }

        // matchups と matches を生成
        int order = 1;
        for (int i = 0; i < teams.size(); i++) {
            for (int j = i + 1; j < teams.size(); j++) {
                CompetitionMatchup matchup = new CompetitionMatchup();
                matchup.setCompetition(comp);
                matchup.setTeamA(teams.get(i));
                matchup.setTeamB(teams.get(j));
                matchup.setMatchupOrder(order++);
                matchup = matchupRepository.save(matchup);
                for (String kind : MATCH_KINDS) {
                    CompetitionMatch match = new CompetitionMatch();
                    match.setMatchup(matchup);
                    match.setMatchKind(kind);
                    matchRepository.save(match);
                }
            }
        }

        comp.setStatus("open");
        competitionRepository.save(comp);
        return ResponseEntity.ok(toCompetitionDetailMap(comp));
    }

    // ── 試合のジャンル指定 (対戦表) ─────────────────────────

    /**
     * 【メソッドの役割】 1 試合に対する {@code requiredGenre} を設定する。
     *
     * <p>プレイヤーはこのジャンルの曲しか提出できなくなる (CompetitionPlayerController で検証)。
     * {@code genre} に null を渡せばクリア。null の試合は「ジャンル指定待ち」としてプレイヤー側に
     * 自選曲 UI を表示しない方針。
     *
     * <p>INSANE は captain 戦のみ許容。それ以外は 7 種から自由に指定可能。
     */
    @PutMapping("/{competitionId}/matches/{matchId}/genre")
    @Transactional
    public ResponseEntity<Map<String, Object>> setMatchGenre(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId,
            @RequestBody SetMatchGenreRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getMatchup().getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では変更できません"));
        }

        String genre = req == null ? null : req.genre();
        if (genre != null) {
            if (!ALLOWED_GENRES.contains(genre)) {
                return ResponseEntity.badRequest().body(Map.of("message", "未知のジャンル: " + genre));
            }
            if ("INSANE".equals(genre) && !"captain".equals(match.getMatchKind())) {
                return ResponseEntity.badRequest()
                        .body(Map.of("message", "INSANE は大将戦 (captain) にのみ指定できます"));
            }
        }
        match.setRequiredGenre(genre);
        matchRepository.save(match);
        return ResponseEntity.ok(toMatchMap(match));
    }

    // ── 起用 (ラインアップ) の公開 ─────────────────────────

    /**
     * 【メソッドの役割】 matchup のラインアップ公開フラグを更新する。
     *
     * <p>{@code side} = "a" / "b" / "both" を指定して片側または両側を一度に
     * 公開/非公開に切り替える。
     * 公開された側のチーム起用名は相手チームの TL / プレイヤーから見えるようになる。
     */
    @PutMapping("/{competitionId}/matchups/{matchupId}/lineup-publish")
    @Transactional
    public ResponseEntity<Map<String, Object>> publishLineup(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchupId,
            @RequestBody PublishRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では変更できません"));
        }
        if (req == null || req.side() == null || req.published() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "side と published が必要です"));
        }
        if (!Set.of("a", "b", "both").contains(req.side())) {
            return ResponseEntity.badRequest().body(Map.of("message", "side は a / b / both のいずれか"));
        }

        CompetitionMatchup mu = matchupRepository.findById(matchupId)
                .orElseThrow(() -> new RuntimeException("Matchup not found: " + matchupId));
        if (!mu.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定 matchup はこの大会に属していません"));
        }

        if ("a".equals(req.side()) || "both".equals(req.side())) mu.setLineupPublishedA(req.published());
        if ("b".equals(req.side()) || "both".equals(req.side())) mu.setLineupPublishedB(req.published());
        matchupRepository.save(mu);
        return ResponseEntity.ok(toMatchupMap(mu));
    }

    // ── 自選曲のロック (編集禁止フラグ) ───────────────────

    /**
     * 【メソッドの役割】 match の {@code locked_a} / {@code locked_b} を切替する。
     *
     * <p>lock = プレイヤーの自選曲編集を禁止する。公開とは独立で、lock 済でも未公開状態を維持できる。
     * 通常フロー: 締切時刻に lock → 試合直前に publishPick で相手に開示。
     *
     * <p>{@code locked=true} のとき {@code lockedAt} 系も自動更新。false で戻す場合はタイムスタンプを null クリア。
     */
    @PutMapping("/{competitionId}/matches/{matchId}/lock")
    @Transactional
    public ResponseEntity<Map<String, Object>> setMatchLock(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId,
            @RequestBody LockRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では変更できません"));
        }
        if (req == null || req.side() == null || req.locked() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "side と locked が必要です"));
        }
        if (!Set.of("a", "b", "both").contains(req.side())) {
            return ResponseEntity.badRequest().body(Map.of("message", "side は a / b / both のいずれか"));
        }

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getMatchup().getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if ("a".equals(req.side()) || "both".equals(req.side())) {
            match.setLockedA(req.locked());
            match.setLockedAAt(req.locked() ? now : null);
        }
        if ("b".equals(req.side()) || "both".equals(req.side())) {
            match.setLockedB(req.locked());
            match.setLockedBAt(req.locked() ? now : null);
        }
        matchRepository.save(match);
        return ResponseEntity.ok(toMatchMap(match));
    }

    // ── 自選曲の公開 ─────────────────────────────────────────

    /**
     * 【メソッドの役割】 match の自選曲公開フラグを更新する。
     *
     * <p>{@code side} = "a" / "b" / "both" を指定。公開された側の自選曲は相手側プレイヤーから
     * 見えるようになる。lock とは独立で、lock 済みでも未公開状態を維持できる。
     */
    @PutMapping("/{competitionId}/matches/{matchId}/pick-publish")
    @Transactional
    public ResponseEntity<Map<String, Object>> publishPick(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId,
            @RequestBody PublishRequest req) {
        requireOrganizer(auth);
        Competition comp = requireCompetition(competitionId);
        if ("finished".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "終了済の大会では変更できません"));
        }
        if (req == null || req.side() == null || req.published() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "side と published が必要です"));
        }
        if (!Set.of("a", "b", "both").contains(req.side())) {
            return ResponseEntity.badRequest().body(Map.of("message", "side は a / b / both のいずれか"));
        }

        CompetitionMatch match = matchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getMatchup().getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }

        if ("a".equals(req.side()) || "both".equals(req.side())) match.setPickPublishedA(req.published());
        if ("b".equals(req.side()) || "both".equals(req.side())) match.setPickPublishedB(req.published());
        matchRepository.save(match);
        return ResponseEntity.ok(toMatchMap(match));
    }

    // ── ヘルパ ────────────────────────────────────────────────

    /**
     * 認証情報から主催ユーザーを解決し、4 ID ホワイトリスト判定する。
     * 該当しなければ {@link RuntimeException} を投げて 500 → SecurityConfig の
     * ExceptionHandler を通る (Phase 1 では 403 への正規化は省略)。
     */
    private User requireOrganizer(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        User user = userRepository.findByIidxId((String) auth.getPrincipal())
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!organizerAuthService.isOrganizer(user)) {
            throw new RuntimeException("Forbidden: not an organizer");
        }
        return user;
    }

    private Competition requireCompetition(Long id) {
        return competitionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Competition not found: " + id));
    }

    /** 招待トークン / TL トークン用に UUID をハイフン無しの 32 文字 hex で生成。 */
    private String newToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 同チーム内の既存 TL を全員 false に降格する (昇格処理の前に呼ぶ)。 */
    private void demoteAllTlsInTeam(List<CompetitionParticipant> teammates) {
        for (CompetitionParticipant tm : teammates) {
            if (Boolean.TRUE.equals(tm.getIsTl())) {
                tm.setIsTl(false);
                participantRepository.save(tm);
            }
        }
    }

    // ── レスポンス整形 ───────────────────────────────────────

    private Map<String, Object> toCompetitionSummaryMap(Competition c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("status", c.getStatus());
        m.put("deadlineAt", c.getDeadlineAt());
        m.put("createdAt", c.getCreatedAt());
        m.put("lockedAt", c.getLockedAt());
        m.put("createdById", c.getCreatedBy() != null ? c.getCreatedBy().getId() : null);
        return m;
    }

    private Map<String, Object> toCompetitionDetailMap(Competition c) {
        Map<String, Object> m = toCompetitionSummaryMap(c);

        List<CompetitionTeam> teams = teamRepository.findByCompetitionOrderByTeamOrderAsc(c);
        List<Map<String, Object>> teamMaps = new ArrayList<>();
        for (CompetitionTeam t : teams) teamMaps.add(toTeamMap(t));
        m.put("teams", teamMaps);

        List<CompetitionParticipant> participants =
                participantRepository.findByCompetitionOrderByCreatedAtAsc(c);
        List<Map<String, Object>> participantMaps = new ArrayList<>();
        for (CompetitionParticipant p : participants) participantMaps.add(toParticipantMap(p));
        m.put("participants", participantMaps);

        if (!"draft".equals(c.getStatus())) {
            List<CompetitionMatchup> matchups = matchupRepository.findByCompetitionOrderByMatchupOrderAsc(c);
            List<Map<String, Object>> matchupMaps = new ArrayList<>();
            for (CompetitionMatchup mu : matchups) matchupMaps.add(toMatchupMap(mu));
            m.put("matchups", matchupMaps);

            List<CompetitionMatch> matches = matchRepository.findAllByCompetition(c);
            List<Map<String, Object>> matchMaps = new ArrayList<>();
            for (CompetitionMatch match : matches) matchMaps.add(toMatchMap(match));
            m.put("matches", matchMaps);
        }

        return m;
    }

    private Map<String, Object> toTeamMap(CompetitionTeam t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("teamName", t.getTeamName());
        m.put("teamOrder", t.getTeamOrder());
        m.put("tlToken", t.getTlToken());
        return m;
    }

    private Map<String, Object> toParticipantMap(CompetitionParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("teamId", p.getTeam() != null ? p.getTeam().getId() : null);
        m.put("displayName", p.getDisplayName());
        m.put("inviteToken", p.getInviteToken());
        m.put("isTl", p.getIsTl());
        m.put("createdAt", p.getCreatedAt());
        return m;
    }

    private Map<String, Object> toMatchupMap(CompetitionMatchup mu) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mu.getId());
        m.put("matchupOrder", mu.getMatchupOrder());
        m.put("teamAId", mu.getTeamA() != null ? mu.getTeamA().getId() : null);
        m.put("teamBId", mu.getTeamB() != null ? mu.getTeamB().getId() : null);
        m.put("lineupPublishedA", mu.getLineupPublishedA());
        m.put("lineupPublishedB", mu.getLineupPublishedB());
        m.put("isFinals", mu.getIsFinals());
        return m;
    }

    private Map<String, Object> toMatchMap(CompetitionMatch match) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", match.getId());
        m.put("matchupId", match.getMatchup() != null ? match.getMatchup().getId() : null);
        m.put("matchKind", match.getMatchKind());
        m.put("requiredGenre", match.getRequiredGenre());
        m.put("playerAId", match.getPlayerA() != null ? match.getPlayerA().getId() : null);
        m.put("playerBId", match.getPlayerB() != null ? match.getPlayerB().getId() : null);
        m.put("lockedA", match.getLockedA());
        m.put("lockedB", match.getLockedB());
        m.put("lockedAAt", match.getLockedAAt());
        m.put("lockedBAt", match.getLockedBAt());
        m.put("pickPublishedA", match.getPickPublishedA());
        m.put("pickPublishedB", match.getPickPublishedB());
        m.put("aSongsWon", match.getASongsWon());
        m.put("bSongsWon", match.getBSongsWon());
        m.put("resultRecordedAt", match.getResultRecordedAt());
        // R-4: 詳細スコア (各曲)
        m.put("song1StrategyId", match.getSong1StrategyId());
        m.put("song1Title", match.getSong1Title());
        m.put("song1ScoreA", match.getSong1ScoreA());
        m.put("song1ScoreB", match.getSong1ScoreB());
        m.put("song2StrategyId", match.getSong2StrategyId());
        m.put("song2Title", match.getSong2Title());
        m.put("song2ScoreA", match.getSong2ScoreA());
        m.put("song2ScoreB", match.getSong2ScoreB());
        return m;
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 大会作成リクエスト。 */
    public record CreateCompetitionRequest(String name) {}

    /** チームリネームリクエスト。 */
    public record RenameTeamRequest(String teamName) {}

    /** 参加者追加・更新の共通リクエスト。{@code isTl} は更新時のみ null 許容 (= 据え置き)。 */
    public record UpsertParticipantRequest(String displayName, Boolean isTl) {}

    /**
     * 試合のジャンル指定リクエスト。{@code genre} が null の場合は指定解除。
     */
    public record SetMatchGenreRequest(String genre) {}

    /**
     * 起用 or 自選曲の公開状態切替リクエスト。
     * {@code side} = "a" / "b" / "both"。{@code published} = true で公開、false で非公開に戻す。
     */
    public record PublishRequest(String side, Boolean published) {}

    /**
     * 自選曲ロック状態切替リクエスト。
     * {@code side} = "a" / "b" / "both"。{@code locked} = true でロック (編集禁止)、false で解除。
     */
    public record LockRequest(String side, Boolean locked) {}

    /**
     * 試合結果記録リクエスト (R-4: スコア入力で勝敗自動判定)。
     *
     * <p>1 戦 = 2 曲制を想定。両曲ぶんの管理番号 / タイトル / 両サイドのスコアを受け取り、
     * サーバ側でスコア比較により {@code aSongsWon} / {@code bSongsWon} を導出。
     * 未入力 (null) のフィールドはスキップ扱い (= 部分記録)。
     */
    public record SetMatchResultRequest(
            Integer song1StrategyId,
            String song1Title,
            Integer song1ScoreA,
            Integer song1ScoreB,
            Integer song2StrategyId,
            String song2Title,
            Integer song2ScoreA,
            Integer song2ScoreB
    ) {}
}
