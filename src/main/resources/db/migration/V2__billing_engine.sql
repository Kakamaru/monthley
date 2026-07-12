-- =====================================================================
-- MONTHLEY REVAMP — DDL DELTA V2 (billing engine)
-- Apply SELEPAS monthley_schema.sql
--
-- Target dev: MySQL 9 local (collation utf8mb4_0900_ai_ci)
-- Untuk MariaDB production: tukar ke utf8mb4_unicode_ci
--
-- Jalankan dengan: Execute SQL Script (Alt+X), BUKAN Ctrl+Enter
-- =====================================================================


-- ---------------------------------------------------------------------
-- 1. PRODUK — anchor month untuk kitaran quarterly/half/yearly
--    NULL = ikut bulan start_date langganan
--    8    = semua akaun dicaj Ogos (cth: insurance JMB)
-- ---------------------------------------------------------------------
ALTER TABLE product
  ADD COLUMN anchor_month TINYINT NULL
    COMMENT '1-12; NULL = ikut start_date langganan';


-- ---------------------------------------------------------------------
-- 2. TETAPAN DOKUMEN — auto/manual eksplisit + grouping + selective
-- ---------------------------------------------------------------------
ALTER TABLE sp_document_setting
  ADD COLUMN auto_generate     TINYINT(1) NOT NULL DEFAULT 1
    COMMENT '1 = auto jana ikut invoice_gen_day (ganti magic value 99)',
  ADD COLUMN invoice_grouping  ENUM('BY_PERIOD','BY_PRODUCT','SINGLE')
    NOT NULL DEFAULT 'BY_PERIOD',
  ADD COLUMN selective_payment TINYINT(1) NOT NULL DEFAULT 0
    COMMENT 'pelanggan boleh pilih invois mana nak bayar';


-- ---------------------------------------------------------------------
-- 3. BARIS DOKUMEN — tempoh di peringkat baris + idempotency
--    idem_key jadi NULL bila active=0  → slot terbuka semula selepas batal
-- ---------------------------------------------------------------------
ALTER TABLE financial_document_line
  ADD COLUMN account_id   BIGINT     NULL COMMENT 'denormal untuk idempotency',
  ADD COLUMN period_start DATE       NULL,
  ADD COLUMN period_end   DATE       NULL,
  ADD COLUMN active       TINYINT(1) NOT NULL DEFAULT 1;

ALTER TABLE financial_document_line
  ADD COLUMN idem_key VARCHAR(120) AS (
    CASE WHEN active = 1
         THEN CONCAT(account_id, ':', product_id, ':', period_start)
    END
  ) STORED,
  ADD UNIQUE KEY uk_line_idem (idem_key);

ALTER TABLE financial_document_line
  ADD CONSTRAINT fk_docline_account FOREIGN KEY (account_id) REFERENCES account (id);


-- ---------------------------------------------------------------------
-- 4. LANGGANAN — elak pendua (asas bulk subscribe idempotent)
--    last_charged_at kekal, tetapi HANYA untuk paparan — bukan authoritative
-- ---------------------------------------------------------------------
ALTER TABLE account_subscription
  ADD UNIQUE KEY uk_subscr (account_id, product_id, start_date);


-- ---------------------------------------------------------------------
-- 5. TEMPOH DIKECUALIKAN
-- ---------------------------------------------------------------------
CREATE TABLE invoice_exclude_period (
  id      BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code VARCHAR(20) NOT NULL,
  period  VARCHAR(7)  NOT NULL COMMENT 'YYYY-MM',
  remarks VARCHAR(255) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_excl (sp_code, period),
  CONSTRAINT fk_excl_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 6. LOG LARIAN JANA INVOIS (audit + retry + laporan pasca-penjanaan)
-- ---------------------------------------------------------------------
CREATE TABLE invoice_run (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20) NOT NULL,
  period         VARCHAR(7)  NOT NULL,
  run_type       ENUM('SCHEDULED','MANUAL_ALL','MANUAL_SINGLE') NOT NULL,
  status         ENUM('RUNNING','COMPLETED','FAILED') NOT NULL DEFAULT 'RUNNING',
  total_accounts INT NOT NULL DEFAULT 0,
  success_count  INT NOT NULL DEFAULT 0,
  skipped_count  INT NOT NULL DEFAULT 0,
  failed_count   INT NOT NULL DEFAULT 0,
  started_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at    DATETIME NULL,
  triggered_by   VARCHAR(64) NULL,
  PRIMARY KEY (id),
  KEY idx_run_sp (sp_code, period),
  CONSTRAINT fk_run_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE invoice_run_item (
  id          BIGINT NOT NULL AUTO_INCREMENT,
  run_id      BIGINT NOT NULL,
  account_id  BIGINT NOT NULL,
  document_id BIGINT NULL,
  status      ENUM('SUCCESS','SKIPPED','FAILED') NOT NULL,
  reason      VARCHAR(500) NULL,
  PRIMARY KEY (id),
  KEY idx_runitem_run (run_id),
  CONSTRAINT fk_runitem_run FOREIGN KEY (run_id)      REFERENCES invoice_run (id),
  CONSTRAINT fk_runitem_acc FOREIGN KEY (account_id)  REFERENCES account (id),
  CONSTRAINT fk_runitem_doc FOREIGN KEY (document_id) REFERENCES financial_document (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 7. LOG OPERASI PUKAL (bulk subscribe, bulk price change, dll)
-- ---------------------------------------------------------------------
CREATE TABLE bulk_operation_log (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20) NOT NULL,
  operation      VARCHAR(50) NOT NULL COMMENT 'BULK_SUBSCRIBE, BULK_PRICE_CHANGE...',
  filter_json    JSON NULL,
  affected_count INT NOT NULL DEFAULT 0,
  skipped_count  INT NOT NULL DEFAULT 0,
  failed_count   INT NOT NULL DEFAULT 0,
  status         ENUM('RUNNING','COMPLETED','FAILED') NOT NULL DEFAULT 'RUNNING',
  started_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at    DATETIME NULL,
  triggered_by   VARCHAR(64) NULL,
  PRIMARY KEY (id),
  KEY idx_bulk_sp (sp_code),
  CONSTRAINT fk_bulk_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ---------------------------------------------------------------------
-- 8. ShedLock — elak dua instance jana invois serentak
-- ---------------------------------------------------------------------
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at  TIMESTAMP(3) NOT NULL,
  locked_by  VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- SEMAKAN
-- =====================================================================
-- SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='monthley_new';
--   → jangkaan: 31 jadual (25 asal + 6 baru)
-- SHOW CREATE TABLE financial_document_line;   -- sahkan idem_key & uk_line_idem
-- SHOW CREATE TABLE product;                   -- sahkan anchor_month
