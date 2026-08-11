-- Kaedah bayaran per SP.
--
-- Sebelum ini `method` ialah rentetan bebas pada exp_payment dan
-- exp_cash_entry — pengguna menaipnya sendiri. Itu bermakna 'Tunai',
-- 'TUNAI', dan 'Cash' menjadi tiga kaedah berbeza dalam laporan, dan
-- tiada cara menapis mengikut kaedah tanpa meneka ejaan.
--
-- Rentetan pada transaksi DIKEKALKAN, bukan ditukar kepada FK: kaedah
-- ialah snapshot pada masa bayaran. Menamakan semula 'Maybank2u' kepada
-- 'Online Banking' tahun depan tidak sepatutnya menulis semula sejarah
-- baucar yang sudah dicetak.

CREATE TABLE exp_payment_method (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  sp_code     VARCHAR(20) NOT NULL,
  name        VARCHAR(50) NOT NULL,
  sort_order  INT         NOT NULL DEFAULT 0,
  status      ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE',
  created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by  VARCHAR(64) NULL,
  updated_at  DATETIME    NULL,
  updated_by  VARCHAR(64) NULL,
  version     BIGINT      NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_exp_method (sp_code, name),
  CONSTRAINT fk_exp_method_sp FOREIGN KEY (sp_code) REFERENCES service_provider (sp_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Kaedah lalai untuk SP yang melanggan modul.
INSERT INTO exp_payment_method (sp_code, name, sort_order, created_by)
SELECT m.sp_code, x.nama, x.urut, 'seed'
FROM   sp_module m
CROSS  JOIN (
    SELECT 'Tunai' AS nama, 1 AS urut
    UNION ALL SELECT 'Pindahan Bank', 2
    UNION ALL SELECT 'Cek', 3
    UNION ALL SELECT 'Kad Kredit/Debit', 4
) x
WHERE  m.module_code = 'PERBELANJAAN' AND m.status = 'ACTIVE'
  AND  NOT EXISTS (
      SELECT 1 FROM exp_payment_method e
      WHERE e.sp_code = m.sp_code AND e.name = x.nama
  );
