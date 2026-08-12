-- Modul Memo — hebahan sehala daripada SP kepada pelanggan.
--
-- Paling ringkas antara modul setakat ini: satu jadual, tiada kategori,
-- tiada tetapan berasingan, tiada thread.
--
-- TARIKH LUPUT ialah per memo dan BUKAN tetapan global. Satu nombor tidak
-- boleh melayan dua jenis memo: hebahan kerja penyelenggaraan 20 Ogos
-- patut hilang 21 Ogos, tetapi nombor telefon pengurusan baharu tidak
-- patut luput langsung. Tetapan global 30 hari salah untuk kedua-duanya.
--
-- NULL bermakna kekal aktif. Itu juga sebab tiada skrin Tetapan Memo —
-- satu-satunya perkara yang perlu dikonfigurasi hidup pada memo itu
-- sendiri.

CREATE TABLE memo_notice (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20)  NOT NULL,
  title        VARCHAR(200) NOT NULL,
  body         TEXT         NOT NULL,

  -- DRAFT membolehkan SP menyediakan memo lebih awal dan menerbitkannya
  -- bila sedia. Tanpanya setiap memo hidup sebaik sahaja ditaip.
  status       ENUM('DRAFT','PUBLISHED') NOT NULL DEFAULT 'DRAFT',

  published_at DATETIME     NULL,
  expires_on   DATE         NULL,          -- NULL = kekal aktif

  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by   VARCHAR(64)  NULL,
  updated_at   DATETIME     NULL,
  updated_by   VARCHAR(64)  NULL,
  version      BIGINT       NOT NULL DEFAULT 0,

  PRIMARY KEY (id),
  KEY idx_memo_sp (sp_code, status, expires_on),
  CONSTRAINT fk_memo_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
