package com.beatseeker.backend.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 【Service の役割】 外部 API トークンの生成・ハッシュ化を担うユーティリティサービス。
 *
 * トークン本体（平文）は DB に保存せず、SHA-256 ハッシュのみを保管する方針。
 * 認証時はリクエスト側の Bearer 値を {@link #hash(String)} で同じハッシュにかけて突合する。
 *
 * 平文の構造:
 *   {@code bs_live_<32文字のBase64URL>}
 * 前置詞 {@code bs_live_} は「これは beat-seeker の本番用トークン」と一目で分かるための prefix。
 * 漏洩検知（GitHub secret scanning 等）のしくみを後から導入しやすくする狙いもある。
 */
@Service
public class ExternalApiTokenService {

    /** 暗号学的乱数源。スレッドセーフ。 */
    private final SecureRandom random = new SecureRandom();

    /** トークンの先頭固定文字列。 */
    private static final String PREFIX = "bs_live_";

    /** 生成するランダム部分のバイト長（Base64URL 化後はだいたい 32 文字弱）。 */
    private static final int RANDOM_BYTES = 24;

    /**
     * 【メソッドの役割】 新しいトークン平文を生成する。
     *
     * 形式: {@code bs_live_<base64url>}。Base64URL（パディング無し）なので URL に
     * そのまま貼れる文字種だけで構成される。
     *
     * @return 平文トークン
     */
    public String generatePlainToken() {
        byte[] buf = new byte[RANDOM_BYTES];
        random.nextBytes(buf);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        return PREFIX + body;
    }

    /**
     * 【メソッドの役割】 平文トークンを SHA-256 で 16 進文字列にハッシュ化する。
     *
     * @param plain 平文トークン
     * @return 64 文字の 16 進ハッシュ
     */
    public String hash(String plain) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    /**
     * 【メソッドの役割】 一覧表示用に末尾 4 文字だけ抜き出した識別子を返す。
     *
     * @param plain 平文トークン
     * @return 末尾 4 文字（短すぎる場合は全体）
     */
    public String prefixForDisplay(String plain) {
        if (plain == null || plain.length() <= 4) return plain;
        return plain.substring(plain.length() - 4);
    }
}
