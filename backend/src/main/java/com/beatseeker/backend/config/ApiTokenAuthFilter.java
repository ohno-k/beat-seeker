package com.beatseeker.backend.config;

import com.beatseeker.backend.entity.ExternalApiToken;
import com.beatseeker.backend.repository.ExternalApiTokenRepository;
import com.beatseeker.backend.service.ExternalApiTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

/**
 * 【クラスの役割】 外部公開 API（{@code /api/external/**}）専用の Bearer 認証フィルタ。
 *
 * 役割は {@link JwtAuthFilter} と類似だが、検証対象が JWT ではなく
 * {@link ExternalApiToken}（ユーザーが発行する個人 API トークン）の SHA-256 ハッシュ突合。
 *
 * 処理の流れ:
 *  1. リクエストパスが {@code /api/external/} で始まるときだけ処理。それ以外は素通し。
 *  2. {@code Authorization: Bearer <token>} を取り出す。
 *  3. 平文を {@link ExternalApiTokenService#hash(String)} で SHA-256 ハッシュ化。
 *  4. {@link ExternalApiTokenRepository#findByTokenHash(String)} で DB から引く。
 *  5. 未失効・期限内なら、所有ユーザーの {@code iidxId} を principal として
 *     SecurityContext に積む。これにより通常の {@link JwtAuthFilter} と同じ形で
 *     コントローラから {@code Authentication} を読み出せる。
 *  6. {@code lastUsedAt} を更新（簡易監視）。
 *  7. 失敗時は何もしない（後段の認可で 401 が返る）。
 *
 * セキュリティ方針:
 *  - DB には平文を保存しないので、リクエスト側の平文を毎回ハッシュ化して突合する。
 *  - 失効済み／期限切れトークンは認証成功とみなさない。
 *  - JWT 検証フィルタとは独立しており、両者の効果が混ざらないようパスで分離している。
 */
@Component
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    /** 外部 API のパスプレフィックス。これに一致した場合のみ本フィルタが動く。 */
    private static final String EXTERNAL_PATH_PREFIX = "/api/external/";

    private final ExternalApiTokenRepository tokenRepository;
    private final ExternalApiTokenService tokenService;

    public ApiTokenAuthFilter(ExternalApiTokenRepository tokenRepository,
                              ExternalApiTokenService tokenService) {
        this.tokenRepository = tokenRepository;
        this.tokenService = tokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 手順1: 対象パス以外は何もしない（JwtAuthFilter にそのまま任せる）。
        String path = request.getRequestURI();
        if (path == null || !path.startsWith(EXTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 手順2: Authorization ヘッダから平文トークンを取り出す。
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String plain = authHeader.substring(7).trim();
            if (!plain.isEmpty()) {
                // 手順3〜4: SHA-256 ハッシュで DB を検索。
                String hash = tokenService.hash(plain);
                Optional<ExternalApiToken> opt = tokenRepository.findByTokenHash(hash);
                if (opt.isPresent() && isActive(opt.get())) {
                    ExternalApiToken token = opt.get();
                    // 手順5: 所有ユーザーの iidxId を principal にして SecurityContext へ。
                    String iidxId = token.getUser().getIidxId();
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            iidxId, null, Collections.emptyList());
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // 手順6: 監視用に lastUsedAt を更新。失敗しても認証自体は通す。
                    try {
                        token.setLastUsedAt(LocalDateTime.now());
                        tokenRepository.save(token);
                    } catch (Exception ignored) {
                        // last_used_at 更新失敗はリクエスト本体の処理を阻害しない。
                    }
                }
            }
        }

        // 手順7: 認証成否に関わらずチェインを進める。後段の認可で 401 を返す。
        filterChain.doFilter(request, response);
    }

    /** トークンが現在有効か（未失効かつ期限内）。 */
    private boolean isActive(ExternalApiToken t) {
        if (t.getRevokedAt() != null) return false;
        if (t.getExpiresAt() != null && t.getExpiresAt().isBefore(LocalDateTime.now())) return false;
        return true;
    }
}
