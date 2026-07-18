-- smallest_denomination: pembundaran amaun akhir (cth 0.05 = bundar ke 5 sen).
-- 0 = tiada pembundaran. Konsep kewangan -> sp_billing_setting.
-- Rujuk legacy-generator-analysis.md §3.2, billing-rules.md §7
--
-- allow_price_override tidak di sini — ia sudah dicipta oleh V14 dalam
-- sp_document_setting. Hanya smallest_denomination yang benar-benar hilang.
ALTER TABLE sp_billing_setting
  ADD COLUMN smallest_denomination DECIMAL(5,2) NOT NULL DEFAULT 0.00;
