-- ONE_TIME: caj sekali seumur hidup, bukan sekali setahun.
--
-- Masalah: idem_key guna period_start. Untuk produk 1T, period_start = 1 Jan
-- tahun semasa, jadi kunci berbeza setiap tahun -> boleh caj berulang.
-- period_start NULL pula membatalkan kekangan sepenuhnya (CONCAT dgn NULL = NULL),
-- yang BETUL untuk baris usage/per-use tetapi salah untuk 1T.
--
-- Penyelesaian: denormal bendera pada baris (corak sama seperti account_id),
-- dan cabangkan idem_key.
--
-- Lebih baik daripada legacy: legacy guna sub.last_charged_period sebagai
-- penunjuk. Batalkan invois 1T -> penunjuk kekal set -> produk tak boleh
-- dicaj semula selamanya tanpa edit DB. Di sini: active=0 -> idem_key NULL
-- -> boleh jana semula.
ALTER TABLE financial_document_line
  ADD COLUMN once_only BOOLEAN NOT NULL DEFAULT 0 AFTER period_id;

ALTER TABLE financial_document_line DROP INDEX uk_line_idem;
ALTER TABLE financial_document_line DROP COLUMN idem_key;

ALTER TABLE financial_document_line ADD COLUMN idem_key VARCHAR(120)
  GENERATED ALWAYS AS (
    CASE WHEN active = 1 THEN
      CASE WHEN once_only = 1
           THEN CONCAT(account_id, ':', product_id, ':ONCE')
           ELSE CONCAT(account_id, ':', product_id, ':', period_start)
      END
    END
  ) STORED;

ALTER TABLE financial_document_line ADD UNIQUE KEY uk_line_idem (idem_key);
