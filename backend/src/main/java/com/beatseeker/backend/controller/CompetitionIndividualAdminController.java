package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.*;
import com.beatseeker.backend.repository.*;
import com.beatseeker.backend.service.IndividualScheduleService;
import com.beatseeker.backend.service.OrganizerAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 【クラスの役割】 個人戦フォーマット ({@code Competition.format = "individual4"}) の主催向け API。
 *
 * <p>{@link CompetitionAdminController} と同じ {@code /api/competitions/{id}/...} 配下に存在し、
 * 個人戦専用のパスは {@code /api/competitions/{id}/individual/...} で名前空間を分けている。
 *
 * <p>提供エンドポイント:
 * <ul>
 *   <li>{@code POST /individual/participants}: 個人戦の参加者を追加 (招待トークン発行)</li>
 *   <li>{@code PUT /individual/participants/{participantId}}: 表示名を更新</li>
 *   <li>{@code DELETE /individual/participants/{participantId}}: 参加者を削除</li>
 *   <li>{@code POST /individual/participants/{participantId}/regenerate-token}: 招待 URL 再発行</li>
 *   <li>{@code POST /individual/open}: draft → open 遷移時に予選試合表を自動生成</li>
 *   <li>{@code PUT /individual/matches/{matchId}/result}: 1 試合 4 スロットの曲・スコア記録</li>
 *   <li>{@code DELETE /individual/matches/{matchId}/result}: 結果を未記録に戻す</li>
 *   <li>{@code GET /individual/standings}: 順位表 (予選 / 決勝の現状ポイント)</li>
 *   <li>{@code POST /individual/generate-finals}: 予選全試合記録後、上位 4 人ずつのバケットで決勝試合を生成</li>
 * </ul>
 *
 * <p>権限: すべて主催 (4 ID) のみ。Controller 冒頭で {@link OrganizerAuthService} を通す。
 */
@RestController
@RequestMapping("/api/competitions/{competitionId}/individual")
public class CompetitionIndividualAdminController {

    /** 個人戦が受理する参加人数 (12 or 16)。 */
    private static final Set<Integer> ALLOWED_PARTICIPANT_COUNTS = Set.of(12, 16);

    private final CompetitionRepository competitionRepository;
    private final CompetitionParticipantRepository participantRepository;
    private final CompetitionIndividualMatchRepository individualMatchRepository;
    private final CompetitionIndividualMatchSlotRepository individualMatchSlotRepository;
    private final UserRepository userRepository;
    private final OrganizerAuthService organizerAuthService;
    private final IndividualScheduleService scheduleService;
    private final CompetitionAdminController competitionAdminController;

    public CompetitionIndividualAdminController(
            CompetitionRepository competitionRepository,
            CompetitionParticipantRepository participantRepository,
            CompetitionIndividualMatchRepository individualMatchRepository,
            CompetitionIndividualMatchSlotRepository individualMatchSlotRepository,
            UserRepository userRepository,
            OrganizerAuthService organizerAuthService,
            IndividualScheduleService scheduleService,
            CompetitionAdminController competitionAdminController) {
        this.competitionRepository = competitionRepository;
        this.participantRepository = participantRepository;
        this.individualMatchRepository = individualMatchRepository;
        this.individualMatchSlotRepository = individualMatchSlotRepository;
        this.userRepository = userRepository;
        this.organizerAuthService = organizerAuthService;
        this.scheduleService = scheduleService;
        this.competitionAdminController = competitionAdminController;
    }

    // ── 参加者管理 ──────────────────────────────────────────

    /**
     * 【メソッドの役割】 個人戦に参加者を 1 名追加する。
     *
     * 制約: 大会フォーマットが individual4、status が draft、現在の参加者数が 16 名未満。
     */
    @PostMapping("/participants")
    @Transactional
    public ResponseEntity<Map<String, Object>> addParticipant(
            Authentication auth,
            @PathVariable Long competitionId,
            @RequestBody AddIndividualParticipantRequest req) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "draft 状態の大会のみ参加者を追加できます"));
        }
        if (req == null || req.displayName() == null || req.displayName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "表示名を入力してください"));
        }
        List<CompetitionParticipant> existing =
                participantRepository.findByCompetitionOrderByCreatedAtAsc(comp);
        if (existing.size() >= 16) {
            return ResponseEntity.badRequest().body(Map.of("message", "個人戦の参加者は 16 名までです"));
        }

        CompetitionParticipant p = new CompetitionParticipant();
        p.setCompetition(comp);
        p.setTeam(null); // 個人戦ではチーム無し
        p.setDisplayName(req.displayName().trim());
        p.setInviteToken(UUID.randomUUID().toString().replace("-", ""));
        p.setIsTl(false);
        p = participantRepository.save(p);
        return ResponseEntity.ok(toParticipantMap(p));
    }

    /**
     * 【メソッドの役割】 個人戦参加者の表示名を更新する。
     */
    @PutMapping("/participants/{participantId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> updateParticipant(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long participantId,
            @RequestBody UpdateIndividualParticipantRequest req) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        CompetitionParticipant p = requireParticipantOf(participantId, comp);
        if (req == null || req.displayName() == null || req.displayName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "表示名を入力してください"));
        }
        p.setDisplayName(req.displayName().trim());
        participantRepository.save(p);
        return ResponseEntity.ok(toParticipantMap(p));
    }

    /**
     * 【メソッドの役割】 参加者を削除する。draft 状態のみ。
     */
    @DeleteMapping("/participants/{participantId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteParticipant(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long participantId) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "open 以降は参加者を削除できません"));
        }
        CompetitionParticipant p = requireParticipantOf(participantId, comp);
        participantRepository.delete(p);
        return ResponseEntity.ok(Map.of("message", "削除しました"));
    }

    /**
     * 【メソッドの役割】 参加者の招待トークンを再採番する (誤公開時のリカバリ)。
     */
    @PostMapping("/participants/{participantId}/regenerate-token")
    @Transactional
    public ResponseEntity<Map<String, Object>> regenerateToken(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long participantId) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        CompetitionParticipant p = requireParticipantOf(participantId, comp);
        p.setInviteToken(UUID.randomUUID().toString().replace("-", ""));
        participantRepository.save(p);
        return ResponseEntity.ok(toParticipantMap(p));
    }

    // ── 開幕処理 (予選試合表自動生成) ──────────────────────

    /**
     * 【メソッドの役割】 draft → open 遷移時に、参加者から予選試合表を自動生成する。
     *
     * 必須条件: 大会フォーマットが individual4、status が draft、参加者数が 12 or 16。
     * 生成内容: 予選 18 試合 (12 名) or 20 試合 (16 名)、各試合 4 スロット。
     */
    @PostMapping("/open")
    @Transactional
    public ResponseEntity<Map<String, Object>> openCompetition(
            Authentication auth,
            @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        if (!"draft".equals(comp.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("message", "draft 状態の大会のみ open に遷移できます"));
        }
        List<CompetitionParticipant> participants =
                participantRepository.findByCompetitionOrderByCreatedAtAsc(comp);
        if (!ALLOWED_PARTICIPANT_COUNTS.contains(participants.size())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "参加者数は 12 名または 16 名 (現在 " + participants.size() + " 名)"));
        }

        List<Long> participantIds = new ArrayList<>();
        for (CompetitionParticipant p : participants) participantIds.add(p.getId());

        Map<Long, CompetitionParticipant> byId = new HashMap<>();
        for (CompetitionParticipant p : participants) byId.put(p.getId(), p);

        List<long[]> schedule = scheduleService.generatePrelimSchedule(participantIds);
        int order = 1;
        for (long[] slotPlayers : schedule) {
            CompetitionIndividualMatch m = new CompetitionIndividualMatch();
            m.setCompetition(comp);
            m.setMatchOrder(order++);
            m.setIsFinals(false);
            m = individualMatchRepository.save(m);
            for (int i = 0; i < slotPlayers.length; i++) {
                CompetitionIndividualMatchSlot s = new CompetitionIndividualMatchSlot();
                s.setMatch(m);
                s.setSlotPosition(i + 1);
                s.setParticipant(byId.get(slotPlayers[i]));
                individualMatchSlotRepository.save(s);
            }
        }
        comp.setStatus("open");
        competitionRepository.save(comp);
        return ResponseEntity.ok(competitionAdminController.toCompetitionDetailMap(comp));
    }

    // ── 試合結果記録 ────────────────────────────────────────

    /**
     * 【メソッドの役割】 1 試合分の 4 曲 + 4 プレイヤースロット × 各曲順位 (= 16 セル) を保存し、
     * 各スロットのポイントと総ポイントを派生する。
     *
     * <p>IIDX ARENA モードと同じ集計。順位は運営がクリック UI で直接指定する (スコア入力は廃止)。
     * 同じ順位を複数プレイヤーに付与することも可能 (タイ扱い)。順位値は 1〜4 のみ受理。
     *
     * <p>ポイント: rank=1 → 2pt / rank=2 → 1pt / rank=3 または 4 → 0pt。試合全体ポイント = 4 曲合計。
     *
     * <p>結果記録の条件: 4 スロット × 4 曲 = 16 セルすべてに順位が入っていること。
     * 1 つでも欠ければ partial 扱いで順位 / ポイントはクリアし、resultRecordedAt も null に戻す。
     */
    @PutMapping("/matches/{matchId}/result")
    @Transactional
    public ResponseEntity<Map<String, Object>> setMatchResult(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId,
            @RequestBody SetIndividualResultRequest req) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        if (req == null || req.slots() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "slots[] を含む payload が必要です"));
        }
        CompetitionIndividualMatch match = individualMatchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }

        // 4 曲のメタを反映 (タイトル / strategy_id)
        match.setSong1StrategyId(req.song1StrategyId());
        match.setSong1Title(emptyToNull(req.song1Title()));
        match.setSong2StrategyId(req.song2StrategyId());
        match.setSong2Title(emptyToNull(req.song2Title()));
        match.setSong3StrategyId(req.song3StrategyId());
        match.setSong3Title(emptyToNull(req.song3Title()));
        match.setSong4StrategyId(req.song4StrategyId());
        match.setSong4Title(emptyToNull(req.song4Title()));

        List<CompetitionIndividualMatchSlot> slots =
                individualMatchSlotRepository.findByMatchOrderBySlotPositionAsc(match);
        Map<Integer, CompetitionIndividualMatchSlot> bySlot = new HashMap<>();
        for (CompetitionIndividualMatchSlot s : slots) bySlot.put(s.getSlotPosition(), s);

        // 入力スロット (= 各プレイヤーの 4 曲ぶんの順位 1〜4) を適用
        for (SetIndividualResultSlot in : req.slots()) {
            if (in == null || in.slotPosition() == null) continue;
            CompetitionIndividualMatchSlot s = bySlot.get(in.slotPosition());
            if (s == null) {
                return ResponseEntity.badRequest().body(Map.of(
                        "message", "不正なスロット位置: " + in.slotPosition()));
            }
            for (Integer v : new Integer[] { in.rank1(), in.rank2(), in.rank3(), in.rank4() }) {
                if (v != null && (v < 1 || v > 4)) {
                    return ResponseEntity.badRequest().body(Map.of("message", "順位は 1〜4 の範囲で指定してください"));
                }
            }
            s.setRank1(in.rank1());
            s.setRank2(in.rank2());
            s.setRank3(in.rank3());
            s.setRank4(in.rank4());
            // 順位からポイントを派生 (rank=1→2pt / rank=2→1pt / それ以外→0pt)
            s.setPoints1(in.rank1() == null ? null : rankToPoints(in.rank1()));
            s.setPoints2(in.rank2() == null ? null : rankToPoints(in.rank2()));
            s.setPoints3(in.rank3() == null ? null : rankToPoints(in.rank3()));
            s.setPoints4(in.rank4() == null ? null : rankToPoints(in.rank4()));
        }

        // 全 4 スロット × 4 曲 = 16 セルすべてに順位が揃っているか判定
        boolean allRanked = slots.size() == 4
                && slots.stream().allMatch(s ->
                        s.getRank1() != null && s.getRank2() != null
                        && s.getRank3() != null && s.getRank4() != null);

        if (allRanked) {
            // totalPoints = 4 曲ぶんの points 合計
            for (CompetitionIndividualMatchSlot s : slots) {
                int total = zero(s.getPoints1()) + zero(s.getPoints2())
                        + zero(s.getPoints3()) + zero(s.getPoints4());
                s.setTotalPoints(total);
            }
            match.setResultRecordedAt(LocalDateTime.now());
        } else {
            // 部分入力: totalPoints はクリア (個別の順位 / ポイントは入力された値をそのまま保持)
            for (CompetitionIndividualMatchSlot s : slots) {
                s.setTotalPoints(null);
            }
            match.setResultRecordedAt(null);
        }

        for (CompetitionIndividualMatchSlot s : slots) individualMatchSlotRepository.save(s);
        individualMatchRepository.save(match);
        return ResponseEntity.ok(toIndividualMatchMap(match));
    }

    /**
     * 【メソッドの役割】 試合結果を未記録に戻す (誤入力修正用)。曲メタも全クリアする。
     */
    @DeleteMapping("/matches/{matchId}/result")
    @Transactional
    public ResponseEntity<Map<String, Object>> clearMatchResult(
            Authentication auth,
            @PathVariable Long competitionId,
            @PathVariable Long matchId) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        CompetitionIndividualMatch match = individualMatchRepository.findById(matchId)
                .orElseThrow(() -> new RuntimeException("Match not found: " + matchId));
        if (!match.getCompetition().getId().equals(comp.getId())) {
            return ResponseEntity.badRequest().body(Map.of("message", "指定試合はこの大会に属していません"));
        }
        match.setSong1StrategyId(null); match.setSong1Title(null);
        match.setSong2StrategyId(null); match.setSong2Title(null);
        match.setSong3StrategyId(null); match.setSong3Title(null);
        match.setSong4StrategyId(null); match.setSong4Title(null);
        List<CompetitionIndividualMatchSlot> slots =
                individualMatchSlotRepository.findByMatchOrderBySlotPositionAsc(match);
        for (CompetitionIndividualMatchSlot s : slots) {
            s.setScore1(null); s.setScore2(null); s.setScore3(null); s.setScore4(null);
            s.setRank1(null); s.setRank2(null); s.setRank3(null); s.setRank4(null);
            s.setPoints1(null); s.setPoints2(null); s.setPoints3(null); s.setPoints4(null);
            s.setTotalPoints(null);
            individualMatchSlotRepository.save(s);
        }
        match.setResultRecordedAt(null);
        individualMatchRepository.save(match);
        return ResponseEntity.ok(toIndividualMatchMap(match));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ── 順位表 ──────────────────────────────────────────────

    /**
     * 【メソッドの役割】 現在の順位表を返す。
     *
     * <p>予選: rows[] は予選試合 (isFinals=false) の集計のみで構成。
     * <p>tie-break: ポイント降順 → 1 位獲得回数降順 → 4 位獲得回数昇順 → 参加者作成日時昇順。
     * <p>決勝後: finalRank は決勝結果に基づいて算出 (bucket × 4 - 4 + 決勝内順位)。
     */
    @GetMapping("/standings")
    public ResponseEntity<Map<String, Object>> getStandings(
            Authentication auth,
            @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        return ResponseEntity.ok(computeStandings(comp));
    }

    /** 内部用: 予選/決勝の集計を計算しレスポンス map を作る。 */
    Map<String, Object> computeStandings(Competition comp) {
        List<CompetitionParticipant> participants =
                participantRepository.findByCompetitionOrderByCreatedAtAsc(comp);
        List<CompetitionIndividualMatch> matches =
                individualMatchRepository.findByCompetitionOrderByMatchOrderAsc(comp);
        Map<Long, List<CompetitionIndividualMatchSlot>> slotsByMatch = new HashMap<>();
        for (CompetitionIndividualMatch m : matches) {
            slotsByMatch.put(m.getId(),
                    individualMatchSlotRepository.findByMatchOrderBySlotPositionAsc(m));
        }

        // 集計用初期化
        Map<Long, ParticipantAgg> agg = new LinkedHashMap<>();
        for (CompetitionParticipant p : participants) agg.put(p.getId(), new ParticipantAgg(p));

        int prelimMatchCount = 0;
        int prelimRecorded = 0;
        boolean finalsExists = false;
        boolean finalsAllRecorded = true;
        int finalsMatchCount = 0;
        int finalsRecorded = 0;

        for (CompetitionIndividualMatch m : matches) {
            boolean isFinals = Boolean.TRUE.equals(m.getIsFinals());
            boolean recorded = m.getResultRecordedAt() != null;
            if (isFinals) {
                finalsExists = true;
                finalsMatchCount++;
                if (recorded) finalsRecorded++;
                else finalsAllRecorded = false;
            } else {
                prelimMatchCount++;
                if (recorded) prelimRecorded++;
            }
            if (!recorded) continue;
            List<CompetitionIndividualMatchSlot> slots = slotsByMatch.get(m.getId());

            // 決勝試合は「試合内総合順位」を別途算出する (totalPoints 降順、同点はタイ)
            Map<Long, Integer> finalsMatchRank = new HashMap<>();
            if (isFinals) {
                for (CompetitionIndividualMatchSlot s : slots) {
                    int myTp = zero(s.getTotalPoints());
                    int better = 0;
                    for (CompetitionIndividualMatchSlot other : slots) {
                        if (zero(other.getTotalPoints()) > myTp) better++;
                    }
                    finalsMatchRank.put(s.getParticipant().getId(), better + 1);
                }
            }

            for (CompetitionIndividualMatchSlot s : slots) {
                Long pid = s.getParticipant().getId();
                ParticipantAgg a = agg.get(pid);
                if (a == null) continue;
                int total = zero(s.getTotalPoints());
                // 1〜4 曲ぶんの順位カウントを加算
                Integer[] ranks = { s.getRank1(), s.getRank2(), s.getRank3(), s.getRank4() };
                if (isFinals) {
                    a.finalsPoints += total;
                    a.finalsRank = finalsMatchRank.get(pid);
                    a.finalsBucket = m.getFinalsBucket();
                } else {
                    a.prelimPoints += total;
                    for (Integer r : ranks) {
                        if (r == null) continue;
                        if (r == 1) a.first++;
                        else if (r == 2) a.second++;
                        else if (r == 3) a.third++;
                        else if (r == 4) a.fourth++;
                    }
                }
            }
        }

        // tie-break: prelimPoints 降順 → 1 位多い順 → 4 位少ない順 → createdAt 昇順
        List<ParticipantAgg> sorted = new ArrayList<>(agg.values());
        sorted.sort((x, y) -> {
            int c = Integer.compare(y.prelimPoints, x.prelimPoints);
            if (c != 0) return c;
            c = Integer.compare(y.first, x.first);
            if (c != 0) return c;
            c = Integer.compare(x.fourth, y.fourth);
            if (c != 0) return c;
            return x.participant.getCreatedAt().compareTo(y.participant.getCreatedAt());
        });
        for (int i = 0; i < sorted.size(); i++) sorted.get(i).prelimRank = i + 1;

        // final rank: 決勝で各バケットの 4 人に finals_rank 1..4 が振られていれば、
        // 全体最終順位 = (bucket - 1) * 4 + finals_rank
        if (finalsExists && finalsAllRecorded) {
            for (ParticipantAgg a : sorted) {
                if (a.finalsBucket != null && a.finalsRank != null) {
                    a.finalRank = (a.finalsBucket - 1) * 4 + a.finalsRank;
                }
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ParticipantAgg a : sorted) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("participantId", a.participant.getId());
            r.put("displayName", a.participant.getDisplayName());
            r.put("prelimRank", a.prelimRank);
            r.put("prelimPoints", a.prelimPoints);
            r.put("first", a.first);
            r.put("second", a.second);
            r.put("third", a.third);
            r.put("fourth", a.fourth);
            r.put("finalsBucket", a.finalsBucket);
            r.put("finalsRank", a.finalsRank);
            r.put("finalsPoints", a.finalsPoints);
            r.put("finalRank", a.finalRank);
            rows.add(r);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("rows", rows);
        root.put("prelimMatchCount", prelimMatchCount);
        root.put("prelimRecordedCount", prelimRecorded);
        root.put("allPrelimRecorded", prelimMatchCount > 0 && prelimRecorded >= prelimMatchCount);
        root.put("finalsExists", finalsExists);
        root.put("finalsMatchCount", finalsMatchCount);
        root.put("finalsRecordedCount", finalsRecorded);
        root.put("allFinalsRecorded", finalsExists && finalsAllRecorded);
        return root;
    }

    // ── 決勝生成 ────────────────────────────────────────────

    /**
     * 【メソッドの役割】 予選全試合記録後、上位 4 人ずつのバケットで決勝試合を生成する。
     *
     * 12 名: 3 試合 (1〜4 位 / 5〜8 位 / 9〜12 位) / 16 名: 4 試合 (1〜4 位 / 5〜8 位 / 9〜12 位 / 13〜16 位)。
     * すでに決勝が生成済みなら 400 を返す。
     */
    @PostMapping("/generate-finals")
    @Transactional
    public ResponseEntity<Map<String, Object>> generateFinals(
            Authentication auth,
            @PathVariable Long competitionId) {
        requireOrganizer(auth);
        Competition comp = requireIndividualCompetition(competitionId);
        List<CompetitionIndividualMatch> existing =
                individualMatchRepository.findByCompetitionOrderByMatchOrderAsc(comp);
        boolean alreadyFinals = existing.stream().anyMatch(m -> Boolean.TRUE.equals(m.getIsFinals()));
        if (alreadyFinals) {
            return ResponseEntity.badRequest().body(Map.of("message", "決勝はすでに生成済みです"));
        }
        Map<String, Object> standings = computeStandings(comp);
        if (!Boolean.TRUE.equals(standings.get("allPrelimRecorded"))) {
            return ResponseEntity.badRequest().body(Map.of("message", "予選全試合の結果が記録されていません"));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) standings.get("rows");
        int participantCount = rows.size();
        if (participantCount % 4 != 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "message", "参加者数が 4 の倍数ではありません: " + participantCount));
        }
        int buckets = participantCount / 4;

        int nextOrder = existing.stream().mapToInt(CompetitionIndividualMatch::getMatchOrder).max().orElse(0) + 1;

        for (int b = 1; b <= buckets; b++) {
            CompetitionIndividualMatch m = new CompetitionIndividualMatch();
            m.setCompetition(comp);
            m.setMatchOrder(nextOrder++);
            m.setIsFinals(true);
            m.setFinalsBucket(b);
            m = individualMatchRepository.save(m);

            // bucket b に入る予選順位は (b-1)*4 + 1 〜 b*4
            for (int slot = 1; slot <= 4; slot++) {
                Map<String, Object> row = rows.get((b - 1) * 4 + (slot - 1));
                Long pid = ((Number) row.get("participantId")).longValue();
                CompetitionParticipant p = participantRepository.findById(pid)
                        .orElseThrow(() -> new RuntimeException("Participant not found: " + pid));
                CompetitionIndividualMatchSlot s = new CompetitionIndividualMatchSlot();
                s.setMatch(m);
                s.setSlotPosition(slot);
                s.setParticipant(p);
                individualMatchSlotRepository.save(s);
            }
        }
        return ResponseEntity.ok(Map.of(
                "message", "決勝 " + buckets + " 試合を生成しました"
        ));
    }

    // ── ヘルパ ──────────────────────────────────────────────

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

    private Competition requireIndividualCompetition(Long id) {
        Competition comp = competitionRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Competition not found: " + id));
        if (!"individual4".equals(comp.getFormat())) {
            throw new RuntimeException("対象の大会フォーマットは individual4 ではありません");
        }
        return comp;
    }

    private CompetitionParticipant requireParticipantOf(Long participantId, Competition comp) {
        CompetitionParticipant p = participantRepository.findById(participantId)
                .orElseThrow(() -> new RuntimeException("Participant not found: " + participantId));
        if (!p.getCompetition().getId().equals(comp.getId())) {
            throw new RuntimeException("指定参加者はこの大会に属していません");
        }
        return p;
    }

    private static int rankToPoints(int rank) {
        return switch (rank) {
            case 1 -> 2;
            case 2 -> 1;
            default -> 0;
        };
    }

    private static int zero(Integer v) {
        return v == null ? 0 : v;
    }

    private Map<String, Object> toParticipantMap(CompetitionParticipant p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("teamId", null);
        m.put("displayName", p.getDisplayName());
        m.put("inviteToken", p.getInviteToken());
        m.put("isTl", false);
        m.put("createdAt", p.getCreatedAt());
        return m;
    }

    private Map<String, Object> toIndividualMatchMap(CompetitionIndividualMatch im) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", im.getId());
        m.put("matchOrder", im.getMatchOrder());
        m.put("isFinals", im.getIsFinals());
        m.put("finalsBucket", im.getFinalsBucket());
        m.put("resultRecordedAt", im.getResultRecordedAt());
        m.put("song1StrategyId", im.getSong1StrategyId());
        m.put("song1Title", im.getSong1Title());
        m.put("song2StrategyId", im.getSong2StrategyId());
        m.put("song2Title", im.getSong2Title());
        m.put("song3StrategyId", im.getSong3StrategyId());
        m.put("song3Title", im.getSong3Title());
        m.put("song4StrategyId", im.getSong4StrategyId());
        m.put("song4Title", im.getSong4Title());
        List<CompetitionIndividualMatchSlot> slots =
                individualMatchSlotRepository.findByMatchOrderBySlotPositionAsc(im);
        List<Map<String, Object>> slotMaps = new ArrayList<>();
        for (CompetitionIndividualMatchSlot s : slots) {
            slotMaps.add(individualSlotMap(s));
        }
        m.put("slots", slotMaps);
        return m;
    }

    /** スロット 1 件分のレスポンス map (4 曲ぶんのスコア・順位・ポイント含む)。 */
    static Map<String, Object> individualSlotMap(CompetitionIndividualMatchSlot s) {
        Map<String, Object> sm = new LinkedHashMap<>();
        sm.put("id", s.getId());
        sm.put("slotPosition", s.getSlotPosition());
        sm.put("participantId", s.getParticipant() != null ? s.getParticipant().getId() : null);
        sm.put("participantName", s.getParticipant() != null ? s.getParticipant().getDisplayName() : null);
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
        return sm;
    }

    // ── 集計内部表現 ──────────────────────────────────────
    private static class ParticipantAgg {
        final CompetitionParticipant participant;
        int prelimPoints = 0;
        int first = 0, second = 0, third = 0, fourth = 0;
        int prelimRank = 0;
        Integer finalsBucket = null;
        Integer finalsRank = null;
        int finalsPoints = 0;
        Integer finalRank = null;
        ParticipantAgg(CompetitionParticipant p) { this.participant = p; }
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 個人戦の参加者追加リクエスト。 */
    public record AddIndividualParticipantRequest(String displayName) {}

    /** 個人戦の参加者更新リクエスト (表示名のみ)。 */
    public record UpdateIndividualParticipantRequest(String displayName) {}

    /**
     * 個人戦 1 試合の結果記録リクエスト (ARENA モード相当)。
     * 4 曲のメタ (タイトル / strategy_id) + 4 スロット × 各曲スコア。
     */
    public record SetIndividualResultRequest(
            Integer song1StrategyId, String song1Title,
            Integer song2StrategyId, String song2Title,
            Integer song3StrategyId, String song3Title,
            Integer song4StrategyId, String song4Title,
            List<SetIndividualResultSlot> slots
    ) {}

    /** 試合結果の 1 スロット分 (= 1 プレイヤーの 4 曲ぶんの順位 1〜4)。 */
    public record SetIndividualResultSlot(
            Integer slotPosition,
            Integer rank1,
            Integer rank2,
            Integer rank3,
            Integer rank4
    ) {}
}
