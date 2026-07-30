-- Invois adhoc memaparkan butiran PENERIMA, bukan akaun teknikal.
--
-- Semua invois adhoc berkongsi satu akaun ADHOC-SALES (V50), jadi PDF
-- memaparkan 'No. Akaun: ADHOC-SALES' dan 'Nama Akaun: Jualan Adhoc'
-- kepada pembeli yang tidak tahu apa itu — dan BILL TO kosong sama
-- sekali.
--
-- Butiran sebenar ada pada dokumen (V49). VIEW mendedahkannya bersama
-- penanda supaya penulis PDF tahu yang mana hendak digunakan.
CREATE OR REPLACE VIEW invoice_header AS
SELECT d.id                     AS invoice_id,
       d.sp_code                AS sp_code,
       d.account_id             AS account_id,
       d.doc_no                 AS invoice_no,
       d.doc_date               AS invoice_date,
       d.due_date               AS due_date,
       d.created_at             AS issued_at,
       d.period_id              AS period_id,
       COALESCE(fp.name_, '')   AS period_name,
       d.amount + d.tax_amount  AS new_charges,
       d.tax_amount             AS tax_amount,
       d.status                 AS status,
       COALESCE(NULLIF(s.invoice_title, ''), 'Invois') AS invoice_title,
       -- Penanda adhoc: akaun TEKNIKAL, bukan pelanggan.
       CASE WHEN a.account_type = 'ADHOC' THEN 1 ELSE 0 END AS adhoc,
       d.issued_to_name         AS issued_to_name,
       d.issued_to_email        AS issued_to_email,
       d.issued_to_phone        AS issued_to_phone,
       d.remarks                AS remarks,
       COALESCE((
           SELECT SUM(e.signed_amount)
           FROM   account_document_entry e
           WHERE  e.account_id = d.account_id
             AND  (e.doc_date < d.doc_date
                   OR (e.doc_date = d.doc_date AND e.document_id < d.id))
       ), 0)                    AS balance_before
FROM       financial_document d
LEFT JOIN  fi_period fp ON fp.period_id = d.period_id
LEFT JOIN  sp_document_setting s ON s.sp_code = d.sp_code
LEFT JOIN  account a ON a.id = d.account_id
WHERE d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
  AND d.account_id IS NOT NULL;
