-- Pautan awam kepada dokumen — resit hari ini, penyata dan invois kelak.
--
-- Pelanggan yang menerima e-mel mungkin TIADA akaun portal. Pautan mesti
-- berfungsi tanpa log masuk, tanpa JWT, tanpa TenantContext.
--
-- CASE-006: legacy menyelesaikannya dengan mencipta dokumen 'P' HANTU
-- untuk setiap e-mel, semata-mata untuk mendapat UUID pautan. Satu akaun
-- yang diperiksa mempunyai 51 rekod bukan-kewangan dalam jadual kewangan.
-- Token duduk dalam jadualnya sendiri.
--
-- SATU token per dokumen. Menghantar semula resit yang sama menghasilkan
-- pautan yang SAMA — e-mel lama kekal berfungsi, dan tiada token yatim
-- terkumpul. Legacy menghasilkan UUID baharu setiap penghantaran, tetapi
-- itu kesan sampingan daripada menggunakan dokumen hantu.
--
-- TIADA TARIKH LUPUT. Resit ialah rekod kewangan yang pelanggan berhak
-- simpan; pautan yang mati selepas 90 hari bermakna panggilan telefon
-- kepada SP. Token yang bocor mendedahkan SATU dokumen, bukan akaun.
-- revoked_at wujud untuk kes SP perlu mematikannya.
--
-- Skrin Finance Documents (akan dibina) mempunyai 'Cancel Document' dan
-- 'Resend Document'. Kedua-duanya menyentuh token ini:
--   Resend  -> pautan SAMA dihantar semula, tiada token baharu
--   Cancel  -> revoked_at ditetapkan; pautan berhenti berfungsi, jika
--              tidak pelanggan membuka resit yang sudah dibatalkan dan
--              menganggapnya sah
CREATE TABLE document_access_token (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code       VARCHAR(20)  NOT NULL,
  token         VARCHAR(64)  NOT NULL,
  document_id   BIGINT       NOT NULL,
  doc_type      VARCHAR(20)  NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  first_seen_at DATETIME     NULL,
  last_seen_at  DATETIME     NULL,
  view_count    INT          NOT NULL DEFAULT 0,
  revoked_at    DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_dat_token (token),
  UNIQUE KEY uk_dat_doc (document_id),
  KEY idx_dat_sp (sp_code),
  CONSTRAINT fk_dat_doc FOREIGN KEY (document_id)
    REFERENCES financial_document (id)
) ENGINE=InnoDB;
