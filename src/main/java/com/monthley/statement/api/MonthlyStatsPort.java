package com.monthley.statement.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Statistik bulanan untuk SP.
 *
 * INVOIS DAN KUTIPAN TIDAK BOLEH DIBANDINGKAN TERUS.
 *
 * Laporan legacy meletakkan 'Jumlah Invois RM31,416' bersebelahan
 * 'Jumlah Resit RM43,700' untuk bulan yang sama. Kedua-duanya betul,
 * tetapi bersebelahan tanpa penjelasan ia kelihatan seperti JMB
 * mengutip lebih daripada yang dibil.
 *
 * Sebabnya: resit Julai boleh membayar invois Mac. Jadi kutipan
 * dipecahkan — berapa untuk bil bulan ini, berapa untuk tunggakan lama.
 */
public interface MonthlyStatsPort {

    /** Satu bulan dalam trend. */
    record MonthPoint(String label, BigDecimal billed, BigDecimal collected) {}

    /**
     * Satu hari dalam bulan.
     *
     * @param cumulative kutipan TERKUMPUL sehingga hari itu — garis yang
     *                   sentiasa menaik menunjukkan rentak kutipan, dan
     *                   bar harian sahaja terlalu bergerigi untuk dibaca
     */
    record DayPoint(int day, BigDecimal amount, BigDecimal cumulative) {}

    /** Ringkasan trend harian. */
    record DailySummary(BigDecimal total, BigDecimal average,
                        int busiestDay, BigDecimal busiestAmount,
                        int transactions) {}

    record Slice(String label, BigDecimal amount) {}

    /**
     * @param invoiceCount bilangan invois yang belum lunas — nombor
     *                     sahaja tidak memberitahu sama ada RM5,000 itu
     *                     satu bil besar atau dua belas bil kecil
     */
    record TopAccount(String accountNo, String accountName,
                      BigDecimal amount, String note, int invoiceCount) {}

    record Stats(
            String periodName,
            LocalDate from, LocalDate to,
            LocalDate asAt,

            // Kad utama
            int invoiceCount,
            BigDecimal billed,
            int receiptCount,
            BigDecimal collected,
            /** Bahagian kutipan yang melangsaikan invois tempoh INI. */
            BigDecimal collectedThisPeriod,
            /** Bahagian yang melangsaikan tunggakan lama. */
            BigDecimal collectedArrears,
            /** collectedThisPeriod / billed, sebagai peratus. */
            BigDecimal collectionRate,

            // Tunggakan dengan arah
            BigDecimal arrears,
            BigDecimal arrearsPrevious,

            int activeAccounts,
            int accountsWithBalance,

            List<MonthPoint> trend,
            List<DayPoint> daily,
            DailySummary dailySummary,
            /** Bil bulan sebelumnya, untuk badge delta. */
            BigDecimal billedPrevious,
            BigDecimal collectedPrevious,
            List<Slice> byPaymentType,
            List<Slice> byProduct,
            List<TopAccount> topArrears,
            /** Akaun yang paling lama tidak membuat bayaran. */
            List<TopAccount> longestSilent
    ) {}

    Stats monthly(String spCode, int year, int month);
}
