package com.beatseeker.backend;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// UNUSED: 初期疎通確認用。`/api/test-root` は本番不要。詳細は UNUSED.md を参照。
/**
 * 【クラスの役割】 アプリ初期化時の疎通確認専用のルートコントローラ。
 *
 * 責務:
 *  - {@code /api/test-root} に GET して文字列を返すだけの簡易エンドポイントを提供
 *  - Spring Boot の `@RestController` が正しくスキャン／起動されているかの確認に使う
 *
 * 依存: なし（リポジトリ・サービスを一切持たない）
 *
 * 主なエンドポイント:
 *  - GET /api/test-root … 生存確認用のプレーンテキストを返す
 *
 * 注意: 本番利用しない。将来削除予定のため UNUSED マーカー付き。
 */
@RestController
public class TestController {
    /**
     * 【メソッドの役割】 固定文字列を返すだけの疎通確認エンドポイント。
     *
     * @return "Root controller is active" の固定文字列
     */
    @GetMapping("/api/test-root")
    public String test() {
        return "Root controller is active";
    }
}
