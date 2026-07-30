-- Dokumen kredit tiada tunggakan.
--
-- V45 memaparkan outstanding = total untuk resit, kerana tiada alokasi
-- di mana resit ialah pihak DEBIT. Resit bukan hutang; ia bayaran.
-- Angka itu tidak bermakna dan sesiapa yang memaparkannya akan
-- menunjukkan tunggakan hantu.
CREATE OR REPLACE VIEW document_payment_status AS
SELECT d.id                     AS document_id,
       d.sp_code                AS sp_code,
       d.amount + d.tax_amount  AS total,
       CASE WHEN d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
            THEN COALESCE(alloc.dibayar, 0) ELSE 0 END AS paid,
       CASE WHEN d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
            THEN (d.amount + d.tax_amount) - COALESCE(alloc.dibayar, 0)
            ELSE 0 END          AS outstanding,
       CASE
         WHEN d.status = 'CANCELLED' THEN 'CANCELLED'
         WHEN d.doc_type NOT IN ('INVOICE', 'DEBIT_NOTE') THEN 'ACTIVE'
         WHEN COALESCE(alloc.dibayar, 0) >= (d.amount + d.tax_amount) - 0.005
              THEN 'PAID'
         WHEN COALESCE(alloc.dibayar, 0) > 0.005 THEN 'PARTIAL'
         ELSE 'UNPAID'
       END                      AS payment_status
FROM   financial_document d
LEFT JOIN (
        SELECT a.debit_document_id AS doc_id, SUM(a.amount) AS dibayar
        FROM   fi_allocation a
        WHERE  a.status = 'ACTIVE'
        GROUP  BY a.debit_document_id
     ) alloc ON alloc.doc_id = d.id
WHERE d.account_id IS NOT NULL;
