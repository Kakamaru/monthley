-- =====================================================================
-- V11 — Pengesahan e-mel & reset kata laluan
-- =====================================================================

-- Bila e-mel disahkan. NULL = belum sahkan → tak boleh log masuk.
ALTER TABLE app_user
  ADD COLUMN email_verified_at DATETIME NULL AFTER status;

-- Token untuk sahkan e-mel & reset kata laluan
CREATE TABLE IF NOT EXISTS user_token (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  token      VARCHAR(64) NOT NULL,
  type       ENUM('VERIFY_EMAIL','RESET_PASSWORD') NOT NULL,
  expires_at DATETIME    NOT NULL,
  used_at    DATETIME    NULL,
  created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_token (token),
  KEY idx_user_type (user_id, type),
  KEY idx_expiry (expires_at),
  CONSTRAINT fk_token_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Superadmin sedia ada dianggap sudah sahkan
UPDATE app_user SET email_verified_at = NOW() WHERE email_verified_at IS NULL;
