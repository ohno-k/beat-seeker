package com.beatseeker.backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * 【クラスの役割】 全リクエストに対して JWT を検証し、SecurityContext へ認証情報を載せるフィルタ。
 *
 * Spring Security のフィルタチェインに {@link SecurityConfig} から登録され、
 * {@code UsernamePasswordAuthenticationFilter} の直前に差し込まれる。
 *
 * 処理の流れ:
 *  1. リクエストの {@code Authorization} ヘッダを見る
 *  2. {@code Bearer <token>} 形式なら、JWT を {@link JwtUtil#validateToken(String)} で検証
 *  3. 検証 OK なら iidxId を取り出し、{@link SecurityContextHolder} に
 *     {@link UsernamePasswordAuthenticationToken} を格納する
 *  4. 検証 NG またはヘッダ未設定の場合は何もせずに次のフィルタへ進む
 *     （= 認証が必要なエンドポイントなら後段で 401 が返る）
 *
 * {@link OncePerRequestFilter} を継承しているため、
 * 同一リクエスト内で複数回 dispatch されても doFilterInternal は一度しか呼ばれない。
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** JWT の発行・検証を担当するユーティリティ。コンストラクタ注入。 */
    private final JwtUtil jwtUtil;

    /**
     * 【コンストラクタ】 Spring の DI コンテナが {@link JwtUtil} を渡して初期化する。
     *
     * @param jwtUtil JWT 検証処理を委譲する先のユーティリティ Bean
     */
    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    /**
     * 【メソッドの役割】 1 リクエストにつき 1 回だけ呼ばれ、JWT を検査して認証情報を紐付ける。
     *
     * 認証に失敗してもここでは例外を投げず、単に SecurityContext を未設定のままにする。
     * 後続の認可（authorizeHttpRequests）で未認証と判断された場合のみ 401 が返る。
     *
     * @param request     HTTP リクエスト
     * @param response    HTTP レスポンス
     * @param filterChain 後続フィルタへ処理を委譲するためのチェイン
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // 手順1: Authorization ヘッダを取得（存在しなければ null）。
        String authHeader = request.getHeader("Authorization");

        // 手順2: "Bearer " で始まる形式のときだけ JWT として処理する。
        //        それ以外（Basic 認証や未設定など）はスキップして次フィルタへ。
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            // "Bearer " の 7 文字を取り除いて生のトークン文字列を得る。
            String token = authHeader.substring(7);
            // 手順3: 署名・有効期限をチェック。改ざん済みや期限切れは false で弾かれる。
            if (jwtUtil.validateToken(token)) {
                // 手順4: subject として埋め込まれている iidxId を取り出す。
                String iidxId = jwtUtil.getIidxIdFromToken(token);
                // 手順5: Spring Security が参照する Authentication を組み立てる。
                //        principal に iidxId、credentials は null、権限は空リスト。
                //        コントローラ側では `@AuthenticationPrincipal String iidxId` で取得可能。
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        iidxId, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }

        // 手順6: 認証成否にかかわらずチェインを進める。認可判断は後段で行われる。
        filterChain.doFilter(request, response);
    }
}
