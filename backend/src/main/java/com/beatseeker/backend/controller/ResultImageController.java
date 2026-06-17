package com.beatseeker.backend.controller;

import com.beatseeker.backend.entity.ResultImage;
import com.beatseeker.backend.entity.User;
import com.beatseeker.backend.repository.ResultImageRepository;
import com.beatseeker.backend.repository.UserRepository;
import com.beatseeker.backend.service.R2StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 【クラスの役割】 楽曲詳細のリザルト画像（スクリーンショット）を管理する REST コントローラ。
 *
 * 譜面の同定は (曲名 title, 難易度名 difficultyName) の組で行う。
 * 画像本体は {@link R2StorageService} 経由で Cloudflare R2 に保存し、DB（{@link ResultImage}）には
 * オブジェクトキーとメタ情報のみを持つ。一覧取得時は短時間有効の署名付き GET URL を返す。
 *
 * エンドポイント:
 *  - GET    /api/result-images?title=&difficultyName=   … 指定譜面の画像一覧（署名付き URL 付き）
 *  - POST   /api/result-images                          … 画像 1 枚を multipart で登録
 *  - DELETE /api/result-images/{id}                     … 自分の画像を削除
 *
 * 認可: {@code /api/result-images/**} は SecurityConfig で要ログインに設定。各操作は本人のデータのみ対象。
 */
@RestController
@RequestMapping("/api/result-images")
public class ResultImageController {

    private static final Logger log = LoggerFactory.getLogger(ResultImageController.class);

    /** 受け付ける画像の最大バイト数（クライアントで圧縮済みを想定。余裕を見て 5MB）。 */
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    /** 1 譜面あたりの登録上限枚数。 */
    private static final long MAX_PER_CHART = 30;
    /** 許可する MIME タイプ。 */
    private static final Set<String> ALLOWED_TYPES = Set.of("image/webp", "image/jpeg", "image/png");

    private final ResultImageRepository resultImageRepository;
    private final UserRepository userRepository;
    private final R2StorageService storage;

    public ResultImageController(ResultImageRepository resultImageRepository,
                                 UserRepository userRepository,
                                 R2StorageService storage) {
        this.resultImageRepository = resultImageRepository;
        this.userRepository = userRepository;
        this.storage = storage;
    }

    /** 指定譜面のリザルト画像一覧を、署名付き GET URL 付きで返す。 */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName) {
        User user = getUser(auth);
        List<ResultImage> images = resultImageRepository
                .findByUserAndTitleAndDifficultyNameOrderByUploadedAtAsc(user, title, difficultyName);
        List<Map<String, Object>> result = images.stream().map(this::toDto).toList();
        return ResponseEntity.ok(result);
    }

    /** リザルト画像を 1 枚アップロードする（multipart/form-data）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> upload(
            Authentication auth,
            @RequestParam String title,
            @RequestParam String difficultyName,
            @RequestParam(required = false) Integer difficultyLevel,
            @RequestParam(required = false) Integer width,
            @RequestParam(required = false) Integer height,
            @RequestParam("file") MultipartFile file) {
        User user = getUser(auth);

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ファイルが空です。");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "画像サイズが大きすぎます（上限 5MB）。");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "画像形式は webp/jpeg/png のみ対応しています。");
        }
        long count = resultImageRepository.countByUserAndTitleAndDifficultyName(user, title, difficultyName);
        if (count >= MAX_PER_CHART) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "1 譜面あたりの登録上限（" + MAX_PER_CHART + " 枚）に達しています。");
        }

        String ext = switch (contentType) {
            case "image/webp" -> "webp";
            case "image/png" -> "png";
            default -> "jpg";
        };
        String key = "results/" + user.getId() + "/" + UUID.randomUUID() + "." + ext;

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "画像の読み込みに失敗しました。");
        }
        // R2 へ先にアップロードし、成功してから DB 行を作る（オーファン行を残さない）。
        storage.upload(key, bytes, contentType);

        ResultImage img = new ResultImage();
        img.setUser(user);
        img.setTitle(title);
        img.setDifficultyName(difficultyName);
        img.setDifficultyLevel(difficultyLevel);
        img.setObjectKey(key);
        img.setContentType(contentType);
        img.setSizeBytes(file.getSize());
        img.setWidth(width);
        img.setHeight(height);
        resultImageRepository.save(img);

        return ResponseEntity.ok(toDto(img));
    }

    /** 自分のリザルト画像を 1 枚削除する。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        User user = getUser(auth);
        ResultImage img = resultImageRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "画像が見つかりません。"));
        try {
            storage.delete(img.getObjectKey());
        } catch (ResponseStatusException e) {
            // ストレージ未設定（503）などはそのまま伝播。
            throw e;
        } catch (Exception e) {
            // ストレージ削除に失敗しても DB 行は消す（UX 優先）。オブジェクトは孤児として残るためログに残す。
            log.warn("R2 オブジェクト削除に失敗しました（DB 行は削除します）: key={}", img.getObjectKey(), e);
        }
        resultImageRepository.delete(img);
        return ResponseEntity.noContent().build();
    }

    /** エンティティ → レスポンス DTO（署名付き URL を都度発行）。 */
    private Map<String, Object> toDto(ResultImage img) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", img.getId());
        m.put("url", storage.presignGet(img.getObjectKey()));
        m.put("width", img.getWidth());
        m.put("height", img.getHeight());
        m.put("uploadedAt", img.getUploadedAt() != null
                ? img.getUploadedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        return m;
    }

    /** JWT の principal（iidxId）から現在ログイン中ユーザーを解決する。 */
    private User getUser(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        String iidxId = (String) auth.getPrincipal();
        return userRepository.findByIidxId(iidxId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
