-- financial_document.period_id: alih rujukan dari accounting_period ke fi_period.
--
-- V1 mencipta period_id -> accounting_period (tutup buku bulanan OPEN/CLOSED).
-- Jadual itu TIDAK PERNAH digunakan — tiada rujukan Java. Ia niat masa depan.
--
-- V6 kemudian menambah period VARCHAR(7) 'YYYY-MM' kerana entiti ditulis
-- guna String. Jadi dua lajur period wujud, satu tidak dipakai.
--
-- Sekarang period_id membawa period LARIAN (aras charge_frequency AKAUN),
-- berbeza dari financial_document_line.period_id iaitu period LIPUTAN
-- (aras produk). Rujuk docs/domain/billing-rules.md §3
--
-- Nota: bila tutup buku dilaksana nanti, ia perlukan lajur SENDIRI
-- (accounting_period_id) — bukan guna semula yang ini.

-- Data lama guna 'YYYY-MM'. Atas skema bersih ini no-op.
UPDATE financial_document d
  JOIN fi_period p ON p.charge_code = 'MO'
   AND DATE_FORMAT(p.start_dt, '%Y-%m') = d.period
   SET d.period_id = p.period_id
 WHERE d.period IS NOT NULL;

ALTER TABLE financial_document DROP FOREIGN KEY fk_doc_period;
ALTER TABLE financial_document DROP COLUMN period;
ALTER TABLE financial_document ADD KEY idx_doc_period (period_id);
ALTER TABLE financial_document ADD CONSTRAINT fk_doc_period
  FOREIGN KEY (period_id) REFERENCES fi_period (period_id);
