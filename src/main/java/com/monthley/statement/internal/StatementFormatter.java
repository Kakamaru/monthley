package com.monthley.statement.internal;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pemformat mengikut tetapan SP.
 *
 * Templat kekal bodoh: ia memanggil fmt.date(...), fmt.money(...),
 * fmt.period(...) dan tidak pernah membuat keputusan format sendiri.
 *
 * Nama bulan datang daripada Locale, bukan daripada teks tersimpan.
 * Legacy tidak boleh menyetempatkan — nama tempohnya ditaip semasa
 * posting, jadi 'July, 2026' kekal Inggeris untuk SP berbahasa Melayu.
 */
public class StatementFormatter {

    private final DateTimeFormatter dateFmt;
    private final DateTimeFormatter monthFmt;
    private final DecimalFormat money;

    StatementFormatter(String language, String datePattern) {
        Locale locale = (language == null || language.isBlank())
                ? Locale.forLanguageTag("en")
                : Locale.forLanguageTag(language);

        DateTimeFormatter d;
        try {
            d = DateTimeFormatter.ofPattern(
                    (datePattern == null || datePattern.isBlank()) ? "dd/MM/yyyy" : datePattern,
                    locale);
        } catch (IllegalArgumentException e) {
            // corak SP tidak sah — jangan gagalkan seluruh penyata
            d = DateTimeFormatter.ofPattern("dd/MM/yyyy", locale);
        }
        this.dateFmt = d;
        this.monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", locale);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        this.money = new DecimalFormat("#,##0.00;(#,##0.00)", sym);
    }

    public String date(LocalDate d) {
        return d == null ? "" : dateFmt.format(d);
    }

    /** Negatif dalam kurungan, mengikut legacy: (130.00) */
    public String money(BigDecimal v) {
        return v == null ? "" : money.format(v);
    }

    /**
     * Tempoh daripada TARIKH, bukan nama tersimpan. Satu bulan dipendekkan
     * kepada 'Januari 2026'; julat lebih panjang menunjukkan kedua-dua hujung.
     */
    public String period(LocalDate start, LocalDate end) {
        if (start == null) {
            return "";
        }
        if (end == null || (start.getYear() == end.getYear()
                && start.getMonthValue() == end.getMonthValue())) {
            return monthFmt.format(start);
        }
        return monthFmt.format(start) + " - " + monthFmt.format(end);
    }

    /** Baris kosong dilangkau, bukan dicetak sebagai ruang atau 'null'. */
    public boolean has(String v) {
        return v != null && !v.isBlank();
    }
}
