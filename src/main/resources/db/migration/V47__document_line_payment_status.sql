-- Status bayaran peringkat BARIS.
--
-- Bila SP menapis ikut produk, granulariti berubah dari DOKUMEN ke BARIS.
-- Invois tak-split mempunyai tiga produk; menapis 'INSURANCE' dan
-- memaparkan invois sebagai satu baris tidak menjawab soalan — SP mahu
-- tahu bahagian INSURANCE itu sudah dibayar atau belum.
--
-- Boleh dijawab kerana alokasi kita peringkat-baris:
-- fi_allocation.debit_document_line_id. FIFO mengalokasi ke baris
-- tertentu, jadi setiap baris mempunyai status bayarannya sendiri.
--
-- NOTA DEBIT TIDAK MUNCUL di sini, dan itu betul: ia pelarasan tanpa
-- baris produk. Keempat-empat alokasi dengan line_id NULL dalam data
-- pembangunan ialah nota debit dengan sifar baris.
CREATE OR REPLACE VIEW document_line_payment_status AS
SELECT l.id                     AS line_id,
       l.document_id            AS document_id,
       d.sp_code                AS sp_code,
       d.doc_no                 AS doc_no,
       d.doc_type               AS doc_type,
       d.doc_date               AS doc_date,
       d.status                 AS doc_status,
       d.account_id             AS account_id,
       l.product_id             AS product_id,
       COALESCE(p.name, l.description) AS product_name,
       l.period_id              AS period_id,
       l.period_start           AS period_start,
       l.period_end             AS period_end,
       l.quantity               AS quantity,
       l.unit_price             AS unit_price,
       l.amount + l.tax_amount  AS total,
       COALESCE(alloc.dibayar, 0) AS paid,
       (l.amount + l.tax_amount) - COALESCE(alloc.dibayar, 0) AS outstanding,
       CASE
         WHEN d.status = 'CANCELLED' THEN 'CANCELLED'
         WHEN COALESCE(alloc.dibayar, 0) >= (l.amount + l.tax_amount) - 0.005
              THEN 'PAID'
         WHEN COALESCE(alloc.dibayar, 0) > 0.005 THEN 'PARTIAL'
         ELSE 'UNPAID'
       END                      AS payment_status
FROM   financial_document_line l
JOIN   financial_document d ON d.id = l.document_id
LEFT   JOIN product p ON p.id = l.product_id
LEFT JOIN (
        SELECT a.debit_document_line_id AS line_id, SUM(a.amount) AS dibayar
        FROM   fi_allocation a
        WHERE  a.status = 'ACTIVE' AND a.debit_document_line_id IS NOT NULL
        GROUP  BY a.debit_document_line_id
     ) alloc ON alloc.line_id = l.id
WHERE l.active = 1
  AND d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
  AND d.account_id IS NOT NULL;
