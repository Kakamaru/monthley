-- V26 — Jemputan pautan akaun + tarikh pautan.
--
-- Reka bentuk (dipermudah dari legacy public_user + public_user_app):
--   app_user tunggal dengan status + email_verified_at membezakan:
--     berdaftar & aktif = status ACTIVE AND email_verified_at IS NOT NULL
--     pending           = jemputan PENDING atau app_user belum verify
--
-- Aliran:
--   - Link ke email berdaftar & aktif  -> set payer_user_id + link_date terus
--   - Link ke email belum berdaftar    -> jemputan PENDING + hantar email;
--       bila daftar dengan email sama   -> padan jemputan -> link + link_date
--
-- Padanan jemputan guna email (tiada token khas).

CREATE TABLE account_invitation (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  sp_code     VARCHAR(20)  NOT NULL,
  account_id  BIGINT       NOT NULL,
  email       VARCHAR(255) NOT NULL,
  status      ENUM('PENDING','ACCEPTED','CANCELLED') NOT NULL DEFAULT 'PENDING',
  invited_by  VARCHAR(64),
  accepted_at DATETIME,
  created_at  DATETIME     NOT NULL,
  created_by  VARCHAR(64),
  updated_at  DATETIME     NOT NULL,
  updated_by  VARCHAR(64),
  version     BIGINT       NOT NULL DEFAULT 0,
  KEY idx_inv_email   (email),
  KEY idx_inv_account (account_id),
  KEY idx_inv_status  (status)
);

-- Tarikh akaun dipautkan ke pengguna (legacy: link_date)
ALTER TABLE account ADD COLUMN link_date DATETIME NULL AFTER payer_user_id;
