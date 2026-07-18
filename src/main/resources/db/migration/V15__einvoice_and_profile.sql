-- =====================================================================
-- V15 — e-Invois LHDN + medan profil yang tertinggal
--
-- Nota: logo_url, helpdesk_email, helpdesk_phone SUDAH ADA dalam V1.
-- Migration ini hanya menambah yang benar-benar tiada.
--
-- Tax Invoice (e-Invois) wajib untuk semua peniaga mulai 1 Januari 2027.
-- =====================================================================

-- ---------- Profil: nombor telefon ----------
ALTER TABLE service_provider
  ADD COLUMN office_no VARCHAR(30) NULL,
  ADD COLUMN mobile_no VARCHAR(30) NULL;

-- ---------- Sales Tax: e-Invois (MyInvois / LHDN) ----------
ALTER TABLE sp_billing_setting
  ADD COLUMN enable_tax_invoice      TINYINT(1)  NOT NULL DEFAULT 0
    COMMENT 'Jana tax invoice mematuhi LHDN',
  ADD COLUMN tin                     VARCHAR(50) NULL
    COMMENT 'Tax Identification No.',
  ADD COLUMN sst_registration_no     VARCHAR(50) NULL,
  ADD COLUMN tax_effective_date      DATE        NULL,
  ADD COLUMN msic_code               VARCHAR(20) NULL
    COMMENT 'Malaysia Standard Industrial Classification',
  ADD COLUMN einvoice_type           ENUM('INVOICE','CREDIT_NOTE','DEBIT_NOTE','REFUND_NOTE')
    NOT NULL DEFAULT 'INVOICE',
  ADD COLUMN einvoice_classification ENUM('GENERAL','SERVICE','RENTAL','MEMBERSHIP','DONATION')
    NOT NULL DEFAULT 'GENERAL';

-- ---------- Receipt: benarkan bayaran manual ----------
ALTER TABLE sp_document_setting
  ADD COLUMN enable_manual_payment TINYINT(1) NOT NULL DEFAULT 1
    COMMENT 'Benarkan rekod bayaran tunai/cek di kaunter';

-- ---------- Branch: alamat ----------
ALTER TABLE account_branch
  ADD COLUMN address VARCHAR(500) NULL;
