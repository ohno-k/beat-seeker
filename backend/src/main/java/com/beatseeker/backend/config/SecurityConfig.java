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

@Configuration
@EnableWebSecurity
public class SecurityConfig {

        @Value("${FRONTEND_URL:http://localhost:5173}")
        private String frontendUrl;

        private final JwtAuthFilter jwtAuthFilter;

        public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
                this.jwtAuthFilter = jwtAuthFilter;
        }

        @Bean
        public PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .csrf(csrf -> csrf.disable())
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers("/api/scores/ranking", "/api/scores/all-user-scores",
                                                                "/api/scores/nearby-players-scores",
                                                                "/api/scores/debug-ranking",
                                                                "/api/scores/debug-user-scores/**", "/api/friends/test",
                                                                "/api/test-root")
                                                .permitAll()
                                                .requestMatchers("/api/auth/me", "/api/auth/me/profile").authenticated()
                                                .requestMatchers("/api/scores/upload", "/api/scores/save-history-log",
                                                                "/api/scores/me", "/api/scores/history",
                                                                "/api/scores/*/memo")
                                                .authenticated()
                                                .requestMatchers("/api/scores/**").authenticated()
                                                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/votes")
                                                .authenticated()
                                                .requestMatchers(org.springframework.http.HttpMethod.DELETE,
                                                                "/api/votes")
                                                .authenticated()
                                                .requestMatchers("/api/friends/**").authenticated()
                                                .requestMatchers("/api/arena/**").authenticated()
                                                .anyRequest().permitAll())
                                .exceptionHandling(ex -> ex
                                                .authenticationEntryPoint(
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

                // default: allow frontend only
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(List.of(frontendUrl, "http://localhost:5173"));
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                config.setAllowCredentials(true);
                source.registerCorsConfiguration("/**", config);

                return source;
        }
}
