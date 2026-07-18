-- =====================================================================
-- V12 — Peranan SP (Roles Setting)
-- Tiga peranan asas: Admin, Cashier, Account Viewer
-- =====================================================================

CREATE TABLE IF NOT EXISTS sp_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  code        VARCHAR(20)  NOT NULL,
  name        VARCHAR(100) NOT NULL,
  description VARCHAR(255) NULL,
  system_role TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 = terbina dalam, tak boleh padam',
  sort_order  INT          NOT NULL DEFAULT 0,
  status      ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  version     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sp_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO sp_role (code, name, description, system_role, sort_order) VALUES
  ('SP_ADMIN', 'Admin',
   'Akses penuh — urus produk, akaun, bil, bayaran & tetapan.', 1, 1),
  ('CLERK', 'Cashier',
   'Rekod bayaran & jana resit. Tidak boleh ubah tetapan.', 1, 2),
  ('VIEWER', 'Account Viewer',
   'Lihat sahaja — akaun, bil & laporan. Tiada kebenaran mengubah.', 1, 3);
