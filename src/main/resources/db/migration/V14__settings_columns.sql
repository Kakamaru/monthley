-- =====================================================================
-- V14 — Kolum tetapan yang dirujuk oleh menu Tetapan
-- Sumber: pemetaan mon_sp lama (rujuk monthley_sp_settings_mapping.md)
-- =====================================================================

-- set_curr_min_denom → pembundaran ke atas per baris (cth 0.05)
ALTER TABLE service_provider
  ADD COLUMN min_denom DECIMAL(6,2) NULL
    COMMENT 'Denominasi min — bulat KE ATAS per baris. cth 0.05: .31→.35';

-- set_inv_split_txn → invois berasingan per produk
-- set_price_override → benarkan harga khas pada langganan
ALTER TABLE sp_document_setting
  ADD COLUMN split_invoice_by_product TINYINT(1) NOT NULL DEFAULT 0
    COMMENT 'Y = satu invois per produk (BY_PRODUCT), N = satu invois per tempoh',
  ADD COLUMN allow_price_override TINYINT(1) NOT NULL DEFAULT 0
    COMMENT 'Y = subscription.unit_price boleh mengatasi product.unit_rate';

-- set_late_penalty_type + set_compounded_int
ALTER TABLE sp_penalty_setting
  ADD COLUMN penalty_type ENUM('FIXED','PERCENT') NOT NULL DEFAULT 'FIXED'
    COMMENT 'FIXED = amaun tetap; PERCENT = peratus baki tertunggak',
  ADD COLUMN compounded TINYINT(1) NOT NULL DEFAULT 0
    COMMENT 'Y = denda berkompaun setiap tempoh';

-- Baris tetapan untuk SP sedia ada (SP baharu dicipta atas permintaan)
INSERT IGNORE INTO sp_billing_setting (sp_code)      SELECT sp_code FROM service_provider;
INSERT IGNORE INTO sp_document_setting (sp_code)     SELECT sp_code FROM service_provider;
INSERT IGNORE INTO sp_penalty_setting (sp_code)      SELECT sp_code FROM service_provider;
INSERT IGNORE INTO sp_notification_setting (sp_code) SELECT sp_code FROM service_provider;
