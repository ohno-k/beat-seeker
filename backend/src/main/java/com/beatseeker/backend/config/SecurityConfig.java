package com.beatseeker.backend.config;

import com.beatseeker.backend.service.CustomOAuth2UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

        private final CustomOAuth2UserService customOAuth2UserService;

        @Value("${FRONTEND_URL:http://localhost:5173}")
        private String frontendUrl;

        public SecurityConfig(CustomOAuth2UserService customOAuth2UserService) {
                this.customOAuth2UserService = customOAuth2UserService;
        }

        @Bean
        public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
                // Determine absolute redirect URLs based on environment
                String successUrl = (frontendUrl != null && !frontendUrl.isEmpty()) ? frontendUrl + "?login=success"
                                : "/?login=success";
                String errorUrl = (frontendUrl != null && !frontendUrl.isEmpty()) ? frontendUrl + "?login=error"
                                : "/?login=error";
                String logoutUrl = (frontendUrl != null && !frontendUrl.isEmpty()) ? frontendUrl : "/";

                http
                                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                                .csrf(csrf -> csrf.disable())
                                .authorizeHttpRequests(auth -> auth
                                                .requestMatchers("/api/auth/me", "/api/auth/me/profile",
                                                                "/api/scores/**")
                                                .authenticated()
                                                .anyRequest().permitAll())
                                .exceptionHandling(ex -> ex
                                                // Return 401 instead of redirect for API calls
                                                .authenticationEntryPoint(
                                                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                                .oauth2Login(oauth2 -> oauth2
                                                .userInfoEndpoint(userInfo -> userInfo
                                                                .userService(customOAuth2UserService))
                                                // After login, explicitly redirect cross-origin to frontend
                                                .defaultSuccessUrl(successUrl, true)
                                                .failureUrl(errorUrl))
                                .logout(logout -> logout
                                                .logoutUrl("/api/auth/logout")
                                                .logoutSuccessUrl(logoutUrl)
                                                .deleteCookies("JSESSIONID")
                                                .invalidateHttpSession(true));

                return http.build();
        }

        @Bean
        public CorsConfigurationSource corsConfigurationSource() {
                CorsConfiguration config = new CorsConfiguration();
                config.setAllowedOrigins(List.of(frontendUrl));
                config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
                config.setAllowedHeaders(List.of("*"));
                config.setAllowCredentials(true); // Required for session cookies

                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", config);
                return source;
        }
}
