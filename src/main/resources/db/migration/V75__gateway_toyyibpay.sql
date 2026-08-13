-- Sokongan gerbang ToyyibPay.
--
-- sp_payment_setting sudah ada gateway/merchant_id/gateway_key/absorb dan
-- kadar yuran. Yang ditambah di sini ialah apa yang ToyyibPay perlukan
-- dan MonthleyPay tidak.
--
-- KUNCI DISULITKAN. Legacy menyimpan pymt_gateway_key sebagai teks biasa;
-- untuk MonthleyPay itu risiko dalaman kerana gerbangnya milik sendiri,
-- tetapi User Secret Key ToyyibPay memberi akses kepada akaun bayaran SP.
-- Sesiapa yang membaca satu backup DB boleh mencipta bil bagi pihak
-- mana-mana SP. Kunci induk hidup dalam pembolehubah persekitaran, jadi
-- dump DB tidak berguna dengan sendirinya.

ALTER TABLE sp_payment_setting
  -- Kod kategori ToyyibPay. Bil dicipta di bawah kategori, dan setiap SP
  -- mempunyai kategorinya sendiri dalam akaun ToyyibPay mereka.
  ADD COLUMN category_code VARCHAR(50) NULL AFTER merchant_id,

  -- Ciphertext + IV + tag, dienkod base64. Panjang kerana AES-GCM
  -- menambah 28 bait overhead pada setiap nilai.
  ADD COLUMN gateway_key_enc VARCHAR(512) NULL AFTER gateway_key,

  -- Sandbox dan pengeluaran ialah HOST berbeza dengan kunci berbeza.
  -- Bendera pada tetapan dan bukan konfigurasi global: satu SP boleh
  -- diuji dalam sandbox sementara yang lain sudah hidup.
  ADD COLUMN sandbox TINYINT(1) NOT NULL DEFAULT 1 AFTER online_payment;

-- Transaksi gerbang.
--
-- BERASINGAN daripada payment: satu transaksi gerbang boleh gagal,
-- dibatalkan, atau tidak pernah selesai — dan tiada satu pun daripadanya
-- ialah bayaran. Hanya transaksi BERJAYA menghasilkan rekod payment.
--
-- 12% transaksi dalam legacy tidak pernah selesai (18,614 daripada
-- 150,580). Mencampurkannya dengan bayaran bermakna setiap laporan perlu
-- menapis status dahulu.
CREATE TABLE gateway_txn (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code         VARCHAR(20)  NOT NULL,
  account_id      BIGINT       NOT NULL,

  -- Rujukan KITA, dihantar ke gerbang sebagai external_reference_no.
  -- UNIK: ia kunci idempotency apabila callback diulang (ADR 0007 #3).
  our_ref         VARCHAR(50)  NOT NULL,

  -- Rujukan GERBANG. NULL sehingga bil dicipta.
  bill_code       VARCHAR(50)  NULL,
  gateway         VARCHAR(20)  NOT NULL,

  -- Amaun DIMINTA. Amaun sebenar datang daripada gerbang dalam callback
  -- dan disimpan berasingan — ADR 0007 #1: amaun resit diambil daripada
  -- gerbang, tidak pernah dikira semula daripada baki invois.
  amount          DECIMAL(15,2) NOT NULL,
  paid_amount     DECIMAL(15,2) NULL,

  -- Yuran gerbang. gross = paid_amount, net = paid_amount - fee.
  fee_amount      DECIMAL(15,2) NULL,

  status          ENUM('NEW','PENDING','SUCCESS','FAILED','EXPIRED')
                  NOT NULL DEFAULT 'NEW',

  -- Rujukan transaksi bank dari gerbang.
  gateway_ref     VARCHAR(100) NULL,
  gateway_status  VARCHAR(20)  NULL,

  -- Respons mentah disimpan PENUH. Bila nombor tidak sepadan enam bulan
  -- kemudian, ini satu-satunya rekod tentang apa yang gerbang sebenarnya
  -- katakan.
  gateway_payload TEXT         NULL,

  -- payment yang terhasil. NULL sehingga callback berjaya diproses.
  payment_id      BIGINT       NULL,

  paid_at         DATETIME     NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by      VARCHAR(64)  NULL,
  updated_at      DATETIME     NULL,
  version         BIGINT       NOT NULL DEFAULT 0,

  PRIMARY KEY (id),
  UNIQUE KEY uk_gtxn_ref (our_ref),
  KEY idx_gtxn_bill (bill_code),
  KEY idx_gtxn_sp_status (sp_code, status, created_at),
  KEY idx_gtxn_account (account_id),
  CONSTRAINT fk_gtxn_sp      FOREIGN KEY (sp_code)    REFERENCES service_provider (sp_code),
  CONSTRAINT fk_gtxn_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Baris invois yang transaksi ini hendak bayar.
--
-- Pelanggan memilih invois SEBELUM membayar, dan pilihan itu mesti kekal
-- sehingga callback tiba — baki boleh berubah antara masa bil dicipta dan
-- bayaran selesai (bayaran manual direkod, penyelarasan dibuat).
--
-- Tanpa jadual ini, alokasi selepas callback terpaksa meneka invois mana
-- yang pelanggan maksudkan.
CREATE TABLE gateway_txn_line (
  id           BIGINT        NOT NULL AUTO_INCREMENT,
  txn_id       BIGINT        NOT NULL,
  document_id  BIGINT        NOT NULL,
  amount       DECIMAL(15,2) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_gtxn_line (txn_id),
  CONSTRAINT fk_gtxn_line_txn FOREIGN KEY (txn_id) REFERENCES gateway_txn (id) ON DELETE CASCADE,
  CONSTRAINT fk_gtxn_line_doc FOREIGN KEY (document_id) REFERENCES financial_document (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
