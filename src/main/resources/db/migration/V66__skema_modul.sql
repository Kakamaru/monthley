-- ADR 0016: katalog modul, hak akses, dan permohonan perubahan.
--
-- Tiga jadual menjawab tiga soalan berbeza:
--   ref_module         apa yang ditawarkan (katalog platform)
--   sp_module          SP ini boleh guna modul ini? (HAK)
--   sp_change_request  SP mohon apa? (aliran kelulusan)
--
-- HAK dipisahkan daripada BIL (account_subscription) kerana satu peristiwa
-- menghasilkan dua tarikh: modul diluluskan 15 Ogos bermakna hak aktif
-- 15 Ogos tetapi bil bermula 1 September. Satu rekod memaksa kita memilih
-- satu tarikh, dan salah satunya akan salah.

-- ---------------------------------------------------------------------------
-- 1) KATALOG MODUL — rujukan platform, bukan per-SP
-- ---------------------------------------------------------------------------
CREATE TABLE ref_module (
  code            VARCHAR(30)  NOT NULL,
  name            VARCHAR(100) NOT NULL,
  description     VARCHAR(1000) NULL,        -- untuk skrin jualan SP
  video_url       VARCHAR(500) NULL,
  product_id      BIGINT       NULL,         -- produk bawah SP platform; HARGA DARI SINI
  business_types  VARCHAR(200) NULL,         -- 'EDU,CLHO' — kosong bermakna semua sektor
  sort_order      INT          NOT NULL DEFAULT 0,
  status          ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version         BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (code),
  CONSTRAINT fk_module_product FOREIGN KEY (product_id) REFERENCES product (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Pautan kepada produk melalui id (FK), BUKAN kod produk sebagai rentetan:
-- kod produk boleh diedit melalui UI, dan pautan rentetan pecah senyap
-- sedangkan FK tidak.

-- ---------------------------------------------------------------------------
-- 2) HAK — SP ini boleh guna modul ini?
-- ---------------------------------------------------------------------------
CREATE TABLE sp_module (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20) NOT NULL,
  module_code  VARCHAR(30) NOT NULL,
  status       ENUM('ACTIVE','ENDED') NOT NULL DEFAULT 'ACTIVE',
  start_date   DATE        NOT NULL,          -- tarikh kelulusan; hak serta-merta
  end_date     DATE        NULL,              -- hujung bulan bila dihentikan
  approved_by  BIGINT      NULL,              -- platform_admin.id
  notes        VARCHAR(500) NULL,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version      BIGINT      NOT NULL DEFAULT 0,
  -- Satu hak AKTIF sahaja per SP per modul. Baris ENDED boleh berulang
  -- (SP boleh langgan semula), jadi kekangan hanya pada nilai ACTIVE:
  -- NULL berulang dibenarkan dalam indeks unik MySQL.
  aktif_flag   VARCHAR(30) GENERATED ALWAYS AS
               (CASE WHEN status = 'ACTIVE' THEN module_code ELSE NULL END) VIRTUAL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_satu_hak_aktif (sp_code, aktif_flag),
  KEY idx_sp_module_lookup (sp_code, module_code, status),
  CONSTRAINT fk_sp_module_sp     FOREIGN KEY (sp_code)     REFERENCES service_provider (sp_code),
  CONSTRAINT fk_sp_module_module FOREIGN KEY (module_code) REFERENCES ref_module (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 3) PERMOHONAN — semua jenis perubahan melalui satu jadual
-- ---------------------------------------------------------------------------
-- Naik pelan, turun pelan, tambah modul, henti modul: satu jadual, satu
-- skrin peti masuk superadmin. Jadual berasingan bagi setiap jenis bermakna
-- tiga skrin yang melakukan kerja yang sama.
CREATE TABLE sp_change_request (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20) NOT NULL,
  request_type   ENUM('MODULE_ADD','MODULE_END','PLAN_CHANGE') NOT NULL,
  module_code    VARCHAR(30) NULL,            -- untuk MODULE_*
  plan_product_id BIGINT     NULL,            -- untuk PLAN_CHANGE
  status         ENUM('PENDING','APPROVED','REJECTED') NOT NULL DEFAULT 'PENDING',
  requested_by   BIGINT      NOT NULL,        -- app_user.id (SP_ADMIN)
  requested_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_by     BIGINT      NULL,            -- platform_admin.id
  decided_at     DATETIME    NULL,
  decision_note  VARCHAR(1000) NULL,          -- WAJIB bila REJECTED; SP nampak
  created_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version        BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_req_pending (status, requested_at),
  KEY idx_req_sp (sp_code, status),
  CONSTRAINT fk_req_sp      FOREIGN KEY (sp_code)         REFERENCES service_provider (sp_code),
  CONSTRAINT fk_req_module  FOREIGN KEY (module_code)     REFERENCES ref_module (code),
  CONSTRAINT fk_req_plan    FOREIGN KEY (plan_product_id) REFERENCES product (id),
  CONSTRAINT fk_req_user    FOREIGN KEY (requested_by)    REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 4) SEED KATALOG — dipaut kepada produk sedia ada bawah SP platform
-- ---------------------------------------------------------------------------
INSERT INTO ref_module (code, name, description, product_id, business_types, sort_order)
SELECT x.code, x.name, x.descr, p.id, x.biz, x.sort
FROM (
    SELECT 'PERBELANJAAN' AS code, 'Perbelanjaan' AS name, 'EXP' AS produk,
           'Rekod perbelanjaan, invois pembekal, baucar bayaran, dan buku tunai. Terus masuk ke Untung Rugi.' AS descr,
           NULL AS biz, 1 AS sort
    UNION ALL SELECT 'ADUAN', 'Aduan', 'ADU',
           'Terima dan urus aduan penghuni dengan status dan susulan.', NULL, 2
    UNION ALL SELECT 'SUMBANGAN', 'Sumbangan', 'SUM',
           'Rekod sumbangan dan derma, dengan resit dan laporan.', NULL, 3
    UNION ALL SELECT 'MEMO', 'Memo', 'MEMO',
           'Hebahan dan memo kepada penghuni.', NULL, 4
) x
JOIN service_provider owner ON owner.is_platform_owner = 1
JOIN product p ON p.sp_code = owner.sp_code AND p.code = x.produk;
