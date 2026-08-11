package com.monthley.expenses.internal;

import com.monthley.shared.Access;
import com.monthley.shared.PageResponse;
import com.monthley.shared.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Invois pembekal.
 *
 *   GET    /api/v1/expenses/invoices?status=&page=&size=
 *   GET    /api/v1/expenses/invoices/{id}
 *   POST   /api/v1/expenses/invoices
 *   DELETE /api/v1/expenses/invoices/{id}   (batal + balikkan ledger)
 *
 * Baki dan status DIBACA dari VIEW exp_invoice_balance — tiada lajur baki
 * pada exp_invoice untuk menyimpang.
 */
@RestController
@RequestMapping("/api/v1/expenses/invoices")
class ExpInvoiceController {

    private final ExpInvoiceService service;

    @PersistenceContext
    private EntityManager em;

    ExpInvoiceController(ExpInvoiceService service) {
        this.service = service;
    }

    record InvoiceRow(Long id, String invNo, Long supplierId, String supplierName,
                      LocalDate invDate, LocalDate dueDate,
                      BigDecimal subtotal, BigDecimal sstAmount, BigDecimal total,
                      BigDecimal paid, BigDecimal balance, String status,
                      boolean overdue) {}

    record ItemRow(Long id, Long categoryId, String categoryName,
                   String description, BigDecimal amount) {}

    record InvoiceDetail(InvoiceRow header, String note, List<ItemRow> items) {}

    record NewItemRequest(Long categoryId, String description, BigDecimal amount) {}

    record NewInvoiceRequest(Long supplierId, @NotBlank String invNo,
                             LocalDate invDate, LocalDate dueDate, String note,
                             List<NewItemRequest> items) {}

    record CancelRequest(String reason) {}

    @GetMapping
    @SuppressWarnings("unchecked")
    PageResponse<InvoiceRow> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Access.requireAnyRole("melihat invois pembekal", "SP_ADMIN", "CLERK");

        String tapis = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status))
                ? null : status.toUpperCase();

        String base = """
            FROM   exp_invoice i
            JOIN   exp_supplier s   ON s.id = i.supplier_id
            JOIN   exp_invoice_balance b ON b.invoice_id = i.id
            WHERE  i.sp_code = :sp
              AND (:st IS NULL OR b.status = :st)
            """;

        var countQ = em.createNativeQuery("SELECT COUNT(*) " + base);
        countQ.setParameter("sp", sp());
        countQ.setParameter("st", tapis);
        long total = ((Number) countQ.getSingleResult()).longValue();

        var q = em.createNativeQuery(
                "SELECT i.id, i.inv_no, i.supplier_id, s.name, i.inv_date, i.due_date, "
                + "i.subtotal, i.sst_amount, i.total, b.paid, b.balance, b.status, "
                + "(i.due_date IS NOT NULL AND i.due_date < CURDATE() AND b.balance > 0) AS overdue "
                + base + " ORDER BY i.inv_date DESC, i.id DESC LIMIT :lim OFFSET :off");
        q.setParameter("sp", sp());
        q.setParameter("st", tapis);
        q.setParameter("lim", size);
        q.setParameter("off", page * size);

        List<InvoiceRow> items = new ArrayList<>();
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            items.add(baris(r));
        }
        return new PageResponse<>(items, total, page, size);
    }

    @GetMapping("/{id}")
    @SuppressWarnings("unchecked")
    InvoiceDetail get(@PathVariable Long id) {
        Access.requireAnyRole("melihat invois pembekal", "SP_ADMIN", "CLERK");

        Object[] h = (Object[]) em.createNativeQuery("""
                SELECT i.id, i.inv_no, i.supplier_id, s.name, i.inv_date, i.due_date,
                       i.subtotal, i.sst_amount, i.total, b.paid, b.balance, b.status,
                       (i.due_date IS NOT NULL AND i.due_date < CURDATE() AND b.balance > 0),
                       i.note
                FROM   exp_invoice i
                JOIN   exp_supplier s ON s.id = i.supplier_id
                JOIN   exp_invoice_balance b ON b.invoice_id = i.id
                WHERE  i.sp_code = :sp AND i.id = :id
                """).setParameter("sp", sp()).setParameter("id", id).getSingleResult();

        List<ItemRow> items = new ArrayList<>();
        var q = em.createNativeQuery("""
                SELECT it.id, it.category_id, c.name, it.description, it.amount
                FROM   exp_invoice_item it
                JOIN   exp_category c ON c.id = it.category_id
                WHERE  it.invoice_id = :id ORDER BY it.id
                """).setParameter("id", id);
        for (Object[] r : (List<Object[]>) q.getResultList()) {
            items.add(new ItemRow(((Number) r[0]).longValue(), ((Number) r[1]).longValue(),
                    (String) r[2], (String) r[3], (BigDecimal) r[4]));
        }
        return new InvoiceDetail(baris(h), (String) h[13], items);
    }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody NewInvoiceRequest r) {
        Access.requireRole("SP_ADMIN", "merekod invois pembekal");

        List<ExpInvoiceService.NewItem> lines = new ArrayList<>();
        if (r.items() != null) {
            for (NewItemRequest i : r.items()) {
                lines.add(new ExpInvoiceService.NewItem(i.categoryId(), i.description(), i.amount()));
            }
        }
        Long id = service.create(new ExpInvoiceService.NewInvoice(
                r.supplierId(), r.invNo(), r.invDate(), r.dueDate(), r.note(), lines));
        return ResponseEntity.ok(Map.of("id", id));
    }

    /**
     * Batal invois. Bayaran yang sudah dibuat mesti dibatalkan dahulu —
     * membatalkan invois yang sudah dibayar meninggalkan PV yang menunjuk
     * dokumen mati dan baki AP yang salah.
     */
    @DeleteMapping("/{id}")
    @Transactional
    ResponseEntity<?> cancel(@PathVariable Long id, @RequestBody CancelRequest r) {
        Access.requireRole("SP_ADMIN", "membatalkan invois pembekal");

        String reason = (r == null || r.reason() == null) ? null : r.reason().trim();
        if (reason == null || reason.isBlank()) {
            throw new IllegalStateException("Sebab pembatalan diperlukan.");
        }

        Number bayaranAktif = (Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM exp_payment WHERE invoice_id = :id AND status = 'ACTIVE'")
                .setParameter("id", id).getSingleResult();
        if (bayaranAktif.intValue() > 0) {
            throw new IllegalStateException(
                    "Invois ini mempunyai " + bayaranAktif.intValue() + " bayaran aktif. "
                    + "Batalkan bayaran tersebut dahulu.");
        }

        service.cancel(id, reason, currentUserId());
        return ResponseEntity.ok(Map.of("message", "Invois dibatalkan."));
    }

    /** User id dari JWT subject (JwtAuthFilter set principal = subject). */
    private Long currentUserId() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("Tiada pengguna dalam konteks.");
        }
        return Long.valueOf(auth.getName());
    }

    private InvoiceRow baris(Object[] r) {
        return new InvoiceRow(
                ((Number) r[0]).longValue(), (String) r[1], ((Number) r[2]).longValue(),
                (String) r[3], toDate(r[4]), toDate(r[5]),
                (BigDecimal) r[6], (BigDecimal) r[7], (BigDecimal) r[8],
                new BigDecimal(r[9].toString()), new BigDecimal(r[10].toString()),
                (String) r[11], toBool(r[12]));
    }

    private static LocalDate toDate(Object v) {
        return v == null ? null : ((java.sql.Date) v).toLocalDate();
    }

    /** tinyint(1) datang sebagai Boolean dari MySQL Connector/J. */
    private static boolean toBool(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private String sp() {
        String sp = TenantContext.get();
        if (sp == null || sp.isBlank()) {
            throw new IllegalStateException("Header X-SP-Id diperlukan");
        }
        return sp;
    }
}
