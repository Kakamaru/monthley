-- Resit PDF — kepala dan butiran bayaran.
--
-- Modul statement mempunyai allowedDependencies = { shared } dan tidak
-- boleh mengimport Payment daripada payment/internal. Butiran bayaran
-- keluar melalui VIEW, sama seperti dokumen dan padanan.
--
-- DUA TARIKH, dua maksud (resit legacy membezakannya):
--   receipt_date  = bila bayaran DITERIMA (doc_date; kerani boleh
--                   merekod bayaran dua hari lepas)
--   issued_at     = bila resit DICIPTA (created_at, dengan masa)
--
-- 'Issued By' dan 'Bank' TIADA dalam skema:
--   created_by menyimpan kod SP ('SP0002'), bukan nama kerani
--   tiada lajur untuk nama bank pada pindahan
-- Kedua-duanya dikeluarkan daripada resit buat masa ini; templat
-- menggunakan th:if supaya ia muncul sebaik sahaja medan wujud.
CREATE OR REPLACE VIEW receipt_header AS
SELECT d.id                AS receipt_id,
       d.sp_code           AS sp_code,
       d.account_id        AS account_id,
       d.doc_no            AS receipt_no,
       d.doc_date          AS receipt_date,
       d.created_at        AS issued_at,
       d.amount + d.tax_amount AS amount_paid,
       d.status            AS status,
       p.method            AS payment_method,
       p.payment_ref_no    AS payment_ref_no,
       p.allocated_amount  AS allocated_amount,
       p.deposit_amount    AS deposit_amount
FROM       financial_document d
LEFT JOIN  payment p ON p.receipt_document_id = d.id
WHERE d.doc_type = 'RECEIPT'
  AND d.account_id IS NOT NULL;
