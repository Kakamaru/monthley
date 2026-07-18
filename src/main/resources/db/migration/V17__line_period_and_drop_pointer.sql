-- period_id pada baris: label period untuk exclude (padanan tepat) + join laporan.
-- Boleh diterbitkan dari period_start + product.charge_frequency, tapi didenormal
-- sebab laporan join fi_period. period_start/period_end kekal kebenaran liputan.
ALTER TABLE financial_document_line ADD COLUMN period_id BIGINT NULL AFTER product_id;
ALTER TABLE financial_document_line ADD KEY idx_docline_period (period_id);
ALTER TABLE financial_document_line ADD CONSTRAINT fk_docline_period
  FOREIGN KEY (period_id) REFERENCES fi_period (period_id);

-- Buang penunjuk stateful. Idempotency dijaga oleh
-- financial_document_line.idem_key (UNIQUE, STORED GENERATED) — kekangan DB,
-- bukan semakan aplikasi, jadi tiada lubang race.
ALTER TABLE account_subscription DROP COLUMN last_charged_at;

-- Tak pernah dipakai: semua 0 dalam prod (mon_sp_prod.gen_day).
-- Penjadualan di aras SP (sp_billing_setting.invoice_gen_day).
ALTER TABLE product DROP COLUMN generation_day;
