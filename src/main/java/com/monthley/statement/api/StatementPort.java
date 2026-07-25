package com.monthley.statement.api;

import java.time.LocalDate;

/**
 * SATU perkhidmatan, TIGA pemanggil: ikon akaun, portal pelanggan, tab
 * Laporan. Tiada query penyata di luar modul ini (ADR 0010 keputusan 1).
 */
public interface StatementPort {

    /** Penyata bagi satu tahun kalendar, dengan baki bawa ke hadapan. */
    StatementModel forYear(String spCode, long accountId, int year);

    /** Penyata bagi julat sebarang. Julat penuh = "semua rekod". */
    StatementModel forRange(String spCode, long accountId, LocalDate from, LocalDate to);
}
