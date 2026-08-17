package com.monthley.document.internal;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Jana nombor dokumen berturutan per SP, thread-safe guna row lock.
 *
 * TIGA LAPISAN (ADR 0012):
 *   sp_document_setting       prefix, saiz, mula  — NIAT SP
 *   document_number_sequence  next_value          — KEADAAN BERJALAN
 *   uk_doc_no (sp_code,doc_no) UNIQUE             — JAMINAN
 *
 * Legacy tidak menyimpan kaunter langsung: ia mengira COUNT dokumen
 * sedia ada setiap kali, kemudian menggelung sehingga tidak bertembung.
 * Gelung itu wujud kerana ia DIPERLUKAN — padam satu dokumen dan COUNT
 * jatuh. Di bawah larian serentak, dua thread mendapat COUNT yang sama.
 *
 * SELECT ... FOR UPDATE di sini menghapuskan kedua-dua masalah, dan
 * uk_doc_no menjadikan pendua mustahil. Kita TIDAK menyalin gelung
 * semakan legacy: menyemak sebelum menulis mempunyai lubang race yang
 * sama.
 */
@Service
class DocumentNumberService {

    @PersistenceContext
    private EntityManager em;

    /**
     * Kaunter MENTAH — tanpa prefix, padding, atau semakan langkau.
     *
     * Rujukan gerbang membina formatnya sendiri (sp_code + base36), jadi
     * ia perlukan nombor dan bukan rentetan berformat. Semakan langkau
     * dalam next() memeriksa financial_document, yang tidak berkaitan
     * dengan rujukan gerbang.
     *
     * Kunci baris yang SAMA: dua kaedah pada turutan yang sama disiri.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    long nextValue(String spCode, String seqType) {
        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery("""
                SELECT id, next_value
                FROM document_number_sequence
                WHERE sp_code = :sp AND seq_type = :type
                FOR UPDATE
                """)
                .setParameter("sp", spCode)
                .setParameter("type", seqType)
                .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            em.createNativeQuery("""
                INSERT INTO document_number_sequence
                  (sp_code, seq_type, prefix, last_prefix, next_value, padding, version)
                VALUES (:sp, :type, '', '', 1, 6, 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("type", seqType)
                .executeUpdate();
            return nextValue(spCode, seqType);
        }

        Long id = ((Number) row[0]).longValue();
        long value = ((Number) row[1]).longValue();

        em.createNativeQuery(
                "UPDATE document_number_sequence SET next_value = :v WHERE id = :id")
                .setParameter("v", value + 1)
                .setParameter("id", id)
                .executeUpdate();

        return value;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String next(String spCode, String seqType) {
        return next(spCode, seqType, tetapan(spCode, seqType));
    }

    /**
     * Varian untuk modul luar: tetapan dihantar masuk, bukan dicari.
     *
     * Modul `document` tidak sepatutnya tahu modul mana yang memanggilnya.
     * Tanpa ini, tetapan() memerlukan cabang baharu bagi setiap modul yang
     * ditambah, dan SP yang tidak melanggan modul terpaksa membawa lajur
     * tetapannya dalam sp_document_setting.
     *
     * Kaunter dan kunci baris SAMA — hanya sumber tetapan yang berbeza.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    String next(String spCode, String seqType, String prefix, int padding, long start) {
        int saiz = (padding < 1 || padding > 18) ? 6 : padding;
        long mula = start < 0 ? 1L : start;
        String p = (prefix == null || prefix.isBlank()) ? "DOC" : prefix.trim();
        return next(spCode, seqType, new Tetapan(p, saiz, mula));
    }

    private String next(String spCode, String seqType, Tetapan t) {

        Object[] row;
        try {
            row = (Object[]) em.createNativeQuery("""
                SELECT id, last_prefix, suffix, next_value
                FROM document_number_sequence
                WHERE sp_code = :sp AND seq_type = :type
                FOR UPDATE
                """)
                .setParameter("sp", spCode)
                .setParameter("type", seqType)
                .getSingleResult();
        } catch (jakarta.persistence.NoResultException e) {
            em.createNativeQuery("""
                INSERT INTO document_number_sequence
                  (sp_code, seq_type, prefix, last_prefix, next_value, padding, version)
                VALUES (:sp, :type, :prefix, :prefix, :mula, :pad, 0)
                """)
                .setParameter("sp", spCode)
                .setParameter("type", seqType)
                .setParameter("prefix", t.prefix())
                .setParameter("mula", t.mula())
                .setParameter("pad", t.saiz())
                .executeUpdate();
            // Rekursi mesti membawa Tetapan yang SAMA. Memanggil varian
            // dua-parameter di sini mencari tetapan semula dan modul luar
            // mendapat lalai "DOC" — prefix yang dihantar masuk hilang.
            return next(spCode, seqType, t);
        }

        Long id = ((Number) row[0]).longValue();
        String lastPrefix = row[1] == null ? "" : row[1].toString();
        String suffix = row[2] == null ? "" : row[2].toString();
        long value = ((Number) row[3]).longValue();

        // Prefix berubah -> kitaran baharu. Prefix menandakan tempoh dalam
        // data produksi (K19 = 2019), jadi menukarnya bermakna bermula
        // semula, bukan menyambung.
        if (!t.prefix().equals(lastPrefix)) {
            value = t.mula();
            em.createNativeQuery("""
                UPDATE document_number_sequence
                SET last_prefix = :p, next_value = :v
                WHERE id = :id
                """)
                .setParameter("p", t.prefix())
                .setParameter("v", value)
                .setParameter("id", id)
                .executeUpdate();
        }

        // Langkau nombor yang sudah wujud.
        //
        // Ini HANYA berlaku selepas reset prefix: SP menukar prefix BALIK
        // kepada yang pernah digunakan (tersilap taip, kemudian
        // membetulkannya). Dalam operasi biasa next_value hanya naik dan
        // gelung ini tidak pernah berjalan.
        //
        // Selamat kerana kita memegang FOR UPDATE pada baris turutan
        // sehingga transaksi commit — iaitu SELEPAS dokumen dimasukkan.
        // Setiap panggilan next() untuk (sp_code, seq_type) yang sama
        // disiri sepenuhnya.
        //
        // Legacy mempunyai gelung yang sama TANPA kunci, yang menjadikan
        // ia berlumba. Kunci itu yang membezakan, bukan gelungnya.
        //
        // Alternatif — biarkan uk_doc_no menolak dan cuba semula — tidak
        // berfungsi: selepas DataIntegrityViolationException, Spring
        // menandakan transaksi rollback-only dan operasi seterusnya gagal.
        int cuba = 0;
        while (wujud(spCode, format(t, value, suffix))) {
            value++;
            if (++cuba > 1000) {
                throw new IllegalStateException(
                        "Tidak dapat mencari nombor " + seqType + " yang belum "
                        + "digunakan untuk " + spCode + " selepas 1000 percubaan. "
                        + "Semak prefix dan nombor mula dalam tetapan.");
            }
        }

        em.createNativeQuery(
                "UPDATE document_number_sequence SET next_value = :v WHERE id = :id")
                .setParameter("v", value + 1)
                .setParameter("id", id)
                .executeUpdate();

        return format(t, value, suffix);
    }

    private String format(Tetapan t, long value, String suffix) {
        return t.prefix() + String.format("%0" + t.saiz() + "d", value) + suffix;
    }

    private boolean wujud(String spCode, String docNo) {
        Number n = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM financial_document "
                + "WHERE sp_code = :sp AND doc_no = :no")
                .setParameter("sp", spCode)
                .setParameter("no", docNo)
                .getSingleResult();
        return n.longValue() > 0;
    }

    // ── Tetapan ──────────────────────────────────────────────────────

    private record Tetapan(String prefix, int saiz, long mula) {}

    /**
     * Prefix, saiz dan nombor mula daripada tetapan SP.
     *
     * Hanya INVOICE dan RECEIPT mempunyai tetapan — nota kredit dan debit
     * ialah pelarasan yang jarang (3 dan 4 dalam produksi berbanding 99
     * invois), dan tiada siapa meminta prefix tersuai untuknya. Menambah
     * empat medan lagi pada skrin tetapan untuk sesuatu yang tidak pernah
     * disentuh menjadikan skrin itu lebih sukar tanpa faedah.
     *
     * SP yang tidak pernah membuka skrin tetapan mendapat lalai semasa.
     */
    private Tetapan tetapan(String spCode, String seqType) {
        String lalai = switch (seqType) {
            case "INVOICE" -> "INV";
            case "RECEIPT" -> "RCP";
            case "CREDIT_NOTE" -> "CN";
            case "DEBIT_NOTE" -> "DN";
            default -> "DOC";
        };

        String lajurPrefix, lajurSaiz, lajurMula;
        if ("INVOICE".equals(seqType)) {
            lajurPrefix = "invoice_prefix";
            lajurSaiz = "invoice_no_size";
            lajurMula = "invoice_no_start";
        } else if ("RECEIPT".equals(seqType)) {
            lajurPrefix = "receipt_prefix";
            lajurSaiz = "receipt_no_size";
            lajurMula = "receipt_no_start";
        } else {
            return new Tetapan(lalai, 6, 1L);
        }

        Object[] r = (Object[]) em.createNativeQuery(
                "SELECT " + lajurPrefix + ", " + lajurSaiz + ", " + lajurMula
                + " FROM sp_document_setting WHERE sp_code = :sp")
                .setParameter("sp", spCode)
                .getResultList().stream().findFirst().orElse(null);

        if (r == null) {
            return new Tetapan(lalai, 6, 1L);
        }
        String prefix = r[0] == null || r[0].toString().isBlank()
                ? lalai : r[0].toString().trim();
        int saiz = r[1] == null ? 6 : ((Number) r[1]).intValue();
        long mula = r[2] == null ? 1L : ((Number) r[2]).longValue();

        // Saiz tidak munasabah akan menghasilkan nombor yang tidak boleh
        // dibaca atau String.format yang gagal.
        if (saiz < 1 || saiz > 18) saiz = 6;
        if (mula < 0) mula = 1L;

        return new Tetapan(prefix, saiz, mula);
    }
}
