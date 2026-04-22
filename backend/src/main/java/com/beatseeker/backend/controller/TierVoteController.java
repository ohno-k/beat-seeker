package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.TierVote;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.TierVoteRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.Collections;

/**
 * 【クラスの役割】 譜面の「ティア昇降格」投票を扱うコントローラ。
 *
 * コミュニティ主導の難易度表運用のため、ユーザー各自が以下のいずれかを投票できる:
 *  - "PROMOTE" : 今のティアより上へ昇格させるべき
 *  - "DEMOTE"  : 今のティアより下へ降格させるべき
 *  - "STAY"    : 今のティアが妥当
 *  - "11.0" ～ "13.0"（0.1 刻み）: 未分類譜面を具体的に tier 値で配置する提案
 *
 * 管理者はこの集計結果を参考にドラフト難易度表を調整する。
 *
 * 主要エンドポイント:
 *  - {@code POST   /api/tier-votes}       … 投票（upsert）
 *  - {@code DELETE /api/tier-votes}       … 自分の投票取消
 *  - {@code GET    /api/tier-votes/all}   … 全譜面の集計結果（公開）
 *  - {@code GET    /api/tier-votes/mine}  … ログインユーザー自身の投票一覧
 */
@RestController
@RequestMapping("/api/tier-votes")
public class TierVoteController {

    /** 受理可能な投票値のセット。不正な文字列を弾く入力バリデーションに使う。 */
    private static final Set<String> VALID_VOTES = buildValidVotes();

    /**
     * {@link #VALID_VOTES} を初期化するヘルパ。
     * 基本 3 種（PROMOTE/DEMOTE/STAY）に加え、未分類譜面向けの tier 値（11.0〜13.0）を 0.1 刻みで追加する。
     *
     * @return イミュータブルな投票候補セット
     */
    private static Set<String> buildValidVotes() {
        Set<String> votes = new HashSet<>(Set.of("PROMOTE", "DEMOTE", "STAY"));
        // Uncategorized 譜面向けの tier 配置値（11.0 〜 13.0、0.1 刻み）
        for (int i = 110; i <= 130; i++) {
            votes.add(String.format("%.1f", i / 10.0));
        }
        return Collections.unmodifiableSet(votes);
    }

    /** 投票エンティティの永続化 Repository。 */
    private final TierVoteRepository tierVoteRepository;
    /** 認証ユーザー解決用 Repository。 */
    private final UserRepository userRepository;

    /**
     * 【コンストラクタ】 Spring が DI で Repository を注入する。
     */
    public TierVoteController(TierVoteRepository tierVoteRepository, UserRepository userRepository) {
        this.tierVoteRepository = tierVoteRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 譜面にティア投票を行う（既存票があれば更新）。
     *
     * 入力値が {@link #VALID_VOTES} にない場合は 400 を返す。
     *
     * @param auth    認証情報
     * @param request {@code (title, difficultyName, vote)} を含むリクエストボディ
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> vote(Authentication auth, @RequestBody VoteRequest request) {
        Long userId = getUserId(auth);

        // 許可値以外を弾く（不正な投票値の混入防止）
        if (!VALID_VOTES.contains(request.vote())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid vote value"));
        }

        Optional<TierVote> existing = tierVoteRepository
                .findByUserIdAndTitleAndDifficultyName(userId, request.title(), request.difficultyName());

        TierVote vote;
        if (existing.isPresent()) {
            // 既存票を上書き（投票時刻も更新）
            vote = existing.get();
            vote.setVote(request.vote());
            vote.setVotedAt(LocalDateTime.now());
        } else {
            // 新規作成
            vote = new TierVote();
            vote.setUserId(userId);
            vote.setTitle(request.title());
            vote.setDifficultyName(request.difficultyName());
            vote.setVote(request.vote());
            vote.setVotedAt(LocalDateTime.now());
        }

        tierVoteRepository.save(vote);
        return ResponseEntity.ok(Map.of("message", "投票しました"));
    }

    /**
     * 【メソッドの役割】 自分のティア投票を取り消す。
     *
     * 投票が存在しなくてもエラーにはしない（冪等）。
     *
     * @param auth           認証情報
     * @param title          曲名
     * @param difficultyName 難易度名
     */
    @DeleteMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteVote(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {

        Long userId = getUserId(auth);
        tierVoteRepository.findByUserIdAndTitleAndDifficultyName(userId, title, difficultyName)
                .ifPresent(tierVoteRepository::delete);

        return ResponseEntity.ok(Map.of("message", "投票を取り消しました"));
    }

    /**
     * 【メソッドの役割】 全譜面の投票集計を返す（公開エンドポイント）。
     *
     * Repository からは行単位で {title, difficultyName, vote, count} が返ってくるため、
     * 譜面ごとにグルーピングして {@code {title, difficultyName, PROMOTE: n, DEMOTE: m, ...}} の
     * 形にまとめ直す。
     *
     * @return 譜面ごとの集計 Map のリスト
     */
    @GetMapping("/all")
    public ResponseEntity<List<Map<String, Object>>> getAllVotes() {
        List<Map<String, Object>> raw = tierVoteRepository.findAggregatedVotes();

        // (title, difficultyName) でキー化して集約する
        Map<String, Map<String, Object>> songMap = new LinkedHashMap<>();

        for (Map<String, Object> row : raw) {
            String title = (String) row.get("title");
            String difficultyName = (String) row.get("difficultyName");
            String voteType = (String) row.get("vote");
            int count = ((Number) row.get("count")).intValue();

            // 区切り文字 "|" でユニークキーを作る（曲名に "|" が混ざらない想定）
            String key = title + "|" + difficultyName;
            songMap.computeIfAbsent(key, k -> {
                Map<String, Object> m = new HashMap<>();
                m.put("title", title);
                m.put("difficultyName", difficultyName);
                return m;
            });
            // 同じ譜面に別の voteType を追加書き込み
            songMap.get(key).put(voteType, count);
        }

        return ResponseEntity.ok(new ArrayList<>(songMap.values()));
    }

    /**
     * 【メソッドの役割】 ログインユーザー自身の全投票を返す。
     *
     * フロントは自分の投票状態をハイライト表示するためにこれを使う。
     *
     * @param auth 認証情報
     * @return 自分が投票した譜面と投票値のリスト
     */
    @GetMapping("/mine")
    public ResponseEntity<List<Map<String, Object>>> getMyVotes(Authentication auth) {
        Long userId = getUserId(auth);
        List<TierVote> votes = tierVoteRepository.findByUserId(userId);

        List<Map<String, Object>> result = votes.stream().map(v -> {
            Map<String, Object> m = new HashMap<>();
            m.put("title", v.getTitle());
            m.put("difficultyName", v.getDifficultyName());
            m.put("vote", v.getVote());
            return m;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 認証情報からユーザー ID を取り出す共通ヘルパ。
     *
     * @param auth 認証情報
     * @return ユーザー ID
     */
    private Long getUserId(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new RuntimeException("Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        User user = userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return user.getId();
    }

    /**
     * 【DTO】 ティア投票 API のリクエストボディ。
     *
     * @param title          曲名
     * @param difficultyName 難易度名
     * @param vote           投票値（PROMOTE/DEMOTE/STAY、または "11.0"〜"13.0"）
     */
    public record VoteRequest(String title, String difficultyName, String vote) {}
}
