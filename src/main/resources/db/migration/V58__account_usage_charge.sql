-- Caj berasaskan penggunaan (usage-based charges).
--
-- Tadika mengecaj jam lebih masa; JMB mengecaj meter air pukal. Kerani
-- memuat turun Excel dengan senarai akaun, mengisi kuantiti atau amaun,
-- dan memuat naiknya. Baris duduk di sini sehingga bil dijana.
--
-- BUKAN LANGGANAN. Akaun tidak perlu melanggan produk itu — nama jadual
-- legacy (mon_acc_subscr_usg) mengandungi 'subscr' tetapi skrin muat
-- naik tidak pernah menyemaknya. Akaun hanya perlu wujud di bawah SP.
--
-- Itu bermakna enjin bil mesti mengeluarkan invois untuk akaun yang
-- TIADA langganan langsung, kalau ia mempunyai caj penggunaan yang
-- belum dibil.
--
-- PERIOD DARIPADA BARIS, BUKAN DARIPADA MOD BIL.
--
-- Kerani memilih tempoh semasa memuat naik. Baris invois membawa tempoh
-- ITU, bukan tempoh yang mod POSTPAID/CURRENT/PREPAID akan kira. Dua
-- muat naik untuk produk yang sama — Jun dan Julai — menghasilkan DUA
-- baris invois dalam larian yang sama, satu bertanda Jun dan satu
-- bertanda Julai.
--
-- Semua baris yang belum dibil disapu oleh larian seterusnya, tanpa
-- mengira tempohnya. Kerani yang memuat naik tempoh Disember dan
-- menjana bil pada Ogos mendapat caj itu pada invois Ogos.
--
-- KUANTITI ATAU AMAUN, AMAUN MENANG.
--
-- Excel mempunyai kedua-dua lajur. Kalau kerani mengisi kuantiti
-- sahaja, sistem mendarabnya dengan kadar produk. Kalau dia mengisi
-- amaun, amaun itu digunakan terus — meter yang dibaca oleh pihak
-- ketiga datang sebagai jumlah, bukan sebagai unit.
--
-- Amaun disimpan MUKTAMAD di sini, dikira semasa muat naik. Mengiranya
-- semula semasa jana bil bermakna perubahan kadar produk antara muat
-- naik dan penjanaan menukar apa yang kerani sudah semak di skrin.

CREATE TABLE account_usage_charge (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  sp_code       VARCHAR(20)  NOT NULL,
  account_id    BIGINT       NOT NULL,
  product_id    BIGINT       NOT NULL,
  period_id     BIGINT       NOT NULL,

  quantity      DECIMAL(18,4) NOT NULL DEFAULT 1,
  amount        DECIMAL(18,2) NOT NULL,
  remarks       VARCHAR(255) NULL,

  -- PENDING sehingga bil dijana; INVOICED selepas itu, dengan dokumen
  -- yang mengandunginya. Baris PENDING boleh dipadam oleh kerani —
  -- tab Per-use pada skrin akaun wujud untuk menyemak sebelum menjana.
  status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
  document_id   BIGINT       NULL,

  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by    VARCHAR(64)  NULL,
  invoiced_at   DATETIME     NULL,

  PRIMARY KEY (id),

  -- Satu caj per (akaun, produk, tempoh). Muat naik kedua untuk
  -- gabungan yang sama DITOLAK, bukan ditambah: kerani yang memuat
  -- naik fail yang salah dan mengulanginya tidak sepatutnya mengecaj
  -- dua kali.
  --
  -- Produk berbeza atau tempoh berbeza dibenarkan — itu caj yang
  -- berbeza.
  UNIQUE KEY uk_auc_acc_prod_period (account_id, product_id, period_id),

  -- Enjin bil menyoal baris PENDING per akaun.
  KEY idx_auc_pending (sp_code, status, account_id),
  KEY idx_auc_doc (document_id),

  CONSTRAINT fk_auc_account FOREIGN KEY (account_id) REFERENCES account (id),
  CONSTRAINT fk_auc_product FOREIGN KEY (product_id) REFERENCES product (id),
  CONSTRAINT fk_auc_period  FOREIGN KEY (period_id)  REFERENCES fi_period (period_id)
) ENGINE=InnoDB;
