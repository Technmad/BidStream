package com.bidstream.config;

import com.bidstream.adapter.out.cache.RedisRateLimiter;
import com.bidstream.common.security.JwtAuthenticationFilter;
import com.bidstream.common.security.JwtService;
import com.bidstream.common.security.RateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * QA-REVIEW.md Low: no {@code .cors(...)} block existed at all, so a browser-based frontend
     * on a different origin than the API would be blocked by the browser's own same-origin policy
     * before a request ever reached this server. {@code bidstream.cors.allowed-origins} is a
     * comma-separated allowlist (Spring's relaxed binding turns it into a {@code List<String>});
     * it defaults to the conventional local frontend dev port so this stays friction-free without
     * configuration until a real deployed frontend origin is known.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${bidstream.cors.allowed-origins:http://localhost:3000}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService,
                                            RedisRateLimiter rateLimiter,
                                            CorsConfigurationSource corsConfigurationSource,
                                            ObjectMapper objectMapper) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService);
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auctions/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auctions").hasAuthority("ROLE_SELLER")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new RateLimitFilter(rateLimiter, objectMapper), JwtAuthenticationFilter.class);

        return http.build();
    }
}
