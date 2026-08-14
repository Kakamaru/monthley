-- SP platform dan katalognya.
--
-- is_platform_owner DIBACA di enam tempat (pelan onboarding, katalog
-- modul, akaun bil) tetapi TIDAK PERNAH ditetapkan oleh kod. Pada mesin
-- pembangunan ia ditandakan dengan tangan; pemasangan baharu tiada cara
-- untuk menetapkannya melalui UI.
--
-- Akibatnya berantai: tiada SP platform bermakna tiada pelan, tiada pelan
-- bermakna onboarding SP pertama tidak boleh diselesaikan, dan sistem
-- tidak boleh digunakan sama sekali.
--
-- Jurang yang sama jenis dengan V76 — kedua-duanya hanya kelihatan pada
-- pemasangan yang benar-benar kosong.
--
-- Migrasi ini idempoten: setiap sisipan menyemak kewujudan dahulu, jadi
-- pemasangan sedia ada tidak berubah.

-- ---------------------------------------------------------------------------
-- 1) SP platform
-- ---------------------------------------------------------------------------
INSERT INTO service_provider
  (sp_code, name, business_type, business_desc, country, status,
   is_platform_owner, created_at, updated_at, created_by, version)
SELECT 'SP0000', 'Rapidevelop Technology Sdn Bhd', 'IT',
       'Penyedia platform Monthley', 'Malaysia', 'ACTIVE',
       1, NOW(), NOW(), 'seed', 0
WHERE NOT EXISTS (SELECT 1 FROM service_provider WHERE sp_code = 'SP0000')
  AND NOT EXISTS (SELECT 1 FROM service_provider WHERE is_platform_owner = 1);

-- ---------------------------------------------------------------------------
-- 2) Tetapan SP — onboarding menciptanya untuk SP biasa; SP platform
--    tidak melalui onboarding, jadi ia dicipta di sini.
-- ---------------------------------------------------------------------------
INSERT INTO sp_billing_setting
  (sp_code, currency, language, payment_term_days, enable_tax_invoice,
   einvoice_type, einvoice_classification, smallest_denomination, version)
SELECT 'SP0000', 'MYR', 'ms', 14, 0, 'INVOICE', 'GENERAL', 0.00, 0
WHERE EXISTS (SELECT 1 FROM service_provider WHERE sp_code = 'SP0000')
  AND NOT EXISTS (SELECT 1 FROM sp_billing_setting WHERE sp_code = 'SP0000');

INSERT INTO sp_document_setting
  (sp_code, invoice_gen_mode, invoice_gen_freq, invoice_prorated, account_no_auto,
   auto_generate, split_invoice_by_product, allow_price_override,
   enable_manual_payment, version)
SELECT 'SP0000', 'CURRENT', 'MONTHLY', 0, 1, 1, 1, 0, 1, 0
WHERE EXISTS (SELECT 1 FROM service_provider WHERE sp_code = 'SP0000')
  AND NOT EXISTS (SELECT 1 FROM sp_document_setting WHERE sp_code = 'SP0000');

INSERT INTO sp_payment_setting
  (sp_code, gateway, manual_payment, online_payment, absorb,
   rate_single, rate_multi, sandbox, version)
SELECT 'SP0000', 'TP', 1, 0, 0, 1.50, 2.00, 1, 0
WHERE EXISTS (SELECT 1 FROM service_provider WHERE sp_code = 'SP0000')
  AND NOT EXISTS (SELECT 1 FROM sp_payment_setting WHERE sp_code = 'SP0000');

-- ---------------------------------------------------------------------------
-- 3) Kategori produk
-- ---------------------------------------------------------------------------
INSERT INTO product_category (sp_code, code, name, version)
SELECT 'SP0000', x.kod, x.nama, 0
FROM (SELECT 'B' AS kod, 'BASIC' AS nama
      UNION ALL SELECT 'ADD', 'ADDITIONAL MODUL') x
WHERE EXISTS (SELECT 1 FROM service_provider WHERE sp_code = 'SP0000')
  AND NOT EXISTS (
      SELECT 1 FROM product_category c WHERE c.sp_code = 'SP0000' AND c.code = x.kod);

-- ---------------------------------------------------------------------------
-- 4) Produk — pelan langganan dan modul tambahan
--
-- account_limit membezakan PELAN daripada item lain: skrin onboarding
-- menyenaraikan hanya produk dengan had akaun sebagai pelan. Modul,
-- onboarding, dan migrasi ialah produk tanpa had.
-- ---------------------------------------------------------------------------
INSERT INTO product
  (sp_code, code, name, category_id, charge_frequency, unit_rate, account_limit,
   main_product, mandatory, prorated, late_penalty, status,
   created_at, updated_at, created_by, version)
SELECT 'SP0000', x.kod, x.nama,
       (SELECT id FROM product_category WHERE sp_code='SP0000' AND name = x.kategori LIMIT 1),
       x.freq, x.kadar, x.had, x.utama, 0, 0, 0, 'ACTIVE',
       NOW(), NOW(), 'seed', 0
FROM (
    SELECT 'P100' AS kod, 'PAKEJ PLAN 100' AS nama, 'BASIC' AS kategori,
           'MONTHLY' AS freq, 60.00 AS kadar, 100 AS had, 1 AS utama
    UNION ALL SELECT 'P200', 'PLAN 200',       'BASIC', 'MONTHLY',  70.00,  200, 1
    UNION ALL SELECT 'P300', 'PAKEJ 300',      'BASIC', 'MONTHLY',  80.00,  300, 1
    UNION ALL SELECT 'P400', 'PAKEJ PLAN 400', 'BASIC', 'MONTHLY',  90.00,  400, 1
    UNION ALL SELECT 'OB',   'ONBOARDING',     'BASIC', 'ONE_TIME', 300.00, NULL, 0
    UNION ALL SELECT 'MG',   'MIGRASI AKAUN',  'BASIC', 'ONE_TIME', 190.00, NULL, 0
    UNION ALL SELECT 'EXP',  'MODUL PERBELANJAAN', 'ADDITIONAL MODUL', 'MONTHLY', 10.00, NULL, 0
    UNION ALL SELECT 'ADU',  'MODUL ADUAN',        'ADDITIONAL MODUL', 'MONTHLY', 10.00, NULL, 0
    UNION ALL SELECT 'SUM',  'Modul Sumbangan',    'ADDITIONAL MODUL', 'MONTHLY', 10.00, NULL, 0
    UNION ALL SELECT 'MEMO', 'Modul Memo',         'ADDITIONAL MODUL', 'MONTHLY',  5.00, NULL, 0
) x
WHERE EXISTS (SELECT 1 FROM service_provider WHERE sp_code = 'SP0000')
  AND NOT EXISTS (
      SELECT 1 FROM product p WHERE p.sp_code = 'SP0000' AND p.code = x.kod);

-- ---------------------------------------------------------------------------
-- 5) Paut produk modul ke katalog (V76 meninggalkannya NULL)
-- ---------------------------------------------------------------------------
UPDATE ref_module m
JOIN   service_provider owner ON owner.is_platform_owner = 1
JOIN   product p ON p.sp_code = owner.sp_code
                AND p.code = CASE m.code
                    WHEN 'PERBELANJAAN' THEN 'EXP'
                    WHEN 'ADUAN'        THEN 'ADU'
                    WHEN 'SUMBANGAN'    THEN 'SUM'
                    WHEN 'MEMO'         THEN 'MEMO'
                END
SET    m.product_id = p.id
WHERE  m.product_id IS NULL;
