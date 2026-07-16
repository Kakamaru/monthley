package com.monthley.identity.internal;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Baca "Authorization: Bearer <jwt>", sahkan, dan letak dalam SecurityContext.
 *
 * Authority yang diberi:
 *   ROLE_USER        — semua pengguna sah
 *   ROLE_SUPERADMIN  — platform admin
 *   SP_<kod>         — akses ke SP tertentu (dari claim 'sps')
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    JwtAuthFilter(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims c = jwt.parse(header.substring(7));

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));

                if (Boolean.TRUE.equals(c.get("superadmin", Boolean.class))) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
                }

                Object sps = c.get("sps");
                if (sps instanceof List<?> list) {
                    for (Object sp : list) {
                        authorities.add(new SimpleGrantedAuthority("SP_" + sp));
                    }
                }

                var auth = new UsernamePasswordAuthenticationToken(
                        c.getSubject(), null, authorities);
                auth.setDetails(c.get("email", String.class));
                SecurityContextHolder.getContext().setAuthentication(auth);

            } catch (Exception e) {
                // Token tak sah / luput — biar sebagai tidak disahkan.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(req, res);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        // Endpoint auth tak perlu token
        return req.getRequestURI().startsWith("/api/v1/auth/");
    }
}
