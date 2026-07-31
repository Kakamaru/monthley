-- Nama penerima pada resit adhoc.
--
-- 'Terima Daripada' dibaca daripada billto_name AKAUN (statement_header,
-- V35). Untuk invois adhoc akaunnya ADHOC-SALES — teknikal, dikongsi,
-- tanpa nama sesiapa — jadi blok itu kekal KOSONG dan pemegang resit
-- melihat 'ADHOC-SALES' sebagai satu-satunya pengenalan.
--
-- Resit diserahkan kepada ORANG AWAM, bukan kepada SP yang memahami
-- akaun teknikal. Nombor akaun itu tidak bermakna kepada seseorang yang
-- keretanya baru dikunci.
--
-- Nama disalin ke dokumen RESIT semasa bayaran (PaymentService), bukan
-- disoal melalui alokasi: resit itu memang dikeluarkan kepada orang itu,
-- dan membetulkan nama pada invois kemudian tidak sepatutnya menulis
-- semula dokumen yang sudah dicetak. CASE-004: teks ialah snapshot papar.
--
-- Selamat kerana guard bayaran menjamin TEPAT SATU invois adhoc setiap
-- resit — satu resit yang membayar dua tidak boleh menjawab "untuk siapa".
--
-- ASAS: V40, bukan V38.
--
-- CREATE OR REPLACE VIEW menulis ganti SEPENUHNYA. Draf pertama migrasi
-- ini disalin daripada V38 dan akan membuang lajur `remarks` yang V40
-- tambah — senyap, tanpa ralat, sehingga ReceiptHead membacanya semasa
-- larian. Salin daripada takrifan HIDUP (information_schema), bukan
-- daripada fail migrasi lama.

CREATE OR REPLACE VIEW receipt_header AS
SELECT d.id                AS receipt_id,
       d.sp_code           AS sp_code,
       d.account_id        AS account_id,
       d.doc_no            AS receipt_no,
       d.doc_date          AS receipt_date,
       d.created_at        AS issued_at,
       d.amount + d.tax_amount AS amount_paid,
       d.status            AS status,
       d.issued_to_name    AS issued_to_name,
       d.issued_to_email   AS issued_to_email,
       d.issued_to_phone   AS issued_to_phone,
       p.method            AS payment_method,
       p.payment_ref_no    AS payment_ref_no,
       p.remarks           AS remarks,
       p.allocated_amount  AS allocated_amount,
       p.deposit_amount    AS deposit_amount
FROM       financial_document d
LEFT JOIN  payment p ON p.receipt_document_id = d.id
WHERE d.doc_type = 'RECEIPT'
  AND d.account_id IS NOT NULL;
