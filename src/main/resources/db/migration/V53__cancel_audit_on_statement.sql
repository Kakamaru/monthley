-- Jejak audit pembatalan pada penyata.
--
-- cancelled_at dan cancelled_by direkod sejak V1 dan diisi sejak
-- 9a21c19, tetapi account_document_entry hanya mengunjurkan
-- cancel_reason. Penyata boleh menunjukkan MENGAPA dokumen dibatalkan,
-- tidak SIAPA atau BILA.
--
-- "Setiap transaksi transparent supaya sistem trusted" memerlukan
-- ketiga-tiganya. Lajur sudah ada di sumber; ia cuma tidak pernah
-- sampai ke lapisan paparan.
--
-- amount dan tax_amount sudah diunjurkan (V33) — amaun ASAL dokumen
-- batal tersedia untuk dipaparkan dicoret di sebelah signed_amount
-- yang sifar. Tiada lajur baharu diperlukan untuk itu.
--
-- CREATE OR REPLACE: tiada perubahan struktur, hanya dua lajur
-- tambahan pada hujung senarai. Lapisan account_balance tidak
-- tersentuh kerana ia hanya membaca signed_amount.

CREATE OR REPLACE VIEW account_document_entry AS
SELECT d.account_id     AS account_id,
       d.sp_code        AS sp_code,
       d.id             AS document_id,
       d.doc_no         AS doc_no,
       d.doc_type       AS doc_type,
       d.doc_date       AS doc_date,
       d.due_date       AS due_date,
       d.status         AS status,
       d.title          AS title,
       d.ref_no         AS ref_no,
       d.cancel_reason  AS cancel_reason,
       d.cancelled_at   AS cancelled_at,
       d.cancelled_by   AS cancelled_by,
       d.amount         AS amount,
       d.tax_amount     AS tax_amount,
       CASE WHEN d.status = 'CANCELLED'                  THEN 0
            WHEN d.doc_type IN ('INVOICE','DEBIT_NOTE')  THEN  (d.amount + d.tax_amount)
            WHEN d.doc_type IN ('RECEIPT','CREDIT_NOTE') THEN -(d.amount + d.tax_amount)
            ELSE 0
       END              AS signed_amount
FROM financial_document d
WHERE d.account_id IS NOT NULL;
