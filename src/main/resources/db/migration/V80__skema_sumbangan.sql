-- Modul Sumbangan — kutipan derma (ADR 0020).
--
-- Derma berbeza daripada setiap aliran wang lain dalam sistem: penderma
-- ialah orang luar tanpa akaun, tiada invois untuk dijelaskan, dan bayaran
-- datang melalui pautan awam tanpa log masuk.

-- ---------------------------------------------------------------------------
-- 1) Akaun kutipan derma, satu per SP
-- ---------------------------------------------------------------------------
--
-- Resit derma memerlukan akaun kerana financial_document mempunyai FK
-- kepadanya. Akaun BERASINGAN daripada ADHOC-SALES: akaun itu wujud untuk
-- jualan yang menghasilkan invois, dan mencampurkan dua jenis transaksi
-- bermakna penyata akaun itu tidak bermakna.
--
-- Akaun ini TIDAK dikira dalam kuota pelan, tidak muncul dalam senarai
-- akaun pelanggan, dan tidak pernah mempunyai tunggakan.

INSERT INTO account (sp_code, account_no, account_name, account_type,
                     status, created_at, updated_at, version)
SELECT sp.sp_code, 'DONATION', 'Kutipan Derma', 'DONATION',
       'ACTIVE', NOW(), NOW(), 0
FROM   service_provider sp
WHERE  NOT EXISTS (
        SELECT 1 FROM account a
        WHERE a.sp_code = sp.sp_code AND a.account_type = 'DONATION');

-- ---------------------------------------------------------------------------
-- 2) Akaun GL hasil derma untuk SP SEDIA ADA
-- ---------------------------------------------------------------------------
--
-- SP baharu mendapatnya melalui ChartOfAccountSeeder. Migrasi ini hanya
-- untuk yang carta akaunnya sudah wujud — seeder tidak dijalankan semula
-- untuk SP sedia ada.
--
-- Berasingan daripada 4000 Service Income: SP perlu tahu berapa yang
-- datang daripada derma berbanding yuran, dan mencampurkannya bermakna
-- penyata pendapatan tidak boleh menjawab soalan itu.

INSERT INTO chart_of_accounts
  (sp_code, code, name, account_type, normal_side, is_control,
   status, created_at, updated_at, version)
SELECT c.sp_code, '4200', 'Donation Income', 'INCOME', 'CREDIT', 0,
       'ACTIVE', NOW(), NOW(), 0
FROM   (SELECT DISTINCT sp_code FROM chart_of_accounts) c
WHERE  NOT EXISTS (
        SELECT 1 FROM chart_of_accounts x
        WHERE x.sp_code = c.sp_code AND x.code = '4200');

-- ---------------------------------------------------------------------------
-- 3) Kempen
-- ---------------------------------------------------------------------------

CREATE TABLE donation_campaign (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sp_code         VARCHAR(20)  NOT NULL,

  title           VARCHAR(200) NOT NULL,
  description     TEXT         NULL,
  poster_url      VARCHAR(500) NULL,

  -- Jenis kutipan: label sahaja, bukan tingkah laku. Derma, tabung khas,
  -- yuran aktiviti, dan zakat berkelakuan sama; yang berbeza ialah cara SP
  -- melaporkannya.
  campaign_type   VARCHAR(50)  NOT NULL DEFAULT 'DERMA',

  -- slug dalam URL awam: monthley.my/derma/{slug}
  --
  -- UNIK MERENTAS SEMUA SP kerana URL tidak membawa kod SP. Dua SP dengan
  -- 'tabung-surau' bermakna satu daripadanya menerima derma yang ditujukan
  -- kepada yang lain.
  slug            VARCHAR(100) NOT NULL,

  status          ENUM('DRAFT','ACTIVE','CLOSED') NOT NULL DEFAULT 'DRAFT',
  start_date      DATE         NULL,
  end_date        DATE         NULL,

  -- Sasaran NULL = kutipan terbuka. Bar kemajuan disembunyikan.
  target_amount   DECIMAL(15,2) NULL,

  -- Amaun pilihan pantas, dipisah koma: '10,50,100'
  preset_amounts  VARCHAR(100) NULL,
  min_amount      DECIMAL(15,2) NULL,
  allow_custom    TINYINT(1)   NOT NULL DEFAULT 1,

  -- Medan penderma yang dikumpul.
  require_name    TINYINT(1)   NOT NULL DEFAULT 1,
  require_email   TINYINT(1)   NOT NULL DEFAULT 1,
  require_phone   TINYINT(1)   NOT NULL DEFAULT 0,
  require_account TINYINT(1)   NOT NULL DEFAULT 0,
  allow_anonymous TINYINT(1)   NOT NULL DEFAULT 1,

  -- Yuran gerbang: kempen MENGATASI tetapan SP (ADR 0020 #3).
  -- NULL = warisi sp_payment_setting.absorb.
  --
  -- SP boleh menyerap yuran untuk bil bulanan tetapi meminta penderma
  -- menanggungnya untuk derma — RM1.50 daripada RM50 ialah 3% yang tidak
  -- pergi kepada tujuan.
  absorb_fee      TINYINT(1)   NULL,

  auto_receipt    TINYINT(1)   NOT NULL DEFAULT 1,

  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 ON UPDATE CURRENT_TIMESTAMP,
  created_by      VARCHAR(64)  NULL,
  version         BIGINT       NOT NULL DEFAULT 0,

  CONSTRAINT uk_campaign_slug UNIQUE (slug),
  CONSTRAINT fk_campaign_sp FOREIGN KEY (sp_code)
      REFERENCES service_provider (sp_code),
  INDEX idx_campaign_sp (sp_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 4) Derma
-- ---------------------------------------------------------------------------
--
-- Maklumat penderma disimpan DI SINI dan bukan pada akaun: penderma bukan
-- pelanggan, dan mencipta akaun untuk derma sekali daripada orang luar
-- meninggalkan rekod kekal yang muncul dalam kuota dan laporan
-- selama-lamanya.

CREATE TABLE donation (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  sp_code         VARCHAR(20)  NOT NULL,
  campaign_id     BIGINT       NOT NULL,

  donor_name      VARCHAR(200) NULL,
  donor_email     VARCHAR(200) NULL,
  donor_phone     VARCHAR(30)  NULL,
  donor_account   VARCHAR(50)  NULL,
  anonymous       TINYINT(1)   NOT NULL DEFAULT 0,

  amount          DECIMAL(15,2) NOT NULL,
  fee_amount      DECIMAL(15,2) NULL,

  status          ENUM('NEW','PENDING','SUCCESS','FAILED','EXPIRED')
                    NOT NULL DEFAULT 'NEW',

  -- Rujukan gerbang: our_ref berbentuk sp_code + base36, sama seperti
  -- bayaran lain, supaya penyata bank boleh dipadankan.
  our_ref         VARCHAR(50)  NOT NULL,
  gateway_ref     VARCHAR(100) NULL,
  bill_code       VARCHAR(50)  NULL,
  gateway_payload TEXT         NULL,

  -- Resit dicipta selepas bayaran berjaya. TIADA invois (ADR 0020 #2).
  receipt_document_id BIGINT   NULL,
  payment_id      BIGINT       NULL,

  paid_at         DATETIME     NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                 ON UPDATE CURRENT_TIMESTAMP,
  version         BIGINT       NOT NULL DEFAULT 0,

  CONSTRAINT uk_donation_ref UNIQUE (our_ref),
  CONSTRAINT fk_donation_campaign FOREIGN KEY (campaign_id)
      REFERENCES donation_campaign (id),
  CONSTRAINT fk_donation_sp FOREIGN KEY (sp_code)
      REFERENCES service_provider (sp_code),
  INDEX idx_donation_campaign (campaign_id, status),
  INDEX idx_donation_sp (sp_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
