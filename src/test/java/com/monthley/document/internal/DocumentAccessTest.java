package com.monthley.document.internal;

import com.monthley.document.api.DocumentAccessPort;
import com.monthley.document.api.DocumentType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token capaian dokumen (V42).
 *
 * CASE-006: legacy mencipta dokumen 'P' HANTU untuk setiap e-mel
 * semata-mata untuk mendapat UUID pautan — 51 rekod bukan-kewangan dalam
 * jadual kewangan satu akaun.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentAccessTest {

    private static final String SP = "SPTK";

    @Autowired DocumentAccessPort access;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long doc1;
    private long doc2;

    @BeforeEach
    void seed() {
        jdbc.sql("""
                INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                VALUES (:sp, 'SP Ujian Token', 'ACTIVE', 0)
                """).param("sp", SP).update();
        doc1 = dokumen("TK-RCP-1-" + System.nanoTime());
        doc2 = dokumen("TK-RCP-2-" + System.nanoTime());
    }

    private long dokumen(String docNo) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, doc_date, amount, tax_amount,
                   status, title, currency)
                VALUES (:sp, :no, 'RECEIPT', '2026-07-01', 50.00, 0,
                        'ACTIVE', 'Resit', 'MYR')
                """).param("sp", SP).param("no", docNo).update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", SP).param("no", docNo).query(Long.class).single();
    }

    @Test
    @DisplayName("token menyelesaikan kepada dokumen yang betul")
    void tokenMenyelesaikan() {
        String t = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        em.flush();

        var d = access.resolve(t).orElseThrow();
        assertThat(d.documentId()).isEqualTo(doc1);
        assertThat(d.spCode()).isEqualTo(SP);
        assertThat(d.type()).isEqualTo(DocumentType.RECEIPT);
    }

    @Test
    @DisplayName("SATU token per dokumen — hantar semula beri pautan SAMA")
    void satuTokenPerDokumen() {
        String a = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        String b = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        String c = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        em.flush();

        assertThat(a)
                .as("skrin Finance Documents mempunyai 'Resend Document'; "
                    + "e-mel lama mesti kekal berfungsi")
                .isEqualTo(b).isEqualTo(c);

        long bil = jdbc.sql(
                "SELECT COUNT(*) FROM document_access_token WHERE document_id = :id")
                .param("id", doc1).query(Long.class).single();
        assertThat(bil).isEqualTo(1);
    }

    @Test
    @DisplayName("dokumen berbeza mendapat token berbeza")
    void tokenUnik() {
        String a = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        String b = access.tokenFor(SP, doc2, DocumentType.RECEIPT);
        em.flush();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("token DIBATALKAN memberi respons SAMA seperti token hantu")
    void dibatalkanSamaSepertiHantu() {
        String t = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        em.flush();
        assertThat(access.resolve(t)).isPresent();

        access.revoke(doc1);
        em.flush();
        em.clear();

        assertThat(access.resolve(t))
                .as("resit dibatalkan; pautan mesti berhenti berfungsi supaya "
                    + "pelanggan tidak membukanya dan menganggapnya sah")
                .isEmpty();
        assertThat(access.resolve("token-yang-tidak-pernah-wujud"))
                .as("membezakan 'dibatalkan' daripada 'tidak wujud' "
                    + "membenarkan penyerang mengesahkan token mana pernah ada")
                .isEmpty();
    }

    @Test
    @DisplayName("paparan direkod — SP boleh tahu pelanggan sudah buka")
    void paparanDirekod() {
        String t = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        em.flush();

        access.resolve(t);
        access.resolve(t);
        access.resolve(t);
        em.flush();
        em.clear();

        var r = jdbc.sql("""
                SELECT view_count, first_seen_at IS NOT NULL, last_seen_at IS NOT NULL
                FROM   document_access_token WHERE token = :t
                """).param("t", t)
                .query((rs, n) -> new Object[]{
                        rs.getInt(1), rs.getBoolean(2), rs.getBoolean(3)})
                .single();

        assertThat((Integer) r[0])
                .as("legacy tidak boleh menjawab 'adakah pelanggan pernah "
                    + "membuka resit ini'")
                .isEqualTo(3);
        assertThat((Boolean) r[1]).isTrue();
        assertThat((Boolean) r[2]).isTrue();
    }

    @Test
    @DisplayName("token cukup panjang untuk tidak boleh diteka")
    void tokenPanjang() {
        String t = access.tokenFor(SP, doc1, DocumentType.RECEIPT);
        assertThat(t.length())
                .as("32 bait base64url = 43 aksara, 256 bit entropi")
                .isGreaterThanOrEqualTo(43);
        assertThat(t).doesNotContain("+", "/", "=");
    }
}
