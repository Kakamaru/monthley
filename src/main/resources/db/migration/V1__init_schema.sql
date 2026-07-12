-- =====================================================================
-- MONTHLEY REVAMP — SKEMA MariaDB 11.x
-- Stack: Spring Boot 4.1 (Hibernate 7) · Angular 22 · MariaDB 11.x LTS
--
-- Keputusan terkunci:
--   * service_provider.sp_code = PK (natural, immutable)
--   * tenant discriminator = sp_code (Hibernate @TenantId)
--   * entiti lain = surrogate BIGINT id + business key UNIQUE
--   * baki DIDERIVE dari ledger (tiada bal_amt sebagai source of truth)
--   * settings = jadual berkumpulan (type-safe)
--
-- Nota: jadual audit Envers (_AUD) dijana auto oleh Hibernate — tak ditulis di sini.
--       Setiap jadual ada lajur audit + `version` (optimistic lock).
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- =====================================================================
-- 1. TIER PLATFORM / SUPERADMIN
-- =====================================================================

CREATE TABLE platform_admin (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  email        VARCHAR(255) NOT NULL,
  full_name    VARCHAR(255) NOT NULL,
  role         ENUM('SUPERADMIN','SUPPORT') NOT NULL DEFAULT 'SUPPORT',
  status       ENUM('ACTIVE','INACTIVE')    NOT NULL DEFAULT 'ACTIVE',
  password_hash VARCHAR(255) NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   VARCHAR(64)  NULL,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by   VARCHAR(64)  NULL,
  version      BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_platform_admin_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 2. USER (pembayar / ahli)  — merentas tenant
-- =====================================================================

CREATE TABLE app_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  email         VARCHAR(255) NOT NULL,
  full_name     VARCHAR(255) NOT NULL,
  mobile        VARCHAR(30)  NULL,
  password_hash VARCHAR(255) NULL,
  status        ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  uuid          CHAR(36)     NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by    VARCHAR(64)  NULL,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by    VARCHAR(64)  NULL,
  version       BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_app_user_email (email),
  UNIQUE KEY uk_app_user_uuid (uuid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 3. TENANT — SERVICE PROVIDER  (sp_code = PK)
-- =====================================================================

CREATE TABLE service_provider (
  sp_code           VARCHAR(20)  NOT NULL,
  name              VARCHAR(255) NOT NULL,
  handle            VARCHAR(100) NULL,
  business_type     VARCHAR(100) NULL,
  business_desc     VARCHAR(500) NULL,
  registration_no   VARCHAR(100) NULL,
  -- alamat
  addr_line1        VARCHAR(255) NULL,
  addr_line2        VARCHAR(255) NULL,
  addr_line3        VARCHAR(255) NULL,
  postcode          VARCHAR(10)  NULL,
  state             VARCHAR(100) NULL,
  country           VARCHAR(100) NULL DEFAULT 'Malaysia',
  -- kontak
  phone             VARCHAR(30)  NULL,
  office_phone      VARCHAR(30)  NULL,
  website           VARCHAR(255) NULL,
  contact_email     VARCHAR(255) NULL,
  helpdesk_email    VARCHAR(255) NULL,
  helpdesk_phone    VARCHAR(30)  NULL,
  logo_url          VARCHAR(500) NULL,
  -- bank
  bank_code         VARCHAR(50)  NULL,
  bank_account_no   VARCHAR(50)  NULL,
  bank_account_name VARCHAR(255) NULL,
  -- status & onboarding (milik superadmin)
  status            ENUM('PENDING','ACTIVE','SUSPENDED','CLOSED') NOT NULL DEFAULT 'PENDING',
  applied_at        DATETIME     NULL,
  approved_at       DATETIME     NULL,
  onboarded_by      BIGINT       NULL,
  onboard_notes     VARCHAR(500) NULL,
  -- audit
  created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by        VARCHAR(64)  NULL,
  updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by        VARCHAR(64)  NULL,
  version           BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_sp_onboarded_by FOREIGN KEY (onboarded_by) REFERENCES platform_admin (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 4. SETTINGS BERKUMPULAN (1:1 dengan service_provider)
-- =====================================================================

CREATE TABLE sp_billing_setting (
  sp_code            VARCHAR(20) NOT NULL,
  currency           VARCHAR(3)  NOT NULL DEFAULT 'MYR',
  language           VARCHAR(10) NOT NULL DEFAULT 'ms',
  date_format        VARCHAR(30) NULL,
  time_format        VARCHAR(30) NULL,
  payment_term_days  INT         NOT NULL DEFAULT 15,
  tax_name           VARCHAR(50) NULL,
  tax_rate           DECIMAL(7,4) NULL,
  tax_id             VARCHAR(50) NULL,
  ar_gl_account_id   BIGINT      NULL,   -- akaun kawalan AR (rujuk chart_of_accounts)
  income_gl_account_id BIGINT    NULL,
  version            BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_billing_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sp_document_setting (
  sp_code              VARCHAR(20) NOT NULL,
  -- invois
  invoice_title        VARCHAR(100) NULL,
  invoice_prefix       VARCHAR(20)  NULL,
  invoice_suffix       VARCHAR(20)  NULL,
  invoice_no_start     BIGINT       NULL DEFAULT 1,
  invoice_no_size      INT          NULL DEFAULT 6,
  invoice_gen_mode     ENUM('PREPAID','POSTPAID','CURRENT') NOT NULL DEFAULT 'CURRENT',
  invoice_gen_freq     ENUM('MONTHLY','QUARTERLY','HALF_YEAR','YEAR') NOT NULL DEFAULT 'MONTHLY',
  invoice_gen_day      INT          NULL DEFAULT 1,
  invoice_prorated     TINYINT(1)   NOT NULL DEFAULT 0,
  invoice_template_id  VARCHAR(50)  NULL,
  -- resit
  receipt_title        VARCHAR(100) NULL,
  receipt_prefix       VARCHAR(20)  NULL,
  receipt_suffix       VARCHAR(20)  NULL,
  receipt_no_start     BIGINT       NULL DEFAULT 1,
  receipt_no_size      INT          NULL DEFAULT 6,
  receipt_template_id  VARCHAR(50)  NULL,
  -- penyata & no akaun
  statement_title      VARCHAR(100) NULL,
  statement_template_id VARCHAR(50) NULL,
  account_no_auto      TINYINT(1)   NOT NULL DEFAULT 1,
  account_no_prefix    VARCHAR(20)  NULL,
  account_no_size      INT          NULL DEFAULT 6,
  version              BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_docset_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sp_penalty_setting (
  sp_code           VARCHAR(20) NOT NULL,
  enabled           TINYINT(1)  NOT NULL DEFAULT 0,
  penalty_code      VARCHAR(50) NULL,
  penalty_title     VARCHAR(100) NULL,
  penalty_desc      VARCHAR(255) NULL,
  penalty_amount    DECIMAL(15,2) NULL,
  penalty_after_day INT          NULL,
  taxable           TINYINT(1)   NOT NULL DEFAULT 0,
  version           BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_penalty_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sp_notification_setting (
  sp_code            VARCHAR(20) NOT NULL,
  sms_on_invoice     TINYINT(1)  NOT NULL DEFAULT 0,
  sms_on_reminder    TINYINT(1)  NOT NULL DEFAULT 0,
  whatsapp_on_invoice TINYINT(1) NOT NULL DEFAULT 0,
  whatsapp_on_reminder TINYINT(1) NOT NULL DEFAULT 0,
  email_on_invoice   TINYINT(1)  NOT NULL DEFAULT 1,
  email_on_reminder  TINYINT(1)  NOT NULL DEFAULT 1,
  version            BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_notifset_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sp_payment_setting (
  sp_code           VARCHAR(20)  NOT NULL,
  gateway           VARCHAR(20)  NOT NULL DEFAULT 'MP',   -- Monthley Pay
  merchant_id       VARCHAR(100) NULL,
  gateway_key       VARCHAR(255) NULL,
  manual_payment    TINYINT(1)   NOT NULL DEFAULT 1,
  online_payment    TINYINT(1)   NOT NULL DEFAULT 1,
  version           BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_payset_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 5. RBAC — keahlian user dalam SP
-- =====================================================================

CREATE TABLE sp_membership (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code    VARCHAR(20) NOT NULL,
  user_id    BIGINT      NOT NULL,
  role       ENUM('SP_ADMIN','CLERK','VIEWER') NOT NULL DEFAULT 'CLERK',
  status     ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(64) NULL,
  updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) NULL,
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_membership (sp_code, user_id, role),
  CONSTRAINT fk_membership_sp   FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code),
  CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 6. REFERENCE per SP — kategori & cawangan
-- =====================================================================

CREATE TABLE account_category (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code    VARCHAR(20) NOT NULL,
  code       VARCHAR(50) NOT NULL,
  name       VARCHAR(255) NOT NULL,
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_acc_catg (sp_code, code),
  CONSTRAINT fk_acc_catg_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_branch (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code    VARCHAR(20) NOT NULL,
  code       VARCHAR(50) NOT NULL,
  name       VARCHAR(255) NOT NULL,
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_acc_branch (sp_code, code),
  CONSTRAINT fk_acc_branch_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 7. PERAKAUNAN — Chart of Accounts & period  (definisi awal, dirujuk journal)
-- =====================================================================

CREATE TABLE chart_of_accounts (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code         VARCHAR(20) NOT NULL,
  code            VARCHAR(20) NOT NULL,
  name            VARCHAR(255) NOT NULL,
  account_type    ENUM('ASSET','LIABILITY','INCOME','EXPENSE','EQUITY') NOT NULL,
  normal_side     ENUM('DEBIT','CREDIT') NOT NULL,
  parent_id       BIGINT      NULL,
  is_control      TINYINT(1)  NOT NULL DEFAULT 0,
  sub_ledger_type ENUM('AR','DEPOSIT') NULL,
  status          ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by      VARCHAR(64) NULL,
  updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by      VARCHAR(64) NULL,
  version         BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_coa (sp_code, code),
  KEY idx_coa_sp (sp_code),
  CONSTRAINT fk_coa_sp     FOREIGN KEY (sp_code)   REFERENCES service_provider (sp_code),
  CONSTRAINT fk_coa_parent FOREIGN KEY (parent_id) REFERENCES chart_of_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE accounting_period (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code    VARCHAR(20) NOT NULL,
  period     VARCHAR(7)  NOT NULL,        -- 'YYYY-MM'
  status     ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  closed_at  DATETIME    NULL,
  closed_by  VARCHAR(64) NULL,
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_period (sp_code, period),
  CONSTRAINT fk_period_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 8. KATALOG — produk
-- =====================================================================

CREATE TABLE product_category (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code    VARCHAR(20) NOT NULL,
  code       VARCHAR(50) NOT NULL,
  name       VARCHAR(255) NOT NULL,
  parent_id  BIGINT      NULL,
  version    BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_prod_catg (sp_code, code),
  CONSTRAINT fk_prod_catg_sp     FOREIGN KEY (sp_code)   REFERENCES service_provider (sp_code),
  CONSTRAINT fk_prod_catg_parent FOREIGN KEY (parent_id) REFERENCES product_category (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product (
  id                BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code           VARCHAR(20) NOT NULL,
  code              VARCHAR(50) NOT NULL,
  subscription_code VARCHAR(50) NULL,
  name              VARCHAR(255) NOT NULL,
  description       VARCHAR(500) NULL,
  category_id       BIGINT      NULL,
  charge_frequency  ENUM('ONE_TIME','MONTHLY','QUARTERLY','HALF_YEAR','YEAR','PER_USE') NOT NULL DEFAULT 'MONTHLY',
  unit_rate         DECIMAL(15,2) NOT NULL DEFAULT 0,
  income_gl_account_id BIGINT   NULL,   -- akaun income untuk produk ni
  main_product      TINYINT(1)  NOT NULL DEFAULT 0,
  mandatory         TINYINT(1)  NOT NULL DEFAULT 0,
  prorated          TINYINT(1)  NOT NULL DEFAULT 0,
  late_penalty      TINYINT(1)  NOT NULL DEFAULT 0,
  generation_day    INT         NULL,
  term_days         INT         NULL,
  status            ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by        VARCHAR(64) NULL,
  updated_at        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by        VARCHAR(64) NULL,
  version           BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_product (sp_code, code),
  KEY idx_product_sp (sp_code),
  CONSTRAINT fk_product_sp     FOREIGN KEY (sp_code)     REFERENCES service_provider (sp_code),
  CONSTRAINT fk_product_catg   FOREIGN KEY (category_id) REFERENCES product_category (id),
  CONSTRAINT fk_product_income FOREIGN KEY (income_gl_account_id) REFERENCES chart_of_accounts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 9. AKAUN PELANGGAN
-- =====================================================================

CREATE TABLE account (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20) NOT NULL,
  account_no     VARCHAR(30) NOT NULL,
  account_name   VARCHAR(255) NOT NULL,
  account_type   VARCHAR(50) NULL,
  category_id    BIGINT      NULL,
  branch_id      BIGINT      NULL,
  payer_user_id  BIGINT      NULL,
  -- maklumat ahli / bill-to
  member_name    VARCHAR(255) NULL,
  member_id_no   VARCHAR(50)  NULL,
  member_email   VARCHAR(255) NULL,
  member_mobile  VARCHAR(30)  NULL,
  billto_name    VARCHAR(255) NULL,
  billto_email   VARCHAR(255) NULL,
  billto_mobile  VARCHAR(30)  NULL,
  billto_addr_line1 VARCHAR(255) NULL,
  billto_addr_line2 VARCHAR(255) NULL,
  billto_addr_line3 VARCHAR(255) NULL,
  billto_postcode VARCHAR(10) NULL,
  billto_state   VARCHAR(100) NULL,
  billto_country VARCHAR(100) NULL,
  -- kitaran
  charge_frequency ENUM('ONE_TIME','MONTHLY','QUARTERLY','HALF_YEAR','YEAR','PER_USE') NULL,
  start_date     DATE        NULL,
  expiry_date    DATE        NULL,
  end_date       DATE        NULL,
  linked_at      DATETIME    NULL,
  status         ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  -- baki HANYA cache (rebuild dari ledger); bukan source of truth
  cached_balance DECIMAL(15,2) NOT NULL DEFAULT 0,
  cached_balance_at DATETIME  NULL,
  uuid           CHAR(36)    NULL,
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by     VARCHAR(64) NULL,
  updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by     VARCHAR(64) NULL,
  version        BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_account_no (sp_code, account_no),
  UNIQUE KEY uk_account_uuid (uuid),
  KEY idx_account_sp (sp_code),
  KEY idx_account_status (sp_code, status),
  CONSTRAINT fk_account_sp     FOREIGN KEY (sp_code)      REFERENCES service_provider (sp_code),
  CONSTRAINT fk_account_catg   FOREIGN KEY (category_id)  REFERENCES account_category (id),
  CONSTRAINT fk_account_branch FOREIGN KEY (branch_id)    REFERENCES account_branch (id),
  CONSTRAINT fk_account_payer  FOREIGN KEY (payer_user_id) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE account_subscription (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code              VARCHAR(20) NOT NULL,
  account_id           BIGINT      NOT NULL,
  product_id           BIGINT      NOT NULL,
  quantity             DECIMAL(15,4) NOT NULL DEFAULT 1,
  unit_price           DECIMAL(15,2) NULL,       -- over-ride harga produk
  start_date           DATE        NULL,
  end_date             DATE        NULL,
  status               ENUM('ACTIVE','INACTIVE','ENDED') NOT NULL DEFAULT 'ACTIVE',
  parent_subscription_id BIGINT    NULL,
  last_charged_at      DATETIME    NULL,
  notes                VARCHAR(500) NULL,
  created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by           VARCHAR(64) NULL,
  updated_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by           VARCHAR(64) NULL,
  version              BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_subscr_account (account_id),
  KEY idx_subscr_product (product_id),
  CONSTRAINT fk_subscr_sp      FOREIGN KEY (sp_code)    REFERENCES service_provider (sp_code),
  CONSTRAINT fk_subscr_account FOREIGN KEY (account_id) REFERENCES account (id),
  CONSTRAINT fk_subscr_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_subscr_parent  FOREIGN KEY (parent_subscription_id) REFERENCES account_subscription (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE subscription_usage (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code         VARCHAR(20) NOT NULL,
  subscription_id BIGINT      NOT NULL,
  period          VARCHAR(7)  NULL,
  quantity        DECIMAL(15,4) NOT NULL DEFAULT 0,
  unit_price      DECIMAL(15,2) NULL,
  amount          DECIMAL(15,2) NULL,
  notes           VARCHAR(255) NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by      VARCHAR(64) NULL,
  version         BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_usage_subscr (subscription_id),
  CONSTRAINT fk_usage_sp     FOREIGN KEY (sp_code)         REFERENCES service_provider (sp_code),
  CONSTRAINT fk_usage_subscr FOREIGN KEY (subscription_id) REFERENCES account_subscription (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 10. DOKUMEN KEWANGAN — invois / resit
-- =====================================================================

CREATE TABLE financial_document (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20) NOT NULL,
  doc_no         VARCHAR(30) NOT NULL,
  doc_type       ENUM('INVOICE','RECEIPT','ADHOC') NOT NULL,
  account_id     BIGINT      NULL,
  period_id      BIGINT      NULL,
  doc_date       DATE        NOT NULL,
  due_date       DATE        NULL,
  title          VARCHAR(255) NULL,
  ref_no         VARCHAR(50) NULL,
  currency       VARCHAR(3)  NOT NULL DEFAULT 'MYR',
  amount         DECIMAL(15,2) NOT NULL DEFAULT 0,
  tax_amount     DECIMAL(15,2) NOT NULL DEFAULT 0,
  status         ENUM('ACTIVE','PAID','PARTIAL','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
  -- bayaran (untuk RECEIPT)
  payment_type   VARCHAR(20) NULL,
  payment_ref_no VARCHAR(64) NULL,          -- JAMBATAN ke mpay.mpay_ol_pymt_txn.payment_ref_no
  paid_at        DATETIME    NULL,
  -- snapshot issued-to (immutable)
  issued_to_name  VARCHAR(255) NULL,
  issued_to_email VARCHAR(255) NULL,
  -- pembatalan
  cancelled_at   DATETIME    NULL,
  cancelled_by   VARCHAR(64) NULL,
  cancel_reason  VARCHAR(255) NULL,
  uuid           CHAR(36)    NULL,
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by     VARCHAR(64) NULL,
  updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by     VARCHAR(64) NULL,
  version        BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_doc_no (sp_code, doc_no),
  UNIQUE KEY uk_doc_uuid (uuid),
  KEY idx_doc_sp_type (sp_code, doc_type),
  KEY idx_doc_account (account_id),
  KEY idx_doc_status (sp_code, status),
  KEY idx_doc_payref (payment_ref_no),
  CONSTRAINT fk_doc_sp      FOREIGN KEY (sp_code)    REFERENCES service_provider (sp_code),
  CONSTRAINT fk_doc_account FOREIGN KEY (account_id) REFERENCES account (id),
  CONSTRAINT fk_doc_period  FOREIGN KEY (period_id)  REFERENCES accounting_period (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE financial_document_line (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  document_id  BIGINT      NOT NULL,
  product_id   BIGINT      NULL,
  description  VARCHAR(255) NULL,
  quantity     DECIMAL(15,4) NOT NULL DEFAULT 1,
  unit_price   DECIMAL(15,2) NOT NULL DEFAULT 0,
  amount       DECIMAL(15,2) NOT NULL DEFAULT 0,
  tax_amount   DECIMAL(15,2) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_docline_doc (document_id),
  CONSTRAINT fk_docline_doc     FOREIGN KEY (document_id) REFERENCES financial_document (id),
  CONSTRAINT fk_docline_product FOREIGN KEY (product_id)  REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 11. LEDGER DOUBLE-ENTRY — journal (append-only)
-- =====================================================================

CREATE TABLE journal_entry (
  id                   BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code              VARCHAR(20) NOT NULL,
  entry_no             VARCHAR(30) NOT NULL,
  entry_date           DATE        NOT NULL,
  period_id            BIGINT      NULL,
  source_type          ENUM('INVOICE','PAYMENT','PENALTY','CANCELLATION','WRITEOFF','ADJUSTMENT','OPENING') NOT NULL,
  source_document_id   BIGINT      NULL,
  description          VARCHAR(255) NULL,
  status               ENUM('DRAFT','POSTED','REVERSED') NOT NULL DEFAULT 'POSTED',
  reverses_entry_id    BIGINT      NULL,
  posted_at            DATETIME    NULL,
  posted_by            VARCHAR(64) NULL,
  created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by           VARCHAR(64) NULL,
  version              BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_entry_no (sp_code, entry_no),
  KEY idx_journal_sp (sp_code),
  KEY idx_journal_doc (source_document_id),
  KEY idx_journal_period (period_id),
  CONSTRAINT fk_journal_sp       FOREIGN KEY (sp_code)            REFERENCES service_provider (sp_code),
  CONSTRAINT fk_journal_period   FOREIGN KEY (period_id)          REFERENCES accounting_period (id),
  CONSTRAINT fk_journal_doc      FOREIGN KEY (source_document_id) REFERENCES financial_document (id),
  CONSTRAINT fk_journal_reverses FOREIGN KEY (reverses_entry_id)  REFERENCES journal_entry (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE journal_line (
  id                    BIGINT      NOT NULL AUTO_INCREMENT,
  journal_entry_id      BIGINT      NOT NULL,
  gl_account_id         BIGINT      NOT NULL,
  debit_amount          DECIMAL(15,2) NOT NULL DEFAULT 0,
  credit_amount         DECIMAL(15,2) NOT NULL DEFAULT 0,
  sub_ledger_account_id BIGINT      NULL,       -- dimensi AR/Deposit (pelanggan mana)
  product_id            BIGINT      NULL,
  line_desc             VARCHAR(255) NULL,
  PRIMARY KEY (id),
  KEY idx_jline_entry (journal_entry_id),
  KEY idx_jline_gl (gl_account_id),
  KEY idx_jline_subledger (sub_ledger_account_id),
  CONSTRAINT chk_jline_dr_cr CHECK (debit_amount = 0 OR credit_amount = 0),
  CONSTRAINT fk_jline_entry     FOREIGN KEY (journal_entry_id)      REFERENCES journal_entry (id),
  CONSTRAINT fk_jline_gl        FOREIGN KEY (gl_account_id)         REFERENCES chart_of_accounts (id),
  CONSTRAINT fk_jline_subledger FOREIGN KEY (sub_ledger_account_id) REFERENCES account (id),
  CONSTRAINT fk_jline_product   FOREIGN KEY (product_id)            REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 12. AR OPEN-ITEM — allocation / knock-off (reversal-by-contra)
-- =====================================================================

CREATE TABLE fi_allocation (
  id                     BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code                VARCHAR(20) NOT NULL,
  account_id             BIGINT      NOT NULL,
  debit_document_id      BIGINT      NOT NULL,   -- invois (item AR)
  credit_document_id     BIGINT      NOT NULL,   -- resit / bayaran
  amount                 DECIMAL(15,2) NOT NULL, -- amaun TEPAT dipadan (kekal untuk contra)
  status                 ENUM('ACTIVE','REVERSED') NOT NULL DEFAULT 'ACTIVE',
  reverses_allocation_id BIGINT      NULL,
  journal_entry_id       BIGINT      NULL,
  created_at             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by             VARCHAR(64) NULL,
  updated_at             DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  updated_by             VARCHAR(64) NULL,
  version                BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_alloc_account (account_id),
  KEY idx_alloc_debit (debit_document_id),
  KEY idx_alloc_credit (credit_document_id),
  CONSTRAINT fk_alloc_sp       FOREIGN KEY (sp_code)            REFERENCES service_provider (sp_code),
  CONSTRAINT fk_alloc_account  FOREIGN KEY (account_id)         REFERENCES account (id),
  CONSTRAINT fk_alloc_debit    FOREIGN KEY (debit_document_id)  REFERENCES financial_document (id),
  CONSTRAINT fk_alloc_credit   FOREIGN KEY (credit_document_id) REFERENCES financial_document (id),
  CONSTRAINT fk_alloc_reverses FOREIGN KEY (reverses_allocation_id) REFERENCES fi_allocation (id),
  CONSTRAINT fk_alloc_journal  FOREIGN KEY (journal_entry_id)   REFERENCES journal_entry (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- =====================================================================
-- 13. SEQUENCE, INTEGRASI mpay, NOTIFIKASI
-- =====================================================================

-- generator running-number (ganti rdt_seq) — guna row lock utk thread-safe
CREATE TABLE document_number_sequence (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code     VARCHAR(20) NOT NULL,
  seq_type    VARCHAR(20) NOT NULL,       -- INVOICE / RECEIPT / ACCOUNT / JOURNAL
  prefix      VARCHAR(20) NULL,
  suffix      VARCHAR(20) NULL,
  next_value  BIGINT      NOT NULL DEFAULT 1,
  padding     INT         NOT NULL DEFAULT 6,
  version     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_seq (sp_code, seq_type),
  CONSTRAINT fk_seq_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- rekod settlement/reconciliation dari Monthley Pay
CREATE TABLE payment_settlement (
  id              BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code         VARCHAR(20) NOT NULL,
  payment_ref_no  VARCHAR(64) NOT NULL,   -- padan mpay_ol_pymt_txn.payment_ref_no
  document_id     BIGINT      NULL,       -- resit/invois berkaitan
  amount          DECIMAL(15,2) NOT NULL,
  gateway         VARCHAR(20) NOT NULL DEFAULT 'MP',
  fpx_response    VARCHAR(20) NULL,
  status          ENUM('PENDING','SETTLED','FAILED') NOT NULL DEFAULT 'PENDING',
  settled_at      DATETIME    NULL,
  reconciled_at   DATETIME    NULL,
  created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version         BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_settlement_ref (payment_ref_no),
  KEY idx_settlement_sp (sp_code),
  CONSTRAINT fk_settle_sp  FOREIGN KEY (sp_code)     REFERENCES service_provider (sp_code),
  CONSTRAINT fk_settle_doc FOREIGN KEY (document_id) REFERENCES financial_document (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- baris gilir notifikasi (ganti mon_notif_q)
CREATE TABLE notification_queue (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code     VARCHAR(20) NOT NULL,
  channel     ENUM('SMS','WHATSAPP','EMAIL') NOT NULL,
  recipient   VARCHAR(255) NOT NULL,
  subject     VARCHAR(255) NULL,
  body        TEXT        NULL,
  status      ENUM('PENDING','SENT','FAILED') NOT NULL DEFAULT 'PENDING',
  priority    INT         NOT NULL DEFAULT 5,
  attempts    INT         NOT NULL DEFAULT 0,
  sent_at     DATETIME    NULL,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  version     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_notif_status (status, priority),
  KEY idx_notif_sp (sp_code),
  CONSTRAINT fk_notif_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET FOREIGN_KEY_CHECKS = 1;

-- =====================================================================
-- Belum termasuk (tambah bila perlu): product_bundle, product_license,
-- gl_account_balance (cache), template, menu/permission terperinci,
-- jadual Envers _AUD (auto-jana Hibernate).
-- =====================================================================
