-- Baris gilir penghantaran (ADR 0014).
--
-- Legacy menghantar lebih 10,000 penyata pada 1 haribulan. Menghantarnya
-- dalam gelung semasa jana bil bermakna: larian berjam-jam, kegagalan
-- separuh jalan tanpa cara mencuba semula dengan selamat, dan tiada
-- keterlihatan sehingga seseorang mengadu.
--
-- Jana bil menulis baris PENDING dan commit. Tugas berjadual meninjau,
-- menghantar, menandakan SENT atau FAILED. Kegagalan pada e-mel
-- ke-5,000 tidak menghantar semula 4,999 yang pertama.
--
-- SATU TULISAN. Legacy menulis dua kali untuk satu peristiwa — DB dan
-- Hazelcast — dengan catch yang hanya log. Kalau baris gilir gagal,
-- baris kekal 'P' selamanya; kalau transaksi digulung selepasnya, e-mel
-- keluar untuk invois yang tidak wujud.
--
-- PARAMETER, bukan badan siap. Legacy menyimpan HTML penuh
-- (String.format dengan tiga belas argumen) tetapi untuk lampiran ia
-- menyimpan nama laporan dan parameternya. Corak kedua itu betul:
-- sepuluh ribu salinan HTML setiap bulan, dan pembetulan templat tidak
-- menjejaskan baris yang sudah beratur.

CREATE TABLE email_outbox (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code      VARCHAR(20)  NOT NULL,

  -- Satu nilai buat masa ini. SMS mungkin menyusul; menambahnya menjadi
  -- baris data, bukan migrasi struktur. WhatsApp dikecualikan dengan
  -- sengaja — penghantaran pukal menyebabkan nombor disekat sebagai
  -- spam, dasar platform bukan had teknikal.
  channel      VARCHAR(10)  NOT NULL DEFAULT 'EMAIL',

  -- STATEMENT | GENERATION_REPORT | REMINDER
  kind         VARCHAR(30)  NOT NULL,

  -- Rujukan unik dalam jenisnya: 'akaun:tempoh' untuk penyata,
  -- 'sp:tempoh' untuk laporan penjanaan.
  --
  -- UNIQUE(sp_code, kind, ref_key) menghalang e-mel berganda: larian
  -- kedua untuk tempoh yang sama tidak boleh menghasilkan baris kedua.
  -- Corak sama seperti idem_key pada baris dokumen.
  ref_key      VARCHAR(100) NOT NULL,

  to_email     VARCHAR(255) NOT NULL,
  -- billto_email_secondary. Legacy memanggilnya add_emails dan
  -- menyimpannya pada baris gilir, bukan menyoal akaun semasa
  -- menghantar — alamat pada masa BERATUR ialah yang dimaksudkan.
  cc_email     VARCHAR(255) NULL,

  -- Kunci/nilai generik, corak p_acc_no / p_period legacy. Badan
  -- dirender semasa menghantar.
  --
  -- DUA PASANG, bukan JSON. Params sebenar cuma akaun dan tempoh;
  -- legacy menggunakan bentuk ini sepuluh tahun tanpa memerlukan yang
  -- ketiga. Ia boleh disoal dengan SQL biasa (WHERE param1_val = '5'),
  -- tiada penyirian untuk gagal, dan modul notification kekal tanpa
  -- kebergantungan JSON.
  param1_key   VARCHAR(30)  NULL,
  param1_val   VARCHAR(100) NULL,
  param2_key   VARCHAR(30)  NULL,
  param2_val   VARCHAR(100) NULL,

  -- PENDING | SENT | FAILED
  status       VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
  attempts     INT          NOT NULL DEFAULT 0,
  last_error   VARCHAR(500) NULL,

  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sent_at      DATETIME     NULL,

  PRIMARY KEY (id),
  UNIQUE KEY uk_outbox_ref (sp_code, kind, ref_key),
  -- Tugas berjadual menyoal PENDING tertua dahulu.
  KEY idx_outbox_pending (status, created_at),
  KEY idx_outbox_sp (sp_code)
) ENGINE=InnoDB;
