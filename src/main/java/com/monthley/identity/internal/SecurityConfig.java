package com.monthley.identity.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Keselamatan API.
 *
 *   /api/v1/auth/**       — terbuka (daftar, log masuk, sahkan, reset)
 *   /api/v1/lookup/**     — terbuka (poskod, reference data)
 *   /api/v1/lookup/**     — terbuka (poskod, reference data)
 *   /api/v1/platform/**   — SUPERADMIN sahaja
 *   /api/**               — mesti log masuk
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    private final JwtAuthFilter jwtFilter;

    SecurityConfig(JwtAuthFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
            .cors(c -> c.configurationSource(corsSource()))
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // terbuka — daftar, log masuk, sahkan e-mel, reset kata laluan
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/lookup/**").permitAll()

                // Pautan awam dokumen — pelanggan yang menerima e-mel resit
                // mungkin TIADA akaun portal. Keselamatan bergantung pada
                // entropi token (256 bit, V42), bukan pada sesi.
                .requestMatchers("/api/v1/pub/**").permitAll()

                // platform — superadmin sahaja
                .requestMatchers("/api/v1/platform/**").hasRole("SUPERADMIN")

                // selebihnya API — mesti log masuk
                .requestMatchers("/api/**").authenticated()

                // bukan API (aset, dll)
                .anyRequest().permitAll())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"Sila log masuk semula.\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"message\":\"Anda tiada kebenaran untuk tindakan ini.\"}");
                }));
        return http.build();
    }

    /**
     * Asal-usul yang dibenarkan.
     *
     * Keras kepada localhost:4200 selama frontend hanya berjalan dari
     * `ng serve`. Selepas deploy, pelayar menghantar Origin sebenar dan
     * Spring menolaknya dengan 403 — yang kelihatan seperti masalah
     * pengesahan, bukan CORS, kerana curl (yang tidak menghantar Origin)
     * berfungsi dengan sempurna.
     *
     * Senarai dipisah koma supaya satu pemasangan boleh melayan
     * pembangunan dan domainnya sendiri.
     */
    @Value("${monthley.cors.origins:http://localhost:4200}")
    private String corsOrigins;

    @Bean
    CorsConfigurationSource corsSource() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(java.util.Arrays.stream(corsOrigins.split(","))
                .map(String::trim).filter(v -> !v.isEmpty()).toList());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", c);
        return src;
    }
}
