-- Pautan awam kepada PENYATA (ADR 0014 P3).
--
-- Penyata bukan dokumen. Ia unjuran BACA-SAHAJA atas julat tarikh
-- (ADR 0010) dan tiada baris dalam financial_document, jadi
-- document_access_token tidak boleh memegangnya: jadual itu mempunyai
-- UNIQUE(document_id) dengan FK ke dokumen sebenar.
--
-- CASE-006: legacy menyelesaikan masalah yang SAMA dengan mencipta
-- dokumen 'P' HANTU untuk setiap e-mel penyata, semata-mata untuk
-- mendapat UUID pautan. Satu akaun yang diperiksa mempunyai 51 rekod
-- bukan-kewangan dalam jadual kewangan.
--
-- Jadual BERASINGAN, bukan melonggarkan document_access_token supaya
-- document_id nullable. Lajur yang bermakna "kadang-kadang dokumen,
-- kadang-kadang akaun+tahun" memerlukan setiap pembaca menyemak yang
-- mana — dan yang terlupa menyemak gagal secara senyap.
--
-- SATU TOKEN PER (SP, AKAUN, TAHUN). Penyata dihantar setiap kali bil
-- dijana — dua belas kali setahun untuk akaun bulanan. Token per
-- penghantaran bermakna dua belas token setahun per akaun, dan sepuluh
-- ribu akaun menjadikannya seratus dua puluh ribu baris setahun untuk
-- mengakses data yang sama.
--
-- Pautan yang sama sepanjang tahun juga bermakna e-mel Januari masih
-- berfungsi pada Disember, dan penyata yang dibukanya menunjukkan
-- keadaan SEMASA — itu sifat, bukan kelemahan.
--
-- TIADA TARIKH LUPUT, sama seperti V42. Penyata ialah rekod kewangan
-- yang pelanggan berhak simpan; pautan yang mati selepas sembilan puluh
-- hari bermakna panggilan telefon kepada SP. Token yang bocor
-- mendedahkan SATU akaun untuk SATU tahun, bukan portal.
--
-- revoked_at wujud untuk kes SP perlu mematikannya — akaun bertukar
-- pemilik, atau alamat e-mel salah.

CREATE TABLE statement_access_token (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code       VARCHAR(20)  NOT NULL,
  token         VARCHAR(64)  NOT NULL,
  account_id    BIGINT       NOT NULL,
  stmt_year     INT          NOT NULL,
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  first_seen_at DATETIME     NULL,
  last_seen_at  DATETIME     NULL,
  view_count    INT          NOT NULL DEFAULT 0,
  revoked_at    DATETIME     NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_sat_token (token),
  UNIQUE KEY uk_sat_acc_year (account_id, stmt_year),
  KEY idx_sat_sp (sp_code),
  CONSTRAINT fk_sat_account FOREIGN KEY (account_id)
    REFERENCES account (id)
) ENGINE=InnoDB;
