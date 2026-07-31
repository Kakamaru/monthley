-- Batal dokumen mesti membebaskan idem_key supaya kerani boleh jana semula.
--
-- Legacy: batalkan invois -> penunjuk sub.last_charged_period kekal set ->
-- produk tersekat selamanya tanpa edit DB. V18 dan InvoiceCalculator
-- kedua-duanya mendokumenkan penyelesaian "active=0 -> idem_key NULL ->
-- boleh jana semula" — tetapi TIADA kod pernah menetapkan active=0.
-- Mekanisme itu didokumenkan, tidak dibina. Ralat legacy yang sama hidup
-- di sini, disorok oleh dua komen yang bersetuju antara satu sama lain.
--
-- KENAPA BUKAN active=0
--
-- `active` sudah ada maksudnya sendiri: baris DITARIK BALIK (V36 — "caj
-- yang sudah ditarik balik"). Baris tidak aktif dikecualikan daripada
-- account_document_line (V36), document_line_payment_status (V47), FIFO
-- (LineAllocationResolver) dan adhoc (V48).
--
-- Menyahaktifkan baris semasa batal akan MENYEMBUNYIKAN dokumen batal
-- daripada penyata — bertentangan dengan keputusan bahawa dokumen batal
-- kekal kelihatan dan ditanda (legend penyata: aktif / batal). Prinsip
-- perakaunan sama: dokumen bernombor tidak hilang, ia dipapar dengan
-- kesan sifar.
--
-- Dua konsep memikul satu lajur. Dipisahkan di sini.
--
-- KENAPA DINYAHNORMAL
--
-- Lajur terjana MySQL hanya boleh membaca barisnya sendiri — tidak boleh
-- join ke financial_document.status. Corak sama seperti account_id
-- ('denormal untuk idempotency').
--
-- V18 TIDAK diedit walaupun komennya kini mengelirukan: ia sudah dipakai
-- dan checksumnya direkod. Mengedit migrasi yang sudah dipakai ialah
-- perkara yang checksum wujud untuk menangkap.

ALTER TABLE financial_document_line
  ADD COLUMN doc_cancelled BOOLEAN NOT NULL DEFAULT 0 AFTER active;

-- Backfill sebelum idem_key dicipta semula: baris di bawah dokumen yang
-- sudah dibatalkan mesti membebaskan kuncinya juga, bukan hanya yang akan
-- datang.
UPDATE financial_document_line l
  JOIN financial_document d ON d.id = l.document_id
   SET l.doc_cancelled = 1
 WHERE d.status = 'CANCELLED';

-- Corak sama seperti V18: gugurkan index, gugurkan lajur, cipta semula.
ALTER TABLE financial_document_line DROP INDEX uk_line_idem;
ALTER TABLE financial_document_line DROP COLUMN idem_key;

ALTER TABLE financial_document_line ADD COLUMN idem_key VARCHAR(120)
  GENERATED ALWAYS AS (
    CASE WHEN active = 1 AND doc_cancelled = 0 THEN
      CASE WHEN once_only = 1
           THEN CONCAT(account_id, ':', product_id, ':ONCE')
           ELSE CONCAT(account_id, ':', product_id, ':', period_start)
      END
    END
  ) STORED;

ALTER TABLE financial_document_line ADD UNIQUE KEY uk_line_idem (idem_key);
