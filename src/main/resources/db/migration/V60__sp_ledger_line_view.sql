-- Lejar SP — setiap transaksi merentas semua akaun, aras BARIS.
--
-- Penyata pelanggan menjawab "akaun ini terdiri daripada apa". Lejar SP
-- menjawab soalan yang BERTENTANGAN: semua yang berlaku merentas semua
-- akaun, dari sudut SP.
--
-- TANDA DICERMINKAN.
--
-- Invois menaikkan hutang pelanggan dan MENURUNKAN baki SP: SP telah
-- mengeluarkan caj yang belum dikutip. Resit menurunkan hutang pelanggan
-- dan MENAIKKAN baki SP.
--
--   account_document_entry.signed_amount   pelanggan
--   -signed_amount                          SP
--
-- Peraturan tanda tidak ditulis semula di sini; ia diterbitkan daripada
-- V33 supaya jenis dokumen baharu dikemas kini di SATU tempat.
--
-- ARAS BARIS, DUA SUMBER
--
-- Resit tiada baris produk — ia mempunyai receipt_amount sahaja. Item
-- yang dipaparkan untuk resit ialah baris invois yang DILANGSAIKANNYA,
-- daripada alokasi.
--
--   INVOICE, DEBIT_NOTE    -> account_document_line   (baris produk)
--   RECEIPT, CREDIT_NOTE   -> account_allocation_match (baris dilangsai)
--
-- SETIAP SEN MESTI MUNCUL
--
-- Resit RM500 terhadap hutang RM300 mengalokasikan RM300 dan meninggalkan
-- RM200 sebagai advance. Advance tiada baris invois untuk dipautkan.
--
-- Membaca alokasi SAHAJA menjadikan RM200 itu hilang daripada lejar, dan
-- jumlah lajur tidak akan sepadan dengan penyata bank. Baris ketiga
-- menangkap baki yang tidak dialokasikan.
--
-- BAKI BERJALAN TIDAK ADA DI SINI
--
-- SUM() OVER bergantung pada susunan dan mesti berjalan atas SEMUA baris
-- SP sebelum tapisan pengguna — kalau tidak, menapis kepada 'Receipt'
-- akan menukar setiap nombor baki. Ia hidup dalam query, bukan VIEW.
--
-- created_at, bukan doc_date: dua resit pada hari yang sama memerlukan
-- susunan yang stabil, dan skrin memaparkan masa.

CREATE OR REPLACE VIEW sp_ledger_line AS

-- 1. Baris invois dan nota debit — produk sebenar.
SELECT e.sp_code                          AS sp_code,
       e.account_id                       AS account_id,
       e.document_id                      AS document_id,
       l.line_id                          AS line_id,
       e.doc_no                           AS doc_no,
       e.doc_type                         AS doc_type,
       e.doc_date                         AS doc_date,
       d.created_at                       AS txn_at,
       l.description                      AS item,
       l.remarks                          AS remarks,
       l.period_start                     AS period_start,
       l.period_end                       AS period_end,
       fdl.product_id                     AS product_id,
       l.amount                           AS amount,
       CASE WHEN e.status = 'CANCELLED' THEN 0
            ELSE -l.amount
       END                                AS signed_amount,
       e.status                           AS status
FROM       account_document_line l
JOIN       account_document_entry e  ON e.document_id = l.document_id
JOIN       financial_document      d  ON d.id         = l.document_id
LEFT JOIN  financial_document_line fdl ON fdl.id      = l.line_id
WHERE e.doc_type IN ('INVOICE', 'DEBIT_NOTE')

UNION ALL

-- 2. Resit dan nota kredit — baris invois yang dilangsaikan.
SELECT m.sp_code,
       m.account_id,
       m.credit_document_id,
       m.debit_document_line_id,
       m.credit_doc_no,
       e.doc_type,
       m.credit_doc_date,
       d.created_at,
       COALESCE(m.product_name, m.line_description, m.debit_title),
       NULL,
       m.debit_period_start,
       m.debit_period_end,
       fdl.product_id,
       m.amount,
       CASE WHEN e.status = 'CANCELLED' THEN 0 ELSE m.amount END,
       e.status
FROM       account_allocation_match m
JOIN       account_document_entry   e ON e.document_id = m.credit_document_id
JOIN       financial_document       d ON d.id          = m.credit_document_id
LEFT JOIN  financial_document_line fdl ON fdl.id       = m.debit_document_line_id

UNION ALL

-- 3. Baki resit yang TIDAK dialokasikan — advance.
--
-- Duit sudah masuk akaun bank hari ini; lejar mesti menunjukkannya
-- walaupun ia belum melangsaikan apa-apa invois.
SELECT e.sp_code,
       e.account_id,
       e.document_id,
       NULL,
       e.doc_no,
       e.doc_type,
       e.doc_date,
       d.created_at,
       'Bayaran Pendahuluan',
       NULL,
       NULL,
       NULL,
       NULL,
       (e.amount + e.tax_amount) - COALESCE(a.dialokasi, 0),
       CASE WHEN e.status = 'CANCELLED' THEN 0
            ELSE (e.amount + e.tax_amount) - COALESCE(a.dialokasi, 0)
       END,
       e.status
FROM       account_document_entry e
JOIN       financial_document     d ON d.id = e.document_id
LEFT JOIN (SELECT credit_document_id, SUM(amount) AS dialokasi
             FROM account_allocation_match
            GROUP BY credit_document_id) a
        ON a.credit_document_id = e.document_id
WHERE e.doc_type IN ('RECEIPT', 'CREDIT_NOTE')
  AND (e.amount + e.tax_amount) - COALESCE(a.dialokasi, 0) > 0.005;
