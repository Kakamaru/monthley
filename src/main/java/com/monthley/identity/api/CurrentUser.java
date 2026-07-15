package com.monthley.identity.api;

import java.util.List;

/** Pengguna semasa dari JWT — dibaca oleh modul lain. */
public record CurrentUser(
        Long userId,
        String email,
        String fullName,
        boolean superadmin,
        List<SpAccess> spAccess) {

    /** Akses ke satu SP dengan peranan tertentu. */
    public record SpAccess(String spCode, String spName, UserRole role) {}

    public boolean canAdminister(String spCode) {
        if (superadmin) return true;
        return spAccess.stream().anyMatch(a ->
                a.spCode().equals(spCode) &&
                (a.role() == UserRole.SP_ADMIN || a.role() == UserRole.CLERK));
    }
}
