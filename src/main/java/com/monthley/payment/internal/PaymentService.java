package com.monthley.payment.internal;

import com.monthley.document.api.DocumentPort;
import com.monthley.document.api.NewReceipt;
import com.monthley.ledger.api.*;
import com.monthley.payment.api.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Terima bayaran, peruntuk FIFO, post ke ledger.
 *
 * Aliran:
 *   1. semak minimum amount SP
 *   2. cipta resit sebagai DOKUMEN (financial_document type RECEIPT)
 *   3. FIFO agih ke invois (hormati selective gate)
 *   4. setiap agihan → fi_allocation (debit=invois, credit=resit)
 *   5. post ledger: Dr Bank / Cr AR (+ Cr Deposit jika lebih)
 *
 * Pembatalan = contra ledger + status REVERSED pada allocation + batal resit.
 */
@Service
class PaymentService implements PaymentPort {

    private final PaymentRepository payments;
    private final AllocationRepository allocations;
    private final DocumentPort documents;
    private final LedgerPort ledger;
    private final AllocationGuard guard;
    private final LineAllocationWriter lineWriter;

    @PersistenceContext
    private EntityManager em;

    PaymentService(PaymentRepository payments, AllocationRepository allocations,
                   DocumentPort documents, LedgerPort ledger, AllocationGuard guard,
                   LineAllocationWriter lineWriter) {
        this.payments = payments;
        this.allocations = allocations;
        this.documents = documents;
        this.ledger = ledger;
        this.guard = guard;
        this.lineWriter = lineWriter;
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<OutstandingInvoice> outstandingFor(Long accountId) {
        List<Object[]> rows = em.createNativeQuery("""
            SELECT d.id, d.doc_no, d.account_id, p.name_, d.doc_date, d.due_date,
                   (d.amount + d.tax_amount) AS total,
                   COALESCE(a.paid, 0) AS paid
            FROM financial_document d
            LEFT JOIN fi_period p ON p.period_id = d.period_id
            LEFT JOIN (
                SELECT debit_document_id, SUM(amount) AS paid
                FROM fi_allocation WHERE status = 'ACTIVE'
                GROUP BY debit_document_id
            ) a ON a.debit_document_id = d.id
            WHERE d.account_id = :acc
              AND d.doc_type IN ('INVOICE','DEBIT_NOTE')
              AND d.status <> 'CANCELLED'
              AND (d.amount + d.tax_amount) - COALESCE(a.paid,0) > 0.005
            ORDER BY COALESCE(d.due_date, d.doc_date) ASC, d.period_id ASC, d.doc_no ASC
            """)
            .setParameter("acc", accountId)
            .getResultList();

        List<OutstandingInvoice> result = new ArrayList<>();
        for (Object[] r : rows) {
            BigDecimal total = (BigDecimal) r[6];
            BigDecimal paid = (BigDecimal) r[7];
            result.add(new OutstandingInvoice(
                    ((Number) r[0]).longValue(), (String) r[1],
                    r[2] == null ? null : ((Number) r[2]).longValue(),
                    (String) r[3],
                    toLocalDate(r[4]),
                    toLocalDate(r[5]),
                    total, paid, total.subtract(paid)));
        }
        return result;
    }

    @Override
    @Transactional
    public PaymentResult receivePayment(NewPayment req) {
        // Minimum TIDAK terpakai pada bayaran gerbang.
        //
        // Bayaran online sudah menyemaknya sebelum bil dicipta — pada
        // JUMLAH transaksi, bukan pada setiap akaun. Menyemak semula di
        // sini memecahkan bayaran merentas akaun: pelanggan membayar RM2
        // untuk dua akaun (RM1 setiap satu), dan setiap pecahan gagal
        // terhadap minimum RM80.
        //
        // Akibatnya paling teruk yang mungkin: wang SUDAH diterima oleh
        // gerbang, callback gagal, dan tiada resit tercipta. Pelanggan
        // membayar dan invois kekal terbuka.
        //
        // Minimum ialah tentang kos transaksi gerbang, dan gerbang
        // mengenakan yuran sekali pada transaksi — bukan sekali setiap
        // akaun.
        if (req.method() != PaymentMethod.FPX) {
            BigDecimal min = minPaymentAmount(req.spCode());
            if (min.signum() > 0 && req.amount().compareTo(min) < 0) {
                throw new PaymentBelowMinimumException(req.amount(), min);
            }
        }

        // Idempotency (ADR 0004): kalau key ni sudah diproses, pulang resit sedia
        // ada — JANGAN proses lagi (elak double-entry).
        if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
            var existing = payments.findBySpCodeAndIdempotencyKey(req.spCode(), req.idempotencyKey());
            if (existing.isPresent()) {
                Payment pmt = existing.get();
                return new PaymentResult(pmt.getId(), pmt.getReceiptDocumentId(),
                        docNoFor(pmt.getReceiptDocumentId()),
                        pmt.getAllocatedAmount(), pmt.getDepositAmount());
            }
        }

        // AKAUN ADHOC — semua invois adhoc SP berkongsi satu akaun (V50),
        // jadi FIFO merentasi invois orang yang tiada kaitan. Bayaran
        // pembeli buku A akan menutup invois pembeli B, dan tiada apa
        // yang kelihatan salah sehingga seseorang mengadu.
        boolean adhoc = akaunAdhoc(req.payerAccountId());
        if (adhoc
                && (req.targetDocumentIds() == null || req.targetDocumentIds().isEmpty())) {
            throw new IllegalStateException(
                    "Bayaran untuk invois adhoc mesti menyatakan invois. "
                    + "Gunakan tab Cari Invois.");
        }
        // TEPAT SATU, bukan sekadar sekurang-kurangnya satu.
        //
        // Setiap invois adhoc dikeluarkan kepada orang BERBEZA. Satu resit
        // yang membayar dua daripadanya tidak boleh menjawab "resit ini
        // untuk siapa" — dan resit ialah dokumen yang diserahkan kepada
        // orang awam, bukan kepada SP yang memahami akaun teknikal.
        //
        // Semakan asal hanya menuntut senarai TIDAK KOSONG. UI menghadkan
        // pilihan kepada satu invois, tetapi UI bukan guard: mana-mana
        // klien boleh menghantar dua ID.
        if (adhoc && req.targetDocumentIds().size() > 1) {
            throw new IllegalStateException(
                    "Satu resit untuk satu invois adhoc. Setiap invois "
                    + "dikeluarkan kepada orang berbeza.");
        }

        // Calon invois (hormati selective gate)
        List<OutstandingInvoice> candidates = outstandingFor(req.payerAccountId());

        if (req.targetDocumentIds() != null && !req.targetDocumentIds().isEmpty()) {
            if (adhoc) {
                // Akaun adhoc: penapis WAJIB, tanpa mengira allow_selective.
                // Akaun ADHOC-SALES dikongsi antara pembeli yang tidak
                // berkaitan, jadi limpahan bermakna duit seorang menjelaskan
                // invois orang lain.
                candidates = candidates.stream()
                        .filter(i -> req.targetDocumentIds().contains(i.documentId()))
                        .toList();

            } else if (allowSelective(req.spCode())) {
                // Invois yang dipilih menetapkan KEUTAMAAN, bukan HAD.
                //
                // FIFO mengalir melalui invois yang ditanda dahulu; jika
                // amaun masih berbaki, ia meneruskan melalui invois
                // tertunggak yang lain.
                //
                // Menapis kepada yang ditanda sahaja bermakna pelanggan yang
                // menanda satu invois RM80 dan membayar RM100 mendapat
                // advance RM20 — sedangkan invois lain masih tertunggak.
                // Advance yang wujud bersama tunggakan mengelirukan: baki
                // akaun menunjukkan hutang, dan pada masa sama sistem
                // memegang kredit yang tidak digunakan.
                //
                // Advance hanya sah apabila SEMUA invois telah dijelaskan.
                List<Long> dipilih = req.targetDocumentIds();
                List<OutstandingInvoice> ditanda = candidates.stream()
                        .filter(i -> dipilih.contains(i.documentId()))
                        .toList();
                List<OutstandingInvoice> selebihnya = candidates.stream()
                        .filter(i -> !dipilih.contains(i.documentId()))
                        .toList();

                List<OutstandingInvoice> tersusun =
                        new java.util.ArrayList<>(ditanda);
                tersusun.addAll(selebihnya);
                candidates = tersusun;
            }
        }

        // FIFO
        FifoAllocator.Result alloc = FifoAllocator.allocate(req.amount(), candidates);
        BigDecimal allocated = alloc.allocations().stream()
                .map(FifoAllocator.Allocation::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // LEBIHAN DITOLAK untuk adhoc. Advance pada akaun kongsi ialah
        // duit orang lain: pembeli seterusnya akan menggunakannya, dan
        // memulangkannya kepada pembayar asal memerlukan menjejak siapa
        // membayar apa — yang akaun kongsi tidak boleh lakukan.
        if (adhoc && allocated.compareTo(req.amount()) < 0) {
            throw new IllegalStateException(
                    "Bayaran melebihi jumlah invois. Amaun mesti tepat atau kurang.");
        }

        // Tarikh bayaran DITERIMA, bukan tarikh rekod dicipta. Kerani boleh
        // merekod bayaran dua hari lepas; tarikh itu mesti sama pada resit,
        // dalam ledger, dan pada rekod bayaran — jika tidak baki berjalan
        // salah untuk hari-hari antara dan rekonsiliasi bank tidak tally.
        //
        // Diambil SEKALI di sini supaya ketiga-tiganya tidak boleh menyimpang.
        LocalDate tarikhBayar = req.paymentDate() == null
                ? LocalDate.now()
                : req.paymentDate();

        // 1. Cipta resit sebagai dokumen
        Long receiptDocId = documents.createReceipt(new NewReceipt(
                req.spCode(), req.payerAccountId(), tarikhBayar,
                "Resit bayaran", req.amount()));

        // Resit adhoc dikeluarkan kepada ORANG, bukan kepada akaun.
        //
        // ADHOC-SALES ialah akaun teknikal yang dikongsi (V50) dan tidak
        // membawa nama sesiapa, jadi 'Terima Daripada' pada resit kekal
        // kosong dan pemegangnya melihat 'ADHOC-SALES' — rentetan yang
        // tidak bermakna kepada orang awam yang keretanya dikunci.
        //
        // Disalin sebagai SNAPSHOT, bukan disoal melalui alokasi. Resit
        // itu memang dikeluarkan kepada orang itu; membetulkan nama pada
        // invois kemudian tidak sepatutnya menulis semula dokumen yang
        // sudah dicetak (CASE-004: teks ialah snapshot papar).
        //
        // Selamat kerana guard di atas menjamin TEPAT SATU invois sasaran.
        if (adhoc) {
            em.createNativeQuery("""
                    UPDATE financial_document r
                      JOIN financial_document i ON i.id = :inv
                       SET r.issued_to_name  = i.issued_to_name,
                           r.issued_to_email = i.issued_to_email,
                           r.issued_to_phone = i.issued_to_phone
                     WHERE r.id = :resit
                    """)
                    .setParameter("inv", req.targetDocumentIds().get(0))
                    .setParameter("resit", receiptDocId)
                    .executeUpdate();
        }

        // 2. Rekod payment (detail kaedah/ref)
        Payment payment = new Payment(req.spCode(), receiptDocId,
                req.payerAccountId(), req.amount(), req.method(), req.paymentRefNo(),
                tarikhBayar);
        payment.setRemarks(req.remarks());
        payment.setTotals(allocated, alloc.deposit());

        // 3. Post ledger: Dr Bank / Cr AR (+ Cr Deposit)
        List<PostingLine> pl = new ArrayList<>();
        pl.add(PostingLine.debit(GlAccounts.BANK, req.amount(), null));
        if (allocated.signum() > 0) {
            pl.add(PostingLine.credit(GlAccounts.ACCOUNTS_RECEIVABLE, allocated, null));
        }
        if (alloc.deposit().signum() > 0) {
            pl.add(PostingLine.credit(GlAccounts.CUSTOMER_DEPOSIT, alloc.deposit(), null));
        }
        Long journalId = ledger.post(new PostingRequest(
                req.spCode(), tarikhBayar, SourceType.PAYMENT, receiptDocId,
                "Resit doc " + receiptDocId, pl, null));
        payment.setJournalEntryId(journalId);
        payment.setIdempotencyKey(req.idempotencyKey());
        try {
            payments.saveAndFlush(payment);
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Race: request lain menang dgn key sama. Pulang resit yang berjaya.
            if (req.idempotencyKey() != null && !req.idempotencyKey().isBlank()) {
                var won = payments.findBySpCodeAndIdempotencyKey(req.spCode(), req.idempotencyKey());
                if (won.isPresent()) {
                    Payment w = won.get();
                    return new PaymentResult(w.getId(), w.getReceiptDocumentId(),
                            docNoFor(w.getReceiptDocumentId()),
                            w.getAllocatedAmount(), w.getDepositAmount());
                }
            }
            throw dup;
        }

        // 4. fi_allocation setiap agihan (debit=invois, credit=resit)
        for (FifoAllocator.Allocation a : alloc.allocations()) {
            // Invariant + kunci pesimis SATU tempat (elak drift family 1/2).
            guard.checkAndLock(a.documentId(), a.amount());
            // Pecah mengikut line (ADR 0006) — jumlah per dokumen kekal sama.
            lineWriter.write(req.spCode(), req.payerAccountId(),
                    a.documentId(), receiptDocId, a.amount());
        }

        return new PaymentResult(payment.getId(), receiptDocId,
                docNoFor(receiptDocId), allocated, alloc.deposit());
    }

    @Override
    @Transactional
    public void cancelReceipt(Long receiptId, String reason) {
        cancelReceipt(receiptId, reason, null);
    }

    @Override
    @Transactional
    public void cancelReceipt(Long receiptId, String reason, Long cancelledBy) {
        Payment payment = payments.findById(receiptId)
                .orElseThrow(() -> new IllegalArgumentException("Resit tak wujud: " + receiptId));
        if (payment.getStatus() == Payment.Status.CANCELLED) {
            throw new IllegalStateException("Resit sudah dibatalkan: " + receiptId);
        }
        if (payment.getJournalEntryId() != null) {
            ledger.reverse(payment.getJournalEntryId(), reason);
        }
        // Nyah-aktif semua allocation resit ni (status REVERSED) → invois terbuka semula
        em.createNativeQuery(
            "UPDATE fi_allocation SET status='REVERSED' WHERE credit_document_id = :rcp")
            .setParameter("rcp", payment.getReceiptDocumentId())
            .executeUpdate();
        // Batalkan dokumen resit
        documents.cancelDocument(payment.getReceiptDocumentId(), reason, cancelledBy);
        payment.markCancelled();
    }

    private static java.time.LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.LocalDate ld) return ld;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        return java.time.LocalDate.parse(o.toString());
    }

    // --- setting SP ---
    private BigDecimal minPaymentAmount(String spCode) {
        try {
            Object v = em.createNativeQuery(
                "SELECT min_pymt_amount FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getSingleResult();
            return v == null ? BigDecimal.ZERO : new BigDecimal(v.toString());
        } catch (RuntimeException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Akaun ADHOC-SALES SP (V50) — dikongsi oleh semua invois adhoc.
     *
     * Bayaran ke akaun ini memerlukan sekatan tambahan kerana invois di
     * dalamnya milik orang yang tiada kaitan antara satu sama lain.
     */
    private boolean akaunAdhoc(Long accountId) {
        if (accountId == null) return false;
        var r = em.createNativeQuery(
                "SELECT account_type FROM account WHERE id = :id")
                .setParameter("id", accountId).getResultList();
        return !r.isEmpty() && "ADHOC".equals(r.get(0));
    }

    private boolean allowSelective(String spCode) {
        try {
            Object v = em.createNativeQuery(
                "SELECT allow_selective FROM service_provider WHERE sp_code = :sp")
                .setParameter("sp", spCode).getSingleResult();
            return v != null && ("1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    @Transactional
    public void cancelInvoice(Long invoiceDocumentId, String reason, Long cancelledBy) {
        Object[] doc = (Object[]) em.createNativeQuery(
                "SELECT doc_type, doc_no, status FROM financial_document WHERE id = :id")
                .setParameter("id", invoiceDocumentId)
                .getResultList().stream().findFirst().orElse(null);
        if (doc == null) {
            throw new IllegalArgumentException("Dokumen tak wujud: " + invoiceDocumentId);
        }
        String type = (String) doc[0];
        if (!"INVOICE".equals(type) && !"DEBIT_NOTE".equals(type)) {
            // Resit mempunyai laluannya sendiri: ia perlu menanda entiti
            // Payment juga, dan cancelReceipt menerima payment.id.
            throw new IllegalStateException(
                    "Gunakan cancelReceipt untuk dokumen " + type + ".");
        }
        if ("CANCELLED".equals(doc[2])) {
            throw new IllegalStateException("Dokumen sudah dibatalkan: " + doc[1]);
        }

        // Balikkan catatan ledger sebagai CONTRA, bukan padam — jejak audit
        // kekal. Invois tiada entiti Payment untuk memegang journalEntryId,
        // jadi ia dicari melalui source_document_id.
        @SuppressWarnings("unchecked")
        java.util.List<Number> jurnal = em.createNativeQuery(
                "SELECT id FROM journal_entry "
                + "WHERE source_document_id = :id AND source_type = 'INVOICE' "
                + "  AND status <> 'REVERSED'")
                .setParameter("id", invoiceDocumentId)
                .getResultList();
        for (Number j : jurnal) {
            ledger.reverse(j.longValue(), reason);
        }

        // LEPASKAN alokasi yang membayar invois ini. Duit kembali menjadi
        // advance dan bayaran seterusnya akan menggunakannya. Kalau tidak,
        // resit kekal 'digunakan' pada invois yang tidak lagi wujud dan
        // advance itu tidak boleh dicapai.
        em.createNativeQuery(
            "UPDATE fi_allocation SET status='REVERSED' WHERE debit_document_id = :inv")
            .setParameter("inv", invoiceDocumentId)
            .executeUpdate();

        // TIADA jadual baris alokasi berasingan: fi_allocation SENDIRI
        // membawa debit_document_line_id, jadi satu UPDATE melepaskan
        // kedua-dua peringkat. Percubaan pertama menulis UPDATE kedua ke
        // 'fi_allocation_line' yang tidak wujud — SQLSyntaxErrorException
        // semasa larian, bukan kompil.

        documents.cancelDocument(invoiceDocumentId, reason, cancelledBy);
    }

    /**
     * Nombor resit sebenar daripada dokumen.
     *
     * Sebelum ini PaymentResult mengembalikan "RCP-" + id — rentetan yang
     * dikarang, bukan doc_no. Itu mengabaikan penomboran dokumen
     * sepenuhnya: SP menetapkan prefix 'R26' dalam Tetapan Resit dan
     * melihat 'RCP-123' pada skrin.
     *
     * (Penomboran itu sendiri masih tidak membaca sp_document_setting —
     * kerja berasingan yang menyentuh semua jenis dokumen.)
     */
    private String docNoFor(Long documentId) {
        Object v = em.createNativeQuery(
                "SELECT doc_no FROM financial_document WHERE id = :id")
                .setParameter("id", documentId)
                .getSingleResult();
        return v == null ? null : v.toString();
    }
}
