-- ADR 0010 — baris dokumen untuk sub-baris penyata.
--
-- Baris ikut DOKUMEN (keputusan 3) menyembunyikan pecahan caj: satu invois
-- dengan 12 baris parking bulanan menjadi satu baris berbunyi 'Invois M01',
-- dan pelanggan tidak nampak dia dicaj untuk apa. Legacy memaparkannya
-- kerana ia mencetak satu baris per baris-ledger.
--
-- Penyelesaiannya bukan kembali kepada baris-per-baris, tetapi memberi
-- invois sub-baris yang sama seperti resit sudah ada. Sub-baris sentiasa
-- menjawab satu soalan: dokumen ini terdiri daripada apa.
--
--   INVOICE / DEBIT_NOTE   -> baris dokumen (produk, tempoh, amaun)
--   RECEIPT / CREDIT_NOTE  -> alokasi (invois mana dibayar)
--
-- Sub-baris TIDAK menggerakkan lajur baki; dokumen yang menggerakkannya.
--
-- Tempoh daripada BARIS, bukan dokumen — INV000021 produksi mempunyai 12
-- baris bulanan di bawah satu dokumen bertempoh '2025'.
--
-- active = 1 sahaja. Baris tidak aktif dikecualikan atas sebab yang sama
-- seperti alokasi REVERSED: memaparkannya bermakna menunjukkan caj yang
-- sudah ditarik balik.
CREATE OR REPLACE VIEW account_document_line AS
SELECT d.sp_code                                   AS sp_code,
       d.account_id                                AS account_id,
       d.id                                        AS document_id,
       l.id                                        AS line_id,
       COALESCE(p.name, l.description, d.title)    AS description,
       COALESCE(l.period_start, lp.start_dt)       AS period_start,
       COALESCE(l.period_end,   lp.end_dt)         AS period_end,
       (l.amount + l.tax_amount)                   AS amount
FROM       financial_document_line l
JOIN       financial_document      d  ON d.id         = l.document_id
LEFT JOIN  product                 p  ON p.id         = l.product_id
LEFT JOIN  fi_period               lp ON lp.period_id = l.period_id
WHERE l.active = 1
  AND d.account_id IS NOT NULL;
