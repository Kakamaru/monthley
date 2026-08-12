-- Modul Aduan (ADR 0016, ADR 0017).
--
-- Sama seperti Perbelanjaan: modul memiliki data operasinya sendiri
-- (adu_*), berkongsi service_provider dan account untuk tenant dan
-- pautan pelanggan, dan document_number_sequence untuk penomboran.
--
-- Tiada posting ledger — aduan bukan peristiwa kewangan.

-- ---------------------------------------------------------------------------
-- 1) KATEGORI
-- ---------------------------------------------------------------------------
CREATE TABLE adu_category (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code     VARCHAR(20)  NOT NULL,
  name        VARCHAR(100) NOT NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  status      ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by  VARCHAR(64)  NULL,
  updated_at  DATETIME     NULL,
  updated_by  VARCHAR(64)  NULL,
  version     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_adu_catg (sp_code, name),
  CONSTRAINT fk_adu_catg_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 2) ADUAN
-- ---------------------------------------------------------------------------
-- account_id memaut aduan kepada pelanggan SP. Pengadu boleh berbeza
-- daripada pemegang akaun — penyewa mengadu tentang unit yang disewa,
-- atau kerani merekod aduan daripada panggilan telefon. Sebab itu
-- reporter_name dan reporter_phone wujud: nama pada akaun bukan
-- semestinya nama orang yang mengadu.
--
-- resolved_at DISIMPAN kerana perubahan status tidak dilog; ia dikosongkan
-- semula apabila aduan dibuka semula. first_reply_at DIDERIVE daripada
-- adu_reply — balasan dilog, jadi tiada sebab menyimpannya dua kali.
CREATE TABLE adu_complaint (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code        VARCHAR(20)  NOT NULL,
  complaint_no   VARCHAR(30)  NOT NULL,
  account_id     BIGINT       NOT NULL,
  category_id    BIGINT       NULL,
  subject        VARCHAR(200) NOT NULL,
  detail         TEXT         NULL,
  priority       ENUM('HIGH','MEDIUM','LOW') NOT NULL DEFAULT 'MEDIUM',
  status         ENUM('NEW','IN_PROGRESS','RESOLVED','REOPENED') NOT NULL DEFAULT 'NEW',
  assigned_to    BIGINT       NULL,          -- app_user (SP_ADMIN/CLERK)
  reported_by    BIGINT       NULL,          -- app_user; NULL bila kerani merekod
  reporter_name  VARCHAR(150) NULL,
  reporter_phone VARCHAR(30)  NULL,
  internal_note  VARCHAR(500) NULL,          -- tidak dipapar kepada pengadu
  resolved_at    DATETIME     NULL,
  created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by     VARCHAR(64)  NULL,
  updated_at     DATETIME     NULL,
  updated_by     VARCHAR(64)  NULL,
  version        BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_adu_no (sp_code, complaint_no),
  KEY idx_adu_sp_status (sp_code, status),
  KEY idx_adu_account (account_id),
  KEY idx_adu_created (sp_code, created_at),
  CONSTRAINT fk_adu_sp       FOREIGN KEY (sp_code)     REFERENCES service_provider (sp_code),
  CONSTRAINT fk_adu_account  FOREIGN KEY (account_id)  REFERENCES account (id),
  CONSTRAINT fk_adu_catg     FOREIGN KEY (category_id) REFERENCES adu_category (id),
  CONSTRAINT fk_adu_assigned FOREIGN KEY (assigned_to) REFERENCES app_user (id),
  CONSTRAINT fk_adu_reporter FOREIGN KEY (reported_by) REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 3) BALASAN
-- ---------------------------------------------------------------------------
-- from_sp membezakan balasan SP daripada balasan pengadu. Tanpa bendera
-- ini, mengira 'purata masa maklum balas' bermakna menyemak peranan
-- setiap pengguna pada masa laporan dijana — dan peranan berubah.
--
-- internal = nota yang pengadu TIDAK nampak. Ia dalam jadual yang sama
-- supaya urutan masa kekal utuh; menapisnya ialah satu klausa WHERE.
CREATE TABLE adu_reply (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  complaint_id BIGINT      NOT NULL,
  message      TEXT        NOT NULL,
  replied_by   BIGINT      NULL,
  from_sp      TINYINT(1)  NOT NULL DEFAULT 0,
  internal     TINYINT(1)  NOT NULL DEFAULT 0,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_adu_reply_c (complaint_id, created_at),
  CONSTRAINT fk_adu_reply_c FOREIGN KEY (complaint_id) REFERENCES adu_complaint (id) ON DELETE CASCADE,
  CONSTRAINT fk_adu_reply_u FOREIGN KEY (replied_by)   REFERENCES app_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 4) TETAPAN
-- ---------------------------------------------------------------------------
CREATE TABLE adu_setting (
  sp_code     VARCHAR(20) NOT NULL,
  prefix      VARCHAR(10) NOT NULL DEFAULT 'ADU',
  no_size     INT         NOT NULL DEFAULT 6,
  no_start    BIGINT      NOT NULL DEFAULT 1,
  sla_days    INT         NOT NULL DEFAULT 5,
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by  VARCHAR(64) NULL,
  updated_at  DATETIME    NULL,
  updated_by  VARCHAR(64) NULL,
  version     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (sp_code),
  CONSTRAINT fk_adu_setting_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------------------------------------------------------------------
-- 5) SEED — kategori lalai untuk SP yang melanggan modul
-- ---------------------------------------------------------------------------
INSERT INTO adu_category (sp_code, name, sort_order, created_by)
SELECT m.sp_code, x.nama, x.urut, 'seed'
FROM   sp_module m
CROSS  JOIN (
    SELECT 'Penyelenggaraan' AS nama, 1 AS urut
    UNION ALL SELECT 'Kewangan',      2
    UNION ALL SELECT 'Keselamatan',   3
    UNION ALL SELECT 'Pembersihan',   4
    UNION ALL SELECT 'Ketenteraman',  5
    UNION ALL SELECT 'Pertanyaan',    6
) x
WHERE  m.module_code = 'ADUAN' AND m.status = 'ACTIVE'
  AND  NOT EXISTS (
      SELECT 1 FROM adu_category c WHERE c.sp_code = m.sp_code AND c.name = x.nama
  );
