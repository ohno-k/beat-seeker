package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.KinjoCupParticipant;
import com.beatseeker.backend.entity.Score;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.KinjoCupParticipantRepository;
import com.beatseeker.backend.repository.ScoreRepository;
import com.beatseeker.backend.repository.SongDefinitionRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.AdminAuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】「きんじょー杯」特設ページ（/kinjocup）用の REST コントローラ。
 *
 * 大会のドラフト選考の参考として、登録された参加者名簿とその実力データを返す。
 * URL 直打ちで誰でも到達できる独立ページ用の API。
 *
 * 認可方針:
 *  - 参加者一覧の閲覧（GET）はログイン不要の公開エンドポイント。
 *    （掲載する総合力 / 段位 / アリーナはランキング等で既に公開済みの指標のみ）
 *  - 参加者の追加（POST）・削除（DELETE）は管理者のみ。
 *    Spring Security 側では「要ログイン」までガードし、管理者判定はここで {@link AdminAuthService} を使って行う。
 *    （SecurityConfig は末尾 anyRequest().permitAll() の fail-open なので、書き込み系は二重で締める）
 *
 * 主なエンドポイント:
 *  - GET    /api/kinjocup/participants        … 参加者一覧（公開・総合力降順）
 *  - POST   /api/kinjocup/participants        … 参加者を追加（管理者）
 *  - DELETE /api/kinjocup/participants/{id}    … 参加者を削除（管理者）
 */
@RestController
@RequestMapping("/api/kinjocup")
public class KinjoCupController {

    /** 参加者名簿の永続化リポジトリ。 */
    private final KinjoCupParticipantRepository participantRepository;
    /** 追加時のユーザー解決・認証ユーザー解決に使うリポジトリ。 */
    private final UserRepository userRepository;
    /** 参加者の詳細表示（ダッシュボード/スコア一覧）用にスコアを引くリポジトリ。 */
    private final ScoreRepository scoreRepository;
    /** LV12 総数・譜面メタ（notes/level）取得用リポジトリ。 */
    private final SongDefinitionRepository songDefinitionRepository;
    /** 管理者判定ロジックを集約した Service。 */
    private final AdminAuthService adminAuthService;

    public KinjoCupController(KinjoCupParticipantRepository participantRepository,
                              UserRepository userRepository,
                              ScoreRepository scoreRepository,
                              SongDefinitionRepository songDefinitionRepository,
                              AdminAuthService adminAuthService) {
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.scoreRepository = scoreRepository;
        this.songDefinitionRepository = songDefinitionRepository;
        this.adminAuthService = adminAuthService;
    }

    /**
     * きんじょー杯「曲別順位」の課題曲に含まれる HYPER 譜面のタイトル（ホワイトリスト）。
     * 通常の曲別順位集計は ANOTHER/LEGGENDARIA のみだが、この特設ページに限りこれらの HYPER を lv10 区分へ追加する。
     * タイトルは本番DBのスコア表記に一致させている（不一致だと突合できず非表示になる）。フロントの
     * kinjocup_challenge_songs.json の HYPER エントリと対応する。
     */
    private static final List<String> KINJO_HYPER_TITLES = List.of(
            "CAN'T STOP FALLIN'IN LOVE", "Summer Vacation(CU mix)", "THE SAFARI", "Distress",
            "GAMBOL", "KEEP ON MOVIN'", "Tangerine Stream", "FLOWERS for ALBION",
            "PUT YOUR FAITH IN ME(for beatmania II)", "still my words", "RISLIM");

    /** 上記 HYPER 譜面の表示レベル（セル見出し用。いずれも Lv10 以下なので lv10 区分に入る）。 */
    private static final Map<String, Integer> KINJO_HYPER_LEVELS = Map.ofEntries(
            Map.entry("CAN'T STOP FALLIN'IN LOVE", 8),
            Map.entry("Summer Vacation(CU mix)", 9),
            Map.entry("THE SAFARI", 10),
            Map.entry("Distress", 10),
            Map.entry("GAMBOL", 2),
            Map.entry("KEEP ON MOVIN'", 3),
            Map.entry("Tangerine Stream", 3),
            Map.entry("FLOWERS for ALBION", 5),
            Map.entry("PUT YOUR FAITH IN ME(for beatmania II)", 6),
            Map.entry("still my words", 6),
            Map.entry("RISLIM", 7));

    /**
     * 【メソッドの役割】 参加者一覧を公開で返す。総合力(Beat-Pt)降順で並べる。
     *
     * 個人を特定し得る IIDX ID は公開ペイロードに含めない（ドラフト参考に不要なため）。
     * 各参加者の詳細ページ（/user/{userId}）へリンクできるよう userId は返す。
     *
     * @return 参加者サマリの List（総合力降順）
     */
    @GetMapping("/participants")
    public ResponseEntity<List<Map<String, Object>>> listParticipants() {
        List<KinjoCupParticipant> parts = participantRepository.findAllByOrderByCreatedAtAsc();

        // LV12 ANOTHER/LEGGENDARIA の総譜面数（AAA数/MAX-数 の分母）。全参加者共通の定数。
        long lv12Total = songDefinitionRepository.countActiveLv12AnotherLegg();

        // 参加者全員の譜面別ベストスコア（notes/level 付き）を 1 クエリで取得し、userId ごとに集計。
        Map<Long, Lv12RateStats> statsByUser = new HashMap<>();
        List<Long> userIds = parts.stream().map(p -> p.getUser().getId()).toList();
        if (!userIds.isEmpty()) {
            Map<Long, List<Map<String, Object>>> rowsByUser = scoreRepository
                    .findBestAnotherLeggWithDefForUsers(userIds).stream()
                    .collect(Collectors.groupingBy(r -> ((Number) r.get("userId")).longValue()));
            rowsByUser.forEach((uid, rows) -> statsByUser.put(uid, computeStats(rows)));
        }

        List<Map<String, Object>> result = parts.stream()
                .map(p -> {
                    Map<String, Object> m = toPublicMap(p);
                    Lv12RateStats st = statsByUser.getOrDefault(p.getUser().getId(), Lv12RateStats.EMPTY);
                    m.put("lv12AaaCount", st.aaa());
                    m.put("lv12MaxMinusCount", st.maxMinus());
                    m.put("lv12Total", lv12Total);
                    m.put("rateFloorScoreRate", st.rateFloor()); // 100曲未満なら null
                    m.put("rateEligibleCount", st.rateEligible());
                    return m;
                })
                .sorted(Comparator.comparingDouble(
                        (Map<String, Object> m) -> ((Number) m.getOrDefault("totalBeatPt", 0.0)).doubleValue())
                        .reversed())
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 1 参加者分の「LV12 AAA数・MAX-数」と「RATE-TIER 下限（100曲目のスコアレート）」を計算する。
     *
     * 入力は {@link ScoreRepository#findBestAnotherLeggWithDefForUsers} の行
     * （userId/title/difficultyName/score/notes/level、譜面別ベスト・重複排除済み）。
     *
     * - AAA  : LV12 かつ score*9 >= notes*16（スコア率 >= 8/9 ≒ 88.89%）
     * - MAX- : LV12 かつ score*9 >= notes*17（スコア率 >= 17/18 ≒ 94.44%）
     * - RATE-TIER 下限: ANOTHER/LEGGENDARIA 全レベルのうち RATE-PT 対象（スコア率 >= 77.77%）の
     *   レートを降順に並べ、100 番目のスコアレート。100 曲未満なら null。
     */
    private Lv12RateStats computeStats(List<Map<String, Object>> rows) {
        int aaa = 0;
        int maxMinus = 0;
        List<Double> eligibleRates = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long score = toLong(r.get("score"));
            long notes = toLong(r.get("notes"));
            if (notes <= 0) continue;
            Object lvlObj = r.get("level");
            int level = lvlObj == null ? 0 : ((Number) lvlObj).intValue();

            if (level == 12) {
                if (score * 9 >= notes * 16) aaa++;        // AAA 以上
                if (score * 9 >= notes * 17) maxMinus++;   // MAX- 以上
            }
            // RATE-PT 対象（スコア率 >= 77.77% で PT > 0）。全レベル。
            double rate = score * 100.0 / (notes * 2.0);
            if (rate >= 77.77) eligibleRates.add(rate);
        }
        eligibleRates.sort(Comparator.reverseOrder());
        Double rateFloor = eligibleRates.size() >= 100
                ? Math.round(eligibleRates.get(99) * 100.0) / 100.0
                : null;
        return new Lv12RateStats(aaa, maxMinus, rateFloor, eligibleRates.size());
    }

    /** ネイティブクエリ由来の数値（Integer/Long/BigInteger 等）を long に正規化する。 */
    private static long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /**
     * 【メソッドの役割】 指定参加者のスコア一覧を公開で返す（特設ページ内のダッシュボード/スコア一覧用）。
     *
     * 名簿に登録された参加者は「閲覧対象として登録済み」とみなし、本人のプライバシー設定に関わらず
     * スコアを返す。ただし名簿に居ないユーザーの ID を渡された場合は 404（任意ユーザーの情報漏洩防止）。
     * 応答はフロントの useScores と同じ「譜面フラット配列」。曲単位のグルーピングはクライアント側で行う。
     *
     * @param userId beat-seeker ユーザーの ID
     * @return スコアのフラット配列。参加者でなければ 404。
     */
    @GetMapping("/participants/{userId}/scores")
    public ResponseEntity<?> getParticipantScores(@PathVariable Long userId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !participantRepository.existsByUser(user)) {
            return ResponseEntity.status(404).body(Map.of("error", "対象は参加者として登録されていません"));
        }

        List<Map<String, Object>> result = scoreRepository.findByUserOrderByUploadedAtAsc(user).stream()
                .map(this::toScoreMap)
                .toList();
        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 参加者同士の総当たり「勝敗マトリクス」を LV10以下 / LV11 / LV12 の 3 区分で返す。
     *
     * 勝敗判定: ANOTHER/LEGGENDARIA の譜面で、両者がプレイ済みの共通譜面ごとに EX スコアを比較し、
     * 高い方を勝ち（同点は引き分け）。区分ごとに総当たりで集計する。
     * 行・列の並びは「勝ち数の多い人を上（左）」に降順ソートする。
     *
     * 公開エンドポイント（特設ページと同様、閲覧は誰でも可）。
     *
     * @return {@code { lv10: {players, matrix}, lv11: {...}, lv12: {...} }}
     */
    @GetMapping("/matrix")
    public ResponseEntity<Map<String, Object>> matrix() {
        List<KinjoCupParticipant> parts = participantRepository.findAllByOrderByCreatedAtAsc();
        List<Participant> players = parts.stream()
                .map(p -> new Participant(p.getUser().getId(),
                        p.getUser().getDisplayName() != null ? p.getUser().getDisplayName() : ""))
                .toList();
        List<Long> userIds = players.stream().map(Participant::userId).toList();

        // userId → 区分('lv10'/'lv11'/'lv12') → (譜面キー → ベスト EX スコア)
        Map<Long, Map<String, Map<String, Integer>>> byUser = new HashMap<>();
        for (Long uid : userIds) {
            Map<String, Map<String, Integer>> m = new HashMap<>();
            m.put("lv10", new HashMap<>());
            m.put("lv11", new HashMap<>());
            m.put("lv12", new HashMap<>());
            byUser.put(uid, m);
        }
        if (!userIds.isEmpty()) {
            for (Map<String, Object> r : scoreRepository.findBestAnotherLeggWithDefForUsers(userIds)) {
                Object lvlObj = r.get("level");
                if (lvlObj == null) continue;
                int level = ((Number) lvlObj).intValue();
                String bracket = level <= 10 ? "lv10" : level == 11 ? "lv11" : level == 12 ? "lv12" : null;
                if (bracket == null) continue;
                long uid = toLong(r.get("userId"));
                Map<String, Map<String, Integer>> um = byUser.get(uid);
                if (um == null) continue;
                String key = r.get("title") + "|" + r.get("difficultyName");
                um.get(bracket).put(key, (int) toLong(r.get("score")));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lv10", buildBracket(players, byUser, "lv10"));
        result.put("lv11", buildBracket(players, byUser, "lv11"));
        result.put("lv12", buildBracket(players, byUser, "lv12"));
        return ResponseEntity.ok(result);
    }

    /**
     * 1 区分分の総当たり勝敗を集計し、勝ち数降順に並べた players と、その並びに揃えた matrix を返す。
     */
    private Map<String, Object> buildBracket(List<Participant> players,
                                             Map<Long, Map<String, Map<String, Integer>>> byUser,
                                             String bracket) {
        int n = players.size();
        // A → B → {A の勝ち, A の負け, 引き分け}
        Map<Long, Map<Long, int[]>> rec = new HashMap<>();
        // userId → {総勝ち, 総負け, 総引き分け}
        Map<Long, int[]> totals = new HashMap<>();
        for (Participant p : players) totals.put(p.userId(), new int[]{0, 0, 0});

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Participant a = players.get(i);
                Participant b = players.get(j);
                Map<String, Integer> ma = byUser.get(a.userId()).get(bracket);
                Map<String, Integer> mb = byUser.get(b.userId()).get(bracket);
                int aWin = 0, bWin = 0, draw = 0;
                // 小さい方を走査して共通譜面のみ比較
                Map<String, Integer> small = ma.size() <= mb.size() ? ma : mb;
                boolean smallIsA = small == ma;
                Map<String, Integer> other = smallIsA ? mb : ma;
                for (Map.Entry<String, Integer> e : small.entrySet()) {
                    Integer os = other.get(e.getKey());
                    if (os == null) continue;
                    int sa = smallIsA ? e.getValue() : os;
                    int sb = smallIsA ? os : e.getValue();
                    if (sa > sb) aWin++;
                    else if (sb > sa) bWin++;
                    else draw++;
                }
                rec.computeIfAbsent(a.userId(), k -> new HashMap<>()).put(b.userId(), new int[]{aWin, bWin, draw});
                rec.computeIfAbsent(b.userId(), k -> new HashMap<>()).put(a.userId(), new int[]{bWin, aWin, draw});
                int[] ta = totals.get(a.userId());
                ta[0] += aWin; ta[1] += bWin; ta[2] += draw;
                int[] tb = totals.get(b.userId());
                tb[0] += bWin; tb[1] += aWin; tb[2] += draw;
            }
        }

        // 勝ち数降順 → 勝率降順 → 負け数昇順 → 名前 でソート
        List<Participant> sorted = new ArrayList<>(players);
        sorted.sort((x, y) -> {
            int[] tx = totals.get(x.userId());
            int[] ty = totals.get(y.userId());
            if (ty[0] != tx[0]) return Integer.compare(ty[0], tx[0]);            // 勝ち数 多い順
            int cr = Double.compare(winRate(ty), winRate(tx));
            if (cr != 0) return cr;                                              // 勝率 高い順
            if (tx[1] != ty[1]) return Integer.compare(tx[1], ty[1]);           // 負け 少ない順
            return x.displayName().compareTo(y.displayName());
        });

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Participant p : sorted) {
            int[] t = totals.get(p.userId());
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("userId", p.userId());
            pm.put("displayName", p.displayName());
            pm.put("wins", t[0]);
            pm.put("losses", t[1]);
            pm.put("draws", t[2]);
            pm.put("winRate", winRate(t));
            playerList.add(pm);
        }

        List<List<Map<String, Object>>> matrix = new ArrayList<>();
        for (Participant rowP : sorted) {
            List<Map<String, Object>> row = new ArrayList<>();
            for (Participant colP : sorted) {
                if (rowP.userId() == colP.userId()) {
                    row.add(null); // 対角は自分自身
                    continue;
                }
                int[] cell = rec.getOrDefault(rowP.userId(), Map.of()).get(colP.userId());
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("w", cell == null ? 0 : cell[0]);
                c.put("l", cell == null ? 0 : cell[1]);
                c.put("d", cell == null ? 0 : cell[2]);
                row.add(c);
            }
            matrix.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("players", playerList);
        out.put("matrix", matrix);
        return out;
    }

    /** 勝率（%、小数第1位）。決着が無ければ 0。 */
    private static double winRate(int[] t) {
        int decided = t[0] + t[1];
        return decided == 0 ? 0.0 : Math.round(t[0] * 1000.0 / decided) / 10.0;
    }

    /**
     * 【メソッドの役割】 マトリクスのセル（A vs B）の内訳を返す。指定区分で両者がプレイ済みの
     * 共通譜面ごとに EX スコアを並べ、A 視点の勝敗（win/lose/draw）を付ける。
     *
     * 公開エンドポイント。並びは「A が負けている譜面（スコア差が小さい順）」を上にする。
     *
     * @param aId     行プレイヤーの userId
     * @param bId     列プレイヤーの userId
     * @param bracket 'lv10' / 'lv11' / 'lv12'
     */
    @GetMapping("/matchup")
    public ResponseEntity<?> matchup(@RequestParam("a") Long aId,
                                     @RequestParam("b") Long bId,
                                     @RequestParam("bracket") String bracket) {
        if (!("lv10".equals(bracket) || "lv11".equals(bracket) || "lv12".equals(bracket))) {
            return ResponseEntity.badRequest().body(Map.of("error", "bracket は lv10/lv11/lv12 のいずれか"));
        }
        User ua = userRepository.findById(aId).orElse(null);
        User ub = userRepository.findById(bId).orElse(null);
        if (ua == null || ub == null
                || !participantRepository.existsByUser(ua) || !participantRepository.existsByUser(ub)) {
            return ResponseEntity.status(404).body(Map.of("error", "対象は参加者として登録されていません"));
        }

        // 譜面キー → {title, difficultyName, level, scoreA, scoreB}
        Map<String, Map<String, Object>> charts = new LinkedHashMap<>();
        for (Map<String, Object> r : scoreRepository.findBestAnotherLeggWithDefForUsers(List.of(aId, bId))) {
            Object lvlObj = r.get("level");
            if (lvlObj == null) continue;
            int level = ((Number) lvlObj).intValue();
            String br = level <= 10 ? "lv10" : level == 11 ? "lv11" : level == 12 ? "lv12" : null;
            if (!bracket.equals(br)) continue;
            String title = String.valueOf(r.get("title"));
            String diff = String.valueOf(r.get("difficultyName"));
            int notes = (int) toLong(r.get("notes"));
            String key = title + "|" + diff;
            Map<String, Object> m = charts.computeIfAbsent(key, k -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("title", title);
                mm.put("difficultyName", diff);
                mm.put("level", level);
                mm.put("notes", notes); // MAX = notes*2。グレード(MAX-/AAA+...)算出用。
                mm.put("scoreA", null);
                mm.put("scoreB", null);
                return mm;
            });
            int score = (int) toLong(r.get("score"));
            long uid = toLong(r.get("userId"));
            if (uid == aId) m.put("scoreA", score);
            else if (uid == bId) m.put("scoreB", score);
        }

        int aWins = 0, bWins = 0, draws = 0;
        List<Map<String, Object>> chartList = new ArrayList<>();
        for (Map<String, Object> m : charts.values()) {
            Object sa = m.get("scoreA");
            Object sb = m.get("scoreB");
            if (sa == null || sb == null) continue; // 両者プレイ済みのみ
            int scoreA = (Integer) sa;
            int scoreB = (Integer) sb;
            String res = scoreA > scoreB ? "win" : scoreA < scoreB ? "lose" : "draw";
            if ("win".equals(res)) aWins++;
            else if ("lose".equals(res)) bWins++;
            else draws++;
            m.put("result", res);
            chartList.add(m);
        }
        // A が負けている譜面（scoreA - scoreB が小さい順）を上に
        chartList.sort(Comparator.comparingInt(m -> ((Integer) m.get("scoreA")) - ((Integer) m.get("scoreB"))));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("playerA", Map.of("userId", aId, "displayName", ua.getDisplayName() != null ? ua.getDisplayName() : ""));
        resp.put("playerB", Map.of("userId", bId, "displayName", ub.getDisplayName() != null ? ub.getDisplayName() : ""));
        resp.put("bracket", bracket);
        resp.put("summary", Map.of("aWins", aWins, "bWins", bWins, "draws", draws, "total", chartList.size()));
        resp.put("charts", chartList);
        return ResponseEntity.ok(resp);
    }

    /**
     * 【メソッドの役割】 参加者内での「曲別順位」を LV10以下 / LV11 / LV12 の 3 区分で返す。
     *
     * 各譜面について、プレイ済み参加者を EX スコア降順に並べて順位を付ける（同点は同順位の競争順位）。
     * 列（参加者）は「平均順位の良い順」で並べ、各譜面行のセルは参加者の順位を持つ。
     *
     * 公開エンドポイント。
     *
     * @return {@code { lv10: {players, charts}, lv11: {...}, lv12: {...} }}
     */
    @GetMapping("/song-ranks")
    public ResponseEntity<Map<String, Object>> songRanks() {
        List<KinjoCupParticipant> parts = participantRepository.findAllByOrderByCreatedAtAsc();
        List<Participant> players = parts.stream()
                .map(p -> new Participant(p.getUser().getId(),
                        p.getUser().getDisplayName() != null ? p.getUser().getDisplayName() : ""))
                .toList();
        List<Long> userIds = players.stream().map(Participant::userId).toList();

        Map<String, Map<String, ChartScores>> data = new HashMap<>();
        data.put("lv10", new LinkedHashMap<>());
        data.put("lv11", new LinkedHashMap<>());
        data.put("lv12", new LinkedHashMap<>());
        if (!userIds.isEmpty()) {
            for (Map<String, Object> r : scoreRepository.findBestAnotherLeggWithDefForUsers(userIds)) {
                Object lvlObj = r.get("level");
                if (lvlObj == null) continue;
                int level = ((Number) lvlObj).intValue();
                String bracket = level <= 10 ? "lv10" : level == 11 ? "lv11" : level == 12 ? "lv12" : null;
                if (bracket == null) continue;
                String title = String.valueOf(r.get("title"));
                String diff = String.valueOf(r.get("difficultyName"));
                String key = title + "|" + diff;
                ChartScores cs = data.get(bracket).computeIfAbsent(key, k -> new ChartScores(title, diff, level));
                cs.scores.put(toLong(r.get("userId")), (int) toLong(r.get("score")));
            }
            // きんじょー杯 特設ページ限定: 課題曲の HYPER 譜面（ホワイトリスト）を lv10 区分へ追加する。
            for (Map<String, Object> r : scoreRepository.findBestHyperForTitlesAndUsers(userIds, KINJO_HYPER_TITLES)) {
                String title = String.valueOf(r.get("title"));
                int level = KINJO_HYPER_LEVELS.getOrDefault(title, 0);
                String key = title + "|HYPER";
                ChartScores cs = data.get("lv10").computeIfAbsent(key, k -> new ChartScores(title, "HYPER", level));
                cs.scores.put(toLong(r.get("userId")), (int) toLong(r.get("score")));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lv10", buildSongRanks(players, data.get("lv10")));
        result.put("lv11", buildSongRanks(players, data.get("lv11")));
        result.put("lv12", buildSongRanks(players, data.get("lv12")));
        return ResponseEntity.ok(result);
    }

    /**
     * 1 区分分の曲別順位を集計する。列（参加者）は平均順位の良い順、行（譜面）は曲名順。
     * cells は sorted players の並びに揃え、未プレイは null。
     */
    private Map<String, Object> buildSongRanks(List<Participant> players, Map<String, ChartScores> charts) {
        // userId → {順位合計, プレイ数, 1位回数}
        Map<Long, long[]> agg = new HashMap<>();
        for (Participant p : players) agg.put(p.userId(), new long[]{0, 0, 0});
        // chartKey → (userId → {rank, score})
        Map<String, Map<Long, int[]>> chartCells = new HashMap<>();

        for (Map.Entry<String, ChartScores> e : charts.entrySet()) {
            List<Map.Entry<Long, Integer>> entries = new ArrayList<>(e.getValue().scores.entrySet());
            entries.sort((x, y) -> Integer.compare(y.getValue(), x.getValue())); // スコア降順
            Map<Long, int[]> cells = new HashMap<>();
            int rank = 0, prevScore = Integer.MIN_VALUE, idx = 0;
            for (Map.Entry<Long, Integer> en : entries) {
                idx++;
                int sc = en.getValue();
                if (sc != prevScore) { rank = idx; prevScore = sc; } // 競争順位（同点は同順位）
                cells.put(en.getKey(), new int[]{rank, sc});
                long[] a = agg.get(en.getKey());
                if (a != null) { a[0] += rank; a[1]++; if (rank == 1) a[2]++; }
            }
            chartCells.put(e.getKey(), cells);
        }

        // 平均順位の良い順（プレイ無しは最後）。同値は 1位回数多い順 → 名前。
        List<Participant> sorted = new ArrayList<>(players);
        sorted.sort((x, y) -> {
            long[] ax = agg.get(x.userId());
            long[] ay = agg.get(y.userId());
            double avx = ax[1] == 0 ? Double.MAX_VALUE : (double) ax[0] / ax[1];
            double avy = ay[1] == 0 ? Double.MAX_VALUE : (double) ay[0] / ay[1];
            if (Double.compare(avx, avy) != 0) return Double.compare(avx, avy);
            if (ax[2] != ay[2]) return Long.compare(ay[2], ax[2]);
            return x.displayName().compareTo(y.displayName());
        });

        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Participant p : sorted) {
            long[] a = agg.get(p.userId());
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("userId", p.userId());
            pm.put("displayName", p.displayName());
            pm.put("played", a[1]);
            pm.put("firstPlaces", a[2]);
            pm.put("avgRank", a[1] == 0 ? null : Math.round((double) a[0] / a[1] * 100.0) / 100.0);
            playerList.add(pm);
        }

        List<Map.Entry<String, ChartScores>> chartEntries = new ArrayList<>(charts.entrySet());
        chartEntries.sort((x, y) -> x.getValue().title.compareToIgnoreCase(y.getValue().title));
        List<Map<String, Object>> chartList = new ArrayList<>();
        for (Map.Entry<String, ChartScores> e : chartEntries) {
            ChartScores cs = e.getValue();
            Map<Long, int[]> cells = chartCells.get(e.getKey());
            List<Map<String, Object>> cellList = new ArrayList<>();
            for (Participant p : sorted) {
                int[] rc = cells.get(p.userId());
                if (rc == null) {
                    cellList.add(null);
                } else {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("rank", rc[0]);
                    cm.put("score", rc[1]);
                    cellList.add(cm);
                }
            }
            Map<String, Object> chm = new LinkedHashMap<>();
            chm.put("title", cs.title);
            chm.put("difficultyName", cs.diff);
            chm.put("level", cs.level);
            chm.put("players", cs.scores.size()); // プレイ人数（順位の母数）
            chm.put("cells", cellList);
            chartList.add(chm);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("players", playerList);
        out.put("charts", chartList);
        return out;
    }

    /**
     * 【メソッドの役割】 参加者を名簿に追加する（管理者のみ）。
     *
     * @param auth 認証情報（管理者限定）
     * @param req  追加対象ユーザーの ID を含むリクエスト
     * @return 追加された参加者サマリ。権限不足は 403、対象ユーザー不在は 404、二重登録は 409。
     */
    @PostMapping("/participants")
    @Transactional
    public ResponseEntity<?> addParticipant(Authentication auth, @RequestBody AddParticipantRequest req) {
        User admin = requireAdmin(auth);
        if (admin == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみ参加者を追加できます"));
        }
        if (req == null || req.userId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "userId は必須です"));
        }

        User target = userRepository.findById(req.userId()).orElse(null);
        if (target == null) {
            return ResponseEntity.status(404).body(Map.of("error", "指定したユーザーが見つかりません"));
        }
        if (participantRepository.existsByUser(target)) {
            return ResponseEntity.status(409).body(Map.of("error", "既に登録済みの参加者です"));
        }

        KinjoCupParticipant p = new KinjoCupParticipant();
        p.setUser(target);
        participantRepository.save(p);

        return ResponseEntity.ok(toPublicMap(p));
    }

    /**
     * 【メソッドの役割】 参加者のメモを更新する。協同編集のため誰でも可（ログイン不要）。
     *
     * 追加・削除は管理者のみだが、メモ（ドラフト選考の覚書）は閲覧者全員が書き込める。
     *
     * @param id   参加者エントリ（KinjoCupParticipant）の ID
     * @param req  新しいメモ本文（null/空文字でメモ消去）
     * @return 更新後の参加者サマリ。対象不在は 404、長すぎは 400。
     */
    @PutMapping("/participants/{id}/note")
    @Transactional
    public ResponseEntity<?> updateNote(@PathVariable Long id,
                                        @RequestBody UpdateNoteRequest req) {
        KinjoCupParticipant p = participantRepository.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.status(404).body(Map.of("error", "対象の参加者が見つかりません"));
        }
        String note = req == null || req.note() == null ? null : req.note().trim();
        if (note != null && note.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "メモは2000文字以内で入力してください"));
        }
        // 空文字は「メモ無し」として null 化する。
        p.setNote(note == null || note.isEmpty() ? null : note);
        participantRepository.save(p);
        return ResponseEntity.ok(toPublicMap(p));
    }

    /**
     * 【メソッドの役割】 参加者を名簿から削除する（管理者のみ）。
     *
     * @param auth 認証情報（管理者限定）
     * @param id   参加者エントリ（KinjoCupParticipant）の ID
     * @return 204 No Content。権限不足は 403、対象不在は 404。
     */
    @DeleteMapping("/participants/{id}")
    @Transactional
    public ResponseEntity<?> removeParticipant(Authentication auth, @PathVariable Long id) {
        User admin = requireAdmin(auth);
        if (admin == null) {
            return ResponseEntity.status(403).body(Map.of("error", "管理者のみ参加者を削除できます"));
        }
        if (!participantRepository.existsById(id)) {
            return ResponseEntity.status(404).body(Map.of("error", "対象の参加者が見つかりません"));
        }
        participantRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------
    // 内部ヘルパー
    // ---------------------------------------------------------------------

    /**
     * 認証情報から管理者ユーザーを解決する。未認証・ユーザー不在・非管理者なら null を返す。
     * （Controller 側で 403 を返す前提。例外は投げない）
     */
    private User requireAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof String iidxId)) {
            return null;
        }
        User user = userRepository.findByIidxId(iidxId).orElse(null);
        if (user == null || !adminAuthService.isAdmin(user)) {
            return null;
        }
        return user;
    }

    /**
     * Score エンティティをフロント（useScores）が期待する譜面フラット Map に変換する。
     * UI に必要な最小限のフィールドのみ（オプション情報は付与しない）。
     */
    private Map<String, Object> toScoreMap(Score s) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle() != null ? s.getTitle() : "");
        m.put("difficultyName", s.getDifficultyName() != null ? s.getDifficultyName() : "");
        m.put("difficultyLevel", s.getDifficultyLevel() != null ? s.getDifficultyLevel() : 0);
        m.put("score", s.getScore() != null ? s.getScore() : 0);
        m.put("clearType", s.getClearType() != null ? s.getClearType() : "");
        m.put("djLevel", s.getDjLevel() != null ? s.getDjLevel() : "");
        m.put("pgreat", s.getPgreat() != null ? s.getPgreat() : 0);
        m.put("great", s.getGreat() != null ? s.getGreat() : 0);
        m.put("missCount", s.getMissCount());
        return m;
    }

    /** 参加者エントリを公開用 Map に変換する。実力データは紐づく User から都度読み出す。 */
    private Map<String, Object> toPublicMap(KinjoCupParticipant p) {
        User u = p.getUser();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("userId", u.getId());
        m.put("iidxId", u.getIidxId() != null ? u.getIidxId() : "");
        m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : "");
        m.put("danRank", u.getDanRank() != null ? u.getDanRank() : "");
        m.put("arenaRank", u.getArenaRank() != null ? u.getArenaRank() : "");
        m.put("totalBeatPt", u.getTotalBeatPt() != null ? u.getTotalBeatPt() : 0.0);
        m.put("lastUploadedAt", u.getLastUploadedAt());
        m.put("note", p.getNote() != null ? p.getNote() : "");
        return m;
    }

    /** 参加者追加リクエストのボディ（追加対象ユーザーの ID）。 */
    public record AddParticipantRequest(Long userId) {
    }

    /** メモ更新リクエストのボディ。 */
    public record UpdateNoteRequest(String note) {
    }

    /** 勝敗マトリクス集計用の参加者（userId と表示名のみ）。 */
    private record Participant(long userId, String displayName) {
    }

    /** 曲別順位の集計用：1 譜面のメタ情報と userId→EXスコア。 */
    private static final class ChartScores {
        final String title;
        final String diff;
        final int level;
        final Map<Long, Integer> scores = new HashMap<>();
        ChartScores(String title, String diff, int level) {
            this.title = title;
            this.diff = diff;
            this.level = level;
        }
    }

    /**
     * 1 参加者分の集計結果。
     * @param aaa          LV12 AAA 達成数
     * @param maxMinus     LV12 MAX- 達成数
     * @param rateFloor    RATE-TIER 下限（100曲目）のスコアレート。100曲未満なら null
     * @param rateEligible RATE-PT 対象（スコア率 >= 77.77%）の譜面数
     */
    private record Lv12RateStats(int aaa, int maxMinus, Double rateFloor, int rateEligible) {
        /** 集計対象スコアが無い参加者向けの空集計。 */
        static final Lv12RateStats EMPTY = new Lv12RateStats(0, 0, null, 0);
    }
}
