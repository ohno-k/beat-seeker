package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ArenaMatch;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ArenaMatchRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【クラスの役割】 IIDX ARENA モードの対戦データを扱うコントローラ。
 *
 * プレイヤーはブックマークレット経由で ARENA 画面のデータを JSON 抽出し、
 * 本 API にインポートする。対戦履歴は DB に蓄積され、対戦成績の振り返りに使える。
 *
 * 主要エンドポイント:
 *  - {@code POST   /api/arena/import}          … ブックマークレット JSON 一括インポート（重複スキップ）
 *  - {@code GET    /api/arena/matches}         … ログインユーザーの対戦履歴
 *  - {@code DELETE /api/arena/matches/{id}}    … 指定対戦を削除
 *
 * 重複判定は (user, matchDate) のペア一意性で行う。
 */
@RestController
@RequestMapping("/api/arena")
public class ArenaController {

    /** ARENA 対戦エンティティの永続化 Repository。 */
    private final ArenaMatchRepository arenaMatchRepository;
    /** 認証ユーザー解決用 Repository。 */
    private final UserRepository userRepository;
    /** 楽曲／プレイヤー情報を JSON 文字列として保存／読み出しするための Jackson マッパー。 */
    private final ObjectMapper objectMapper;

    /**
     * 【コンストラクタ】 Spring が DI で Repository/ObjectMapper を注入する。
     */
    public ArenaController(ArenaMatchRepository arenaMatchRepository,
                           UserRepository userRepository,
                           ObjectMapper objectMapper) {
        this.arenaMatchRepository = arenaMatchRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 【メソッドの役割】 ブックマークレットが抽出した ARENA 対戦 JSON を一括インポートする。
     *
     * ブックマークレット側は日付を "MM/dd HH:mm" 形式でしか取れないため、
     * リクエスト側で現在の "year" を添えて送ってもらい、サーバー側で
     * "yyyy-MM-dd HH:mm" に正規化してから保存する。
     * (user, matchDate) が既にある場合はスキップし、重複保存を避ける。
     *
     * @param auth 認証情報
     * @param req  マイ DJ 名、年、対戦データ配列を含むリクエストボディ
     * @return 保存件数・スキップ件数・メッセージを含む Map
     */
    @PostMapping("/import")
    @Transactional
    public ResponseEntity<Map<String, Object>> importMatches(
            Authentication auth,
            @RequestBody ImportRequest req) {

        User user = getUser(auth);
        int savedCount = 0;
        int skippedCount = 0;

        for (BattleData battle : req.battles()) {
            // 日付文字列に年を補って "yyyy-MM-dd HH:mm" 形式に整形する
            String fullDate = req.year() + "-" + battle.date().replace("/", "-").replace(" ", " ").trim();
            // 例: req.year() = "2026", battle.date() = "03/15 17:10"
            //     → fullDate = "2026-03-15 17:10"

            // (user, matchDate) がユニーク制約なので、既存ならスキップ
            if (arenaMatchRepository.existsByUserAndMatchDate(user, fullDate)) {
                skippedCount++;
                continue;
            }

            // 対戦結果の中から「自分の行」を特定し、ランク・ポイント・クラスを取り出す
            String myDjName = req.myDjName();
            int myRank = 0;
            int myTotalPt = 0;
            String myArenaClass = "";

            for (PlayerData p : battle.players()) {
                if (myDjName.equals(p.djName())) {
                    myRank = p.rank();
                    myTotalPt = p.totalPt();
                    myArenaClass = p.arenaClass();
                    break;
                }
            }

            ArenaMatch match = new ArenaMatch();
            match.setUser(user);
            match.setBattleType(battle.battleType());
            match.setMatchDate(fullDate);
            match.setMyDjName(myDjName);
            match.setMyArenaClass(myArenaClass);
            match.setMyRank(myRank);
            match.setMyTotalPt(myTotalPt);

            try {
                // 楽曲・プレイヤー情報は都度カラムを作るのではなく JSON 文字列として一括保存
                match.setSongsJson(objectMapper.writeValueAsString(battle.songs()));
                match.setPlayersJson(objectMapper.writeValueAsString(battle.players()));
            } catch (Exception e) {
                // JSON 化失敗はコード or 型不整合に起因する想定外エラー。500 でアボート。
                return ResponseEntity.internalServerError()
                        .body(Map.of("message", "JSONシリアライズに失敗しました"));
            }

            arenaMatchRepository.save(match);
            savedCount++;
        }

        return ResponseEntity.ok(Map.of(
                "savedCount", savedCount,
                "skippedCount", skippedCount,
                "message", savedCount + "件保存しました（" + skippedCount + "件重複スキップ）"));
    }

    /**
     * 【メソッドの役割】 ログインユーザーの ARENA 対戦履歴を返す（日付降順）。
     *
     * JSON 文字列で保存されている {@code songsJson} / {@code playersJson} を
     * パースして List<Map> として返す。
     * 古い行には {@code myRank} 等の個別カラムが未保存（0/空）のケースがあるため、
     * その場合は {@code playersJson} を遡って再計算してから返す（後方互換）。
     *
     * @param auth 認証情報
     * @return 対戦情報の Map リスト
     */
    @GetMapping("/matches")
    public ResponseEntity<List<Map<String, Object>>> getMatches(Authentication auth) {
        User user = getUser(auth);
        List<ArenaMatch> matches = arenaMatchRepository.findByUserOrderByMatchDateDesc(user);

        List<Map<String, Object>> result = new ArrayList<>();
        for (ArenaMatch m : matches) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getId());
            map.put("battleType", m.getBattleType());
            map.put("matchDate", m.getMatchDate());
            map.put("myDjName", m.getMyDjName());

            // プレイヤー JSON を List<Map> にパース（失敗時は空リスト）
            List<Map<String, Object>> players = List.of();
            try {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> parsed = objectMapper.readValue(m.getPlayersJson(), List.class);
                players = parsed;
            } catch (Exception ignored) {}
            map.put("players", players);

            // 楽曲 JSON もパース（失敗時は空リスト）
            try {
                map.put("songs", objectMapper.readValue(m.getSongsJson(), List.class));
            } catch (Exception ignored) {
                map.put("songs", List.of());
            }

            // 旧スキーマで保存された行は myRank 等の個別カラムが 0/空の可能性があるため、
            // その場合は playersJson から自分の行を探して再計算する（後方互換のため）
            int myRank = m.getMyRank() != null ? m.getMyRank() : 0;
            int myTotalPt = m.getMyTotalPt() != null ? m.getMyTotalPt() : 0;
            String myArenaClass = m.getMyArenaClass() != null ? m.getMyArenaClass() : "";
            String myDjName = m.getMyDjName();

            if (myRank == 0 && myDjName != null && !myDjName.isEmpty()) {
                for (Map<String, Object> p : players) {
                    if (myDjName.equals(p.get("djName"))) {
                        Object rankObj = p.get("rank");
                        Object ptObj = p.get("totalPt");
                        Object clsObj = p.get("arenaClass");
                        // JSON 由来は Integer/Long 混在の可能性があるため Number で受ける
                        if (rankObj instanceof Number) myRank = ((Number) rankObj).intValue();
                        if (ptObj instanceof Number) myTotalPt = ((Number) ptObj).intValue();
                        if (clsObj instanceof String) myArenaClass = (String) clsObj;
                        break;
                    }
                }
            }

            map.put("myArenaClass", myArenaClass);
            map.put("myRank", myRank);
            map.put("myTotalPt", myTotalPt);

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 指定 ID の対戦を削除する（所有者チェックあり）。
     *
     * 他人のデータを消せないよう、対戦の所有者が認証ユーザーと一致することを確認する。
     *
     * @param auth 認証情報
     * @param id   削除対象の ArenaMatch ID
     */
    @DeleteMapping("/matches/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteMatch(
            Authentication auth,
            @PathVariable Long id) {

        User user = getUser(auth);
        ArenaMatch match = arenaMatchRepository.findById(id)
                .orElse(null);

        // 見つからない or 所有者が違う場合は一律 403（存在を露呈しない）
        if (match == null || !match.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("message", "権限がありません"));
        }

        arenaMatchRepository.delete(match);
        return ResponseEntity.ok(Map.of("message", "削除しました"));
    }

    /**
     * 認証情報から User を解決する共通ヘルパ。
     *
     * @param auth 認証情報
     * @return ユーザーエンティティ
     */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        return userRepository.findByIidxId((String) auth.getPrincipal())
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    // ── DTO ───────────────────────────────────────────────
    //   インポート時に受け取る階層構造を表すレコード群。

    /**
     * インポート API のトップレベルリクエストボディ。
     *
     * @param myDjName 自分の DJ 名（対戦ログから自分の行を特定するため）
     * @param year     対戦日の年（ブックマークレット由来の日付に年が無いため外挿する）
     * @param battles  対戦配列
     */
    public record ImportRequest(
            String myDjName,
            String year,
            List<BattleData> battles) {}

    /**
     * 1 対戦分のデータ。
     *
     * @param battleType 対戦タイプ（例: "CLASS MATCH"）
     * @param date       対戦日時（"MM/dd HH:mm"）
     * @param songs      プレイ楽曲一覧
     * @param players    参加プレイヤー一覧（最大 4 人）
     */
    public record BattleData(
            String battleType,
            String date,
            List<SongData> songs,
            List<PlayerData> players) {}

    /**
     * 対戦で使われた楽曲情報。
     *
     * @param title      曲名
     * @param difficulty 難易度（例: "ANOTHER"）
     */
    public record SongData(String title, String difficulty) {}

    /**
     * 対戦参加プレイヤー 1 名分の情報。
     *
     * @param djName     DJ 名
     * @param arenaClass アリーナクラス（例: "A1"）
     * @param totalPt    対戦合計ポイント
     * @param rank       対戦内順位（1〜4）
     * @param songScores 曲ごとのスコアと獲得ポイント
     */
    public record PlayerData(
            String djName,
            String arenaClass,
            int totalPt,
            int rank,
            List<SongScore> songScores) {}

    /**
     * 楽曲別スコアと獲得ポイント。
     *
     * @param score 素点
     * @param pt    対戦ルールで得られたポイント
     */
    public record SongScore(int score, int pt) {}
}
