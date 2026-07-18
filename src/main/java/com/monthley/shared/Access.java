package com.monthley.shared;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Semak peranan pengguna dalam SP semasa (dari TenantContext).
 *
 * Authority daripada JWT:
 *   ROLE_SUPERADMIN          — platform admin
 *   SP_<kod>                 — ada akses ke SP
 *   SP_<kod>_<PERANAN>       — peranan tertentu dalam SP itu
 *
 * Pengasingan tugas: Admin urus sistem, Cashier terima duit.
 * Satu pengguna boleh ada dua-dua (dua baris sp_membership).
 */
public final class Access {

    private Access() {}

    public static boolean isSuperadmin() {
        return has("ROLE_SUPERADMIN");
    }

    /** Ada peranan ini dalam SP semasa? Superadmin sentiasa ya. */
    public static boolean hasRole(String role) {
        if (isSuperadmin()) return true;
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) return false;
        return has("SP_" + sp + "_" + role);
    }

    /** Tolak jika tiada peranan — guna di awal endpoint terlindung. */
    public static void requireRole(String role, String action) {
        if (!hasRole(role)) {
            throw new AccessDeniedException(
                    "Anda tiada kebenaran untuk " + action + ". Peranan diperlukan: " + role + ".");
        }
    }

    private static boolean has(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            if (authority.equals(a.getAuthority())) return true;
        }
        return false;
    }

    /** 403 dengan mesej jelas. */
    public static class AccessDeniedException extends RuntimeException {
        public AccessDeniedException(String msg) { super(msg); }
    }
}
