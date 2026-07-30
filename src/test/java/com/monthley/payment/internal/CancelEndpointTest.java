package com.monthley.payment.internal;

import com.monthley.ledger.internal.ChartOfAccountSeeder;
import com.monthley.payment.api.NewPayment;
import com.monthley.payment.api.PaymentMethod;
import com.monthley.payment.api.PaymentPort;
import com.monthley.shared.Access;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Endpoint batal dokumen — laluan HTTP, bukan PaymentPort.
 *
 * CancelDocumentTest memanggil port terus dan meliputi logik pembalikan.
 * Yang TIDAK diliputinya: semakan peranan, sebab wajib, pemilihan laluan
 * resit lawan invois, dan pengasingan penyewa. Menanggalkan satu baris
 * requireRole tidak akan memerahkan mana-mana ujian sebelum ini
 * (soalan terbuka 27).
 *
 * PENGASINGAN TUGAS: kerani menerima duit, admin membatalkannya. Kalau
 * CLERK boleh membatalkan, kerani boleh menerima bayaran dan
 * memadamkannya sendiri.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CancelEndpointTest {

    private static final String SP = "SPCE";
    private static final String LAIN = "SPCF";

    @Autowired ManualPaymentController controller;
    @Autowired PaymentPort payment;
    @Autowired ChartOfAccountSeeder seeder;
    @Autowired JdbcClient jdbc;
    @PersistenceContext EntityManager em;

    private long acc;
    private long invoisId;
    private long resitDocId;
    private long docSpLain;

    @BeforeEach
    void seed() {
        for (String sp : new String[]{SP, LAIN}) {
            jdbc.sql("""
                    INSERT IGNORE INTO service_provider (sp_code, name, status, version)
                    VALUES (:sp, 'SP Ujian Batal HTTP', 'ACTIVE', 0)
                    """).param("sp", sp).update();
            seeder.seedFor(sp);
            jdbc.sql("""
                    INSERT IGNORE INTO sp_document_setting
                      (sp_code, enable_manual_payment, version)
                    VALUES (:sp, 1, 0)
                    """).param("sp", sp).update();
        }

        acc = akaun(SP, "CE-" + System.nanoTime());
        invoisId = dokumen(SP, acc, "CE-INV-" + System.nanoTime(), "INVOICE", "200.00");
        docSpLain = dokumen(LAIN, akaun(LAIN, "CF-" + System.nanoTime()),
                "CF-INV-" + System.nanoTime(), "INVOICE", "50.00");
        em.flush();

        TenantContext.set(SP);
        jadiAdmin();

        // Resit sebenar melalui PaymentService — endpoint mencari
        // payment.id daripada document.id, jadi rekod payment mesti wujud.
        var r = payment.receivePayment(new NewPayment(SP, acc,
                new BigDecimal("120.00"), PaymentMethod.CASH, null,
                List.of(), null, null, null));
        em.flush();
        resitDocId = r.receiptDocumentId();
    }

    @AfterEach
    void clear() { TenantContext.clear(); SecurityContextHolder.clearContext(); }

    private void jadiAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("77", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_" + SP + "_SP_ADMIN"))));
    }

    private void jadiClerk() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("88", "n/a",
                        List.of(new SimpleGrantedAuthority("SP_" + SP + "_CLERK"))));
    }

    private long akaun(String sp, String no) {
        jdbc.sql("""
                INSERT INTO account (sp_code, account_no, account_name, status)
                VALUES (:sp, :no, 'Ujian Batal', 'ACTIVE')
                """).param("sp", sp).param("no", no).update();
        return jdbc.sql("SELECT id FROM account WHERE sp_code=:sp AND account_no=:no")
                .param("sp", sp).param("no", no).query(Long.class).single();
    }

    private long dokumen(String sp, long accountId, String docNo,
                         String type, String amaun) {
        jdbc.sql("""
                INSERT INTO financial_document
                  (sp_code, doc_no, doc_type, account_id, doc_date,
                   amount, tax_amount, status, title, currency)
                VALUES (:sp, :no, :type, :acc, :d, :amt, 0, 'ACTIVE', 'Ujian', 'MYR')
                """)
                .param("sp", sp).param("no", docNo).param("type", type)
                .param("acc", accountId).param("d", LocalDate.of(2026, 7, 1))
                .param("amt", new BigDecimal(amaun)).update();
        return jdbc.sql("SELECT id FROM financial_document WHERE sp_code=:sp AND doc_no=:no")
                .param("sp", sp).param("no", docNo).query(Long.class).single();
    }

    private String status(long docId) {
        em.flush();
        em.clear();
        return jdbc.sql("SELECT status FROM financial_document WHERE id = :id")
                .param("id", docId).query(String.class).single();
    }

    private ManualPaymentController.CancelRequest sebab(String s) {
        return new ManualPaymentController.CancelRequest(s);
    }

    // ── peranan ──────────────────────────────────────────────────────

    @Test
    @DisplayName("SP_ADMIN boleh membatalkan")
    void adminBoleh() {
        var res = controller.cancelDocument(invoisId, sebab("dijana tersilap"));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(status(invoisId)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("CLERK DITOLAK — kerani terima duit, admin batalkan")
    void clerkDitolak() {
        jadiClerk();

        assertThatThrownBy(() -> controller.cancelDocument(invoisId, sebab("cuba")))
                .as("kalau kerani boleh membatalkan, dia boleh menerima "
                    + "bayaran dan memadamkannya sendiri")
                .isInstanceOf(Access.AccessDeniedException.class);

        assertThat(status(invoisId))
                .as("dokumen mesti TIDAK tersentuh")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("tanpa peranan langsung DITOLAK")
    void tanpaPerananDitolak() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> controller.cancelDocument(invoisId, sebab("cuba")))
                .isInstanceOf(Access.AccessDeniedException.class);
        assertThat(status(invoisId)).isEqualTo("ACTIVE");
    }

    // ── sebab wajib ──────────────────────────────────────────────────

    @Test
    @DisplayName("sebab KOSONG ditolak — lajur cancel_reason mesti berisi")
    void sebabKosongDitolak() {
        for (String s : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> controller.cancelDocument(invoisId, sebab(s)))
                    .as("dialog mempunyai medan Remarks bertanda merah; "
                        + "menerima kosong bermakna lajur itu mati semula")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Sebab pembatalan diperlukan");
        }
        assertThat(status(invoisId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("sebab DIREKOD bersama id pengguna daripada JWT")
    void sebabDirekod() {
        controller.cancelDocument(invoisId, sebab("  amaun salah  "));

        var rec = jdbc.sql("""
                SELECT cancel_reason, cancelled_by
                FROM   financial_document WHERE id = :id
                """).param("id", invoisId)
                .query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)})
                .single();

        assertThat(rec[0])
                .as("ruang di hujung dipangkas")
                .isEqualTo("amaun salah");
        assertThat(rec[1])
                .as("JWT subject ialah app_user.id")
                .isEqualTo("77");
    }

    // ── pemilihan laluan ─────────────────────────────────────────────

    @Test
    @DisplayName("RESIT menggunakan cancelReceipt — payment.id dicari daripada document.id")
    void resitGunaLaluanResit() {
        var res = controller.cancelDocument(resitDocId, sebab("cek pulang"));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(status(resitDocId)).isEqualTo("CANCELLED");

        String pStatus = jdbc.sql(
                "SELECT status FROM payment WHERE receipt_document_id = :id")
                .param("id", resitDocId).query(String.class).single();
        assertThat(pStatus)
                .as("cancelInvoice TIDAK akan menanda entiti Payment — itu "
                    + "sebab laluan berasingan wujud")
                .isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("membatalkan dua kali ditolak")
    void duaKaliDitolak() {
        controller.cancelDocument(invoisId, sebab("sekali"));
        em.flush();

        assertThatThrownBy(() -> controller.cancelDocument(invoisId, sebab("dua kali")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sudah dibatalkan");
    }

    // ── pengasingan penyewa ──────────────────────────────────────────

    @Test
    @DisplayName("dokumen SP LAIN tidak dijumpai")
    void dokumenSpLain() {
        assertThatThrownBy(() -> controller.cancelDocument(docSpLain, sebab("cuba")))
                .as("membatalkan dokumen SP lain ialah kerosakan data merentas "
                    + "penyewa, bukan sekadar kebocoran bacaan")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tidak dijumpai");

        assertThat(status(docSpLain)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("dokumen tidak wujud ditolak dengan mesej SAMA")
    void dokumenHantu() {
        assertThatThrownBy(() -> controller.cancelDocument(99999999L, sebab("cuba")))
                .as("mesej berbeza membenarkan penyerang membilang id dokumen")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tidak dijumpai");
    }
}
