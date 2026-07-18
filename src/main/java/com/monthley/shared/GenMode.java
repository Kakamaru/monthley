package com.monthley.shared;

/**
 * Mod penjanaan invois — menentukan anjakan period asas relatif kepada bulan larian.
 *
 * Padan legacy {@code sp.invoice_generation_mode}:
 *   C = CURRENT, P = POSTPAID, F = PREPAID (future)
 *
 * Anjakan berlaku pada aras {@code charge_frequency} AKAUN, bukan bulan.
 * Akaun tahunan POSTPAID → tahun lepas, bukan bulan lepas.
 */
public enum GenMode {

    /** Bil untuk period semasa. */
    CURRENT("C"),

    /** Bil untuk period lepas. */
    POSTPAID("P"),

    /** Bil untuk period hadapan. */
    PREPAID("F");

    private final String legacyCode;

    GenMode(String legacyCode) { this.legacyCode = legacyCode; }

    public String legacyCode() { return legacyCode; }

    public static GenMode fromLegacy(String code) {
        for (GenMode m : values()) {
            if (m.legacyCode.equalsIgnoreCase(code)) return m;
        }
        throw new IllegalArgumentException("Kod mod tidak dikenali: " + code);
    }
}
