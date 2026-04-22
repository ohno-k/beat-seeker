package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.TierComment;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.TierCommentRepository;
import com.beatseeker.backend.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 【クラスの役割】 譜面（chart = title × difficultyName）へのコメント機能を担うコントローラ。
 *
 * プレイヤー同士が「この譜面は○○が難しい」「○○のオプションで捌ける」といった
 * ティップスを交換するために使う掲示板的な機能。
 *
 * 主要エンドポイント:
 *  - {@code GET    /api/tier-comments/stats}  … 全譜面のコメント件数集計（一覧バッジ表示用）
 *  - {@code GET    /api/tier-comments}        … 特定譜面のコメント一覧
 *  - {@code POST   /api/tier-comments}        … コメント投稿
 *  - {@code DELETE /api/tier-comments/{id}}   … 自分のコメントを削除
 *
 * バリデーション: 空文字禁止、1000 文字上限。
 */
@RestController
@RequestMapping("/api/tier-comments")
public class TierCommentController {

    /** コメントエンティティの永続化 Repository。 */
    private final TierCommentRepository tierCommentRepository;
    /** 投稿者情報を引くための User Repository。 */
    private final UserRepository userRepository;

    /**
     * 【コンストラクタ】 Spring が DI で Repository を注入する。
     */
    public TierCommentController(TierCommentRepository tierCommentRepository, UserRepository userRepository) {
        this.tierCommentRepository = tierCommentRepository;
        this.userRepository = userRepository;
    }

    /**
     * 【メソッドの役割】 全譜面のコメント集計（件数＋最新投稿日時）を返す。
     *
     * 難易度表一覧ページで各譜面の行に「コメント N 件」バッジを表示するために使われる。
     *
     * @return 譜面ごとの {title, difficultyName, count, maxCreatedAt} を含む Map のリスト
     */
    @GetMapping("/stats")
    public ResponseEntity<List<Map<String, Object>>> getCommentStats() {
        return ResponseEntity.ok(tierCommentRepository.findCommentStats());
    }

    /**
     * 【メソッドの役割】 特定譜面のコメント一覧を返す。
     *
     * コメント本体＋投稿者の {@code totalBeatPt}（レート表示用）を併せて返すため、
     * 一旦コメント一覧を取った後、関連ユーザーをまとめてロードし Map に詰め直す。
     *
     * @param title          曲名
     * @param difficultyName 難易度名
     * @return コメント配列（古い順）
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getComments(
            @RequestParam String title,
            @RequestParam String difficultyName) {

        List<TierComment> comments = tierCommentRepository.findByTitleAndDifficultyNameOrderByCreatedAtAsc(title, difficultyName);

        // 関連ユーザーを N+1 回引かないよう、まとめて一度で取得してマップ化する
        Set<Long> userIds = comments.stream().map(TierComment::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        List<Map<String, Object>> result = comments.stream().map(c -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", c.getId());
            map.put("userId", c.getUserId());
            User u = userMap.get(c.getUserId());
            // 投稿者が退会等で見つからない場合は 0.0 にフォールバック
            map.put("totalBeatPt", u != null ? u.getTotalBeatPt() : 0.0);
            map.put("title", c.getTitle());
            map.put("difficultyName", c.getDifficultyName());
            map.put("content", c.getContent());
            map.put("createdAt", c.getCreatedAt());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    /**
     * 【メソッドの役割】 新規コメントを投稿する。
     *
     * バリデーション:
     *  - 空文字 or 空白のみ → 400
     *  - 1000 文字超        → 400
     *
     * @param auth    認証情報
     * @param request {@code (title, difficultyName, content)} を含むリクエストボディ
     */
    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> postComment(
            Authentication auth,
            @RequestBody CommentRequest request) {

        Long userId = getUserId(auth);

        // 空文字コメントを弾く（トリム後判定）
        if (request.content() == null || request.content().trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment content cannot be empty"));
        }

        // 長すぎるコメントを弾く（DB 側のカラム長・UI 表示の観点）
        if (request.content().length() > 1000) {
            return ResponseEntity.badRequest().body(Map.of("error", "Comment is too long (max 1000 chars)"));
        }

        TierComment comment = new TierComment();
        comment.setUserId(userId);
        comment.setTitle(request.title());
        comment.setDifficultyName(request.difficultyName());
        comment.setContent(request.content().trim());
        comment.setCreatedAt(LocalDateTime.now());

        tierCommentRepository.save(comment);

        return ResponseEntity.ok(Map.of("message", "Comment posted successfully"));
    }

    /**
     * 【メソッドの役割】 自分のコメントを削除する。
     *
     * 所有者チェック: 認証ユーザーと投稿者が一致しない場合は 403。
     *
     * @param auth 認証情報
     * @param id   削除対象のコメント ID
     */
    @DeleteMapping("/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteComment(
            Authentication auth,
            @PathVariable Long id) {

        Long userId = getUserId(auth);

        Optional<TierComment> commentOpt = tierCommentRepository.findById(id);
        if (commentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        TierComment comment = commentOpt.get();
        // 他人のコメントを消せないよう所有者チェック
        if (!comment.getUserId().equals(userId)) {
             return ResponseEntity.status(403).body(Map.of("error", "Not authorized to delete this comment"));
        }

        tierCommentRepository.delete(comment);
        return ResponseEntity.ok(Map.of("message", "Comment deleted"));
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
     * 【DTO】 コメント投稿リクエストのボディ。
     *
     * @param title          曲名
     * @param difficultyName 難易度名
     * @param content        コメント本文（空不可、1000 文字以内）
     */
    public record CommentRequest(String title, String difficultyName, String content) {}
}
