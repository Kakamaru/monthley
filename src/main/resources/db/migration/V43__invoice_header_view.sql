-- Invois PDF — kepala dan ringkasan.
--
-- BAKI SEBELUM ialah baki tepat SEBELUM dokumen ini, bukan baki awal
-- bulan. Bukti daripada dua invois split legacy pada tarikh yang sama:
--
--   I20204912  maintenance 70.00  ->  sebelum 1,606.15, jumlah 1,676.15
--   I20204913  sinking      7.00  ->  sebelum 1,676.15, jumlah 1,683.15
--
-- Invois kedua memasukkan yang pertama. Label legacy 'Previous Month
-- Balance' mengelirukan; nombornya jelas.
--
-- Susunan mesti sepadan dengan susunan penyata supaya kedua-dua dokumen
-- menunjukkan angka yang sama: doc_date, kemudian id.
--
-- Lajur 'Adjustments' legacy DIKELUARKAN. Pelarasan ialah dokumen
-- berasingan (nota kredit/debit), bukan sesuatu yang diubah pada invois,
-- jadi ruangan itu tidak pernah boleh berisi — ketiga-tiga sampel
-- produksi menunjukkan 0.00.
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
       COALESCE((
           SELECT SUM(e.signed_amount)
           FROM   account_document_entry e
           WHERE  e.account_id = d.account_id
             AND  (e.doc_date < d.doc_date
                   OR (e.doc_date = d.doc_date AND e.document_id < d.id))
       ), 0)                    AS balance_before
FROM       financial_document d
LEFT JOIN  fi_period fp ON fp.period_id = d.period_id
WHERE d.doc_type IN ('INVOICE', 'DEBIT_NOTE')
  AND d.account_id IS NOT NULL;
