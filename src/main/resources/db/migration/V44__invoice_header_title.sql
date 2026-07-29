-- Tajuk invois pada VIEW.
--
-- StatementHeader.statementTitle ialah tajuk PENYATA. Invois yang
-- menggunakannya memaparkan 'Statement of Account' pada dokumen invois —
-- ditemui semasa ujian pertama InvoicePdfTest.
--
-- sp_document_setting.invoice_title sudah wujud dan sudah digunakan oleh
-- lajur 'Title' pada skrin Finance Documents. VIEW mendedahkannya supaya
-- penulis PDF tidak perlu menyoal jadual tetapan sendiri.
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
WHERE d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
  AND d.account_id IS NOT NULL;
