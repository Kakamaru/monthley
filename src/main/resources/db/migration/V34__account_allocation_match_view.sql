-- ADR 0010 P3 — VIEW padanan alokasi untuk sub-baris penyata.
--
-- Modul statement mempunyai allowedDependencies = { shared }; ia TIDAK BOLEH
-- mengimport PaymentAllocation daripada payment/internal. Padanan mesti keluar
-- melalui VIEW, sama seperti dokumen. Itu bukan halangan — ia ujian reka
-- bentuk yang lulus. Jika kita terpaksa melonggarkan sempadan modul untuk
-- menyiapkan penyata, itu petanda modul berasingan adalah silap.
--
-- Sub-baris TIDAK menggerakkan lajur baki. Alokasi ialah PADANAN (resit mana
-- membayar invois mana), bukan pergerakan baki (ADR 0009). Baki digerakkan
-- oleh dokumen sahaja, melalui account_document_entry.
--
-- DUA ARAH. Alokasi ialah credit_document_id -> debit_document_id, jadi VIEW
-- yang sama menjawab dua soalan:
--   baris RESIT  -> invois mana yang dibayarnya
--   baris INVOIS -> resit mana yang membayarnya
-- Legacy hanya boleh menjawab yang pertama.
--
-- status = 'ACTIVE' sahaja. Alokasi REVERSED mesti dikecualikan, jika tidak
-- penyata akan memaparkan resit membayar invois yang padanannya sudah
-- dibatalkan — CASE-001 (alokasi yatim) muncul semula sebagai pepijat
-- paparan.
--
-- product_name BOLEH NULL. V30 menambah debit_document_line_id sebagai
-- NULLABLE, jadi alokasi yang dibuat sebelum ADR 0006 hanya menunjuk ke
-- dokumen, bukan ke baris. Pemanggil mesti mengendalikan NULL dengan anggun.
--
-- Tempoh datang daripada BARIS, bukan dokumen. INV000021 mempunyai 12 baris
-- parking bulanan di bawah satu dokumen bertempoh '2025'; menggunakan tempoh
-- dokumen akan mencetak '2025' dua belas kali dan menyembunyikan bulan mana
-- yang sebenarnya dibayar — iaitu soalan yang sub-baris wujud untuk dijawab.
-- financial_document_line membawa period_id, period_start dan period_end
-- sendiri (keputusan 11 ADR 0010; sudah wujud dalam skema).
-- Tempoh dokumen dikekalkan sebagai sandaran untuk alokasi aras-dokumen.
--
-- debit_document_line_id didedahkan supaya pemanggil boleh membezakan baris
-- yang kelihatan serupa. Tanpanya, enam alokasi kepada enam baris parking
-- berbeza kelihatan seperti enam pendua.
CREATE OR REPLACE VIEW account_allocation_match AS
SELECT a.sp_code             AS sp_code,
       a.account_id          AS account_id,
       a.credit_document_id  AS credit_document_id,
       a.debit_document_id   AS debit_document_id,
       a.debit_document_line_id AS debit_document_line_id,
       cd.doc_no             AS credit_doc_no,
       cd.doc_date           AS credit_doc_date,
       dd.doc_no             AS debit_doc_no,
       dd.doc_date           AS debit_doc_date,
       COALESCE(lp.name_, fp.name_)       AS debit_period,
       COALESCE(l.period_start, lp.start_dt, fp.start_dt) AS debit_period_start,
       COALESCE(l.period_end,   lp.end_dt,   fp.end_dt)   AS debit_period_end,
       p.name                AS product_name,
       l.description         AS line_description,
       dd.title              AS debit_title,
       a.amount              AS amount
FROM       fi_allocation a
JOIN       financial_document      cd ON cd.id        = a.credit_document_id
JOIN       financial_document      dd ON dd.id        = a.debit_document_id
LEFT JOIN  financial_document_line l  ON l.id         = a.debit_document_line_id
                                      AND l.active     = 1
LEFT JOIN  product                 p  ON p.id         = l.product_id
LEFT JOIN  fi_period               lp ON lp.period_id = l.period_id
LEFT JOIN  fi_period               fp ON fp.period_id = dd.period_id
WHERE a.status = 'ACTIVE';
