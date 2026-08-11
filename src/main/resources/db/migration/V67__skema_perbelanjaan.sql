-- Modul Perbelanjaan (ADR 0016, ADR 0017).
--
-- Modul memiliki data operasinya sendiri. Ia TIDAK berkongsi
-- financial_document, account, atau fi_allocation — invois pembekal ialah
-- hutang KEPADA pihak lain, dan memasukkannya ke financial_document
-- bermakna setiap query sedia ada (baki, tunggakan, penyata, ageing) perlu
-- menapis jenis dokumen atau ia mengira invois belian sebagai hutang
-- pelanggan.
--
-- Yang DIKONGSI hanyalah yang tidak boleh berasingan: service_provider
-- (FK tenant), chart_of_accounts (kategori menunjuk GL), journal_entry
-- (ledger tunggal — Untung Rugi mesti boleh dibuktikan seimbang), dan
-- document_number_sequence (kaunter berkunci, ADR 0012).
--
-- BAKI DIDERIVE, bukan disimpan. exp_invoice menyimpan subtotal/sst/total
-- kerana itu snapshot dokumen, tetapi paid/balance/status datang dari VIEW.
-- Menyimpan baki ialah corak bal_amt legacy dan cached_balance yang baru
-- digugurkan: ia menyimpang sebaik ada satu laluan tulis yang terlepas.

-- ---------------------------------------------------------------------------
-- 1) KATEGORI — pokok dua aras: kategori (induk) -> jenis (anak)
-- ---------------------------------------------------------------------------
-- GL diletak pada kategori INDUK sahaja. Untung Rugi menunjukkan tiga baris
-- perbelanjaan, bukan berpuluh; pecahan sehingga 'Elektrik (TNB)' datang
-- dari laporan yang membaca kategori.
CREATE TABLE exp_category (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20) NOT NULL,
  name           VARCHAR(150) NOT NULL,
  parent_id      BIGINT      NULL,
  gl_account_id  BIGINT      NULL,            -- NULL -> jatuh ke 5900 Perbelanjaan Am
  sort_order     INT         NOT NULL DEFAULT 0,
  status         ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by     VARCHAR(64) NULL,
  updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by     VARCHAR(64) NULL,
  version        BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_exp_catg_sp (sp_code, status),
  CONSTRAINT fk_exp_catg_sp     FOREIGN KEY (sp_code)       REFERENCES service_provider (sp_code),
  CONSTRAINT fk_exp_catg_parent FOREIGN KEY (parent_id)     REFERENCES exp_category (id),
  CONSTRAINT fk_exp_catg_gl     FOREIGN KEY (gl_account_id) REFERENCES chart_of_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 2) PEMBEKAL
-- ---------------------------------------------------------------------------
CREATE TABLE exp_supplier (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20)  NOT NULL,
  name         VARCHAR(150) NOT NULL,
  reg_no       VARCHAR(50)  NULL,
  tin          VARCHAR(30)  NULL,
  address      VARCHAR(255) NULL,
  phone        VARCHAR(30)  NULL,
  email        VARCHAR(100) NULL,
  bank_name    VARCHAR(60)  NULL,
  bank_acc_no  VARCHAR(40)  NULL,
  status       ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   VARCHAR(64)  NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by   VARCHAR(64)  NULL,
  version      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_exp_supplier_sp (sp_code, status),
  CONSTRAINT fk_exp_supplier_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 3) INVOIS PEMBEKAL
-- ---------------------------------------------------------------------------
-- inv_no DIMASUKKAN, bukan dijana — ia nombor invois pembekal (cth no
-- invois TNB). Unik per pembekal, bukan per SP: dua pembekal boleh
-- menggunakan nombor yang sama.
--
-- sst_rate ialah SNAPSHOT kadar pada tarikh invois. Kadar berubah; invois
-- lama mesti kekal menunjukkan apa yang sebenarnya dicaj.
CREATE TABLE exp_invoice (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20)  NOT NULL,
  inv_no       VARCHAR(50)  NOT NULL,
  supplier_id  BIGINT       NOT NULL,
  inv_date     DATE         NOT NULL,
  due_date     DATE         NULL,
  note         VARCHAR(255) NULL,
  subtotal     DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  sst_rate     DECIMAL(5,2)  NOT NULL DEFAULT 0.00,
  sst_amount   DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  total        DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  status       ENUM('ACTIVE','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  cancelled_at DATETIME     NULL,
  cancelled_by BIGINT       NULL,
  cancel_reason VARCHAR(255) NULL,
  journal_entry_id BIGINT   NULL,             -- posting Dr Belanja / Cr AP
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   VARCHAR(64)  NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by   VARCHAR(64)  NULL,
  version      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exp_inv_no (sp_code, supplier_id, inv_no),
  KEY idx_exp_inv_sp (sp_code, status),
  KEY idx_exp_inv_due (sp_code, due_date),
  CONSTRAINT fk_exp_inv_sp       FOREIGN KEY (sp_code)     REFERENCES service_provider (sp_code),
  CONSTRAINT fk_exp_inv_supplier FOREIGN KEY (supplier_id) REFERENCES exp_supplier (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE exp_invoice_item (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  invoice_id  BIGINT        NOT NULL,
  category_id BIGINT        NOT NULL,
  description VARCHAR(255)  NULL,
  amount      DECIMAL(15,2) NOT NULL DEFAULT 0.00,
  PRIMARY KEY (id),
  KEY idx_exp_item_inv (invoice_id),
  CONSTRAINT fk_exp_item_inv  FOREIGN KEY (invoice_id)  REFERENCES exp_invoice (id) ON DELETE CASCADE,
  CONSTRAINT fk_exp_item_catg FOREIGN KEY (category_id) REFERENCES exp_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 4) BAUCAR BAYARAN (PV) — membayar invois pembekal
-- ---------------------------------------------------------------------------
-- pv_no DIJANA melalui document_number_sequence (kaunter berkunci, ADR
-- 0012), tetapi prefix/saiz datang dari exp_setting. DocumentNumberService
-- menyemak keunikan terhadap financial_document sahaja, jadi jaminan untuk
-- PV mesti datang dari UNIQUE di sini.
CREATE TABLE exp_payment (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20)   NOT NULL,
  pv_no        VARCHAR(30)   NOT NULL,
  invoice_id   BIGINT        NOT NULL,
  pay_date     DATE          NOT NULL,
  amount       DECIMAL(15,2) NOT NULL,
  method       VARCHAR(40)   NOT NULL,
  ref_no       VARCHAR(60)   NULL,
  note         VARCHAR(255)  NULL,
  status       ENUM('ACTIVE','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  cancelled_at DATETIME      NULL,
  cancelled_by BIGINT        NULL,
  cancel_reason VARCHAR(255) NULL,
  journal_entry_id BIGINT    NULL,            -- posting Dr AP / Cr Bank
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   VARCHAR(64)   NULL,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by   VARCHAR(64)   NULL,
  version      BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exp_pv_no (sp_code, pv_no),
  KEY idx_exp_pay_inv (invoice_id, status),
  KEY idx_exp_pay_date (sp_code, pay_date),
  CONSTRAINT fk_exp_pay_sp  FOREIGN KEY (sp_code)    REFERENCES service_provider (sp_code),
  CONSTRAINT fk_exp_pay_inv FOREIGN KEY (invoice_id) REFERENCES exp_invoice (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 5) BAYARAN TERUS — tanpa invois (gaji, khairat, tunai runcit)
-- ---------------------------------------------------------------------------
-- Bukan pendua ledger: ini dokumen sumber, dengan penerima dan kategori
-- yang lejar sahaja tidak menyimpan.
CREATE TABLE exp_cash_entry (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20)   NOT NULL,
  voucher_no   VARCHAR(30)   NOT NULL,
  entry_date   DATE          NOT NULL,
  category_id  BIGINT        NOT NULL,
  payee        VARCHAR(150)  NOT NULL,
  description  VARCHAR(255)  NULL,
  amount       DECIMAL(15,2) NOT NULL,
  method       VARCHAR(40)   NOT NULL,
  ref_no       VARCHAR(60)   NULL,
  status       ENUM('ACTIVE','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  cancelled_at DATETIME      NULL,
  cancelled_by BIGINT        NULL,
  cancel_reason VARCHAR(255) NULL,
  journal_entry_id BIGINT    NULL,            -- posting Dr Belanja / Cr Bank
  created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   VARCHAR(64)   NULL,
  updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by   VARCHAR(64)   NULL,
  version      BIGINT        NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exp_voucher_no (sp_code, voucher_no),
  KEY idx_exp_cash_date (sp_code, entry_date),
  CONSTRAINT fk_exp_cash_sp   FOREIGN KEY (sp_code)     REFERENCES service_provider (sp_code),
  CONSTRAINT fk_exp_cash_catg FOREIGN KEY (category_id) REFERENCES exp_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 6) TETAPAN MODUL — milik modul, bukan sp_document_setting
-- ---------------------------------------------------------------------------
-- SP yang tidak melanggan modul tidak sepatutnya membawa lajur PV dalam
-- tetapan teras mereka. Prefix dibaca oleh modul dan dihantar kepada
-- DocumentNumberPort; modul document tidak perlu tahu modul mana yang
-- memanggilnya.
CREATE TABLE exp_setting (
  sp_code        VARCHAR(20)  NOT NULL,
  sst_enabled    TINYINT(1)   NOT NULL DEFAULT 0,
  sst_rate       DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  pv_prefix      VARCHAR(10)  NOT NULL DEFAULT 'PV',
  pv_no_size     INT          NOT NULL DEFAULT 6,
  pv_no_start    BIGINT       NOT NULL DEFAULT 1,
  cash_prefix    VARCHAR(10)  NOT NULL DEFAULT 'BT',
  cash_no_size   INT          NOT NULL DEFAULT 6,
  cash_no_start  BIGINT       NOT NULL DEFAULT 1,
  bank_gl_account_id BIGINT   NULL,           -- NULL -> 1000 Bank / Tunai
  updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by     VARCHAR(64)  NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_exp_setting_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code),
  CONSTRAINT fk_exp_setting_gl FOREIGN KEY (bank_gl_account_id) REFERENCES chart_of_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 7) BAKI INVOIS — DIDERIVE (ADR 0009 corak yang sama)
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW exp_invoice_balance AS
SELECT i.id                                        AS invoice_id,
       i.sp_code,
       i.total,
       COALESCE(SUM(p.amount), 0)                  AS paid,
       i.total - COALESCE(SUM(p.amount), 0)        AS balance,
       CASE
         WHEN i.status = 'CANCELLED'                        THEN 'CANCELLED'
         WHEN COALESCE(SUM(p.amount), 0) = 0                THEN 'UNPAID'
         WHEN COALESCE(SUM(p.amount), 0) >= i.total         THEN 'SETTLED'
         ELSE 'PARTIAL'
       END                                         AS status
FROM   exp_invoice i
LEFT   JOIN exp_payment p
       ON p.invoice_id = i.id AND p.status = 'ACTIVE'
GROUP  BY i.id, i.sp_code, i.total, i.status;
