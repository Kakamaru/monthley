-- =====================================================================
-- V9 — Onboarding SP (borang superadmin)
-- =====================================================================

-- ---------- Pelan perkhidmatan (harga boleh diubah melalui skrin nanti) ----------
CREATE TABLE IF NOT EXISTS service_plan (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  code          VARCHAR(20)  NOT NULL,
  name          VARCHAR(100) NOT NULL,
  account_limit INT          NOT NULL,
  price_monthly DECIMAL(15,2) NOT NULL DEFAULT 0,
  price_yearly  DECIMAL(15,2) NOT NULL DEFAULT 0,
  status        ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_service_plan_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pelan lalai — harga boleh diubah kemudian
INSERT IGNORE INTO service_plan (code, name, account_limit, price_monthly, price_yearly) VALUES
  ('P100', 'Pakej 100', 100, 100.00, 1000.00),
  ('P200', 'Pakej 200', 200, 200.00, 2000.00),
  ('P300', 'Pakej 300', 300, 300.00, 3000.00);

-- ---------- service_provider: medan borang onboarding ----------
ALTER TABLE service_provider
  ADD COLUMN city                VARCHAR(100) NULL AFTER addr_line3,
  ADD COLUMN org_registered_date DATE         NULL,
  ADD COLUMN service_plan_id     BIGINT       NULL,
  ADD COLUMN billing_plan        ENUM('MONTHLY','YEARLY') NOT NULL DEFAULT 'MONTHLY',
  ADD COLUMN est_invoices_month  INT          NULL,
  ADD CONSTRAINT fk_sp_service_plan FOREIGN KEY (service_plan_id) REFERENCES service_plan (id);

-- ---------- sp_payment_setting: siapa serap caj FPX ----------
ALTER TABLE sp_payment_setting
  ADD COLUMN absorb      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '1 = organisasi serap; 0 = pelanggan bayar',
  ADD COLUMN rate_single DECIMAL(15,2) NULL COMMENT 'RM1.50 — 1 akaun + 1 period',
  ADD COLUMN rate_multi  DECIMAL(15,2) NULL COMMENT 'RM2.00 — >1 akaun atau >1 period';
