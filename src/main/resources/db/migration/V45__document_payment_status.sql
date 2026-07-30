-- Status BAYARAN dokumen, untuk skrin Dokumen Kewangan.
--
-- Lajur Status dahulu memaparkan 'Aktif' untuk setiap invois yang belum
-- dibatalkan. Itu tidak memberitahu apa-apa: SEMUA invois aktif sampai
-- dibatalkan, sedangkan yang SP mahu tahu ialah invois mana belum
-- dibayar.
--
-- Data pembangunan menunjukkan masalahnya — enam invois, semuanya
-- 'ACTIVE', tiga lunas dan tiga tanpa sebarang bayaran.
--
-- Dokumen KREDIT (resit, nota kredit) tiada status bayaran; ia SENDIRI
-- bayaran. Mereka mendapat 'ACTIVE' atau 'CANCELLED'.
CREATE OR REPLACE VIEW document_payment_status AS
SELECT d.id                     AS document_id,
       d.sp_code                AS sp_code,
       d.amount + d.tax_amount  AS total,
       COALESCE(alloc.dibayar, 0) AS paid,
       (d.amount + d.tax_amount) - COALESCE(alloc.dibayar, 0) AS outstanding,
       CASE
         WHEN d.status = 'CANCELLED' THEN 'CANCELLED'
         WHEN d.doc_type NOT IN ('INVOICE', 'DEBIT_NOTE') THEN 'ACTIVE'
         -- Toleransi 0.005: aritmetik perpuluhan boleh meninggalkan baki
         -- satu sen yang bukan tunggakan sebenar.
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
