package com.beatseeker.backend.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * 【クラスの役割】 JWT（JSON Web Token）の発行・検証を担当するユーティリティ。
 *
 * beat-seeker では、ログイン成功時にこのクラスで発行した JWT を
 * クライアント（ブラウザ）へ返し、以降のリクエストは `Authorization: Bearer <token>`
 * ヘッダに載せられた JWT を {@link JwtAuthFilter} が検証してユーザーを特定する。
 *
 * 主な責務:
 *  - {@link #generateToken(String)} … iidxId（ユーザー識別子）から署名付き JWT を発行
 *  - {@link #getIidxIdFromToken(String)} … JWT から署名を検証しつつ iidxId を取り出す
 *  - {@link #validateToken(String)} … JWT が改ざんされていないか／期限切れでないかをチェック
 *
 * 依存:
 *  - io.jsonwebtoken（jjwt）: JWT のビルド・検証ライブラリ
 *  - application.yml の `jwt.secret` / `jwt.expiration-ms` 設定（環境変数で上書き可能）
 *
 * スレッド安全性: フィールドはすべて `final`。Spring の `@Component` により
 *   シングルトンとしてインスタンス化され、各リクエストから並行アクセスされても安全。
 */
@Component
public class JwtUtil {

    /**
     * JWT 署名・検証に使う HMAC-SHA 鍵。
     * コンストラクタで application.yml の文字列から生成され、以降は不変。
     */
    private final SecretKey key;

    /**
     * トークンの有効期限（ミリ秒）。デフォルト 604800000ms = 7日間。
     * application.yml の `jwt.expiration-ms` で上書き可能。
     */
    private final long expirationMs;

    /**
     * 【コンストラクタ】 Spring が application.yml の設定値を注入して初期化する。
     *
     * @param secret       JWT 署名用の秘密鍵。本番では必ず環境変数で上書きすること。
     *                     デフォルト値はあくまで開発用のフォールバック。
     * @param expirationMs トークン有効期限（ミリ秒）。デフォルトは 7 日間。
     */
    public JwtUtil(
            @Value("${jwt.secret:beatseeker-default-secret-key-change-in-production-at-least-256-bits!!}") String secret,
            @Value("${jwt.expiration-ms:604800000}") long expirationMs) { // デフォルト 7 日間
        // 秘密文字列を UTF-8 バイト列に変換し、HMAC-SHA 鍵オブジェクトを構築する。
        // Keys.hmacShaKeyFor は 256bit 以上のキー長を要求するため、
        // application.yml のデフォルト値はそれを満たす長さに設定してある。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * 【メソッドの役割】 iidxId を subject に埋め込んだ JWT を新規発行する。
     *
     * ログイン成功時に {@code AuthController} から呼ばれ、
     * 生成された文字列がクライアントに返される。
     *
     * @param iidxId JWT の subject として埋め込むユーザー識別子（例: "1234-5678"）
     * @return 署名済み JWT 文字列（ヘッダ.ペイロード.署名 の Base64URL エンコード）
     */
    public String generateToken(String iidxId) {
        return Jwts.builder()
                .subject(iidxId)                                              // sub クレームに iidxId をセット
                .issuedAt(new Date())                                         // iat クレーム（発行時刻）
                .expiration(new Date(System.currentTimeMillis() + expirationMs)) // exp クレーム（有効期限）
                .signWith(key)                                                // HMAC-SHA で署名
                .compact();                                                   // 最終的な JWT 文字列を返す
    }

    /**
     * 【メソッドの役割】 JWT を検証したうえで subject（iidxId）を取り出す。
     *
     * 署名が不正だったり期限切れの場合は {@code JwtException} 派生の例外が
     * 投げられる点に注意。本メソッドは「有効な JWT である前提」で呼ばれる想定。
     *
     * @param token クライアントから送られてきた JWT 文字列
     * @return JWT の subject クレーム（= iidxId）
     */
    public String getIidxIdFromToken(String token) {
        // parser() → verifyWith(key) で署名検証、build() でパーサー確定、
        // parseSignedClaims(token) で実際にトークンをパースしつつ検証する。
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        // subject には generateToken() で埋め込んだ iidxId が入っている。
        return claims.getSubject();
    }

    /**
     * 【メソッドの役割】 JWT が「有効」かどうかを真偽値で返す軽量チェック。
     *
     * {@link JwtAuthFilter} が毎リクエスト呼ぶため、
     * パース失敗時に例外を投げず false を返す実装にしている。
     *
     * @param token 検証対象の JWT 文字列
     * @return 署名が正しく、かつ期限切れでもない場合 true。それ以外は false。
     */
    public boolean validateToken(String token) {
        try {
            // パースが成功した時点で「署名 OK」「期限内」の両方が確認できる。
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            // 署名不正・期限切れ・フォーマット異常など、すべての失敗を false に畳む。
            // ここで握り潰しても、呼び出し元が 401 を返す責務を持つので問題ない。
            return false;
        }
    }
}
