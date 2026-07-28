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
public class StatementFormatter implements com.monthley.statement.api.StatementTextFormat {

    private final DateTimeFormatter dateFmt;
    private final DateTimeFormatter monthFmt;
    private final DateTimeFormatter timeFmt;
    private final DecimalFormat money;

    public StatementFormatter(String language, String datePattern) {
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
        // Locale Melayu memberi "PG"/"PTG" untuk penanda AM/PM. Resit
        // kewangan menggunakan AM/PM di seluruh dunia, dan legacy pun
        // begitu — kekalkan Locale.US untuk penanda itu sahaja.
        this.timeFmt = DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

        DecimalFormatSymbols sym = new DecimalFormatSymbols(Locale.US);
        this.money = new DecimalFormat("#,##0.00;(#,##0.00)", sym);
    }

    @Override
    public String date(LocalDate d) {
        return d == null ? "" : dateFmt.format(d);
    }

    /** Negatif dalam kurungan, mengikut legacy: (130.00) */
    @Override
    public String money(BigDecimal v) {
        return v == null ? "" : money.format(v);
    }

    /**
     * Tempoh daripada TARIKH, bukan nama tersimpan.
     *
     * Dipendekkan kepada 'Januari 2026' hanya apabila ia BULAN PENUH.
     * Sebahagian bulan menunjukkan tarikhnya: satu akaun boleh mempunyai
     * dua langganan produk yang sama dengan start_date berbeza —
     * uk_subscr (account_id, product_id, start_date) membenarkannya
     * dengan sengaja, kerana pelanggan boleh menyewa dua petak parking.
     *
     * Memendekkan kedua-duanya kepada 'Julai 2026' menjadikan dua baris
     * sah kelihatan seperti pendua, dan pembaca tidak dapat membezakannya.
     */
    @Override
    public String period(LocalDate start, LocalDate end) {
        if (start == null) {
            return "";
        }
        boolean bulanSama = end != null
                && start.getYear() == end.getYear()
                && start.getMonthValue() == end.getMonthValue();

        if (end == null) {
            return monthFmt.format(start);
        }
        if (bulanSama) {
            boolean penuh = start.getDayOfMonth() == 1
                    && end.getDayOfMonth() == end.lengthOfMonth();
            return penuh
                    ? monthFmt.format(start)
                    : start.getDayOfMonth() + "-" + end.getDayOfMonth()
                      + " " + monthFmt.format(start);
        }
        return monthFmt.format(start) + " - " + monthFmt.format(end);
    }

    @Override
    public String dateTime(java.time.LocalDateTime d) {
        return d == null ? "" : dateFmt.format(d.toLocalDate())
                + " " + timeFmt.format(d.toLocalTime());
    }

    @Override
    public String paymentMethod(String kod) {
        if (kod == null) return "";
        return switch (kod) {
            case "CASH"       -> "Tunai";
            case "CHEQUE"     -> "Cek";
            case "TRANSFER"   -> "Pindahan Bank";
            case "FPX"        -> "FPX / Dalam Talian";
            case "ADJUSTMENT" -> "Penyelarasan";
            default           -> kod;
        };
    }

    /** Baris kosong dilangkau, bukan dicetak sebagai ruang atau 'null'. */
    public boolean has(String v) {
        return v != null && !v.isBlank();
    }
}
