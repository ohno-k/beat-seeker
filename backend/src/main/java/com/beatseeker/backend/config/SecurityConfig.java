package com.beatseeker.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * 【クラスの役割】 Spring Security のフィルタチェインと CORS/パスワードエンコーダを構成するコンフィグクラス。
 *
 * beat-seeker のバックエンド側セキュリティポリシーを一元管理する。
 *
 * 主な設定内容:
 *  - CORS: フロントエンド（開発時は http://localhost:5173、本番は FRONTEND_URL 環境変数）からのアクセスを許可
 *  - CSRF: 無効化（JWT + ステートレス運用なのでセッション依存の CSRF トークンは不要）
 *  - セッション管理: STATELESS（サーバー側セッションを持たず、各リクエストを JWT で認証）
 *  - 認可ルール: パスごとに permitAll() / authenticated() を細かく振り分け
 *  - 認証失敗時: 401 Unauthorized を返す
 *  - カスタムフィルタ: {@link JwtAuthFilter} を UsernamePasswordAuthenticationFilter の前に挿入
 *  - パスワード: BCrypt でハッシュ化
 *
 * 依存:
 *  - {@link JwtAuthFilter} … 毎リクエストで JWT を検証するフィルタ
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

        /**
         * フロントエンドの URL。CORS の Allowed-Origins に使う。
         * 本番では Render などの環境変数 {@code FRONTEND_URL} で上書きされる。
         */
        @Value("${FRONTEND_URL:http://localhost:5173}")
        private String frontendUrl;

        /** 毎リクエスト JWT を検証するカスタムフィルタ。DI 経由で注入される。 */
        private final JwtAuthFilter jwtAuthFilter;

        /**
         * 【コンストラクタ】 {@link JwtAuthFilter} を Spring が注入して初期化する。
         *
         * @param jwtAuthFilter JWT 検証フィルタ Bean
         */
        public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
                this.jwtAuthFilter = jwtAuthFilter;
        }

        /**
         * 【Bean の役割】 パスワードハッシュ化用の {@link PasswordEncoder} を公開する。
         *
         * ユーザー登録時・ログイン時のパスワード比較で使われる。
         * BCrypt は強度調整付きソルト内包ハッシュなので、ユーザーごとに異なるハッシュ値が得られる。
         *
         * @return BCrypt エンコーダ
         */
        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        /**
         * 【Bean の役割】 Spring Security のフィルタチェインを構築する。
         *
         * エンドポイントごとの認可ルールや CORS/CSRF/セッション設定をここで束ねる。
         *
         * @param http Spring Security の HttpSecurity ビルダ
         * @return 構築済みの {@link SecurityFilterChain}
         * @throws Exception ビルダ内部で発生する例外
         */
        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

                http
                                // CORS: 別途定義した corsConfigurationSource() を使う
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                // CSRF: JWT + ステートレス運用のため不要 → 無効化
                                .csrf(csrf -> csrf.disable())
                                // セッション: サーバー側に状態を持たない STATELESS モード
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                // ここから認可ルール: マッチした順にチェックされる点に注意
                                .authorizeHttpRequests(auth -> auth
                                                // 曲データ・難易度表などのマスターデータ参照はログイン不要
                                                .requestMatchers("/api/game-data/**").permitAll()
                                                // ランキング・集計系の GET 公開 API（未ログインでも閲覧可）
                                                .requestMatchers("/api/scores/ranking", "/api/scores/rate-ranking",
                                                                "/api/scores/ranking/arena-averages",
                                                                "/api/scores/ranking/top-rankers",
                                                                "/api/scores/ranking/by-rank",
                                                                "/api/scores/rate-ranking/arena-averages",
                                                                "/api/scores/rate-ranking/top-rankers",
                                                                "/api/scores/song-top-rankers",
                                                                "/api/scores/top-ranker-profile",
                                                                "/api/scores/all-user-scores",
                                                                "/api/scores/song-arena-averages",
                                                                "/api/scores/song-avg-score-rates",
                                                                "/api/scores/user-tier-totals/**",
                                                                "/api/scores/debug-ranking",
                                                                "/api/scores/debug-user-scores/**", "/api/friends/test",
                                                                "/api/test-root")
                                                .permitAll()
                                                // パスワード忘れ・リセットはログイン前に叩かれるので公開
                                                .requestMatchers("/api/auth/forgot-password", "/api/auth/reset-password").permitAll()
                                                // 共有トークンの管理（発行・一覧・失効）は要ログイン
                                                .requestMatchers("/api/share/tokens", "/api/share/tokens/**").authenticated()
                                                // 共有トークン経由のビュー（/api/share/{token}/...）はログイン不要
                                                .requestMatchers("/api/share/**").permitAll()
                                                // アクティビティフィードも未ログイン閲覧を許可
                                                .requestMatchers("/api/activity/feed").permitAll()
                                                // 他ユーザーのプロフィール・スコア・履歴参照は公開
                                                .requestMatchers("/api/users/*/profile", "/api/users/*/scores",
                                                                "/api/users/*/history")
                                                .permitAll()
                                                // フレンド状態確認は要ログイン（自分との関係を返すため）
                                                .requestMatchers("/api/users/*/friend-status").authenticated()
                                                // Ko-fi（外部寄付サービス）からの Webhook は署名で検証するため公開
                                                .requestMatchers("/api/kofi/webhook").permitAll()
                                                // 自分自身の情報取得は要ログイン
                                                .requestMatchers("/api/auth/me", "/api/auth/me/profile").authenticated()
                                                // 通知・管理系も要ログイン
                                                .requestMatchers("/api/notifications/**").authenticated()
                                                .requestMatchers("/api/admin/game-data/**").authenticated()
                                                // スコア書き込み・自分のスコア読み出しは要ログイン
                                                .requestMatchers("/api/scores/upload", "/api/scores/save-history-log",
                                                                "/api/scores/me", "/api/scores/history",
                                                                "/api/scores/*/memo")
                                                .authenticated()
                                                // 上記で permitAll/authenticated に該当しなかったスコア系 API はデフォルト要ログイン
                                                .requestMatchers("/api/scores/**").authenticated()
                                                // 投票 API（POST/DELETE）は要ログイン。GET はデフォルトで permitAll に落ちる
                                                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/votes")
                                                .authenticated()
                                                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                                                                "/api/votes")
                                                .authenticated()
                                                // Tier 投票も同様
                                                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/tier-votes")
                                                .authenticated()
                                                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                                                                "/api/tier-votes")
                                                .authenticated()
                                                .requestMatchers("/api/tier-votes/mine").authenticated()
                                                // フレンド操作・アリーナ系はすべて要ログイン
                                                .requestMatchers("/api/friends/**").authenticated()
                                                .requestMatchers("/api/arena/**").authenticated()
                                                // 上記いずれにも該当しないリクエストは公開扱い（静的リソース等）
                                                .anyRequest().permitAll())
                                // 未認証で要ログインエンドポイントへアクセスされた場合は 401 を返す
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                                // JWT 検証フィルタを UsernamePasswordAuthenticationFilter の直前に差し込む
                                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        /**
         * 【Bean の役割】 CORS ポリシーを組み立てて返す。
         *
         * Allowed Origins はフロントエンドの本番 URL と、開発時の Vite devserver（5173）の 2 種類。
         * クッキーや Authorization ヘッダを跨がせるため {@code allowCredentials=true} にしている。
         *
         * @return 全パスに同じ CORS 設定を適用する {@link CorsConfigurationSource}
         */
        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                // デフォルト: フロントエンド（本番 URL + localhost:5173）からのみ許可
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(List.of(frontendUrl, "http://localhost:5173"));
                // 使用するメソッドを列挙。プリフライト（OPTIONS）も含める。
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                // Authorization など任意のヘッダを受け付ける
                config.setAllowedHeaders(List.of("*"));
                // 資格情報（Cookie / Authorization ヘッダ）付きリクエストを許可
                config.setAllowCredentials(true);
                // すべてのパスに同一設定を適用
                source.registerCorsConfiguration("/**", config);

                return source;
        }
}
