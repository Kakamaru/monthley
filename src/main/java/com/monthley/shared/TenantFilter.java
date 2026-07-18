package com.monthley.shared;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Baca header X-SP-Id (sp_code) → TenantContext.
 *
 * PENTING: sahkan pengguna memang ada akses ke SP tersebut.
 * Tanpa semakan ini, admin SP A boleh hantar X-SP-Id: SP_B
 * dan membaca data SP lain.
 *
 * Berjalan SELEPAS rantaian Spring Security, jadi SecurityContext sudah terisi.
 */
@Component
@Order(1)
public class TenantFilter implements Filter {

    public static final String HEADER = "X-SP-Id";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (req instanceof HttpServletRequest http && res instanceof HttpServletResponse resp) {

                // Laluan auth berlaku SEBELUM konsep tenant wujud untuk pengguna.
                // Jangan sekali-kali tenant-scope /api/v1/auth/** — SecurityContext
                // memang kosong di sini, jadi hasAccess() akan sentiasa tolak.
                if (http.getRequestURI().startsWith("/api/v1/auth/")) {
                    chain.doFilter(req, res);
                    return;
                }

                String sp = http.getHeader(HEADER);

                if (sp != null && !sp.isBlank()) {
                    if (!hasAccess(sp)) {
                        resp.setStatus(403);
                        resp.setContentType("application/json");
                        resp.getWriter().write(
                                "{\"message\":\"Anda tiada akses kepada SP ini.\"}");
                        return;   // hentikan — jangan teruskan ke controller
                    }
                    TenantContext.set(sp);
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();   // elak bocor antara request
        }
    }

    /** Superadmin boleh semua SP; pengguna lain perlu authority SP_<kod>. */
    private boolean hasAccess(String spCode) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority a : auth.getAuthorities()) {
            String role = a.getAuthority();
            if ("ROLE_SUPERADMIN".equals(role)) return true;
            if (("SP_" + spCode).equals(role)) return true;
        }
        return false;
    }
}
