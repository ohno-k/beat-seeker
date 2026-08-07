package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import com.beatseeker.backend.service.CompetitionMatchKinds;
import com.beatseeker.backend.service.CompetitionTeamStandingsService;
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

    /**
     * StrategyCard を使える「予選 matchup 数」上限。
     * 各チームは予選 4 matchup のうち、card.enabled = true の matchup を最大この数まで持てる。
     * 決勝 matchup ({@code isFinals = true}) はカウント対象外。
     */
    private static final int STRATEGY_MATCHUP_LIMIT_PER_TEAM =
            CompetitionTeamStandingsService.STRATEGY_MATCHUP_LIMIT_PER_TEAM;

    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionPickRepository pickRepository;
    private final CompetitionMatchRepository matchRepository;
    private final CompetitionStrategyUseRepository strategyUseRepository;
    private final CompetitionIndividualMatchRepository individualMatchRepository;
    private final CompetitionIndividualMatchSlotRepository individualMatchSlotRepository;

    public CompetitionPlayerController(CompetitionParticipantRepository participantRepository,
                                       CompetitionPickRepository pickRepository,
                                       CompetitionMatchRepository matchRepository,
                                       CompetitionStrategyUseRepository strategyUseRepository,
                                       CompetitionIndividualMatchRepository individualMatchRepository,
                                       CompetitionIndividualMatchSlotRepository individualMatchSlotRepository) {
        this.participantRepository = participantRepository;
        this.pickRepository = pickRepository;
        this.matchRepository = matchRepository;
        this.strategyUseRepository = strategyUseRepository;
        this.individualMatchRepository = individualMatchRepository;
        this.individualMatchSlotRepository = individualMatchSlotRepository;
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

        // 個人戦は構造が大きく異なるため (チーム無し / 4 人試合 / StrategyCard 無し) 別レスポンスを返す
        if ("individual4".equals(comp.getFormat())) {
            return ResponseEntity.ok(buildIndividualPlayerView(me, comp));
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("participant", participantSelfMap(me));
        root.put("team", teamMap(myTeam));
        root.put("competition", competitionMap(comp));

        // 自チームが現時点で StrategyCard を使用している予選 matchup ID 集合を集計し、残り使用可能数を expose
        Set<Long> teamUsedPrelimMatchupIds = new HashSet<>();
        for (CompetitionStrategyUse su : strategyUseRepository.findAllByCompetition(comp)) {
            if (!Boolean.TRUE.equals(su.getEnabled())) continue;
            CompetitionParticipant user = su.getUsedByParticipant();
            if (user == null || !myTeam.getId().equals(user.getTeam().getId())) continue;
            CompetitionMatchup mu = su.getMatch().getMatchup();
            if (Boolean.TRUE.equals(mu.getIsFinals())) continue;
            teamUsedPrelimMatchupIds.add(mu.getId());
        }
        Map<String, Object> strategyQuota = new LinkedHashMap<>();
        strategyQuota.put("limit", STRATEGY_MATCHUP_LIMIT_PER_TEAM);
        strategyQuota.put("usedMatchupCount", teamUsedPrelimMatchupIds.size());
        strategyQuota.put("usedMatchupIds", new ArrayList<>(teamUsedPrelimMatchupIds));
        root.put("strategyQuota", strategyQuota);

        // 担当試合: 大会全試合をスキャンして自分が player_a / player_b の物を抽出
        List<CompetitionMatch> allMatches = matchRepository.findAllByCompetition(comp);
        List<Map<String, Object>> matchMaps = new ArrayList<>();
        for (CompetitionMatch m : allMatches) {
            // 運営が未設定の matchup に属する試合はプレイヤーに表示しない (設定済みになって初めて公開)
            if (!Boolean.TRUE.equals(m.getMatchup().getConfigured())) continue;
            CompetitionParticipant pa = m.getPlayerA();
            CompetitionParticipant pb = m.getPlayerB();
            boolean iAmA = pa != null && pa.getId().equals(me.getId());
            boolean iAmB = pb != null && pb.getId().equals(me.getId());
            if (!iAmA && !iAmB) continue;

            CompetitionParticipant opponent = iAmA ? pb : pa;
            // 自選曲は起用クローズ日時の対象外 (起用編集のみロック)。プレイヤー側の自選曲ロックは常に false。
            boolean opponentLocked = false;
            boolean myLocked = false;
            // 公開フラグ: 相手側の起用と自選曲がそれぞれ「自分から見える」状態か
            // 起用公開は起用公開日時 (lineupPublishAt) 到達で自動公開。相手起用が見えるかは isLineupPublished() で判定 (起用クローズとは独立)。
            boolean opponentLineupPublished = comp.isLineupPublished();
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

        // 自選曲は起用クローズ日時の対象外。締切後も終了するまで提出/変更できる (起用編集のみがクローズ対象)。

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
        // 自選曲は起用クローズ日時の対象外。締切後も終了するまで取消できる (起用編集のみがクローズ対象)。

        pickRepository.findByMatchAndParticipant(match, me).ifPresent(pickRepository::delete);
        return ResponseEntity.ok(Map.of("message", "削除しました"));
    }

    // StrategyCard 発動の決定は TL に移管 (CompetitionTlController#setStrategy)。
    // 選手 (メンバー URL) からは発動できない (getView では myStrategyUse を閲覧専用で返すのみ)。
    // 相手曲のランダム化抽選は Reveal データ生成時 (CompetitionAdminController#getRevealData) に確定する。

    // ── 内部ヘルパ ───────────────────────────────────────────

    /**
     * matchKind ごとの Lv 制約。
     * 予選: 先鋒 8-10 / 中堅 11 / 大将 12。決勝: 先鋒 8-10 / 次鋒 10 / 五将・中堅 11 / 三将・副将・大将 12。
     */
    private boolean isLevelInRange(int level, String matchKind) {
        return CompetitionMatchKinds.isLevelInRange(level, matchKind);
    }

    // ── 個人戦プレイヤー Read-only ビュー ──────────────────

    /**
     * 個人戦 ({@code format = "individual4"}) 参加者向けビュー。
     *
     * <p>個人戦には StrategyCard / 自選曲提出フェーズが無いので、本人スケジュールと
     * 試合結果 (順位 / ポイント) のリードオンリー表示のみ提供する。
     */
    private Map<String, Object> buildIndividualPlayerView(CompetitionParticipant me, Competition comp) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> participant = new LinkedHashMap<>();
        participant.put("id", me.getId());
        participant.put("displayName", me.getDisplayName());
        participant.put("teamId", null);
        participant.put("isTl", false);
        root.put("participant", participant);
        Map<String, Object> compMap = new LinkedHashMap<>();
        compMap.put("id", comp.getId());
        compMap.put("name", comp.getName());
        compMap.put("format", comp.getFormat());
        compMap.put("status", comp.getStatus());
        compMap.put("deadlineAt", comp.getDeadlineAt());
        compMap.put("lockedAt", comp.getLockedAt());
        root.put("competition", compMap);

        // 担当試合: 自分が参加するスロット → 試合 ID で逆引きしてユニーク化
        List<CompetitionIndividualMatchSlot> mySlots = individualMatchSlotRepository.findByParticipant(me);
        Set<Long> myMatchIds = new HashSet<>();
        for (CompetitionIndividualMatchSlot s : mySlots) myMatchIds.add(s.getMatch().getId());

        List<CompetitionIndividualMatch> allMatches =
                individualMatchRepository.findByCompetitionOrderByMatchOrderAsc(comp);
        List<Map<String, Object>> matchMaps = new ArrayList<>();
        for (CompetitionIndividualMatch m : allMatches) {
            if (!myMatchIds.contains(m.getId())) continue;
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("matchId", m.getId());
            mm.put("matchOrder", m.getMatchOrder());
            mm.put("isFinals", m.getIsFinals());
            mm.put("finalsBucket", m.getFinalsBucket());
            mm.put("resultRecordedAt", m.getResultRecordedAt());
            mm.put("song1StrategyId", m.getSong1StrategyId());
            mm.put("song1Title", m.getSong1Title());
            mm.put("song2StrategyId", m.getSong2StrategyId());
            mm.put("song2Title", m.getSong2Title());
            mm.put("song3StrategyId", m.getSong3StrategyId());
            mm.put("song3Title", m.getSong3Title());
            mm.put("song4StrategyId", m.getSong4StrategyId());
            mm.put("song4Title", m.getSong4Title());
            List<CompetitionIndividualMatchSlot> slots =
                    individualMatchSlotRepository.findByMatchOrderBySlotPositionAsc(m);
            List<Map<String, Object>> slotMaps = new ArrayList<>();
            for (CompetitionIndividualMatchSlot s : slots) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("slotPosition", s.getSlotPosition());
                sm.put("participantId", s.getParticipant() != null ? s.getParticipant().getId() : null);
                sm.put("participantName", s.getParticipant() != null ? s.getParticipant().getDisplayName() : null);
                sm.put("isMe", s.getParticipant() != null && s.getParticipant().getId().equals(me.getId()));
                sm.put("score1", s.getScore1());
                sm.put("score2", s.getScore2());
                sm.put("score3", s.getScore3());
                sm.put("score4", s.getScore4());
                sm.put("rank1", s.getRank1());
                sm.put("rank2", s.getRank2());
                sm.put("rank3", s.getRank3());
                sm.put("rank4", s.getRank4());
                sm.put("points1", s.getPoints1());
                sm.put("points2", s.getPoints2());
                sm.put("points3", s.getPoints3());
                sm.put("points4", s.getPoints4());
                sm.put("totalPoints", s.getTotalPoints());
                slotMaps.add(sm);
            }
            mm.put("slots", slotMaps);
            matchMaps.add(mm);
        }
        root.put("matches", matchMaps);
        return root;
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
        m.put("format", c.getFormat());
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
        // R-4 連携: enabled=true 時にサーバが抽選した曲 (相手から見えるとは限らず、AdminView の
        // スコア入力 / SongRevealView スピン着地で使う)。
        m.put("resultSongStrategyId", su.getResultSongStrategyId());
        m.put("resultSongTitle", su.getResultSongTitle());
        m.put("resultSongVersion", su.getResultSongVersion());
        m.put("resultSongDiff", su.getResultSongDiff());
        m.put("resultSongLevel", su.getResultSongLevel());
        m.put("resultSongGenre", su.getResultSongGenre());
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
}
