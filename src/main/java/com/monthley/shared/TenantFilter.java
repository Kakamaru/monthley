package com.monthley.shared;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Baca header X-SP-Id (sp_code) → TenantContext.
 * Semua skrin SP hantar header ni (rujuk handoff §5).
 */
@Component
@Order(1)
public class TenantFilter implements Filter {

    public static final String HEADER = "X-SP-Id";

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        try {
            if (req instanceof HttpServletRequest http) {
                String sp = http.getHeader(HEADER);
                if (sp != null && !sp.isBlank()) {
                    TenantContext.set(sp);
                }
            }
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();   // elak bocor antara request
        }
    }
}
