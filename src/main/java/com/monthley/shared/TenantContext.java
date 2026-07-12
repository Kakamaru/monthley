package com.monthley.shared;

/**
 * Tenant semasa (sp_code). Diisi oleh filter/interceptor,
 * dibaca oleh Hibernate CurrentTenantIdentifierResolver.
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(String spCode) { CURRENT.set(spCode); }
    public static String get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }
}
